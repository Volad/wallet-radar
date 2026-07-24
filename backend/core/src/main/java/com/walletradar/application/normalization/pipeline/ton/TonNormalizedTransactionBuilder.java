package com.walletradar.application.normalization.pipeline.ton;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.NormalizedCapabilityFlagStamper;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds canonical {@link NormalizedTransaction} documents from TON Center v3 raw evidence.
 *
 * <p>Ownership and direction are resolved through {@link TonAddressCanonicalizer} (ADR-064 /
 * PR3 RC-T1): the stored user-friendly wallet ({@code UQ…}) and the raw workchain form
 * ({@code 0:hex}) that TON Center emits in message {@code source}/{@code destination} fields are
 * compared on their shared canonical key set, not via {@code equalsIgnoreCase}. Before this fix
 * every native TON + jetton transfer collapsed to {@code UNKNOWN} with a fee-only flow.</p>
 *
 * <p>Native TON flows are only built from plain value-transfer messages (opcode absent,
 * {@code text_comment}, or {@code excess}); jetton/DeFi machinery messages (e.g.
 * {@code jetton_transfer}, {@code dedust_swap}, {@code pton_ton_transfer}) merely forward gas and
 * must never be booked as native TON value. Jetton economic flows are built from
 * {@code rawData.jettonTransfers} (toncenter {@code /jetton/transfers}, owner-addressed). When a
 * transaction carries jetton/DeFi value but that evidence is unavailable, it is surfaced as
 * {@code UNKNOWN / NEEDS_REVIEW} with {@link #ONCHAIN_UNRESOLVED_VALUE} so the replay-safe
 * promotion guard (RC-T2) keeps it visible instead of silently confirming dropped value.</p>
 *
 * <p><b>Jetton fan-out deduplication (RULE 2).</b> A single logical jetton transfer produces
 * several TON transactions in one trace (jetton_transfer → excess → jetton_notify …), and the
 * adapter replicates the owner-addressed {@code jettonTransfers} entry onto every one of the
 * wallet's raw rows in that trace. Booking each raw would inflate the ledger N× (phantom
 * residuals; e.g. an own↔own send counted twice on the sender). Duplicates are collapsed by a
 * stable identity key — {@code transaction_hash} (TON Center trace hash) or, when absent, the
 * composite {@code {jettonMaster, query_id, amount, source, destination}} — first <em>within</em>
 * a row's list, then <em>across</em> the wallet's rows via {@link JettonFanoutClaim}: the transfer
 * is booked once on the canonical (lowest {@code txHash}) sibling, and non-canonical siblings are
 * excluded from accounting with {@link #JETTON_FANOUT_DUPLICATE_REASON}. Genuinely distinct
 * transfers (different {@code transaction_hash}) are never merged. Because direction is derived
 * from the transfer's owner {@code source}/{@code destination} versus the wallet, an own↔own
 * transfer then books exactly one CARRY_OUT on the sender and one CARRY_IN on the receiver
 * (RULE 3, symmetric move-basis).</p>
 */
@Component
@Slf4j
public class TonNormalizedTransactionBuilder {

    private static final BigDecimal NANO_TON_DIVISOR = BigDecimal.valueOf(1_000_000_000L);
    /**
     * Per-jetton fallback decimals when a jetton has no entry in {@code network-descriptors.yml}
     * token-overrides and no live-resolver result. This is NOT the native TON precision
     * (which is derived from the descriptor via {@link NetworkNativeAssets#nativeDecimals}) —
     * it is only the blind fallback for unknown jettons whose decimals could not be resolved,
     * kept at 9 because the most common misconfigured jettons use this precision.
     */
    private static final int DEFAULT_JETTON_DECIMALS = 9;
    /** DEX swap forward-payload {@code @type} markers (toncenter decoded). */
    private static final String STONFI_SWAP_MARKER = "stonfi_swap";
    private static final String DEDUST_MARKER = "dedust";
    /** Family tag (ton-protocol-registry.json) that classifies wallet↔vault jetton moves as stake/unstake. */
    private static final String STAKING_FAMILY = "STAKING";

    /** Missing-data reason set when a real jetton/DeFi value was observed but could not be booked. */
    public static final String ONCHAIN_UNRESOLVED_VALUE = "TON_ONCHAIN_UNRESOLVED_VALUE";

    /**
     * Accounting-exclusion reason for a TON row whose jetton value is a fan-out duplicate: TON's
     * async message model surfaces a single logical jetton transfer across every trace transaction
     * touching the wallet (jetton_transfer → excess → jetton_notify …), and {@code
     * rawData.jettonTransfers} is replicated onto each. Booking the transfer on every raw inflates
     * the ledger N× (phantom residuals). The transfer is booked once on the canonical (lowest
     * {@code txHash}) sibling; the non-canonical siblings are excluded from accounting with this
     * reason so the value is not double-counted and the row is not mistaken for dropped value.
     */
    public static final String JETTON_FANOUT_DUPLICATE_REASON = "TON_JETTON_FANOUT_DUPLICATE";

    /**
     * Decides whether a given raw transaction is the canonical owner of a jetton transfer identity,
     * i.e. the one raw (per wallet) on which the transfer's economic flow must be booked. Used to
     * collapse the TON message fan-out (RULE 2). The default {@link #CLAIM_ALL} books every transfer
     * (no cross-raw dedup) and is used by isolated builder callers/tests; the pipeline supplies a
     * repository-backed claim keyed by {@code rawData.jettonTransfers.transaction_hash}.
     */
    @FunctionalInterface
    public interface JettonFanoutClaim {
        boolean claims(String walletAddress, String rawTxHash, String jettonTransactionHash);
    }

    /** Books every jetton transfer (no cross-raw dedup). Within-raw duplicates are still collapsed. */
    public static final JettonFanoutClaim CLAIM_ALL = (wallet, rawTxHash, jettonTxHash) -> true;

    /**
     * Accounting-exclusion reason for a TON row that is genuinely unbookable / out of scope
     * (no resolvable jetton transfer and no bookable native value movement — e.g. unsolicited scam
     * {@code transfer_notification} dust or deep TON DeFi that is out of scope). Excluding such
     * rows keeps them visible as {@code NEEDS_REVIEW} while making them non-blocking for the
     * portfolio conservation / AVCO gate. This never confirms dropped value (RC-T2 preserved).
     */
    public static final String UNSUPPORTED_SCOPE_REASON = "TON_UNSUPPORTED_SCOPE";

    /**
     * Decoded opcodes that carry a plain native-TON value transfer (as opposed to jetton/DeFi
     * machinery that only forwards gas). {@code excess} is the jetton-wallet gas refund back to the
     * owner — a genuine, if small, native inflow.
     */
    private static final Set<String> NATIVE_VALUE_OPCODES = Set.of("text_comment", "excess");

    /**
     * Decoded opcodes that indicate jetton/DeFi economic activity. Their presence with no usable
     * {@code jettonTransfers} evidence means real value was dropped → the row stays NEEDS_REVIEW.
     */
    private static final Set<String> JETTON_DEFI_OPCODES = Set.of(
            "jetton_transfer",
            "jetton_notify",
            "jetton_burn",
            "jetton_internal_transfer",
            "internal_transfer",
            "dedust_swap",
            "dedust_payout",
            "pton_ton_transfer"
    );

    private final AccountingUniverseService accountingUniverseService;

    /** Jetton master (raw canonical key) → decimals; resolved once at normalization (background) time. */
    private final Cache<String, Integer> jettonDecimalsCache = Caffeine.newBuilder()
            .maximumSize(1_024)
            .build();

    public TonNormalizedTransactionBuilder(AccountingUniverseService accountingUniverseService) {
        this.accountingUniverseService = accountingUniverseService;
    }

    public NormalizedTransaction build(RawTransaction rawTransaction, Instant now) {
        return build(rawTransaction, now, CLAIM_ALL);
    }

    public NormalizedTransaction build(RawTransaction rawTransaction, Instant now, JettonFanoutClaim claim) {
        TonRawTransactionView view = TonRawTransactionView.wrap(rawTransaction);
        String walletAddress = view.walletAddress();

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(rawTransaction.getId());
        tx.setTxHash(view.txHash());
        tx.setNetworkId(NetworkId.TON);
        tx.setWalletAddress(walletAddress);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setBlockTimestamp(view.blockTimestamp());
        tx.setClassifiedBy(ClassificationSource.HEURISTIC);
        tx.setConfidence(ConfidenceLevel.MEDIUM);
        // WS-8 (ADR-073/074): stamp capability flags at the shared ingestion seam. TON lending is
        // receipt-less (receiptBearingCollateral=false) and has no concentrated-liquidity LP
        // (lpConcentrated stays null); networkId is already set so this is safe before the early
        // returns below. Uniform with EVM/Solana so reads stay network-agnostic.
        NormalizedCapabilityFlagStamper.stamp(tx);
        tx.setPricingAttempts(0);
        tx.setStatAttempts(0);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);

        // Failed transactions → NEEDS_REVIEW
        if (view.isFailed()) {
            tx.setType(NormalizedTransactionType.UNKNOWN);
            tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            tx.setMissingDataReasons(List.of("TON_FAILED_TRANSACTION"));
            tx.setFlows(buildFeeFlow(view));
            return tx;
        }

        // Collapse the TON jetton message fan-out (RULE 2): within-row duplicates first, then the
        // cross-row claim (transfer booked once on the canonical sibling).
        List<Document> dedupedTransfers = dedupeWithinRow(view.jettonTransfers());

        // WS-2 (B2): Ston.fi / Dedust DEX swap. Proxy-TON (pTON) legs are netted to native TON
        // (never a held pTON jetton — kills the phantom pTON + its bogus avco) BEFORE the swap
        // siblings are paired, and the swap is booked exactly once on the canonical trace row
        // (swap-aware fan-out) as SELL + BUY so the acquired asset gets a swap-derived basis instead
        // of being mistaken for external capital-in (keeps native TON AVCO healthy).
        DexSwap swap = detectDexSwap(view, walletAddress, dedupedTransfers);
        if (swap != null) {
            if (!swapClaimsThisRow(swap, walletAddress, view.txHash(), claim)) {
                return markFanoutDuplicate(tx, view);
            }
            tx.setType(NormalizedTransactionType.SWAP);
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            tx.setFlows(buildDexSwapFlows(swap, view));
            return tx;
        }

        List<Document> bookableTransfers = filterClaimed(dedupedTransfers, walletAddress, view.txHash(), claim);

        // Non-canonical fan-out sibling: real transfer(s) present but all booked on another raw.
        // Exclude from accounting (never double-count) and stay UNKNOWN so metadata enrichment
        // does not promote/mutate it; this is not dropped value (RC-T2 preserved).
        if (!dedupedTransfers.isEmpty() && bookableTransfers.isEmpty()) {
            return markFanoutDuplicate(tx, view);
        }

        ClassifyResult result = classify(view, walletAddress, bookableTransfers);
        tx.setType(result.type());
        if (result.needsReview()) {
            tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            List<String> reasons = new ArrayList<>();
            reasons.add("TON_UNCLASSIFIED");
            // RC-T2: mark rows whose real jetton/DeFi value was dropped so the replay-safe
            // promotion guard keeps them visible instead of silently confirming empty/fee-only.
            if (hasUnresolvedEconomicValue(view)) {
                reasons.add(ONCHAIN_UNRESOLVED_VALUE);
            }
            // A row with no resolvable jetton transfer AND no bookable native value movement is
            // genuinely unbookable / out of scope (scam transfer_notification dust, deep TON DeFi).
            // Exclude it from accounting so it is non-blocking for the conservation/AVCO gate while
            // still visible as NEEDS_REVIEW. Excluding is not confirming — RC-T2 is preserved.
            if (isUnbookableOutOfScope(view, walletAddress)) {
                reasons.add(UNSUPPORTED_SCOPE_REASON);
                tx.setExcludedFromAccounting(true);
                tx.setAccountingExclusionReason(UNSUPPORTED_SCOPE_REASON);
            }
            tx.setMissingDataReasons(reasons);
        } else {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        }
        tx.setContinuityCandidate(result.continuityCandidate() ? Boolean.TRUE : null);
        tx.setFlows(buildFlows(view, walletAddress, result, bookableTransfers));
        return tx;
    }

    // ---- classification ----

    private ClassifyResult classify(TonRawTransactionView view, String walletAddress,
                                    List<Document> jettonTransfers) {
        if (!jettonTransfers.isEmpty()) {
            // WS-2: a wallet↔registered-STAKING-vault jetton move (e.g. Affluent affGOLDm) is a
            // stake/unstake, not a plain transfer. Detection is registry-driven (no hardcoded
            // wallet); inert until a STAKING vault is configured in ton-protocol-registry.json.
            ClassifyResult staking = classifyStaking(walletAddress, jettonTransfers);
            if (staking != null) {
                return staking;
            }

            boolean anyIn = false;
            boolean anyOut = false;
            boolean allOwnSource = true;
            boolean allOwnDest = true;

            for (Document jt : jettonTransfers) {
                String src = stringField(jt, "source");
                String dest = stringField(jt, "destination");
                boolean srcOwn = isOwnAddress(src);
                boolean destOwn = isOwnAddress(dest);
                boolean srcIsWallet = sameTonAddress(walletAddress, src);
                boolean destIsWallet = sameTonAddress(walletAddress, dest);
                if (destIsWallet) anyIn = true;
                if (srcIsWallet) anyOut = true;
                if (!srcOwn) allOwnSource = false;
                if (!destOwn) allOwnDest = false;
            }

            if ((anyIn || anyOut) && allOwnSource && allOwnDest) {
                return ClassifyResult.internalTransfer();
            }
            if (anyIn && !anyOut) {
                return ClassifyResult.of(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
            }
            if (anyOut && !anyIn) {
                return ClassifyResult.of(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
            }
            if (anyIn && anyOut) {
                return ClassifyResult.internalTransfer();
            }
        }

        // Native TON flows — only plain value transfers, never jetton/DeFi gas-forwarding messages.
        String inMsgDest = view.inMsgDestination();
        long inMsgValue = view.inMsgValueNano();
        boolean inboundNative = inMsgValue > 0
                && sameTonAddress(walletAddress, inMsgDest)
                && isNativeValueOpcode(view.inMsgOpcode());

        boolean outboundNative = false;
        for (Document outMsg : view.outMsgs()) {
            String outSrc = stringField(outMsg, "source");
            long val = toLong(outMsg.get("value"));
            if (val > 0 && sameTonAddress(walletAddress, outSrc)
                    && isNativeValueOpcode(stringField(outMsg, "decoded_opcode"))) {
                outboundNative = true;
                break;
            }
        }

        if (inboundNative && !outboundNative) {
            String inSrc = view.inMsgSource();
            if (isOwnAddress(inSrc) && isOwnAddress(inMsgDest)) {
                return ClassifyResult.internalTransfer();
            }
            return ClassifyResult.of(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        }
        if (outboundNative && !inboundNative) {
            return ClassifyResult.of(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        }
        if (inboundNative && outboundNative) {
            return ClassifyResult.internalTransfer();
        }

        return ClassifyResult.unknown();
    }

    // ---- flow building ----

    private List<NormalizedTransaction.Flow> buildFlows(TonRawTransactionView view,
                                                         String walletAddress,
                                                         ClassifyResult result,
                                                         List<Document> jettonTransfers) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();

        if (!result.needsReview()) {
            if (!jettonTransfers.isEmpty()) {
                for (Document jt : jettonTransfers) {
                    buildJettonFlow(jt, walletAddress, flows);
                }
            } else {
                buildNativeTonFlow(view, walletAddress, flows);
            }
        }

        // Fee flow (always emitted when fees present, including on NEEDS_REVIEW rows).
        flows.addAll(buildFeeFlow(view));
        return flows;
    }

    private void buildJettonFlow(Document jt,
                                 String walletAddress,
                                 List<NormalizedTransaction.Flow> flows) {
        String jettonMaster = stringField(jt, "jetton_master");
        if (jettonMaster == null) {
            jettonMaster = stringField(jt, "jetton_address");
        }
        String amountStr = stringField(jt, "amount");
        if (amountStr == null) {
            return;
        }
        BigDecimal rawAmount;
        try {
            rawAmount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            return;
        }

        String src = stringField(jt, "source");
        String dest = stringField(jt, "destination");
        boolean incoming = sameTonAddress(walletAddress, dest);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        // WS-2: proxy-TON (pTON) is native TON wrapped for Ston.fi routing. Net it to native TON so
        // it never surfaces as a held pTON jetton (phantom inventory + bogus avco).
        if (TonProtocolRegistry.isProxyTon(jettonMaster)) {
            BigDecimal quantity = rawAmount.movePointLeft(NetworkNativeAssets.nativeDecimals(NetworkId.TON));
            flow.setAssetContract(NetworkNativeAssets.nativeIdentity(NetworkId.TON));
            flow.setAssetSymbol(NetworkNativeAssets.nativeSymbol(NetworkId.TON));
            flow.setRole(NormalizedLegRole.TRANSFER);
            flow.setQuantityDelta(incoming ? quantity : quantity.negate());
            flow.setCounterpartyAddress(incoming ? src : dest);
            flows.add(flow);
            return;
        }

        int decimals = resolveJettonDecimals(jt, jettonMaster);
        BigDecimal quantity = rawAmount.movePointLeft(decimals);

        flow.setAssetContract(canonicalJettonContract(jettonMaster));
        flow.setAssetSymbol(resolveJettonSymbol(jt, jettonMaster));
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(incoming ? quantity : quantity.negate());
        flow.setCounterpartyAddress(incoming ? src : dest);
        flows.add(flow);
    }

    private void buildNativeTonFlow(TonRawTransactionView view,
                                    String walletAddress,
                                    List<NormalizedTransaction.Flow> flows) {
        long inValue = view.inMsgValueNano();
        String inDest = view.inMsgDestination();
        boolean inboundNative = inValue > 0
                && sameTonAddress(walletAddress, inDest)
                && isNativeValueOpcode(view.inMsgOpcode());

        if (inboundNative) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(NetworkNativeAssets.nativeIdentity(NetworkId.TON));
            flow.setAssetSymbol(NetworkNativeAssets.nativeSymbol(NetworkId.TON));
            flow.setRole(NormalizedLegRole.TRANSFER);
            flow.setQuantityDelta(nanoToTon(inValue));
            flow.setCounterpartyAddress(view.inMsgSource());
            flows.add(flow);
            return;
        }

        // Outbound: find the relevant plain-value out_msg.
        for (Document outMsg : view.outMsgs()) {
            String outSrc = stringField(outMsg, "source");
            String outDest = stringField(outMsg, "destination");
            long val = toLong(outMsg.get("value"));
            if (val > 0 && sameTonAddress(walletAddress, outSrc)
                    && isNativeValueOpcode(stringField(outMsg, "decoded_opcode"))) {
                NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
                flow.setAssetContract(NetworkNativeAssets.nativeIdentity(NetworkId.TON));
                flow.setAssetSymbol(NetworkNativeAssets.nativeSymbol(NetworkId.TON));
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setQuantityDelta(nanoToTon(val).negate());
                flow.setCounterpartyAddress(outDest);
                flows.add(flow);
                break;
            }
        }
    }

    private List<NormalizedTransaction.Flow> buildFeeFlow(TonRawTransactionView view) {
        long feeNano = view.totalFeesNano();
        if (feeNano <= 0) {
            return List.of();
        }
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setAssetContract(NetworkNativeAssets.nativeIdentity(NetworkId.TON));
        fee.setAssetSymbol(NetworkNativeAssets.nativeSymbol(NetworkId.TON));
        fee.setRole(NormalizedLegRole.FEE);
        fee.setQuantityDelta(nanoToTon(feeNano).negate());
        return List.of(fee);
    }

    // ---- WS-2: DEX swap (Ston.fi / Dedust) + proxy-TON netting + staking ----

    /**
     * Marks a row as a fan-out duplicate: real transfer(s) present but booked on another
     * (canonical) sibling raw. Excluded from accounting so value is never double-counted, kept
     * {@code UNKNOWN}/NEEDS_REVIEW so metadata enrichment does not promote it (RC-T2 preserved).
     */
    private NormalizedTransaction markFanoutDuplicate(NormalizedTransaction tx, TonRawTransactionView view) {
        tx.setType(NormalizedTransactionType.UNKNOWN);
        tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        tx.setExcludedFromAccounting(true);
        tx.setAccountingExclusionReason(JETTON_FANOUT_DUPLICATE_REASON);
        List<String> reasons = new ArrayList<>();
        reasons.add("TON_JETTON_FANOUT");
        reasons.add(JETTON_FANOUT_DUPLICATE_REASON);
        tx.setMissingDataReasons(reasons);
        tx.setFlows(buildFeeFlow(view));
        return tx;
    }

    /**
     * Registry-driven stake/unstake: a wallet↔registered-STAKING-vault jetton move classifies as
     * {@code STAKING_DEPOSIT} (wallet is source) / {@code STAKING_WITHDRAW} (wallet is destination).
     * Returns {@code null} when no leg touches a configured STAKING vault (the common case; inert
     * until a vault such as Affluent is added to {@code ton-protocol-registry.json}).
     */
    private ClassifyResult classifyStaking(String walletAddress, List<Document> jettonTransfers) {
        boolean deposit = false;
        boolean withdraw = false;
        for (Document jt : jettonTransfers) {
            String src = stringField(jt, "source");
            String dest = stringField(jt, "destination");
            boolean srcWallet = sameTonAddress(walletAddress, src);
            boolean destWallet = sameTonAddress(walletAddress, dest);
            String peer = srcWallet ? dest : (destWallet ? src : null);
            if (peer == null || !STAKING_FAMILY.equals(TonProtocolRegistry.family(peer))) {
                continue;
            }
            if (srcWallet) {
                deposit = true;
            }
            if (destWallet) {
                withdraw = true;
            }
        }
        if (deposit && !withdraw) {
            return ClassifyResult.of(NormalizedTransactionType.STAKING_DEPOSIT);
        }
        if (withdraw && !deposit) {
            return ClassifyResult.of(NormalizedTransactionType.STAKING_WITHDRAW);
        }
        return null;
    }

    /**
     * Detects a Ston.fi / Dedust DEX swap from the wallet's deduped jetton legs. Proxy-TON (pTON)
     * legs are folded into native TON before pairing (so pTON nets to 0 and never becomes a swap
     * sibling leg). A swap requires a DEX marker (a {@code stonfi_swap*}/{@code dedust*} forward
     * payload, or a proxy-TON leg — pTON exists only for Ston.fi routing) and a clean shape of
     * exactly one net-out asset and one net-in asset. Returns {@code null} otherwise.
     */
    private DexSwap detectDexSwap(TonRawTransactionView view, String walletAddress, List<Document> deduped) {
        if (deduped.isEmpty()) {
            return null;
        }
        boolean marker = false;
        Map<String, SwapAsset> byAsset = new LinkedHashMap<>();
        for (Document jt : deduped) {
            String master = stringField(jt, "jetton_master");
            if (master == null) {
                master = stringField(jt, "jetton_address");
            }
            boolean proxy = master != null && TonProtocolRegistry.isProxyTon(master);
            if (proxy || hasDexSwapMarker(jt)) {
                marker = true;
            }
            boolean incoming = sameTonAddress(walletAddress, stringField(jt, "destination"));
            boolean outgoing = sameTonAddress(walletAddress, stringField(jt, "source"));
            if (!incoming && !outgoing) {
                continue;
            }
            String amountStr = stringField(jt, "amount");
            if (amountStr == null) {
                continue;
            }
            BigDecimal rawAmount;
            try {
                rawAmount = new BigDecimal(amountStr);
            } catch (NumberFormatException e) {
                continue;
            }
            String contract = proxy ? NetworkNativeAssets.nativeIdentity(NetworkId.TON) : canonicalJettonContract(master);
            if (contract == null) {
                continue;
            }
            String symbol = proxy ? NetworkNativeAssets.nativeSymbol(NetworkId.TON) : resolveJettonSymbol(jt, master);
            int decimals = proxy ? NetworkNativeAssets.nativeDecimals(NetworkId.TON) : resolveJettonDecimals(jt, master);
            BigDecimal signed = rawAmount.movePointLeft(decimals);
            if (outgoing) {
                signed = signed.negate();
            }
            String peer = incoming ? stringField(jt, "source") : stringField(jt, "destination");
            String claimKey = jettonClaimKey(jt);
            SwapAsset asset = byAsset.computeIfAbsent(contract, k -> new SwapAsset(contract, symbol));
            asset.qty = asset.qty.add(signed);
            if (asset.counterparty == null && peer != null) {
                asset.counterparty = peer;
            }
            if (claimKey != null && (asset.claimKey == null || claimKey.compareTo(asset.claimKey) < 0)) {
                asset.claimKey = claimKey;
            }
        }
        if (!marker) {
            return null;
        }
        SwapAsset out = null;
        SwapAsset in = null;
        for (SwapAsset asset : byAsset.values()) {
            int sign = asset.qty.signum();
            if (sign == 0) {
                continue;
            }
            if (sign < 0) {
                if (out != null) {
                    return null;
                }
                out = asset;
            } else {
                if (in != null) {
                    return null;
                }
                in = asset;
            }
        }
        if (out == null || in == null) {
            return null;
        }
        return new DexSwap(out.contract, out.symbol, out.qty, out.counterparty,
                in.contract, in.symbol, in.qty, in.counterparty,
                minClaimKey(out.claimKey, in.claimKey));
    }

    /**
     * Swap-aware fan-out claim: the swap is booked once, on the canonical (lowest {@code txHash})
     * sibling for its primary leg identity. Non-canonical siblings are excluded as duplicates so a
     * multi-row DEX trace never double-books. When no queryable identity is present (isolated
     * builder callers / {@link #CLAIM_ALL}), the row books the swap directly.
     */
    private boolean swapClaimsThisRow(DexSwap swap, String walletAddress, String rawTxHash, JettonFanoutClaim claim) {
        if (swap.primaryClaimKey() == null) {
            return true;
        }
        return claim.claims(walletAddress, rawTxHash, swap.primaryClaimKey());
    }

    private List<NormalizedTransaction.Flow> buildDexSwapFlows(DexSwap swap, TonRawTransactionView view) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(swapLeg(swap.outContract(), swap.outSymbol(), swap.outQty(),
                NormalizedLegRole.SELL, swap.outCounterparty()));
        flows.add(swapLeg(swap.inContract(), swap.inSymbol(), swap.inQty(),
                NormalizedLegRole.BUY, swap.inCounterparty()));
        flows.addAll(buildFeeFlow(view));
        return flows;
    }

    private static NormalizedTransaction.Flow swapLeg(String contract, String symbol, BigDecimal qty,
                                                      NormalizedLegRole role, String counterparty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetContract(contract);
        if (symbol != null) {
            flow.setAssetSymbol(symbol);
        }
        flow.setRole(role);
        flow.setQuantityDelta(qty);
        flow.setCounterpartyAddress(counterparty);
        return flow;
    }

    private static boolean hasDexSwapMarker(Document jt) {
        Document forwardPayload = jt.get("decoded_forward_payload", Document.class);
        if (forwardPayload == null) {
            return false;
        }
        String type = stringField(forwardPayload, "@type");
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains(STONFI_SWAP_MARKER) || lower.contains(DEDUST_MARKER);
    }

    private static String minClaimKey(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    /** Mutable per-asset swap accumulator (proxy-TON already folded into the TON asset key). */
    private static final class SwapAsset {
        private final String contract;
        private final String symbol;
        private BigDecimal qty = BigDecimal.ZERO;
        private String counterparty;
        private String claimKey;

        private SwapAsset(String contract, String symbol) {
            this.contract = contract;
            this.symbol = symbol;
        }
    }

    /** Resolved two-leg DEX swap: out leg (SELL) + in leg (BUY), booked once per {@code primaryClaimKey}. */
    private record DexSwap(
            String outContract, String outSymbol, BigDecimal outQty, String outCounterparty,
            String inContract, String inSymbol, BigDecimal inQty, String inCounterparty,
            String primaryClaimKey
    ) {
    }

    // ---- jetton fan-out dedup (RULE 2) ----

    /**
     * Stable identity key for jetton fan-out dedup: the TON Center trace/transaction hash when
     * present, otherwise the composite {@code {jettonMaster|query_id|amount|source|destination}}.
     * Genuinely distinct transfers (different {@code transaction_hash}, or differing
     * query_id/amount/peers) never collapse; only replicated copies of one logical transfer do.
     */
    private static String jettonIdentityKey(Document jt) {
        String txHash = stringField(jt, "transaction_hash");
        if (txHash != null) {
            return "h:" + txHash;
        }
        String master = stringField(jt, "jetton_master");
        if (master == null) {
            master = stringField(jt, "jetton_address");
        }
        return String.join("|",
                "c",
                nullSafe(master),
                nullSafe(stringField(jt, "query_id")),
                nullSafe(stringField(jt, "amount")),
                nullSafe(stringField(jt, "source")),
                nullSafe(stringField(jt, "destination")));
    }

    /** Cross-raw claim key (the queryable identity): {@code transaction_hash}, or null when absent. */
    private static String jettonClaimKey(Document jt) {
        return stringField(jt, "transaction_hash");
    }

    /** Collapses replicated jetton transfers within a single raw row, preserving encounter order. */
    private static List<Document> dedupeWithinRow(List<Document> jettonTransfers) {
        if (jettonTransfers.size() <= 1) {
            return jettonTransfers;
        }
        Map<String, Document> byIdentity = new LinkedHashMap<>();
        for (Document jt : jettonTransfers) {
            byIdentity.putIfAbsent(jettonIdentityKey(jt), jt);
        }
        return new ArrayList<>(byIdentity.values());
    }

    /**
     * Keeps only the jetton transfers this raw is the canonical owner of (per
     * {@link JettonFanoutClaim}). Transfers without a queryable {@code transaction_hash} are always
     * kept, since cross-raw dedup cannot key them.
     */
    private static List<Document> filterClaimed(List<Document> dedupedTransfers, String walletAddress,
                                                String rawTxHash, JettonFanoutClaim claim) {
        if (dedupedTransfers.isEmpty()) {
            return dedupedTransfers;
        }
        List<Document> claimed = new ArrayList<>(dedupedTransfers.size());
        for (Document jt : dedupedTransfers) {
            String claimKey = jettonClaimKey(jt);
            if (claimKey == null || claim.claims(walletAddress, rawTxHash, claimKey)) {
                claimed.add(jt);
            }
        }
        return claimed;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ---- helpers ----

    private boolean isOwnAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return accountingUniverseService.isMember(address, NetworkId.TON);
        } catch (IllegalStateException e) {
            // No universe bound (e.g. test or standalone context)
            return false;
        }
    }

    /**
     * Canonical-form equality for two TON addresses. The stored wallet is user-friendly
     * ({@code UQ…}) while TON Center emits raw {@code workchain:hex}; both are expanded to their
     * shared {@link TonAddressCanonicalizer} key set (raw {@code 0:hex} + friendly) and compared on
     * intersection. Falls back to case-insensitive equality when neither is a recognisable TON form.
     */
    static boolean sameTonAddress(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        List<String> leftKeys = TonAddressCanonicalizer.lookupKeys(left.trim());
        List<String> rightKeys = TonAddressCanonicalizer.lookupKeys(right.trim());
        for (String key : leftKeys) {
            if (rightKeys.contains(key)) {
                return true;
            }
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean isNativeValueOpcode(String opcode) {
        if (opcode == null || opcode.isBlank()) {
            return true;
        }
        return NATIVE_VALUE_OPCODES.contains(opcode.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isJettonOrDefiOpcode(String opcode) {
        if (opcode == null || opcode.isBlank()) {
            return false;
        }
        return JETTON_DEFI_OPCODES.contains(opcode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when the transaction carries jetton/DeFi economic value that the current evidence cannot
     * book (jetton transfers absent), so an {@code UNKNOWN} classification represents dropped value
     * rather than a genuinely empty/fee-only row.
     */
    private boolean hasUnresolvedEconomicValue(TonRawTransactionView view) {
        if (!view.jettonTransfers().isEmpty()) {
            return false;
        }
        if (isJettonOrDefiOpcode(view.inMsgOpcode())) {
            return true;
        }
        for (Document outMsg : view.outMsgs()) {
            if (isJettonOrDefiOpcode(stringField(outMsg, "decoded_opcode"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the row carries no resolvable jetton transfer and no bookable native TON value
     * movement, so nothing can be booked. Such rows are out of scope (scam
     * {@code transfer_notification} dust, deep TON DeFi) and are excluded from accounting so they do
     * not block the conservation gate. Only meaningful on the {@code needsReview} path.
     */
    private boolean isUnbookableOutOfScope(TonRawTransactionView view, String walletAddress) {
        if (!view.jettonTransfers().isEmpty()) {
            return false;
        }
        return !hasBookableNativeValue(view, walletAddress);
    }

    /**
     * True when the transaction has a plain native-TON value transfer (inbound or outbound) that
     * would be booked. Mirrors the native-value branch of {@link #classify}; jetton/DeFi machinery
     * opcodes that merely forward gas are excluded via {@link #isNativeValueOpcode}.
     */
    private boolean hasBookableNativeValue(TonRawTransactionView view, String walletAddress) {
        if (view.inMsgValueNano() > 0
                && sameTonAddress(walletAddress, view.inMsgDestination())
                && isNativeValueOpcode(view.inMsgOpcode())) {
            return true;
        }
        for (Document outMsg : view.outMsgs()) {
            if (toLong(outMsg.get("value")) > 0
                    && sameTonAddress(walletAddress, stringField(outMsg, "source"))
                    && isNativeValueOpcode(stringField(outMsg, "decoded_opcode"))) {
                return true;
            }
        }
        return false;
    }

    private int resolveJettonDecimals(Document jt, String jettonMaster) {
        Integer fromContent = decimalsFromContent(jt);
        if (fromContent != null) {
            cacheDecimals(jettonMaster, fromContent);
            return fromContent;
        }
        String cacheKey = decimalsCacheKey(jettonMaster);
        if (cacheKey != null) {
            Integer cached = jettonDecimalsCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
            Integer configured = TonJettonMetadataRegistry.decimals(jettonMaster);
            if (configured != null) {
                jettonDecimalsCache.put(cacheKey, configured);
                return configured;
            }
        }
        return DEFAULT_JETTON_DECIMALS;
    }

    private void cacheDecimals(String jettonMaster, int decimals) {
        String cacheKey = decimalsCacheKey(jettonMaster);
        if (cacheKey != null) {
            jettonDecimalsCache.put(cacheKey, decimals);
        }
    }

    private static String decimalsCacheKey(String jettonMaster) {
        if (jettonMaster == null || jettonMaster.isBlank()) {
            return null;
        }
        List<String> keys = TonAddressCanonicalizer.lookupKeys(jettonMaster.trim());
        return keys.isEmpty() ? jettonMaster.trim().toLowerCase(Locale.ROOT) : keys.get(0);
    }

    private static Integer decimalsFromContent(Document jt) {
        Document metadata = jt.get("jetton_content", Document.class);
        if (metadata == null) {
            return null;
        }
        Object dec = metadata.get("decimals");
        if (dec instanceof Number num) {
            return num.intValue();
        }
        if (dec instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String resolveJettonSymbol(Document jt, String jettonMaster) {
        Document metadata = jt.get("jetton_content", Document.class);
        if (metadata != null) {
            String symbol = stringField(metadata, "symbol");
            if (symbol != null) {
                return symbol.toUpperCase(Locale.ROOT);
            }
            String name = stringField(metadata, "name");
            if (name != null) {
                return name.toUpperCase(Locale.ROOT);
            }
        }
        String configured = TonJettonMetadataRegistry.symbol(jettonMaster);
        if (configured != null) {
            return configured.toUpperCase(Locale.ROOT);
        }
        if (jettonMaster != null && jettonMaster.length() > 8) {
            return jettonMaster.substring(0, 8).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Jetton asset contract key: the master lowercased as TON Center provides it (ADR-064 stores
     * the jetton master as the asset contract; the DefiLlama {@code ton:<master_lowercase>} pricing
     * key is derived from this form). The pricing form reconciliation for the toncenter jetton
     * master vs. the {@code network-descriptors.yml} stablecoin-contract form is a follow-up once a
     * populated {@code jettonTransfers} payload is available to verify against.
     */
    private static String canonicalJettonContract(String jettonMaster) {
        if (jettonMaster == null || jettonMaster.isBlank()) {
            return null;
        }
        return jettonMaster.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal nanoToTon(long nanoTon) {
        return BigDecimal.valueOf(nanoTon).divide(NANO_TON_DIVISOR, 9, java.math.RoundingMode.DOWN);
    }

    private static String stringField(Document doc, String key) {
        if (doc == null) {
            return null;
        }
        Object val = doc.get(key);
        if (val == null) {
            return null;
        }
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    // ---- inner result record ----

    private record ClassifyResult(
            NormalizedTransactionType type,
            boolean needsReview,
            boolean continuityCandidate
    ) {
        static ClassifyResult of(NormalizedTransactionType type) {
            return new ClassifyResult(type, false, false);
        }

        static ClassifyResult internalTransfer() {
            return new ClassifyResult(NormalizedTransactionType.INTERNAL_TRANSFER, false, true);
        }

        static ClassifyResult unknown() {
            return new ClassifyResult(NormalizedTransactionType.UNKNOWN, true, false);
        }
    }
}
