package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds canonical normalized docs from Bybit source rows.
 */
@Component
public class BybitCanonicalTransactionBuilder {

    private static final Set<String> STABLECOIN_SYMBOLS = Set.of(
            "USDT",
            "USDC",
            "USDE",
            "USDS",
            "USDD",
            "DAI",
            "FDUSD",
            "PYUSD",
            "TUSD",
            "USD1"
    );

    public NormalizedTransaction buildTradePair(
            ExternalLedgerRaw left,
            ExternalLedgerRaw right,
            Instant now
    ) {
        NormalizedTransaction transaction = baseTransaction(pairId(left, right), left, NormalizedTransactionType.SWAP, now);
        TradeLeg buyLeg;
        TradeLeg sellLeg;
        try {
            buyLeg = resolveTradeLeg(left);
            sellLeg = resolveTradeLeg(right);
        } catch (IllegalStateException error) {
            return buildMalformedTrade(left, now, "UTA_TRADE_ROLE_MISSING");
        }
        if (buyLeg.role() == sellLeg.role()) {
            return buildMalformedTrade(left, now, "UTA_TRADE_ROLE_AMBIGUOUS");
        }
        ExternalLedgerRaw buyRow = buyLeg.role() == NormalizedLegRole.BUY ? left : right;
        ExternalLedgerRaw sellRow = sellLeg.role() == NormalizedLegRole.SELL ? right : left;
        BigDecimal executionPrice = firstNonNull(buyRow.getFilledPrice(), sellRow.getFilledPrice());

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        FlowPricing buyPricing = tradeFlowPricing(
                buyRow.getAssetSymbol(),
                NormalizedLegRole.BUY,
                buyRow.getAssetSymbol(),
                sellRow.getAssetSymbol(),
                executionPrice
        );
        flows.add(flow(
                NormalizedLegRole.BUY,
                buyRow.getAssetSymbol(),
                abs(buyRow.getQuantityRaw()),
                buyPricing.unitPriceUsd(),
                buyPricing.priceSource()
        ));
        FlowPricing sellPricing = tradeFlowPricing(
                sellRow.getAssetSymbol(),
                NormalizedLegRole.SELL,
                buyRow.getAssetSymbol(),
                sellRow.getAssetSymbol(),
                executionPrice
        );
        flows.add(flow(
                NormalizedLegRole.SELL,
                sellRow.getAssetSymbol(),
                negate(abs(sellRow.getQuantityRaw())),
                sellPricing.unitPriceUsd(),
                sellPricing.priceSource()
        ));
        appendFeeFlow(flows, firstFeeRow(left, right), buyRow, sellRow, executionPrice);
        transaction.setFlows(flows);
        initializeStatus(transaction, now);
        return transaction;
    }

    public NormalizedTransaction buildOrphanTrade(
            ExternalLedgerRaw row,
            Instant now
    ) {
        TradeLeg tradeLeg;
        try {
            tradeLeg = resolveTradeLeg(row);
        } catch (IllegalStateException error) {
            return buildMalformedTrade(row, now, "UTA_TRADE_ROLE_MISSING");
        }
        NormalizedLegRole role = tradeLeg.role();
        NormalizedTransactionType type = role == NormalizedLegRole.BUY
                ? NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                : NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;

        NormalizedTransaction transaction = baseTransaction(row.getId(), row, type, now);
        FlowPricing pricing = orphanTradePricing(row);
        List<NormalizedTransaction.Flow> flows = List.of(flow(
                role,
                row.getAssetSymbol(),
                role == NormalizedLegRole.BUY ? abs(row.getQuantityRaw()) : negate(abs(row.getQuantityRaw())),
                pricing.unitPriceUsd(),
                pricing.priceSource()
        ));
        transaction.setFlows(new ArrayList<>(flows));
        transaction.setMissingDataReasons(List.of("UTA_TRADE_PAIR_NOT_FOUND"));
        transaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        markExcludedFromAccounting(transaction, "UTA_TRADE_PAIR_NOT_FOUND");
        return transaction;
    }

