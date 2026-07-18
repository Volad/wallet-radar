package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.application.costbasis.support.LpReceiptSymbolSupport;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class ReplayAssetSupport {

    private static final Map<String, String> CORRELATED_TRANSFER_SYMBOL_ALIASES = Map.ofEntries(
            Map.entry("USDT0", "USDT"),
            Map.entry("USD₮0", "USDT")
    );

    public boolean allSameAsset(
            List<NormalizedTransaction.Flow> flows,
            NormalizedTransaction transaction
    ) {
        if (flows == null || flows.isEmpty()) {
            return true;
        }
        String first = continuityIdentity(transaction, flows.getFirst());
        for (int index = 1; index < flows.size(); index++) {
            if (!Objects.equals(first, continuityIdentity(transaction, flows.get(index)))) {
                return false;
            }
        }
        return true;
    }

    public boolean allHaveKnownPrices(List<NormalizedTransaction.Flow> flows) {
        return flows != null
                && !flows.isEmpty()
                && flows.stream().allMatch(this::hasKnownPrice);
    }

    public boolean allHaveKnownReplayPrices(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows
    ) {
        return flows != null
                && !flows.isEmpty()
                && flows.stream().allMatch(indexedFlow -> replayUnitPriceUsd(transaction, indexedFlow.flow()) != null);
    }

    public BigDecimal replayUnitPriceUsd(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow == null) {
            return null;
        }
        if (hasKnownPrice(flow)) {
            return flow.getUnitPriceUsd();
        }
        if (transaction != null && CanonicalAssetCatalog.isUsdStablecoin(
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                transaction.getSource()
        )) {
            return BigDecimal.ONE;
        }
        return null;
    }

    public boolean hasKnownPrice(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getUnitPriceUsd() != null
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN;
    }

    public String continuityIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (familyIdentity != null) {
            return familyIdentity;
        }
        return assetIdentity(transaction, flow);
    }

    public boolean sameAssetIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow left,
            NormalizedTransaction.Flow right
    ) {
        return Objects.equals(assetIdentity(transaction, left), assetIdentity(transaction, right));
    }

    public AssetKey assetKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        String assetContract = AccountingAssetIdentitySupport.positionAssetIdentity(transaction, flow);
        String assetSymbol = normalizeSymbol(flow == null ? null : flow.getAssetSymbol());
        String assetIdentity = assetContract != null ? assetContract : "SYMBOL:" + assetSymbol;
        return new AssetKey(
                AccountingAssetIdentitySupport.replayPositionWalletAddress(transaction, flow),
                AccountingAssetIdentitySupport.positionNetwork(transaction),
                assetContract != null ? assetContract : assetIdentity,
                assetSymbol,
                assetIdentity
        );
    }

    /**
     * Bybit EXECUTION_SPOT / SWAP SELL rows often normalize on {@code :UTA} while inventory
     * remains on {@code :FUND} or {@code :EARN} from earn-principal / corridor paths.
     * Route disposals to the sub-wallet position that actually holds quantity.
     *
     * <p>ADR-042: when the flow carries an explicit {@code accountRef} naming a
     * {@code :FUND}/{@code :UTA}/{@code :EARN} sub-account that already exists with enough inventory
     * to cover the fill, route the disposal to that named sub-position <em>before</em> the
     * max-quantity scan. This keeps every fill of a multi-fill convert sticky to the pool the
     * convert names — the max-quantity tiebreak otherwise flips to the umbrella mid-convert once the
     * named sub-account drops below the umbrella lot, stranding the remaining fills on the umbrella
     * and leaving a phantom residual on the sub-account.
     */
    public AssetKey resolveSellAssetKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            Map<AssetKey, PositionState> positions
    ) {
        AssetKey defaultKey = assetKey(transaction, flow);
        if (!isBybitSpotSell(transaction, flow) || positions == null || positions.isEmpty()) {
            return defaultKey;
        }
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        // ADR-042: honour the named sub-account FIRST, before the default-key coverage check and the
        // max-quantity scan. This keeps every fill of a multi-fill convert sticky to the pool the
        // convert names ({@code flow.accountRef}) even after that pool drops below a sibling umbrella
        // lot mid-convert, so the sub-account fully drains and no phantom residual is stranded.
        AssetKey accountRefKey = AccountRefPositionResolver.resolveInventoryBearingAccountRefKey(
                defaultKey, flow.getAccountRef(), positions, requestedQuantity);
        if (!accountRefKey.equals(defaultKey)) {
            return accountRefKey;
        }
        PositionState defaultPosition = positions.get(defaultKey);
        if (defaultPosition != null && coversSell(defaultPosition, requestedQuantity)) {
            return defaultKey;
        }
        String umbrellaRoot = bybitUmbrellaRoot(transaction.getWalletAddress(), flow.getAccountRef());
        AssetKey bestKey = defaultKey;
        BigDecimal bestQuantity = defaultPosition == null ? BigDecimal.ZERO : zeroIfNull(defaultPosition.quantity());
        for (Map.Entry<AssetKey, PositionState> entry : positions.entrySet()) {
            AssetKey candidateKey = entry.getKey();
            if (!Objects.equals(candidateKey.assetIdentity(), defaultKey.assetIdentity())) {
                continue;
            }
            if (!sameBybitUmbrella(candidateKey.walletAddress(), umbrellaRoot)) {
                continue;
            }
            BigDecimal candidateQuantity = zeroIfNull(entry.getValue().quantity());
            if (candidateQuantity.compareTo(bestQuantity) > 0) {
                bestQuantity = candidateQuantity;
                bestKey = candidateKey;
            }
        }
        if (bestQuantity.signum() > 0 && coversSell(positions.get(bestKey), requestedQuantity)) {
            return bestKey;
        }
        if (bestQuantity.signum() > 0 && (defaultPosition == null || defaultPosition.quantity().signum() == 0)) {
            return bestKey;
        }
        return defaultKey;
    }

    private static boolean isBybitSpotSell(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction == null
                || flow == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || flow.getRole() != NormalizedLegRole.SELL
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        if (transaction.getType() == NormalizedTransactionType.SWAP) {
            return true;
        }
        String txId = transaction.getId();
        return txId != null && txId.contains(":EXECUTION_SPOT:");
    }

    private static boolean coversSell(PositionState position, BigDecimal requestedQuantity) {
        return position != null
                && position.quantity() != null
                && position.quantity().compareTo(requestedQuantity) >= 0;
    }

    private static String bybitUmbrellaRoot(String walletAddress, String accountRef) {
        if (walletAddress != null && !walletAddress.isBlank()) {
            String root = extractBybitUmbrellaRoot(walletAddress);
            if (root != null) {
                return root;
            }
        }
        if (accountRef != null && !accountRef.isBlank()) {
            return extractBybitUmbrellaRoot(accountRef);
        }
        return null;
    }

    private static String extractBybitUmbrellaRoot(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletAddress.trim());
        if (ref.domain() != WalletDomainKind.CEX || ref.uid().isBlank()) {
            return null;
        }
        return ref.umbrellaKey();
    }

    private static boolean sameBybitUmbrella(String walletAddress, String umbrellaRoot) {
        if (walletAddress == null || umbrellaRoot == null) {
            return false;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        WalletRef rootRef = WalletRef.parse(umbrellaRoot);
        if (ref.domain() != WalletDomainKind.CEX || rootRef.domain() != WalletDomainKind.CEX) {
            return false;
        }
        return ref.umbrellaKey().equalsIgnoreCase(rootRef.umbrellaKey());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Position-scoped NFT LP receipt marker keyed by {@code lp-position:} correlation id.
     * Uses the same {@code SYMBOL:LP-RECEIPT:...} identity as materialized receipt flows.
     */
    public AssetKey lpReceiptPositionKey(NormalizedTransaction transaction, String correlationId) {
        if (transaction == null || correlationId == null || correlationId.isBlank()) {
            return null;
        }
        String receiptSymbol = LpReceiptSymbolSupport.fromLpPositionCorrelation(correlationId);
        if (receiptSymbol == null) {
            return null;
        }
        return new AssetKey(
                AccountingAssetIdentitySupport.positionWalletAddress(transaction),
                AccountingAssetIdentitySupport.positionNetwork(transaction),
                null,
                receiptSymbol,
                "SYMBOL:" + receiptSymbol
        );
    }

    public String assetIdentity(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return assetKey(transaction, flow).assetIdentity();
    }

    public String bridgeFamilyIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return null;
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return null;
        }
        return BridgeAssetFamilySupport.continuityIdentity(flow);
    }

    public String correlatedTransferIdentity(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return null;
        }
        String identity = continuityIdentity(transaction, flow);
        if (identity == null || identity.isBlank()) {
            return identity;
        }
        if (identity.startsWith("FAMILY:")) {
            return identity;
        }
        String canonicalSymbol = canonicalCorrelatedTransferSymbol(flow.getAssetSymbol());
        if (canonicalSymbol == null || canonicalSymbol.isBlank()) {
            return identity;
        }
        return "SYMBOL:" + canonicalSymbol;
    }

    public String canonicalCorrelatedTransferSymbol(String assetSymbol) {
        String normalized = normalizeSymbol(assetSymbol);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return CORRELATED_TRANSFER_SYMBOL_ALIASES.getOrDefault(normalized, normalized);
    }

    public String normalizeContract(String contract) {
        return contract == null || contract.isBlank() ? null : contract.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        if (LpReceiptSymbolSupport.isLpReceiptSymbol(symbol)) {
            return LpReceiptSymbolSupport.canonicalize(symbol);
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
