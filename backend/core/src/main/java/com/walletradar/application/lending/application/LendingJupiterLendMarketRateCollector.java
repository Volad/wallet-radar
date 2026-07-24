package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Live Jupiter Lend (Solana) market-rate reader (WS-3), the Solana analogue of
 * {@link LendingAaveV3MarketRateCollector}. Jupiter Lend exposes per-vault supply/borrow rates through
 * its Borrow API ({@code GET /lend/v1/borrow/vaults}); this reader matches a discovered active market to
 * its vault by side + canonical underlying symbol and publishes the protocol supply/borrow APY so the
 * lending page can render Net APY instead of {@code "--"} (previously null because no live rate reader
 * existed for Jupiter Lend and it fell back to ACCOUNTING_ESTIMATE).
 *
 * <p>Rates arrive already decoded to a true percent by the platform client (basis points → percent). We
 * treat the venue rate as an APR and derive the APY via the same per-second compounding convention used
 * by the Aave reader, so both protocols report on one comparable basis. We never fabricate a rate: when
 * the API omits the side's rate the snapshot is {@code UNAVAILABLE} with an explicit reason.</p>
 *
 * <p>Background-only, idempotent, best-effort: any venue failure resolves to an {@code UNAVAILABLE}
 * snapshot (the platform client never throws). The shared vault list is cached briefly so the
 * supply-side and borrow-side reads of the same wallet do not re-fetch it.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LendingJupiterLendMarketRateCollector implements LendingMarketRateReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SECONDS_PER_YEAR = BigDecimal.valueOf(31_536_000L);
    private static final String PROTOCOL_MATCH = "JUPITER";
    private static final String NETWORK_SOLANA = "SOLANA";
    private static final String RATE_SOURCE = "JUPITER_LEND_BORROW_API";
    private static final String WSOL_SYMBOL = "WSOL";
    private static final String SOL_SYMBOL = "SOL";
    private static final long VAULT_CACHE_TTL_MS = 60_000L;

    private final JupiterLendClient jupiterLendClient;

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
    public Optional<LendingMarketRateSnapshot> collect(LendingActiveMarketDiscoveryService.ActiveMarket market) {
        List<JupiterLendClient.BorrowVault> vaults = cachedVaults();
        if (vaults.isEmpty()) {
            return Optional.of(unavailable(market, "JUPITER_LEND_VAULTS_UNAVAILABLE"));
        }
        boolean borrowSide = "BORROW".equalsIgnoreCase(market.side());
        String underlying = canonicalSymbol(market.underlyingSymbol());
        Optional<JupiterLendClient.BorrowVault> matched = vaults.stream()
                .filter(vault -> matches(vault, borrowSide, underlying))
                .findFirst();
        if (matched.isEmpty()) {
            return Optional.of(unavailable(market, "JUPITER_LEND_MARKET_NOT_FOUND"));
        }
        JupiterLendClient.BorrowVault vault = matched.get();
        BigDecimal sideRatePct = borrowSide ? vault.borrowRatePct() : vault.supplyRatePct();
        if (sideRatePct == null) {
            return Optional.of(unavailable(market, "JUPITER_LEND_RATE_ABSENT"));
        }

        Instant capturedAt = Instant.now();
        BigDecimal supplyAprPct = vault.supplyRatePct();
        BigDecimal borrowAprPct = vault.borrowRatePct();
        LendingMarketRateSnapshot snapshot = new LendingMarketRateSnapshot()
                .setId(snapshotId(market, capturedAt))
                .setSessionId(market.sessionId())
                .setProtocol(market.protocol())
                .setNetworkId(networkKey(market))
                .setMarketKey(market.marketKey())
                .setWalletAddress(market.walletAddress())
                .setAssetSymbol(market.assetSymbol())
                .setUnderlyingSymbol(market.underlyingSymbol())
                .setSide(market.side())
                .setSupplyAprPct(supplyAprPct)
                .setSupplyApyPct(apyPctFromAprPct(supplyAprPct))
                .setBorrowAprPct(borrowAprPct)
                .setBorrowApyPct(apyPctFromAprPct(borrowAprPct))
                .setRewardAprStatus(LendingMarketRateStatus.UNAVAILABLE)
                .setRewardAprUnavailableReason(LendingMarketRateStatus.REWARDS_COLLECTOR_NOT_IMPLEMENTED)
                .setNetSupplyApyPct(apyPctFromAprPct(supplyAprPct))
                .setNetBorrowApyPct(apyPctFromAprPct(borrowAprPct))
                .setRateSource(RATE_SOURCE)
                .setRateStatus(LendingMarketRateStatus.API_SNAPSHOT)
                .setApyConvention(LendingMarketRateStatus.PER_SECOND_COMPOUNDING)
                .setCapturedAt(capturedAt)
                .setRawSnapshotRef("vault:" + vault.vaultId());
        return Optional.of(snapshot);
    }

    private boolean matches(JupiterLendClient.BorrowVault vault, boolean borrowSide, String underlying) {
        if (underlying == null || underlying.isBlank()) {
            return false;
        }
        JupiterLendClient.VaultToken token = borrowSide ? vault.borrowToken() : vault.supplyToken();
        BigDecimal rate = borrowSide ? vault.borrowRatePct() : vault.supplyRatePct();
        return rate != null && token != null && underlying.equals(canonicalSymbol(token.symbol()));
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

    private static String canonicalSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return WSOL_SYMBOL.equals(normalized) ? SOL_SYMBOL : normalized;
    }

    private static BigDecimal apyPctFromAprPct(BigDecimal aprPct) {
        if (aprPct == null) {
            return null;
        }
        double apr = aprPct.divide(ONE_HUNDRED, MC).doubleValue();
        double apy = Math.pow(1.0d + apr / SECONDS_PER_YEAR.doubleValue(), SECONDS_PER_YEAR.doubleValue()) - 1.0d;
        if (!Double.isFinite(apy)) {
            return null;
        }
        return BigDecimal.valueOf(apy).multiply(ONE_HUNDRED, MC).setScale(8, RoundingMode.HALF_UP);
    }

    private LendingMarketRateSnapshot unavailable(
            LendingActiveMarketDiscoveryService.ActiveMarket market,
            String reason
    ) {
        Instant capturedAt = Instant.now();
        return new LendingMarketRateSnapshot()
                .setId(snapshotId(market, capturedAt))
                .setSessionId(market.sessionId())
                .setProtocol(market.protocol())
                .setNetworkId(networkKey(market))
                .setMarketKey(market.marketKey())
                .setWalletAddress(market.walletAddress())
                .setAssetSymbol(market.assetSymbol())
                .setUnderlyingSymbol(market.underlyingSymbol())
                .setSide(market.side())
                .setRateSource(RATE_SOURCE)
                .setRateStatus(LendingMarketRateStatus.UNAVAILABLE)
                .setApyConvention(LendingMarketRateStatus.PER_SECOND_COMPOUNDING)
                .setRewardAprStatus(LendingMarketRateStatus.UNAVAILABLE)
                .setRewardAprUnavailableReason(LendingMarketRateStatus.REWARDS_COLLECTOR_NOT_IMPLEMENTED)
                .setCapturedAt(capturedAt)
                .setUnavailableReason(reason);
    }

    private static String networkKey(LendingActiveMarketDiscoveryService.ActiveMarket market) {
        return market.networkId() == null ? null : market.networkId().trim().toUpperCase(Locale.ROOT);
    }

    private static String snapshotId(LendingActiveMarketDiscoveryService.ActiveMarket market, Instant capturedAt) {
        return String.join(":",
                nullToUnknown(market.sessionId()),
                "jupiter-lend",
                nullToUnknown(market.networkId()),
                nullToUnknown(market.marketKey()),
                nullToUnknown(market.underlyingSymbol()),
                nullToUnknown(market.side()),
                String.valueOf(capturedAt.toEpochMilli())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