    public NormalizedTransaction buildMappedRow(
            ExternalLedgerRaw row,
            Instant now
    ) {
        Optional<NormalizedTransactionType> mappedType = mapCanonicalType(row.getCanonicalType());
        if (mappedType.isEmpty() && hasExplicitBasisRelevantCanonicalType(row)) {
            return buildNeedsReviewRow(row, now, "BYBIT_CANONICAL_TYPE_UNMAPPED");
        }
        NormalizedTransactionType type = mappedType.orElse(NormalizedTransactionType.UNKNOWN);
        NormalizedTransaction transaction = baseTransaction(normalizedId(row), row, type, now);
        transaction.setFlows(new ArrayList<>(mappedFlows(row, type)));
        initializeStatus(transaction, now);
        return transaction;
    }

    public String canonicalId(ExternalLedgerRaw row) {
        return normalizedId(row);
    }

    public NormalizedTransaction buildConvertCluster(
            List<ExternalLedgerRaw> rows,
            Instant now
    ) {
        ExternalLedgerRaw anchor = rows.stream()
                .min(Comparator.comparing(ExternalLedgerRaw::getTimeUtc).thenComparing(ExternalLedgerRaw::getId))
                .orElseThrow();
        NormalizedTransaction transaction = baseTransaction(clusterId("convert", rows), anchor, NormalizedTransactionType.SWAP, now);
        transaction.setFlows(aggregateClusterFlows(rows));
        initializeStatus(transaction, now);
        return transaction;
    }

    public NormalizedTransaction buildStakingPair(
            ExternalLedgerRaw left,
            ExternalLedgerRaw right,
            Instant now
    ) {
        ExternalLedgerRaw anchor = left.getId().compareTo(right.getId()) <= 0 ? left : right;
        NormalizedTransaction transaction = baseTransaction(pairId(left, right), anchor, NormalizedTransactionType.STAKING_DEPOSIT, now);
        transaction.setFlows(stakingPairFlows(left, right));
        initializeStatus(transaction, now);
        return transaction;
    }

    public NormalizedTransaction buildNeedsReviewRow(
            ExternalLedgerRaw row,
            Instant now,
            String missingReason
    ) {
        NormalizedTransactionType type = mapCanonicalType(row.getCanonicalType())
                .orElse(NormalizedTransactionType.UNKNOWN);
        NormalizedTransaction transaction = baseTransaction(normalizedId(row), row, type, now);
        transaction.setFlows(new ArrayList<>());
        transaction.setMissingDataReasons(List.of(missingReason));
        transaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        return transaction;
    }

    public NormalizedTransaction buildExcludedReviewRow(
            ExternalLedgerRaw row,
            Instant now,
            String missingReason
    ) {
        NormalizedTransaction transaction = buildNeedsReviewRow(row, now, missingReason);
        markExcludedFromAccounting(transaction, missingReason);
        return transaction;
    }

    public void markMatchedContinuityCandidate(
            NormalizedTransaction transaction,
            String correlationId,
            String matchedCounterparty,
            Instant now
    ) {
        transaction.setCorrelationId(correlationId);
        transaction.setContinuityCandidate(true);
        transaction.setMatchedCounterparty(matchedCounterparty);
        if (transaction.getStatus() != NormalizedTransactionStatus.NEEDS_REVIEW
                && transaction.getStatus() != NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
            transaction.setConfirmedAt(transaction.getConfirmedAt() != null ? transaction.getConfirmedAt() : now);
        }
        transaction.setUpdatedAt(now);
    }

    public void markExternalCustodyExcluded(
            NormalizedTransaction transaction,
            Instant now,
            String exclusionReason
    ) {
        markExcludedContinuityLikeRow(transaction, now, exclusionReason);
    }

    public void markTransferShadowExcluded(
            NormalizedTransaction transaction,
            Instant now,
            String exclusionReason
    ) {
        markConfirmedExcludedContinuityLikeRow(transaction, now, exclusionReason);
    }

    private void markExcludedContinuityLikeRow(
            NormalizedTransaction transaction,
            Instant now,
            String exclusionReason
    ) {
        transaction.setCorrelationId(null);
        transaction.setMatchedCounterparty(null);
        transaction.setContinuityCandidate(false);
        transaction.setConfirmedAt(null);
        transaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        transaction.setUpdatedAt(now);
        transaction.getMissingDataReasons().remove("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        if (!transaction.getMissingDataReasons().contains(exclusionReason)) {
            transaction.getMissingDataReasons().add(exclusionReason);
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            flow.setRole(NormalizedLegRole.TRANSFER);
            flow.setUnitPriceUsd(null);
            flow.setValueUsd(null);
            flow.setPriceSource(null);
        }
        markExcludedFromAccounting(transaction, exclusionReason);
    }

