package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyType;
import com.walletradar.ingestion.pipeline.clarification.FlowCounterpartySupport;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.session.application.AccountingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds canonical normalized docs from Bybit source rows.
 */
@Component
public class BybitCanonicalTransactionBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(BybitCanonicalTransactionBuilder.class);

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

    private static final Pattern SELF_TRANSFER_PATTERN =
            Pattern.compile("selfTransfer_([0-9a-fA-F-]{36})");
    private static final Pattern UNI_TRANS_PATTERN =
            Pattern.compile("uni_trans_([0-9a-fA-F-]{36})");
    private static final String BYBIT_PREFIX = "BYBIT:";
    private static final String FIAT_P2P_COUNTERPARTY = "FIAT:P2P";
    private static final String TX_HASH_MISSING_BYBIT_CORRIDOR = "TX_HASH_MISSING_BYBIT_CORRIDOR";
    /**
     * FA-001 P0: synthetic counterparty stamped on Bybit EXTERNAL_TRANSFER rows when the chain-side
     * address is genuinely missing from the API payload. The address is namespaced by network so
     * the row still routes through {@link FlowCounterpartySupport#applyTransactionCounterparty} and
     * stat validation accepts a non-blank counterparty. Cross-system linking
     * ({@link com.walletradar.ingestion.pipeline.clarification.BybitTransferContinuityRepairService})
     * remains driven by {@code txHash}, so this synthetic address never participates in FA-001
     * pairing — its sole purpose is to keep the conservation gate honest about untracked custody.
     */
    private static final String BYBIT_HOT_WALLET_PREFIX = "BYBIT:HOT_WALLET:";

    private final AccountingUniverseService accountingUniverseService;

    public BybitCanonicalTransactionBuilder() {
        this(null);
    }

    @Autowired
    public BybitCanonicalTransactionBuilder(@Nullable AccountingUniverseService accountingUniverseService) {
        this.accountingUniverseService = accountingUniverseService;
    }

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
        BigDecimal buyNet = netExecutionLegQuantity(buyRow);
        BigDecimal sellNet = netExecutionLegQuantity(sellRow);
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
                buyNet,
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
                sellNet,
                sellPricing.unitPriceUsd(),
                sellPricing.priceSource()
        ));
        transaction.setFlows(flows);
        finalizeBybitFlows(transaction, left);
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
        BigDecimal netLeg = netExecutionLegQuantity(row);
        if (netLeg == null) {
            return buildMalformedTrade(row, now, "UTA_TRADE_QTY_MISSING");
        }
        List<NormalizedTransaction.Flow> flows = List.of(flow(
                role,
                row.getAssetSymbol(),
                netLeg,
                pricing.unitPriceUsd(),
                pricing.priceSource()
        ));
        transaction.setFlows(new ArrayList<>(flows));
        finalizeBybitFlows(transaction, row);
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
        type = resolveEarnLifecycleCanonicalType(row).orElse(type);
        NormalizedTransaction transaction = baseTransaction(normalizedId(row), row, type, now);
        transaction.setFlows(new ArrayList<>(mappedFlows(row, type)));

        if (type == NormalizedTransactionType.INTERNAL_TRANSFER) {
            // Cycle/6 A1: always use the deterministic economy correlation id (uid|asset|abs(qty)|minute)
            // for INTERNAL_TRANSFER rows. The Bybit `selfTransfer_<uuid>` identifier is leg-local —
            // both sides of a real transfer carry DIFFERENT UUIDs, so the previous "sub-transfer"
            // correlation key produced 228 singleton legs that never paired in replay. The economy
            // correlation id is symmetric: both legs derive the same key from the shared signature.
            // The remaining cross-minute drift (~10% of pairs) is repaired by
            // {@code BybitInternalTransferPairer} after normalization.
            String correlationId = bybitInternalTransferEconomyCorrelationId(row);
            if (correlationId != null) {
                transaction.setCorrelationId(correlationId);
                transaction.setContinuityCandidate(true);
                String walletRef = row.getWalletRef();
                String masterUid = extractUid(walletRef);
                if (masterUid.isBlank()) {
                    masterUid = normalize(row.getUid());
                }
                String matched = resolveInternalTransferCounterparty(row, walletRef, masterUid);
                if (matched == null || matched.isBlank() || matched.equalsIgnoreCase(walletRef)) {
                    matched = fallbackInternalTransferCounterparty(walletRef, masterUid);
                }
                transaction.setMatchedCounterparty(matched);
            }
        }

        if (type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY) {
            String orderId = loanOrderKey(row);
            if (orderId != null && !orderId.isBlank()) {
                transaction.setCorrelationId(orderId);
            }
        }

        // Cycle/5 N1/N5: extraction marks non-authoritative mirror rows (FH/Deposit, TX_LOG/TRANSFER_IN, …)
        // with basisRelevant=false. They must not hit AVCO replay or they re-inflate inventory (phantom qty).
        if (Boolean.FALSE.equals(row.getBasisRelevant())) {
            markExcludedFromAccounting(transaction, "BYBIT_BASIS_IRRELEVANT");
        }

        recordMissingTxHashForBybitCorridor(transaction, row);
        finalizeBybitFlows(transaction, row);
        if (isBotTransfer(row)) {
            reclassifyBotTransfer(transaction, row, now);
        }
        applyStableUsdPegForExternalTransfers(transaction);
        initializeStatus(transaction, now);
        if (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            confirmWhenAllBuySellFlowsPriced(transaction, now);
        }
        if (isFiatP2pRow(row)) {
            confirmFiatP2pTransaction(transaction, row, now);
        }
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
        finalizeBybitFlows(transaction, anchor);
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
        finalizeBybitFlows(transaction, anchor);
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
                && transaction.getStatus() != NormalizedTransactionStatus.PENDING_CLARIFICATION
                && transaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
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
        // FA-001 P1: canonicalise per network so SOL/TON corridors compare correctly with on-chain rows.
        transaction.setTxHash(NetworkAddressFormat.canonicalTxHash(row.getNetworkId(), row.getTxHash()));
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
        // Cycle/15 R5 F3: Bybit pegged-native INTERNAL_TRANSFER (CMETH/METH/WEETH/BBSOL) needs a
        // pricing pass before stat/replay so applyPeggedNativeSpotFallback can promote residual uncov.
        boolean hasPeggedNativeTransfer = transaction.getFlows() != null
                && transaction.getFlows().stream().anyMatch(flow -> flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getAssetSymbol() != null
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && (transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN));
        if (hasBuyOrSell || hasPeggedNativeTransfer) {
            transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            return;
        }
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setConfirmedAt(now);
    }

    private void confirmWhenAllBuySellFlowsPriced(NormalizedTransaction transaction, Instant now) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return;
        }
        boolean allBuySellPriced = transaction.getFlows().stream()
                .filter(flow -> flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL)
                .allMatch(flow -> flow.getValueUsd() != null && flow.getValueUsd().signum() != 0);
        if (allBuySellPriced) {
            transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
            transaction.setConfirmedAt(now);
        }
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
            // Cycle/5 N16: external transfers are economic events (cash arrives/leaves the venue at
            // market value), aligned with on-chain classifier convention (OnChainClassificationSupport)
            // and indispensable for AVCO basis acquisition / disposal. Using TRANSFER role here pushed
            // these events into transfer-matching, which has no counterpart in the Bybit ledger and
            // silently leaked basis (quantityShortfallAfter accumulated, realized PnL never crystallised).
            // BUY/SELL roles route to applyBuy/applySell → ACQUIRE at market price (basis = qty × price)
            // and DISPOSE at market price (basis released, realized PnL = sell_price − avco).
            case EXTERNAL_TRANSFER_IN -> List.of(flow(NormalizedLegRole.BUY, row.getAssetSymbol(), quantity, null, null));
            case EXTERNAL_TRANSFER_OUT -> List.of(flow(NormalizedLegRole.SELL, row.getAssetSymbol(), negate(quantity), null, null));
            case BORROW -> List.of(flow(NormalizedLegRole.BUY, row.getAssetSymbol(), abs(signedQuantity(row)), null, null));
            case REPAY -> List.of(flow(NormalizedLegRole.SELL, row.getAssetSymbol(), negate(abs(signedQuantity(row))), null, null));
            case STAKING_DEPOSIT, STAKING_WITHDRAW -> List.of(flow(
                    NormalizedLegRole.TRANSFER,
                    row.getAssetSymbol(),
                    signedQuantity(row),
                    null,
                    null
            ));
            case LENDING_DEPOSIT, LENDING_WITHDRAW, EARN_FLEXIBLE_SAVING -> List.of(flow(
                    NormalizedLegRole.TRANSFER,
                    row.getAssetSymbol(),
                    signedQuantity(row),
                    null,
                    null
            ));
            case INTERNAL_TRANSFER -> List.of(flow(
                    NormalizedLegRole.TRANSFER,
                    row.getAssetSymbol(),
                    signedQuantity(row),
                    null,
                    null
            ));
            case FEE -> List.of(flow(
                    NormalizedLegRole.FEE,
                    row.getAssetSymbol(),
                    negate(abs(signedQuantity(row))),
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

    /**
     * Bybit Earn / Launchpool / Easy-Earn lifecycle rows are extracted as {@code INTERNAL_TRANSFER}
     * but are economically protocol custody (subscribe / redeem), not sub-account transfer pairs.
     */
    private Optional<NormalizedTransactionType> resolveEarnLifecycleCanonicalType(ExternalLedgerRaw row) {
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
        return Optional.empty();
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
            case "EXTERNAL_IN_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            case "EXTERNAL_OUT_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_OUT.name();
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
            // FA-001 P1: preserve Solana base58 case in the deterministic id so two FH/Withdraw rows
            // pointing at distinct on-chain signatures never collapse into a single normalized id.
            String canonicalTxHash = NetworkAddressFormat.canonicalTxHash(row.getNetworkId(), row.getTxHash());
            return String.join(":",
                    "BYBIT",
                    normalize(row.getUid()),
                    "withdraw_deposit",
                    row.getNetworkId().name(),
                    canonicalTxHash,
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

    private void finalizeBybitFlows(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        if (transaction == null || row == null) {
            return;
        }
        applyBybitFlowCounterparty(transaction, row);
        FlowCounterpartySupport.applyTransactionCounterparty(transaction);
    }

    private void applyBybitFlowCounterparty(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        String uid = normalize(row.getUid());
        String walletRef = resolveWalletRef(row);
        String masterUid = extractUid(walletRef);
        if (masterUid.isBlank()) {
            masterUid = uid;
        }
        String accountRef = walletRef;
        NormalizedTransactionType type = transaction.getType();

        if (isFiatP2pRow(row)) {
            String fundRef = BYBIT_PREFIX + masterUid + ":FUND";
            String fiatCounterparty = resolveFiatP2pCounterparty(row);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(fundRef);
                flow.setCounterpartyAddress(fiatCounterparty);
                flow.setCounterpartyType(CounterpartyType.CEX);
                applyStableUsdPegIfEligible(flow);
            }
            transaction.setCounterpartyAddress(fiatCounterparty);
            transaction.setCounterpartyType(CounterpartyType.CEX);
            return;
        }

        if (type == NormalizedTransactionType.SWAP) {
            String matchedBook = BYBIT_PREFIX + masterUid + ":MATCHED_BOOK";
            String convertKey = resolveConvertCounterpartyKey(row, masterUid);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(accountRef);
                flow.setCounterpartyAddress(convertKey != null ? convertKey : matchedBook);
                flow.setCounterpartyType(CounterpartyType.CEX);
            }
            return;
        }

        if (type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY) {
            String loanCounterparty = BYBIT_PREFIX + "LOAN:" + loanOrderKey(row);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(ledgerAccountRef(masterUid, walletRef, "UTA"));
                flow.setCounterpartyAddress(loanCounterparty);
                flow.setCounterpartyType(CounterpartyType.PROTOCOL);
            }
            return;
        }

        if (type == NormalizedTransactionType.REWARD_CLAIM) {
            String rewardCounterparty = BYBIT_PREFIX + "REWARD:" + normalizeRewardBusiType(row);
            String earnRef = ledgerAccountRef(masterUid, walletRef, "EARN");
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(earnRef);
                flow.setCounterpartyAddress(rewardCounterparty);
                flow.setCounterpartyType(CounterpartyType.PROTOCOL);
            }
            return;
        }

        if (type == NormalizedTransactionType.INTERNAL_TRANSFER) {
            String counterparty = resolveInternalTransferCounterparty(row, walletRef, masterUid);
            if (counterparty == null || counterparty.isBlank() || counterparty.equalsIgnoreCase(walletRef)) {
                LOGGER.warn(
                        "BYBIT_NORM_BAD_CP rowId={} walletRef={} bybitType={} bybitDescription={} sourceFile={} resolvedCp={}",
                        row == null ? null : row.getId(),
                        walletRef,
                        row == null ? null : row.getBybitType(),
                        row == null ? null : row.getBybitDescription(),
                        row == null ? null : row.getSourceFile(),
                        counterparty
                );
                counterparty = fallbackInternalTransferCounterparty(walletRef, masterUid);
            }
            transaction.setCounterpartyAddress(counterparty);
            transaction.setCounterpartyType(CounterpartyType.CEX);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(accountRef);
                flow.setCounterpartyAddress(counterparty);
                flow.setCounterpartyType(CounterpartyType.CEX);
            }
            return;
        }

        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            applyBybitExternalTransferCounterparty(transaction, row, masterUid, walletRef, /*inbound=*/true);
            return;
        }

        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            applyBybitExternalTransferCounterparty(transaction, row, masterUid, walletRef, /*inbound=*/false);
            return;
        }

        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            flow.setAccountRef(accountRef);
            String topLevel = transaction.getCounterpartyAddress();
            if (topLevel != null && !topLevel.isBlank()) {
                flow.setCounterpartyAddress(topLevel);
            }
            if (transaction.getCounterpartyType() != null) {
                flow.setCounterpartyType(transaction.getCounterpartyType());
            }
        }
    }

    /**
     * FA-001 P0: stamp a non-blank {@code counterpartyAddress} and {@code counterpartyType} on
     * Bybit external-transfer rows (Deposit / Withdraw) so the stat validation gate does not park
     * them as NEEDS_REVIEW with {@code STAT_COUNTERPARTY_TYPE_MISSING} / {@code FLOW_COUNTERPARTY_MISSING}.
     *
     * <p>The chain-side address comes from the {@code DEPOSIT_ONCHAIN} / {@code WITHDRAWAL} sibling
     * (Bybit's deposit-records / withdrawal-records APIs):
     * <ul>
     *   <li><b>EXTERNAL_TRANSFER_IN</b> (Bybit Deposit): {@code senderAddress} is the on-chain
     *       source — typically the user's external wallet ({@code PERSONAL_WALLET} when present in
     *       the accounting universe), otherwise an arbitrary EOA ({@code UNKNOWN_EOA}).</li>
     *   <li><b>EXTERNAL_TRANSFER_OUT</b> (Bybit Withdraw): {@code receivedAddress} is the on-chain
     *       destination — same classification rules.</li>
     * </ul>
     *
     * <p>Address namespace is preserved per-network via {@link NetworkAddressFormat}: EVM hex is
     * lower-cased, Solana base58 stays case-sensitive, TON addresses canonicalised. If the chain
     * address is genuinely absent (legacy CSV imports, FH-only rows that never linked to a chain
     * sibling) we stamp a synthetic {@code BYBIT:HOT_WALLET:<network>} key with type
     * {@code UNKNOWN_EOA}; the row still passes stat validation and the conservation gate flags
     * the missing custody track via {@code TX_HASH_MISSING_BYBIT_CORRIDOR}.</p>
     */
    private void applyBybitExternalTransferCounterparty(
            NormalizedTransaction transaction,
            ExternalLedgerRaw row,
            String masterUid,
            String walletRef,
            boolean inbound
    ) {
        NetworkId networkId = row.getNetworkId();
        String rawAddress = inbound ? row.getSenderAddress() : row.getReceivedAddress();
        String chainAddress = NetworkAddressFormat.canonicalAddress(networkId, rawAddress);
        boolean addressMissing = chainAddress == null || chainAddress.isBlank();
        if (addressMissing) {
            chainAddress = BYBIT_HOT_WALLET_PREFIX + (networkId == null ? "UNKNOWN" : networkId.name());
        }
        String counterpartyType = classifyExternalTransferCounterpartyType(
                addressMissing ? null : chainAddress,
                networkId
        );
        String fundRef = ledgerAccountRef(masterUid, walletRef, "FUND");
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            flow.setAccountRef(fundRef);
            flow.setCounterpartyAddress(chainAddress);
            flow.setCounterpartyType(counterpartyType);
        }
        transaction.setCounterpartyAddress(chainAddress);
        transaction.setCounterpartyType(counterpartyType);
    }

    /**
     * Counterparty classification reuses the accounting-universe binding when present:
     * <ul>
     *   <li>address in universe → {@link CounterpartyType#PERSONAL_WALLET} when ON_CHAIN_WALLET,
     *       {@link CounterpartyType#CEX} when EXCHANGE_ACCOUNT;</li>
     *   <li>address absent from universe (or universe not bound) → {@link CounterpartyType#UNKNOWN_EOA}.</li>
     * </ul>
     * Missing address (synthetic {@code BYBIT_HOT_WALLET}) is also {@code UNKNOWN_EOA}.
     */
    private String classifyExternalTransferCounterpartyType(String address, NetworkId networkId) {
        if (address != null && accountingUniverseService != null) {
            try {
                AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(address, networkId);
                if (membership.isMember()) {
                    AccountingUniverse.MemberType memberType = membership.memberType();
                    if (memberType == AccountingUniverse.MemberType.ON_CHAIN_WALLET) {
                        return CounterpartyType.PERSONAL_WALLET;
                    }
                    if (memberType == AccountingUniverse.MemberType.EXCHANGE_ACCOUNT) {
                        return CounterpartyType.CEX;
                    }
                }
            } catch (IllegalStateException ignored) {
                // Universe not bound on this thread; fall through to UNKNOWN_EOA.
            }
        }
        return CounterpartyType.UNKNOWN_EOA;
    }

    private static final String BOT_TRANSFER_MARKER = "BOT_TRANSFER";
    private static final String BOT_TRANSFER_PENDING_COST = "BOT_TRANSFER_PENDING_COST";

    private boolean isBotTransfer(ExternalLedgerRaw row) {
        return row != null && "Bot".equalsIgnoreCase(row.getBybitType() == null ? null : row.getBybitType().trim());
    }

    /**
     * Reclassifies Bot FUNDING_HISTORY events from INTERNAL_TRANSFER to EXTERNAL_TRANSFER_IN/OUT.
     * Bot sub-accounts are untracked black boxes; transfers to/from them are economic entries/exits.
     * <ul>
     *   <li>Positive qty (return from bot): EXTERNAL_TRANSFER_IN, BUY role</li>
     *   <li>Negative qty (deposit to bot): EXTERNAL_TRANSFER_OUT, SELL role</li>
     *   <li>Stablecoins pegged at $1 and confirmed immediately</li>
     *   <li>Non-stablecoins marked PENDING_PRICE with BOT_TRANSFER_PENDING_COST for the
     *       {@code BybitBotTransferCostBasisService} to resolve</li>
     * </ul>
     */
    private void reclassifyBotTransfer(NormalizedTransaction transaction, ExternalLedgerRaw row, Instant now) {
        transaction.setCorrelationId(null);
        transaction.setContinuityCandidate(false);
        transaction.setMatchedCounterparty(null);

        BigDecimal qty = row.getQuantityRaw();
        int sign = qty == null ? 0 : qty.signum();

        if (sign > 0) {
            transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow != null && flow.getRole() == NormalizedLegRole.TRANSFER) {
                    flow.setRole(NormalizedLegRole.BUY);
                }
            }
        } else if (sign < 0) {
            transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow != null && flow.getRole() == NormalizedLegRole.TRANSFER) {
                    flow.setRole(NormalizedLegRole.SELL);
                }
            }
        }

        if (!transaction.getMissingDataReasons().contains(BOT_TRANSFER_MARKER)) {
            transaction.getMissingDataReasons().add(BOT_TRANSFER_MARKER);
        }

        boolean nonStablecoinReturn = sign > 0
                && row.getAssetSymbol() != null
                && !isStablecoin(row.getAssetSymbol());
        if (nonStablecoinReturn) {
            if (!transaction.getMissingDataReasons().contains(BOT_TRANSFER_PENDING_COST)) {
                transaction.getMissingDataReasons().add(BOT_TRANSFER_PENDING_COST);
            }
        }
    }

    private boolean isFiatP2pRow(ExternalLedgerRaw row) {
        if (row == null) {
            return false;
        }
        String canonical = normalizeCanonicalLiteral(row.getCanonicalType());
        if ("EXTERNAL_IN_FIAT_P2P".equals(canonical) || "EXTERNAL_OUT_FIAT_P2P".equals(canonical)) {
            return true;
        }
        String bybitType = normalize(row.getBybitType());
        String description = normalize(row.getBybitDescription());
        return "fiat".equals(bybitType)
                || (description != null && description.contains("p2p purchase"));
    }

    private String resolveFiatP2pCounterparty(ExternalLedgerRaw row) {
        return FIAT_P2P_COUNTERPARTY;
    }

    private void confirmFiatP2pTransaction(NormalizedTransaction transaction, ExternalLedgerRaw row, Instant now) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            applyStableUsdPegIfEligible(flow);
        }
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setConfirmedAt(now);
    }

    private void applyStableUsdPegForExternalTransfers(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return;
        }
        if (transaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            applyStableUsdPegIfEligible(flow);
        }
    }

    private void applyStableUsdPegIfEligible(NormalizedTransaction.Flow flow) {
        if (flow == null || !isStablecoin(flow.getAssetSymbol())) {
            return;
        }
        BigDecimal quantity = flow.getQuantityDelta();
        if (quantity == null) {
            return;
        }
        flow.setUnitPriceUsd(BigDecimal.ONE);
        flow.setPriceSource(PriceSource.STABLECOIN);
        flow.setValueUsd(Decimal128Support.normalize(quantity.abs()));
    }

    private void recordMissingTxHashForBybitCorridor(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        if (transaction == null || row == null) {
            return;
        }
        String bybitType = row.getBybitType();
        if (bybitType == null) {
            return;
        }
        String normalizedType = normalize(bybitType);
        if (!"deposit".equals(normalizedType) && !"withdraw".equals(normalizedType)) {
            return;
        }
        if (row.getTxHash() != null && !row.getTxHash().isBlank()) {
            return;
        }
        if (!transaction.getMissingDataReasons().contains(TX_HASH_MISSING_BYBIT_CORRIDOR)) {
            transaction.getMissingDataReasons().add(TX_HASH_MISSING_BYBIT_CORRIDOR);
        }
    }

    private String resolveConvertCounterpartyKey(ExternalLedgerRaw row, String masterUid) {
        if (row == null || !isConvertType(row.getBybitType())) {
            return null;
        }
        String orderKey = firstNonBlank(row.getTradeOrderId(), extractConvertOrderKey(row.getId()));
        if (orderKey == null || orderKey.isBlank()) {
            return null;
        }
        return BYBIT_PREFIX + masterUid + ":CONVERT:" + orderKey;
    }

    private boolean isConvertType(String bybitType) {
        if (bybitType == null) {
            return false;
        }
        String normalized = normalize(bybitType);
        return "convert".equals(normalized) || "currency_buy".equals(normalized) || "currency_sell".equals(normalized);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractConvertOrderKey(String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return null;
        }
        int lastColon = rowId.lastIndexOf(':');
        return lastColon >= 0 ? rowId.substring(lastColon + 1) : rowId;
    }

    private String loanOrderKey(ExternalLedgerRaw row) {
        if (row.getTradeOrderId() != null && !row.getTradeOrderId().isBlank()) {
            return row.getTradeOrderId().trim();
        }
        if (row.getId() != null && !row.getId().isBlank()) {
            return row.getId();
        }
        return normalize(row.getBybitDescription());
    }

    private String normalizeRewardBusiType(ExternalLedgerRaw row) {
        String busi = row.getBybitType();
        if (busi == null || busi.isBlank()) {
            busi = row.getBybitDescription();
        }
        return busi == null || busi.isBlank() ? "UNKNOWN" : busi.trim();
    }

    private String resolveWalletRef(ExternalLedgerRaw row) {
        if (row.getWalletRef() != null && !row.getWalletRef().isBlank()) {
            return row.getWalletRef().trim();
        }
        return BYBIT_PREFIX + normalize(row.getUid());
    }

    private String extractUid(String walletRef) {
        if (walletRef == null || !walletRef.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            return "";
        }
        String remainder = walletRef.substring(BYBIT_PREFIX.length()).trim();
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon).trim() : remainder.trim();
    }

    private String ledgerAccountRef(String masterUid, String walletRef, String defaultLedger) {
        if (walletRef != null && walletRef.contains(":")) {
            return walletRef;
        }
        return BYBIT_PREFIX + masterUid + ":" + defaultLedger;
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

    private BigDecimal netExecutionLegQuantity(ExternalLedgerRaw leg) {
        if (leg == null || leg.getQuantityRaw() == null) {
            return null;
        }
        BigDecimal fee = leg.getFeePaid() != null ? leg.getFeePaid() : BigDecimal.ZERO;
        return leg.getQuantityRaw().add(fee);
    }

    private String bybitSubAccountTransferCorrelationId(ExternalLedgerRaw row) {
        if (row == null) {
            return null;
        }
        String id = row.getId();
        if (id == null || id.isBlank()) {
            return null;
        }
        Matcher matcher = SELF_TRANSFER_PATTERN.matcher(id);
        if (matcher.find()) {
            return "bybit-sub-transfer:" + normalize(row.getUid()) + ":" + matcher.group(1);
        }
        matcher = UNI_TRANS_PATTERN.matcher(id);
        if (matcher.find()) {
            return "bybit-uni-transfer:" + normalize(row.getUid()) + ":" + matcher.group(1);
        }
        return null;
    }

    private static String otherSubAccount(String walletRef) {
        if (walletRef.endsWith(":UTA")) {
            return walletRef.substring(0, walletRef.length() - 3) + "FUND";
        }
        if (walletRef.endsWith(":FUND")) {
            return walletRef.substring(0, walletRef.length() - 4) + "UTA";
        }
        if (walletRef.endsWith(":EARN")) {
            return walletRef.substring(0, walletRef.length() - 4) + "FUND";
        }
        return null;
    }

    /**
     * Picks the counterparty Bybit sub-account for an INTERNAL_TRANSFER row from the raw
     * extracted metadata. The rules below mirror Bybit's documented bookkeeping:
     * <ul>
     *   <li><b>FUNDING_HISTORY</b> rows always sit on {@code BYBIT:&lt;uid&gt;:FUND}. The
     *       description disambiguates the other side:
     *       <ul>
     *         <li>"Transfer to/from Unified Trading Account" → counterparty=UTA;</li>
     *         <li>"Flexible Savings Subscription/Redemption/Distribution" → counterparty=EARN;</li>
     *         <li>Otherwise default to UTA (most common Bybit transfer).</li>
     *       </ul></li>
     *   <li><b>EARN_FLEXIBLE_SAVING</b> rows always sit on {@code BYBIT:&lt;uid&gt;:EARN} with
     *       FUND as counterparty.</li>
     *   <li><b>INTERNAL_TRANSFER</b> (self-transfer) rows: counterparty = the sibling account of
     *       walletRef (UTA↔FUND).</li>
     *   <li><b>TRANSACTION_LOG</b> rows sit on {@code BYBIT:&lt;uid&gt;:UTA}:
     *       <ul>
     *         <li>{@code FLEXIBLE_STAKING_SUBSCRIPTION/REDEMPTION/PROFIT} → counterparty=EARN;</li>
     *         <li>{@code TRANSFER_IN/TRANSFER_OUT} → counterparty=FUND.</li>
     *       </ul></li>
     * </ul>
     * In all cases the result is guaranteed to differ from {@code walletRef}.
     */
    private String resolveInternalTransferCounterparty(ExternalLedgerRaw row, String walletRef, String masterUid) {
        if (row == null) {
            return null;
        }
        String subCorrelation = bybitSubAccountTransferCorrelationId(row);
        if (subCorrelation != null) {
            String sibling = otherSubAccount(walletRef);
            if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                return sibling;
            }
        }
        String sourceFile = normalize(row.getSourceFile());
        String description = normalize(row.getBybitDescription());
        String bybitType = normalize(row.getBybitType());
        if (sourceFile.contains("earn_flexible_saving")) {
            return BYBIT_PREFIX + masterUid + ":FUND";
        }
        if (sourceFile.contains("funding_history")) {
            if (description.contains("flexible savings") || description.contains("savings") || "earn".equalsIgnoreCase(row.getBybitType())) {
                return BYBIT_PREFIX + masterUid + ":EARN";
            }
            return BYBIT_PREFIX + masterUid + ":UTA";
        }
        if (sourceFile.contains("transaction_log")) {
            if (bybitType.contains("flexible_staking") || bybitType.contains("staking")) {
                return BYBIT_PREFIX + masterUid + ":EARN";
            }
            if (bybitType.contains("transfer")) {
                return BYBIT_PREFIX + masterUid + ":FUND";
            }
        }
        // Cycle/6 A1+: INTERNAL_TRANSFER (master ledger) rows describe the other side via
        // `bybitDescription` ("Transfer from <SRC> to <DST>"). Map the role names (FUND/UNIFIED
        // /UTA/EARN/FUNDING) onto sibling accounts. The own side is identified by walletRef so the
        // counterparty is whichever side of the description does NOT match the walletRef suffix.
        if (sourceFile.contains("internal_transfer")) {
            String walletAccount = walletAccountSuffix(walletRef);
            String descriptionOther = parseInternalTransferDescriptionOther(description, walletAccount);
            if (descriptionOther != null) {
                return BYBIT_PREFIX + masterUid + ":" + descriptionOther;
            }
            if (bybitType.contains("transfer in") && walletAccount != null) {
                String sibling = otherSubAccount(walletRef);
                if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                    return sibling;
                }
            }
            if (bybitType.contains("transfer out") && walletAccount != null) {
                String sibling = otherSubAccount(walletRef);
                if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                    return sibling;
                }
            }
        }
        return null;
    }

    private static String walletAccountSuffix(String walletRef) {
        if (walletRef == null) {
            return null;
        }
        int colon = walletRef.lastIndexOf(':');
        if (colon < 0 || colon == walletRef.length() - 1) {
            return null;
        }
        return walletRef.substring(colon + 1).toUpperCase(Locale.ROOT);
    }

    private static String parseInternalTransferDescriptionOther(String description, String walletAccount) {
        if (description == null || description.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("transfer\\s+from\\s+(\\w+)\\s+to\\s+(\\w+)").matcher(description);
        if (!matcher.find()) {
            return null;
        }
        String src = normalizeAccountToken(matcher.group(1));
        String dst = normalizeAccountToken(matcher.group(2));
        if (src == null || dst == null) {
            return null;
        }
        if (walletAccount != null && walletAccount.equals(src)) {
            return dst;
        }
        if (walletAccount != null && walletAccount.equals(dst)) {
            return src;
        }
        return null;
    }

    private static String normalizeAccountToken(String token) {
        if (token == null) {
            return null;
        }
        String upper = token.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "FUND", "FUNDING" -> "FUND";
            case "UNIFIED", "UTA" -> "UTA";
            case "EARN", "SAVINGS" -> "EARN";
            default -> null;
        };
    }

    /**
     * Last-resort counterparty for an INTERNAL_TRANSFER row when domain signals fail to resolve.
     * Guarantees the result differs from {@code walletRef}. Default sibling is UTA↔FUND, falling
     * back to FUND when walletRef does not carry a sub-account suffix.
     */
    private String fallbackInternalTransferCounterparty(String walletRef, String masterUid) {
        String sibling = walletRef == null ? null : otherSubAccount(walletRef);
        if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
            return sibling;
        }
        if (walletRef != null && walletRef.toUpperCase(Locale.ROOT).endsWith(":FUND")) {
            return BYBIT_PREFIX + masterUid + ":UTA";
        }
        return BYBIT_PREFIX + masterUid + ":FUND";
    }

    /**
     * Cycle/5 N3 / D-1: deterministic key so TX_LOG sender, FH sender, and INTERNAL_TRANSFER receiver
     * legs of the same (uid, asset, |qty|, minute) pair into the same pending-transfer queue.
     */
    private static boolean shouldAttachBybitEconomyCorrelationId(ExternalLedgerRaw row) {
        if (row == null) {
            return false;
        }
        String sf = row.getSourceFile();
        if (sf == null || sf.isBlank()) {
            return false;
        }
        String u = sf.toUpperCase(Locale.ROOT);
        return u.contains("TRANSACTION_LOG")
                || u.contains("INTERNAL_TRANSFER")
                || u.contains("FUNDING_HISTORY")
                || u.contains("EARN_FLEXIBLE_SAVING");
    }

    private String bybitInternalTransferEconomyCorrelationId(ExternalLedgerRaw row) {
        if (row == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return null;
        }
        Instant anchor = row.getTimeUtc() != null ? row.getTimeUtc() : row.getImportedAt();
        if (anchor == null) {
            return null;
        }
        long minuteBucket = anchor.getEpochSecond() / 60;
        String qtyPlain = row.getQuantityRaw().abs().stripTrailingZeros().toPlainString();
        String payload = normalize(row.getUid())
                + "|"
                + row.getAssetSymbol().trim().toUpperCase(Locale.ROOT)
                + "|"
                + qtyPlain
                + "|"
                + minuteBucket;
        return "bybit-econ-v1:" + sha256Hex(payload);
    }

    /**
     * Cycle/9 S4: deterministic family-aware correlation id for cross-sub-account liquid-staking
     * pairs (e.g., FUND METH ↔ EARN CMETH).
     *
     * <p>Symmetric across both legs: derives uid, family identity, absolute quantity (both legs
     * share magnitude for staking conversion), and a minute-bucket centered on the earlier
     * leg. The family identity collapses METH and CMETH onto {@code FAMILY:ETH}, allowing
     * {@code FamilyEquivalentCustodyReplayHandler} to pair the legs and carry basis across
     * sub-accounts.</p>
     */
    public String crossSubAccountStakingCorrelationId(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (left == null || right == null) {
            return null;
        }
        String uid = normalize(left.getUid());
        if (uid.isBlank()) {
            uid = normalize(right.getUid());
        }
        if (uid.isBlank()) {
            return null;
        }
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right.getAssetSymbol(), null);
        String family = leftFamily != null ? leftFamily : rightFamily;
        if (family == null || family.isBlank()) {
            return null;
        }
        java.math.BigDecimal leftQty = left.getQuantityRaw() == null ? null : left.getQuantityRaw().abs().stripTrailingZeros();
        java.math.BigDecimal rightQty = right.getQuantityRaw() == null ? null : right.getQuantityRaw().abs().stripTrailingZeros();
        java.math.BigDecimal qty = leftQty != null && (rightQty == null || leftQty.compareTo(rightQty) >= 0) ? leftQty : rightQty;
        if (qty == null) {
            return null;
        }
        Instant leftTime = left.getTimeUtc() != null ? left.getTimeUtc() : left.getImportedAt();
        Instant rightTime = right.getTimeUtc() != null ? right.getTimeUtc() : right.getImportedAt();
        Instant anchor;
        if (leftTime != null && rightTime != null) {
            anchor = leftTime.isBefore(rightTime) ? leftTime : rightTime;
        } else {
            anchor = leftTime != null ? leftTime : rightTime;
        }
        if (anchor == null) {
            return null;
        }
        long minuteBucket = anchor.getEpochSecond() / 60;
        String payload = uid
                + "|"
                + family
                + "|"
                + qty.toPlainString()
                + "|"
                + minuteBucket;
        return "bybit-stake-pair-v1:" + sha256Hex(payload);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
