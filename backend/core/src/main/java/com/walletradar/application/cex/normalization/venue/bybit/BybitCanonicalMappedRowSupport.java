package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
class BybitCanonicalMappedRowSupport {

    record TradeLeg(NormalizedLegRole role) {
    }

    record FlowKey(NormalizedLegRole role, String assetSymbol) {
    }

    record FlowPricing(BigDecimal unitPriceUsd, PriceSource priceSource) {
        private static FlowPricing none() {
            return new FlowPricing(null, null);
        }
    }

    Optional<NormalizedTransactionType> resolveEarnLifecycleCanonicalType(ExternalLedgerRaw row) {
        if (row == null || !"Earn".equalsIgnoreCase(normalize(row.getBybitType()))) {
            return Optional.empty();
        }
        String description = normalize(row.getBybitDescription());
        if (description == null) {
            return Optional.empty();
        }
        String lower = description.toLowerCase(Locale.ROOT);
        if (lower.contains("launchpool") && lower.contains("subscription")) {
            return Optional.of(NormalizedTransactionType.LENDING_DEPOSIT);
        }
        if (lower.contains("launchpool")
                && (lower.contains("auto-withdrawal")
                || lower.contains("auto withdrawal")
                || lower.contains("manual withdrawal")
                || lower.contains("manual-withdrawal")
                || lower.contains("withdrawal")
                || lower.contains("withdraw"))) {
            return Optional.of(NormalizedTransactionType.LENDING_WITHDRAW);
        }
        if (lower.contains("fixed") && (lower.contains("redemption") || lower.contains("principal redemption"))) {
            return Optional.of(NormalizedTransactionType.LENDING_WITHDRAW);
        }
        if (lower.contains("flexible") && (lower.contains("redemption") || lower.contains("principal redemption"))) {
            return Optional.of(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        }
        if (lower.contains("flexible") && lower.contains("subscription") && !lower.contains("interest")) {
            return Optional.of(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        }
        return Optional.empty();
    }

    Optional<NormalizedTransactionType> mapCanonicalType(String canonicalType) {
        String normalized = normalizeCanonicalLiteral(canonicalType);
        if (normalized == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(NormalizedTransactionType.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    String mappedCanonicalLiteral(String canonicalType) {
        return mapCanonicalType(canonicalType)
                .map(Enum::name)
                .orElseGet(() -> normalizeCanonicalLiteral(canonicalType));
    }

    String normalizeCanonicalLiteral(String canonicalType) {
        if (canonicalType == null || canonicalType.isBlank()) {
            return null;
        }
        String normalized = canonicalType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EXTERNAL_INBOUND" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            case "EXTERNAL_IN_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            case "EXTERNAL_OUT_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_OUT.name();
            default -> normalized;
        };
    }

    String initialCounterpartyAddress(ExternalLedgerRaw row, NormalizedTransactionType type) {
        if (row == null || type == null) {
            return null;
        }
        return switch (type) {
            case EXTERNAL_TRANSFER_IN -> blankToNull(row.getSenderAddress());
            case EXTERNAL_TRANSFER_OUT -> blankToNull(row.getReceivedAddress());
            default -> null;
        };
    }

    TradeLeg resolveTradeLeg(ExternalLedgerRaw row) {
        if (row.getQuantityRaw() != null && row.getQuantityRaw().signum() > 0) {
            return new TradeLeg(NormalizedLegRole.BUY);
        }
        if (row.getQuantityRaw() != null && row.getQuantityRaw().signum() < 0) {
            return new TradeLeg(NormalizedLegRole.SELL);
        }
        if ("BUY".equalsIgnoreCase(row.getUtaLegRole()) || "BUY".equalsIgnoreCase(row.getUtaDirection())) {
            return new TradeLeg(NormalizedLegRole.BUY);
        }
        if ("SELL".equalsIgnoreCase(row.getUtaLegRole()) || "SELL".equalsIgnoreCase(row.getUtaDirection())) {
            return new TradeLeg(NormalizedLegRole.SELL);
        }
        throw new IllegalStateException("UTA trade row is missing leg role");
    }

    List<NormalizedTransaction.Flow> mappedFlows(ExternalLedgerRaw row, NormalizedTransactionType type) {
        BigDecimal quantity = abs(row.getQuantityRaw());
        return switch (type) {
            case REWARD_CLAIM -> List.of(flow(
                    NormalizedLegRole.BUY, row.getAssetSymbol(), rewardClaimQuantity(row), null, null));
            case VAULT_DEPOSIT -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), negate(quantity), null, null));
            case VAULT_WITHDRAW -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), quantity, null, null));
            case EXTERNAL_TRANSFER_IN -> List.of(flow(NormalizedLegRole.BUY, row.getAssetSymbol(), quantity, null, null));
            case EXTERNAL_TRANSFER_OUT -> List.of(flow(NormalizedLegRole.SELL, row.getAssetSymbol(), negate(quantity), null, null));
            case BORROW -> List.of(flow(NormalizedLegRole.BUY, row.getAssetSymbol(), abs(signedQuantity(row)), null, null));
            case REPAY -> List.of(flow(NormalizedLegRole.SELL, row.getAssetSymbol(), negate(abs(signedQuantity(row))), null, null));
            case STAKING_DEPOSIT, STAKING_WITHDRAW, LENDING_DEPOSIT, LENDING_WITHDRAW, EARN_FLEXIBLE_SAVING,
                 INTERNAL_TRANSFER -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), signedQuantity(row), null, null));
            case FEE -> List.of(flow(NormalizedLegRole.FEE, row.getAssetSymbol(), negate(abs(signedQuantity(row))), null, null));
            default -> List.of();
        };
    }

    List<NormalizedTransaction.Flow> stakingPairFlows(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (!sameContinuityFamily(left, right)) {
            return aggregateClusterFlows(List.of(left, right));
        }
        return List.of(
                flow(NormalizedLegRole.TRANSFER, left.getAssetSymbol(), signedQuantity(left), null, null),
                flow(NormalizedLegRole.TRANSFER, right.getAssetSymbol(), signedQuantity(right), null, null)
        );
    }

    List<NormalizedTransaction.Flow> aggregateClusterFlows(List<ExternalLedgerRaw> rows) {
        Map<FlowKey, BigDecimal> aggregated = new LinkedHashMap<>();
        for (ExternalLedgerRaw row : rows) {
            if (row.getQuantityRaw() == null || row.getQuantityRaw().signum() == 0 || row.getAssetSymbol() == null) {
                continue;
            }
            NormalizedLegRole role = row.getQuantityRaw().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            BigDecimal quantity = role == NormalizedLegRole.BUY ? abs(row.getQuantityRaw()) : negate(abs(row.getQuantityRaw()));
            aggregated.merge(new FlowKey(role, row.getAssetSymbol()), quantity, BigDecimal::add);
        }
        return aggregated.entrySet().stream()
                .map(e -> flow(e.getKey().role(), e.getKey().assetSymbol(), e.getValue(), null, null))
                .toList();
    }

    NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(quantityDelta);
        BigDecimal persistedUnitPriceUsd = Decimal128Support.normalize(unitPriceUsd);
        flow.setUnitPriceUsd(persistedUnitPriceUsd);
        flow.setPriceSource(priceSource);
        flow.setValueUsd(persistedUnitPriceUsd == null || quantityDelta == null
                ? null
                : Decimal128Support.normalize(quantityDelta.abs().multiply(persistedUnitPriceUsd)));
        return flow;
    }

    FlowPricing orphanTradePricing(ExternalLedgerRaw row) {
        if (isStablecoin(row.getAssetSymbol())) {
            return new FlowPricing(BigDecimal.ONE, PriceSource.STABLECOIN);
        }
        return row.getFilledPrice() == null ? FlowPricing.none() : new FlowPricing(row.getFilledPrice(), PriceSource.EXECUTION);
    }

    FlowPricing tradeFlowPricing(
            String assetSymbol,
            NormalizedLegRole role,
            String buyAssetSymbol,
            String sellAssetSymbol,
            BigDecimal executionPrice
    ) {
        if (isStablecoin(assetSymbol)) {
            return new FlowPricing(BigDecimal.ONE, PriceSource.STABLECOIN);
        }
        if (executionPrice == null) {
            return FlowPricing.none();
        }
        boolean buyStablecoin = isStablecoin(buyAssetSymbol);
        boolean sellStablecoin = isStablecoin(sellAssetSymbol);
        if (buyStablecoin ^ sellStablecoin) {
            String pricedAsset = buyStablecoin ? sellAssetSymbol : buyAssetSymbol;
            if (symbolEquals(assetSymbol, pricedAsset)) {
                return new FlowPricing(executionPrice, PriceSource.EXECUTION);
            }
            return FlowPricing.none();
        }
        return role == NormalizedLegRole.BUY ? new FlowPricing(executionPrice, PriceSource.EXECUTION) : FlowPricing.none();
    }

    boolean symbolEquals(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    boolean sameContinuityFamily(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (AccountingAssetClassificationSupport.sharesLiquidStakingNormalizationCluster(
                left == null ? null : left.getAssetSymbol(),
                right == null ? null : right.getAssetSymbol()
        )) {
            return true;
        }
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left == null ? null : left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right == null ? null : right.getAssetSymbol(), null);
        return leftFamily != null && leftFamily.startsWith("FAMILY:") && leftFamily.equals(rightFamily);
    }

    BigDecimal rewardClaimQuantity(ExternalLedgerRaw row) {
        BigDecimal quantity = firstNonZero(abs(row.getQuantityRaw()), abs(row.getCashFlow()));
        return firstNonZero(quantity, abs(row.getChange()));
    }

    BigDecimal firstNonZero(BigDecimal left, BigDecimal right) {
        if (left != null && left.signum() != 0) {
            return left;
        }
        return right;
    }

    boolean hasExplicitBasisRelevantCanonicalType(ExternalLedgerRaw row) {
        return Boolean.TRUE.equals(row.getBasisRelevant())
                && row.getCanonicalType() != null
                && !row.getCanonicalType().isBlank();
    }

    BigDecimal netExecutionLegQuantity(ExternalLedgerRaw leg) {
        if (leg == null || leg.getQuantityRaw() == null) {
            return null;
        }
        BigDecimal fee = leg.getFeePaid() != null ? leg.getFeePaid() : BigDecimal.ZERO;
        return leg.getQuantityRaw().add(fee);
    }

    /**
     * Computes the USD equivalent of the buy-side commission charged on a Bybit spot BUY leg.
     *
     * <p>Bybit charges the taker fee in the received (base) asset. The fee is already netted into
     * the received quantity via {@link #netExecutionLegQuantity}. This method converts the raw fee
     * back to USD so the replay engine can add it to Net AVCO without affecting Market AVCO.
     *
     * <ul>
     *   <li>If the base asset is a USD-stablecoin, effective price = 1.0.</li>
     *   <li>Otherwise, effective price = execution fill price (falls back to {@code executionPrice}).</li>
     * </ul>
     *
     * @return non-null positive value if a fee can be computed, {@code null} otherwise
     */
    BigDecimal acquisitionFeeUsd(
            ExternalLedgerRaw buyRow,
            FlowPricing buyPricing,
            BigDecimal executionPrice
    ) {
        if (buyRow == null || buyRow.getFeePaid() == null || buyRow.getFeePaid().signum() == 0) {
            return null;
        }
        BigDecimal feeAbs = buyRow.getFeePaid().abs();
        // Use the already-resolved unit price; fall back to raw execution price for non-stable pairs
        // where buyPricing may be null/empty (SWAP_DERIVED path).
        BigDecimal unitPrice = buyPricing != null && buyPricing.unitPriceUsd() != null
                ? buyPricing.unitPriceUsd()
                : (isStablecoin(buyRow.getAssetSymbol()) ? BigDecimal.ONE : executionPrice);
        if (unitPrice == null || unitPrice.signum() <= 0) {
            return null;
        }
        return feeAbs.multiply(unitPrice);
    }

    BigDecimal signedQuantity(ExternalLedgerRaw row) {
        if (row == null || row.getQuantityRaw() == null) {
            return null;
        }
        BigDecimal quantity = abs(row.getQuantityRaw());
        return row.getQuantityRaw().signum() < 0 ? negate(quantity) : quantity;
    }

    BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    BigDecimal negate(BigDecimal value) {
        return value == null ? null : value.negate();
    }

    BigDecimal firstNonNull(BigDecimal left, BigDecimal right) {
        return left != null ? left : right;
    }

    boolean isStablecoin(String assetSymbol) {
        return BybitStablecoinPegSymbols.isPegged(assetSymbol);
    }

    String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