    private void markConfirmedExcludedContinuityLikeRow(
            NormalizedTransaction transaction,
            Instant now,
            String exclusionReason
    ) {
        transaction.setCorrelationId(null);
        transaction.setMatchedCounterparty(null);
        transaction.setContinuityCandidate(false);
        transaction.setConfirmedAt(transaction.getConfirmedAt() != null ? transaction.getConfirmedAt() : now);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setUpdatedAt(now);
        transaction.getMissingDataReasons().remove("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        if (!transaction.getMissingDataReasons().contains(exclusionReason)) {
            transaction.getMissingDataReasons().add(exclusionReason);
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            flow.setRole(NormalizedLegRole.TRANSFER);
            flow.setUnitPriceUsd(null);
            flow.setValueUsd(null);
            flow.setPriceSource(null);
        }
        markExcludedFromAccounting(transaction, exclusionReason);
    }

    private NormalizedTransaction baseTransaction(
            String id,
            ExternalLedgerRaw row,
            NormalizedTransactionType type,
            Instant now
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash(row.getTxHash());
        transaction.setNetworkId(row.getNetworkId());
        transaction.setWalletAddress(row.getWalletRef() != null && !row.getWalletRef().isBlank()
                ? row.getWalletRef()
                : "BYBIT:" + row.getUid());
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setBlockTimestamp(row.getTimeUtc());
        transaction.setTransactionIndex(0);
        transaction.setType(type);
        transaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        transaction.setClarificationAttempts(0);
        transaction.setFullReceiptClarificationAttempts(0);
        transaction.setPricingAttempts(0);
        transaction.setStatAttempts(0);
        transaction.setCreatedAt(now);
        transaction.setUpdatedAt(now);
        transaction.setMissingDataReasons(new ArrayList<>());
        transaction.setExcludedFromAccounting(false);
        transaction.setCounterpartyAddress(initialCounterpartyAddress(row, type));
        return transaction;
    }

    private void markExcludedFromAccounting(
            NormalizedTransaction transaction,
            String exclusionReason
    ) {
        transaction.setExcludedFromAccounting(true);
        transaction.setAccountingExclusionReason(exclusionReason);
    }

    private void initializeStatus(NormalizedTransaction transaction, Instant now) {
        boolean hasBuyOrSell = transaction.getFlows().stream()
                .anyMatch(flow -> flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL);
        if (hasBuyOrSell) {
            transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            return;
        }
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setConfirmedAt(now);
    }

