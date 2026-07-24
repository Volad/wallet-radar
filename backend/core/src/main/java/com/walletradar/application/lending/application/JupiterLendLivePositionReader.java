package com.walletradar.application.lending.application;

import com.walletradar.application.lending.spi.LendingLivePositionReader;
import com.walletradar.application.lending.spi.LiveLendingAssetAmount;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import com.walletradar.application.normalization.pipeline.metadata.NetworkTokenOverrides;
import com.walletradar.platform.networks.solana.SolanaChain;
import com.walletradar.application.pricing.latest.CurrentPriceReadService;
import com.walletradar.application.pricing.latest.ResolvedPrice;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Live Jupiter Lend (Solana) borrow-position reader (WS-3, B3). Jupiter Lend issues no fungible
 * receipt token, so its collateral/debt/HF cannot flow through the generic ERC-20 {@code balanceOf}
 * path that makes Aave live. This reader queries the Jupiter Lend Borrow API for the wallet's
 * positions + vault risk params and produces the single-authority {@link LiveLendingPosition}:
 *
 * <ul>
 *   <li>collateral (e.g. ~5.42 SOL) as a <strong>carry</strong> — market-valued from the live SOL
 *       quote, never a minted basis;</li>
 *   <li>debt (e.g. ~233 USDT incl. accrued interest, from the position's {@code borrow}) marked at
 *       the stable $1 pin;</li>
 *   <li>Aave-style health factor {@code HF = liquidationThreshold × collateralUsd / debtUsd} and the
 *       loan-to-value {@code LTV = debtUsd / collateralUsd}, with the liquidation threshold discovered
 *       from the vault config (never hardcoded).</li>
 * </ul>
 *
 * <p>Background-only (invoked by the refresh services / balance refresh), best-effort, and reuses the
 * platform Jupiter throttle via {@link JupiterLendClient}. Vault config is cached briefly so the
 * per-wallet read does not re-fetch the shared reserve params.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JupiterLendLivePositionReader implements LendingLivePositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    static final String SOURCE_LIVE_PROTOCOL = "LIVE_PROTOCOL";
    private static final String NETWORK_SOLANA = "SOLANA";
    private static final String PROTOCOL_MATCH = "JUPITER";

    private static final long VAULT_CACHE_TTL_MS = 60_000L;

    private final JupiterLendClient jupiterLendClient;
    private final CurrentPriceReadService currentPriceReadService;

    private volatile List<JupiterLendClient.BorrowVault> cachedVaults = List.of();
    private volatile long cachedVaultsAtMs = 0L;

    @Override
    public boolean supports(String protocolKey, String networkId) {
        return protocolKey != null
                && protocolKey.toUpperCase(Locale.ROOT).contains(PROTOCOL_MATCH)
                && networkId != null
                && NETWORK_SOLANA.equalsIgnoreCase(networkId.trim());
    }

    @Override
    public Optional<LiveLendingPosition> read(LivePositionRequest request) {
        if (request == null || request.walletAddress() == null || request.walletAddress().isBlank()) {
            return Optional.empty();
        }
        List<JupiterLendClient.BorrowPosition> positions =
                jupiterLendClient.fetchBorrowPositions(request.walletAddress());
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, JupiterLendClient.BorrowVault> vaultsById = vaultsById();

        Map<String, LiveLendingAssetAmount> collateralByIdentity = new HashMap<>();
        Map<String, LiveLendingAssetAmount> debtByIdentity = new HashMap<>();
        BigDecimal collateralUsd = BigDecimal.ZERO;
        BigDecimal debtUsd = BigDecimal.ZERO;
        BigDecimal liquidationThreshold = null;
        BigDecimal collateralFactor = null;
        String rawRef = null;

        for (JupiterLendClient.BorrowPosition position : positions) {
            JupiterLendClient.BorrowVault vault = vaultsById.get(position.vaultId());
            if (vault == null) {
                continue;
            }
            LiveLendingAssetAmount collateral = assetAmount(vault.supplyToken(), position.supply(),
                    NetworkNativeAssets.nativeDecimals(NetworkId.SOLANA));
            LiveLendingAssetAmount debt = assetAmount(vault.borrowToken(), outstandingDebt(position), 6);
            if (collateral != null) {
                mergeAsset(collateralByIdentity, collateral);
                collateralUsd = collateralUsd.add(nz(collateral.marketValueUsd()), MC);
            }
            if (debt != null) {
                mergeAsset(debtByIdentity, debt);
                debtUsd = debtUsd.add(nz(debt.marketValueUsd()), MC);
            }
            if (liquidationThreshold == null || isSmaller(vault.liquidationThreshold(), liquidationThreshold)) {
                if (vault.liquidationThreshold() != null && vault.liquidationThreshold().signum() > 0) {
                    liquidationThreshold = vault.liquidationThreshold();
                }
            }
            if (collateralFactor == null && vault.collateralFactor() != null) {
                collateralFactor = vault.collateralFactor();
            }
            rawRef = "vault:" + position.vaultId() + ":nft:" + position.nftId();
        }

        if (collateralByIdentity.isEmpty() && debtByIdentity.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal healthFactor = healthFactor(liquidationThreshold, collateralUsd, debtUsd);
        BigDecimal loanToValue = loanToValue(collateralUsd, debtUsd);

        return Optional.of(new LiveLendingPosition(
                new ArrayList<>(collateralByIdentity.values()),
                new ArrayList<>(debtByIdentity.values()),
                healthFactor,
                liquidationThreshold,
                loanToValue,
                SOURCE_LIVE_PROTOCOL,
                null,
                rawRef
        ));
    }

    private static BigInteger outstandingDebt(JupiterLendClient.BorrowPosition position) {
        // `borrow` is the authoritative outstanding debt in borrow-token base units and already
        // reflects accrued interest (Jupiter Lend exposes it as the current position debt, matching
        // the app's "Debt 233.38 USDT"). `dustBorrow` is a negligible sub-unit residual (~0.05 USDT),
        // NOT the outstanding debt: prioritising it collapsed the debt to dust and inflated the health
        // factor to ~7005 instead of the real ~1.5 (LTV ~56%). Fall back to it only when `borrow` is
        // absent so a rounding-dust-only position is still surfaced.
        if (position.borrow() != null && position.borrow().signum() > 0) {
            return position.borrow();
        }
        return position.dustBorrow();
    }

    private LiveLendingAssetAmount assetAmount(
            JupiterLendClient.VaultToken token,
            BigInteger rawBaseUnits,
            int fallbackDecimals
    ) {
        if (token == null || rawBaseUnits == null || rawBaseUnits.signum() <= 0) {
            return null;
        }
        String mint = token.mint() == null ? "" : token.mint().trim();
        boolean isNative = SolanaChain.WSOL_MINT.equals(mint);
        int decimals = token.decimals() != null && token.decimals() >= 0
                ? token.decimals()
                : defaultDecimals(mint, fallbackDecimals);
        BigDecimal quantity = new BigDecimal(rawBaseUnits).movePointLeft(Math.max(0, decimals));
        String symbol = canonicalSymbol(mint, token.symbol());
        String contract = isNative ? NetworkNativeAssets.nativeIdentity(NetworkId.SOLANA) : mint;
        BigDecimal price = resolvePrice(symbol);
        BigDecimal usd = price == null ? null : quantity.multiply(price, MC);
        return new LiveLendingAssetAmount(symbol, contract, decimals, quantity, usd);
    }

    private static int defaultDecimals(String mint, int fallback) {
        if (SolanaChain.WSOL_MINT.equals(mint)) {
            return NetworkNativeAssets.nativeDecimals(NetworkId.SOLANA);
        }
        return NetworkTokenOverrides.find(NetworkId.SOLANA, mint)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)
                .filter(d -> d != null)
                .orElse(fallback);
    }

    private static String canonicalSymbol(String mint, String rawSymbol) {
        if (SolanaChain.WSOL_MINT.equals(mint)) {
            return NetworkNativeAssets.nativeSymbol(NetworkId.SOLANA);
        }
        String overrideSymbol = NetworkTokenOverrides.find(NetworkId.SOLANA, mint)
                .map(NetworkTokenOverrides.Override::symbol)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        if (overrideSymbol != null) {
            return overrideSymbol;
        }
        return rawSymbol == null || rawSymbol.isBlank() ? mint : rawSymbol.trim();
    }

    private BigDecimal resolvePrice(String symbol) {
        return currentPriceReadService.resolveOne(symbol)
                .map(ResolvedPrice::priceUsd)
                .filter(price -> price != null && price.signum() > 0)
                .orElse(null);
    }

    private static void mergeAsset(Map<String, LiveLendingAssetAmount> byIdentity, LiveLendingAssetAmount amount) {
        byIdentity.merge(amount.assetContract(), amount, (existing, incoming) -> new LiveLendingAssetAmount(
                existing.assetSymbol(),
                existing.assetContract(),
                existing.decimals(),
                nz(existing.quantity()).add(nz(incoming.quantity()), MC),
                addNullable(existing.marketValueUsd(), incoming.marketValueUsd())
        ));
    }

    private static BigDecimal healthFactor(BigDecimal lt, BigDecimal collateralUsd, BigDecimal debtUsd) {
        if (lt == null || lt.signum() <= 0 || debtUsd == null || debtUsd.signum() <= 0
                || collateralUsd == null || collateralUsd.signum() <= 0) {
            return null;
        }
        return lt.multiply(collateralUsd, MC).divide(debtUsd, MC).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal loanToValue(BigDecimal collateralUsd, BigDecimal debtUsd) {
        if (collateralUsd == null || collateralUsd.signum() <= 0 || debtUsd == null || debtUsd.signum() <= 0) {
            return null;
        }
        return debtUsd.divide(collateralUsd, MC).setScale(6, RoundingMode.HALF_UP);
    }

    private Map<Integer, JupiterLendClient.BorrowVault> vaultsById() {
        List<JupiterLendClient.BorrowVault> vaults = cachedVaults();
        Map<Integer, JupiterLendClient.BorrowVault> byId = new HashMap<>();
        for (JupiterLendClient.BorrowVault vault : vaults) {
            byId.put(vault.vaultId(), vault);
        }
        return byId;
    }

    private List<JupiterLendClient.BorrowVault> cachedVaults() {
        long now = System.currentTimeMillis();
        List<JupiterLendClient.BorrowVault> snapshot = cachedVaults;
        if (!snapshot.isEmpty() && (now - cachedVaultsAtMs) < VAULT_CACHE_TTL_MS) {
            return snapshot;
        }
        List<JupiterLendClient.BorrowVault> fresh = jupiterLendClient.fetchBorrowVaults();
        if (!fresh.isEmpty()) {
            cachedVaults = fresh;
            cachedVaultsAtMs = now;
            return fresh;
        }
        return snapshot;
    }

    private static boolean isSmaller(BigDecimal candidate, BigDecimal current) {
        return candidate != null && candidate.signum() > 0 && candidate.compareTo(current) < 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal addNullable(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right, MC);
    }
}
