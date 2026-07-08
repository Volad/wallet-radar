package com.walletradar.application.cex.normalization.venue.bybit;

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
import com.walletradar.application.linking.pipeline.clarification.CounterpartyType;
import com.walletradar.application.linking.pipeline.clarification.FlowCounterpartySupport;
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

    private final BybitCanonicalFlowCounterpartySupport flowCounterpartySupport;
    private final BybitCanonicalMappedRowSupport mappedRowSupport;

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
     * ({@link com.walletradar.application.linking.pipeline.clarification.BybitTransferContinuityRepairService})
     * remains driven by {@code txHash}, so this synthetic address never participates in FA-001
     * pairing — its sole purpose is to keep the conservation gate honest about untracked custody.
     */
    private static final String BYBIT_HOT_WALLET_PREFIX = "BYBIT:HOT_WALLET:";


    public BybitCanonicalTransactionBuilder() {
        this(new BybitCanonicalFlowCounterpartySupport(null), new BybitCanonicalMappedRowSupport());
    }

    @Autowired
    public BybitCanonicalTransactionBuilder(
            BybitCanonicalFlowCounterpartySupport flowCounterpartySupport,
            BybitCanonicalMappedRowSupport mappedRowSupport
    ) {
        this.flowCounterpartySupport = flowCounterpartySupport;
        this.mappedRowSupport = mappedRowSupport;
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
        BigDecimal executionPrice = mappedRowSupport.firstNonNull(buyRow.getFilledPrice(), sellRow.getFilledPrice());

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        BigDecimal buyNet = mappedRowSupport.netExecutionLegQuantity(buyRow);
        BigDecimal sellNet = mappedRowSupport.netExecutionLegQuantity(sellRow);
        BybitCanonicalMappedRowSupport.FlowPricing buyPricing = mappedRowSupport.tradeFlowPricing(
                buyRow.getAssetSymbol(),
                NormalizedLegRole.BUY,
                buyRow.getAssetSymbol(),
                sellRow.getAssetSymbol(),
                executionPrice
        );
        flows.add(mappedRowSupport.flow(
                NormalizedLegRole.BUY,
                buyRow.getAssetSymbol(),
                buyNet,
                buyPricing.unitPriceUsd(),
                buyPricing.priceSource()
        ));
        BybitCanonicalMappedRowSupport.FlowPricing sellPricing = mappedRowSupport.tradeFlowPricing(
                sellRow.getAssetSymbol(),
                NormalizedLegRole.SELL,
                buyRow.getAssetSymbol(),
                sellRow.getAssetSymbol(),
                executionPrice
        );
        flows.add(mappedRowSupport.flow(
                NormalizedLegRole.SELL,
                sellRow.getAssetSymbol(),
                sellNet,
                sellPricing.unitPriceUsd(),
                sellPricing.priceSource()
        ));
        transaction.setFlows(flows);
        flowCounterpartySupport.finalizeBybitFlows(transaction, left);
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
        BybitCanonicalMappedRowSupport.FlowPricing pricing = mappedRowSupport.orphanTradePricing(row);
        BigDecimal netLeg = mappedRowSupport.netExecutionLegQuantity(row);
        if (netLeg == null) {
            return buildMalformedTrade(row, now, "UTA_TRADE_QTY_MISSING");
        }
        List<NormalizedTransaction.Flow> flows = List.of(mappedRowSupport.flow(
                role,
                row.getAssetSymbol(),
                netLeg,
                pricing.unitPriceUsd(),
                pricing.priceSource()
        ));
        transaction.setFlows(new ArrayList<>(flows));
        flowCounterpartySupport.finalizeBybitFlows(transaction, row);
        transaction.setMissingDataReasons(List.of("UTA_TRADE_PAIR_NOT_FOUND"));
        transaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        markExcludedFromAccounting(transaction, "UTA_TRADE_PAIR_NOT_FOUND");
        return transaction;
    }

    public NormalizedTransaction buildMappedRow(
            ExternalLedgerRaw row,
            Instant now
    ) {
        Optional<NormalizedTransactionType> mappedType = mappedRowSupport.mapCanonicalType(row.getCanonicalType());
        if (mappedType.isEmpty() && mappedRowSupport.hasExplicitBasisRelevantCanonicalType(row)) {
            return buildNeedsReviewRow(row, now, "BYBIT_CANONICAL_TYPE_UNMAPPED");
        }
        NormalizedTransactionType type = mappedType.orElse(NormalizedTransactionType.UNKNOWN);
        type = mappedRowSupport.resolveEarnLifecycleCanonicalType(row).orElse(type);
        NormalizedTransaction transaction = baseTransaction(normalizedId(row), row, type, now);
        transaction.setFlows(new ArrayList<>(mappedRowSupport.mappedFlows(row, type)));

        if (type == NormalizedTransactionType.INTERNAL_TRANSFER) {
            // Cycle/6 A1: always use the deterministic economy correlation id (uid|asset|mappedRowSupport.abs(qty)|minute)
            // for INTERNAL_TRANSFER rows. The Bybit `selfTransfer_<uuid>` identifier is leg-local —
            // both sides of a real transfer carry DIFFERENT UUIDs, so the previous "sub-transfer"
            // correlation key produced 228 singleton legs that never paired in replay. The economy
            // correlation id is symmetric: both legs derive the same key from the shared signature.
            // The remaining cross-minute drift (~10% of pairs) is repaired by
            // {@code BybitInternalTransferPairer} after normalization.
            String correlationId = BybitCanonicalCorrelationSupport.bybitInternalTransferEconomyCorrelationId(row);
            if (correlationId != null) {
                transaction.setCorrelationId(correlationId);
                transaction.setContinuityCandidate(true);
                String walletRef = row.getWalletRef();
                String masterUid = flowCounterpartySupport.extractUid(walletRef);
                if (masterUid.isBlank()) {
                    masterUid = mappedRowSupport.normalize(row.getUid());
                }
                String matched = flowCounterpartySupport.resolveInternalTransferCounterparty(row, walletRef, masterUid);
                if (matched == null || matched.isBlank() || matched.equalsIgnoreCase(walletRef)) {
                    matched = flowCounterpartySupport.fallbackInternalTransferCounterparty(walletRef, masterUid);
                }
                transaction.setMatchedCounterparty(matched);
            }
        }

        if (type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY) {
            String orderId = flowCounterpartySupport.loanOrderKey(row);
            if (orderId != null && !orderId.isBlank()) {
                transaction.setCorrelationId(orderId);
            }
        }

        // Cycle/5 N1/N5: extraction marks non-authoritative mirror rows (FH/Deposit, TX_LOG/TRANSFER_IN, …)
        // with basisRelevant=false. They must not hit AVCO replay or they re-inflate inventory (phantom qty).
        if (Boolean.FALSE.equals(row.getBasisRelevant())) {
            markExcludedFromAccounting(transaction, "BYBIT_BASIS_IRRELEVANT");
        }

        flowCounterpartySupport.recordMissingTxHashForBybitCorridor(transaction, row);
        flowCounterpartySupport.finalizeBybitFlows(transaction, row);
        if (flowCounterpartySupport.isBotTransfer(row)) {
            flowCounterpartySupport.reclassifyBotTransfer(transaction, row, now);
        }
        flowCounterpartySupport.applyStableUsdPegForExternalTransfers(transaction);
        initializeStatus(transaction, now);
        if (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            confirmWhenAllBuySellFlowsPriced(transaction, now);
        }
        if (flowCounterpartySupport.isFiatP2pRow(row)) {
            flowCounterpartySupport.confirmFiatP2pTransaction(transaction, row, now);
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
        transaction.setFlows(mappedRowSupport.aggregateClusterFlows(rows));
        flowCounterpartySupport.finalizeBybitFlows(transaction, anchor);
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
        transaction.setFlows(mappedRowSupport.stakingPairFlows(left, right));
        flowCounterpartySupport.finalizeBybitFlows(transaction, anchor);
        initializeStatus(transaction, now);
        return transaction;
    }

    /**
     * Cross-sub-account ETH-family liquid-staking conversion (e.g. FUND {@code METH}/{@code ETH}
     * debit ↔ EARN {@code CMETH} credit). These are economically one conversion (source disposed,
     * liquid-staking token received) but land on two Bybit sub-accounts, so they cannot be paired
     * cross-document by the family-equivalent continuity bucket (that bucket is keyed by the raw
     * sub-account {@code walletAddress}).
     *
     * <p>They are fused here into a single two-flow {@code STAKING_DEPOSIT} so
     * {@code LiquidStakingReplayHandler} — the same handler that carries the same-sub-account
     * {@code ETH→METH} staking control — moves the source family basis into the received token.
     * Both legs are booked on the UID umbrella ({@code BYBIT:<uid>}, ADR-017 family rollup): the
     * replay position key strips {@code :FUND}/{@code :UTA} to the umbrella, and {@code :EARN} would
     * otherwise stay siloed, so the whole conversion is normalized onto the umbrella where the
     * source acquisition lot lives and where any later corridor-out drains it. The anchor is the
     * debit (outflow) leg — deterministic and independent of Mongo {@code _id} ordering (ADR-041).
     */
    public NormalizedTransaction buildCrossSubAccountStakingPair(
            ExternalLedgerRaw debit,
            ExternalLedgerRaw credit,
            Instant now
    ) {
        NormalizedTransaction transaction = baseTransaction(
                pairId(debit, credit), debit, NormalizedTransactionType.STAKING_DEPOSIT, now);
        transaction.setWalletAddress(BYBIT_PREFIX + flowCounterpartySupport.extractUid(flowCounterpartySupport.resolveWalletRef(debit)));
        transaction.setFlows(mappedRowSupport.stakingPairFlows(debit, credit));
        flowCounterpartySupport.finalizeBybitFlows(transaction, debit);
        initializeStatus(transaction, now);
        return transaction;
    }

    public NormalizedTransaction buildNeedsReviewRow(
            ExternalLedgerRaw row,
            Instant now,
            String missingReason
    ) {
        NormalizedTransactionType type = mappedRowSupport.mapCanonicalType(row.getCanonicalType())
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
        transaction.setCounterpartyAddress(mappedRowSupport.initialCounterpartyAddress(row, type));
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

    

    

    /**
     * Bybit Earn / Launchpool / Easy-Earn lifecycle rows are extracted as {@code INTERNAL_TRANSFER}
     * but are economically protocol custody (subscribe / redeem), not sub-account transfer pairs.
     */
    

    

    

    

    

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
        String canonicalIdLiteral = mappedRowSupport.mappedCanonicalLiteral(row.getCanonicalType());
        if ("withdraw_deposit".equals(mappedRowSupport.normalize(row.getSourceFileType()))
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
                    mappedRowSupport.normalize(row.getUid()),
                    "withdraw_deposit",
                    row.getNetworkId().name(),
                    canonicalTxHash,
                    canonicalIdLiteral,
                    row.getAssetSymbol(),
                    mappedRowSupport.abs(row.getQuantityRaw()).stripTrailingZeros().toPlainString()
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
    

    /**
     * Counterparty classification reuses the accounting-universe binding when present:
     * <ul>
     *   <li>address in universe → {@link CounterpartyType#PERSONAL_WALLET} when ON_CHAIN_WALLET,
     *       {@link CounterpartyType#CEX} when EXCHANGE_ACCOUNT;</li>
     *   <li>address absent from universe (or universe not bound) → {@link CounterpartyType#UNKNOWN_EOA}.</li>
     * </ul>
     * Missing address (synthetic {@code BYBIT_HOT_WALLET}) is also {@code UNKNOWN_EOA}.
     */
    


    

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
    

    

    

    

    /**
     * Last-resort counterparty for an INTERNAL_TRANSFER row when domain signals fail to resolve.
     * Guarantees the result differs from {@code walletRef}. Default sibling is UTA↔FUND, falling
     * back to FUND when walletRef does not carry a sub-account suffix.
     */
    

    /**
     * Cycle/5 N3 / D-1: deterministic key so TX_LOG sender, FH sender, and INTERNAL_TRANSFER receiver
     * legs of the same (uid, asset, |qty|, minute) pair into the same pending-transfer queue.
     */
    

    

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
        return BybitCanonicalCorrelationSupport.crossSubAccountStakingCorrelationId(left, right);
    }


    

    

    

}