    private List<NormalizedTransaction.Flow> mappedFlows(
            ExternalLedgerRaw row,
            NormalizedTransactionType type
    ) {
        BigDecimal quantity = abs(row.getQuantityRaw());
        return switch (type) {
            case REWARD_CLAIM -> List.of(flow(
                    NormalizedLegRole.BUY,
                    row.getAssetSymbol(),
                    rewardClaimQuantity(row),
                    null,
                    null
            ));
            case VAULT_DEPOSIT -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), negate(quantity), null, null));
            case VAULT_WITHDRAW -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), quantity, null, null));
            case EXTERNAL_TRANSFER_IN -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), quantity, null, null));
            case EXTERNAL_TRANSFER_OUT -> List.of(flow(NormalizedLegRole.TRANSFER, row.getAssetSymbol(), negate(quantity), null, null));
            case BORROW -> List.of(flow(NormalizedLegRole.SELL, row.getAssetSymbol(), negate(quantity), null, null));
            case REPAY -> List.of(flow(NormalizedLegRole.BUY, row.getAssetSymbol(), quantity, null, null));
            case STAKING_DEPOSIT, STAKING_WITHDRAW -> List.of(flow(
                    NormalizedLegRole.TRANSFER,
                    row.getAssetSymbol(),
                    signedQuantity(row),
                    null,
                    null
            ));
            default -> List.of();
        };
    }

    private List<NormalizedTransaction.Flow> stakingPairFlows(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (!sameContinuityFamily(left, right)) {
            return aggregateClusterFlows(List.of(left, right));
        }
        return List.of(
                flow(NormalizedLegRole.TRANSFER, left.getAssetSymbol(), signedQuantity(left), null, null),
                flow(NormalizedLegRole.TRANSFER, right.getAssetSymbol(), signedQuantity(right), null, null)
        );
    }

    private Optional<NormalizedTransactionType> mapCanonicalType(String canonicalType) {
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

    private String mappedCanonicalLiteral(String canonicalType) {
        return mapCanonicalType(canonicalType)
                .map(Enum::name)
                .orElseGet(() -> normalizeCanonicalLiteral(canonicalType));
    }

    private String normalizeCanonicalLiteral(String canonicalType) {
        if (canonicalType == null || canonicalType.isBlank()) {
            return null;
        }
        String normalized = canonicalType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EXTERNAL_INBOUND" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            default -> normalized;
        };
    }

    private String initialCounterpartyAddress(
            ExternalLedgerRaw row,
            NormalizedTransactionType type
    ) {
        if (row == null || type == null) {
            return null;
        }
        return switch (type) {
            case EXTERNAL_TRANSFER_IN -> blankToNull(row.getSenderAddress());
            case EXTERNAL_TRANSFER_OUT -> blankToNull(row.getReceivedAddress());
            default -> null;
        };
    }

    private void appendFeeFlow(
            List<NormalizedTransaction.Flow> flows,
            ExternalLedgerRaw feeRow,
            ExternalLedgerRaw buyRow,
            ExternalLedgerRaw sellRow,
            BigDecimal executionPrice
    ) {
        if (feeRow == null || feeRow.getFeePaid() == null || feeRow.getFeePaid().signum() == 0) {
            return;
        }
        FlowPricing feePricing = feeFlowPricing(feeRow, buyRow, sellRow, executionPrice);
        flows.add(flow(
                NormalizedLegRole.FEE,
                feeRow.getAssetSymbol(),
                negate(abs(feeRow.getFeePaid())),
                feePricing.unitPriceUsd(),
                feePricing.priceSource()
        ));
    }

    private ExternalLedgerRaw firstFeeRow(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (left.getFeePaid() != null && left.getFeePaid().signum() != 0) {
            return left;
        }
        if (right.getFeePaid() != null && right.getFeePaid().signum() != 0) {
            return right;
        }
        return null;
    }

    private String pairId(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        return left.getId().compareTo(right.getId()) <= 0
                ? left.getId() + "|" + right.getId()
                : right.getId() + "|" + left.getId();
    }

    private String clusterId(String prefix, List<ExternalLedgerRaw> rows) {
        return prefix + ":" + rows.stream()
                .map(ExternalLedgerRaw::getId)
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private String normalizedId(ExternalLedgerRaw row) {
        String canonicalIdLiteral = mappedCanonicalLiteral(row.getCanonicalType());
        if ("withdraw_deposit".equals(normalize(row.getSourceFileType()))
                && row.getTxHash() != null
                && !row.getTxHash().isBlank()
                && row.getNetworkId() != null
                && canonicalIdLiteral != null
                && row.getAssetSymbol() != null
                && row.getQuantityRaw() != null) {
            return String.join(":",
                    "BYBIT",
                    normalize(row.getUid()),
                    "withdraw_deposit",
                    row.getNetworkId().name(),
                    row.getTxHash().toLowerCase(Locale.ROOT),
                    canonicalIdLiteral,
                    row.getAssetSymbol(),
                    abs(row.getQuantityRaw()).stripTrailingZeros().toPlainString()
            );
        }
        return row.getId();
    }

    private NormalizedTransaction buildMalformedTrade(
            ExternalLedgerRaw row,
            Instant now,
            String missingReason
    ) {
        NormalizedTransaction transaction = baseTransaction(row.getId(), row, NormalizedTransactionType.UNKNOWN, now);
        transaction.setFlows(new ArrayList<>());
        transaction.setMissingDataReasons(List.of(missingReason));
        transaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        return transaction;
    }

    private TradeLeg resolveTradeLeg(ExternalLedgerRaw row) {
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

    private record TradeLeg(NormalizedLegRole role) {
    }

    private List<NormalizedTransaction.Flow> aggregateClusterFlows(List<ExternalLedgerRaw> rows) {
        Map<FlowKey, BigDecimal> aggregated = new LinkedHashMap<>();
        for (ExternalLedgerRaw row : rows) {
            if (row.getQuantityRaw() == null || row.getQuantityRaw().signum() == 0 || row.getAssetSymbol() == null) {
                continue;
            }
            NormalizedLegRole role = row.getQuantityRaw().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            BigDecimal quantity = role == NormalizedLegRole.BUY
                    ? abs(row.getQuantityRaw())
                    : negate(abs(row.getQuantityRaw()));
            FlowKey key = new FlowKey(role, row.getAssetSymbol());
            aggregated.merge(key, quantity, BigDecimal::add);
        }
        return aggregated.entrySet().stream()
                .map(entry -> flow(entry.getKey().role(), entry.getKey().assetSymbol(), entry.getValue(), null, null))
                .toList();
    }

    private NormalizedTransaction.Flow flow(
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

    private BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    private BigDecimal negate(BigDecimal value) {
        return value == null ? null : value.negate();
    }

    private BigDecimal signedQuantity(ExternalLedgerRaw row) {
        if (row == null || row.getQuantityRaw() == null) {
            return null;
        }
        BigDecimal quantity = abs(row.getQuantityRaw());
        return row.getQuantityRaw().signum() < 0 ? negate(quantity) : quantity;
    }

    private BigDecimal firstNonNull(BigDecimal left, BigDecimal right) {
        return left != null ? left : right;
    }

    private FlowPricing orphanTradePricing(ExternalLedgerRaw row) {
        if (isStablecoin(row.getAssetSymbol())) {
            return new FlowPricing(BigDecimal.ONE, PriceSource.STABLECOIN);
        }
        return row.getFilledPrice() == null
                ? FlowPricing.none()
                : new FlowPricing(row.getFilledPrice(), PriceSource.EXECUTION);
    }

    private FlowPricing feeFlowPricing(
            ExternalLedgerRaw feeRow,
            ExternalLedgerRaw buyRow,
            ExternalLedgerRaw sellRow,
            BigDecimal executionPrice
    ) {
        if (isStablecoin(feeRow.getAssetSymbol())) {
            return new FlowPricing(BigDecimal.ONE, PriceSource.STABLECOIN);
        }
        if (symbolEquals(feeRow.getAssetSymbol(), buyRow.getAssetSymbol())) {
            return tradeFlowPricing(
                    feeRow.getAssetSymbol(),
                    NormalizedLegRole.BUY,
                    buyRow.getAssetSymbol(),
                    sellRow.getAssetSymbol(),
                    executionPrice
            );
        }
        if (symbolEquals(feeRow.getAssetSymbol(), sellRow.getAssetSymbol())) {
            return tradeFlowPricing(
                    feeRow.getAssetSymbol(),
                    NormalizedLegRole.SELL,
                    buyRow.getAssetSymbol(),
                    sellRow.getAssetSymbol(),
                    executionPrice
            );
        }
        return FlowPricing.none();
    }

    private FlowPricing tradeFlowPricing(
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
        return role == NormalizedLegRole.BUY
                ? new FlowPricing(executionPrice, PriceSource.EXECUTION)
                : FlowPricing.none();
    }

    private boolean isStablecoin(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        return STABLECOIN_SYMBOLS.contains(assetSymbol.trim().toUpperCase(Locale.ROOT));
    }

    private boolean symbolEquals(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private boolean sameContinuityFamily(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left == null ? null : left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right == null ? null : right.getAssetSymbol(), null);
        return leftFamily != null
                && leftFamily.startsWith("FAMILY:")
                && leftFamily.equals(rightFamily);
    }

    private BigDecimal rewardClaimQuantity(ExternalLedgerRaw row) {
        BigDecimal quantity = firstNonZero(abs(row.getQuantityRaw()), abs(row.getCashFlow()));
        quantity = firstNonZero(quantity, abs(row.getChange()));
        return quantity;
    }

    private BigDecimal firstNonZero(BigDecimal left, BigDecimal right) {
        if (left != null && left.signum() != 0) {
            return left;
        }
        return right;
    }

    private boolean hasExplicitBasisRelevantCanonicalType(ExternalLedgerRaw row) {
        return Boolean.TRUE.equals(row.getBasisRelevant())
                && row.getCanonicalType() != null
                && !row.getCanonicalType().isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record FlowKey(NormalizedLegRole role, String assetSymbol) {
    }

    private record FlowPricing(BigDecimal unitPriceUsd, PriceSource priceSource) {
        private static FlowPricing none() {
            return new FlowPricing(null, null);
        }
    }
}
