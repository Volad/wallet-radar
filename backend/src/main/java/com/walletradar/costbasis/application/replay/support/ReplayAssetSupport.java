package com.walletradar.costbasis.application.replay.support;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.accounting.support.LpReceiptSymbolSupport;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
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
