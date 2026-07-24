package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RC-T1 canonical-equality + jetton-decimals coverage for {@link TonNormalizedTransactionBuilder}.
 *
 * <p>The core bug this proves fixed: the stored wallet is user-friendly ({@code UQ…}) while TON
 * Center emits the raw {@code workchain:hex} form on {@code in_msg}/{@code out_msgs}/jetton
 * {@code source}/{@code destination}. Before RC-T1 the {@code equalsIgnoreCase} comparison never
 * matched, so every native TON + jetton transfer collapsed to {@code UNKNOWN} with a fee-only
 * flow. Here the wallet is seeded in its friendly form and every raw peer/self field uses the raw
 * form derived from the same address, so a correct canonical comparison is the only way the
 * expected direction can be produced.</p>
 */
class TonNormalizedTransactionBuilderTest {

    /** USDT-TON jetton master (descriptor-override to 6 decimals in network-descriptors.yml). Evidence anchor. */
    private static final String USDT_TON_MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    // Deterministic raw addresses; friendly forms are derived so the round-trip is guaranteed.
    private static final String RAW_WALLET = "0:" + "11".repeat(32);
    private static final String RAW_EXTERNAL_PEER = "0:" + "22".repeat(32);
    private static final String RAW_OWN_PEER = "0:" + "33".repeat(32);

    private static final String FRIENDLY_WALLET = friendly(RAW_WALLET);

    private static String friendly(String raw) {
        return TonAddressCanonicalizer.lookupKeys(raw).stream()
                .filter(key -> !key.contains(":"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no friendly form for " + raw));
    }

    private TonNormalizedTransactionBuilder builder(AccountingUniverseService universe) {
        return new TonNormalizedTransactionBuilder(universe);
    }

    private TonNormalizedTransactionBuilder builder() {
        return builder(mock(AccountingUniverseService.class));
    }

    private static RawTransaction raw(Document transaction, List<Document> jettonTransfers) {
        return raw("hash1", FRIENDLY_WALLET, transaction, jettonTransfers);
    }

    private static RawTransaction raw(String txHash, String walletAddress,
                                      Document transaction, List<Document> jettonTransfers) {
        RawTransaction r = new RawTransaction();
        r.setId(txHash + ":TON:" + walletAddress);
        r.setTxHash(txHash);
        r.setWalletAddress(walletAddress);
        r.setNetworkId(NetworkId.TON.name());
        Document rawData = new Document("source", "TONCENTER_V3").append("transaction", transaction);
        if (jettonTransfers != null) {
            rawData.append("jettonTransfers", jettonTransfers);
        }
        r.setRawData(rawData);
        return r;
    }

    private static Document usdtJt(String source, String destination, String amount) {
        return new Document("jetton_master", USDT_TON_MASTER)
                .append("source", source)
                .append("destination", destination)
                .append("amount", amount);
    }

    private static Document txDoc() {
        return new Document("now", 1_700_000_000L).append("total_fees", 3_000_000L);
    }

    @Test
    @DisplayName("RC-T1: friendly UQ wallet matches raw 0:hex in_msg destination → EXTERNAL_TRANSFER_IN")
    void inboundNativeCanonicalEquality() {
        Document tx = txDoc().append("in_msg", new Document("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("value", 2_000_000_000L));

        NormalizedTransaction normalized = builder().build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        NormalizedTransaction.Flow transfer = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getAssetSymbol()).isEqualTo("TON");
        assertThat(transfer.getQuantityDelta().doubleValue()).isEqualTo(2.0);
        assertThat(transfer.getCounterpartyAddress()).isEqualTo(RAW_EXTERNAL_PEER);
    }

    @Test
    @DisplayName("RC-T1: friendly UQ wallet matches raw 0:hex out_msg source → EXTERNAL_TRANSFER_OUT")
    void outboundNativeCanonicalEquality() {
        Document tx = txDoc()
                .append("in_msg", new Document("source", RAW_EXTERNAL_PEER).append("value", 0L))
                .append("out_msgs", List.of(new Document("source", RAW_WALLET)
                        .append("destination", RAW_EXTERNAL_PEER)
                        .append("value", 1_500_000_000L)
                        .append("decoded_opcode", "text_comment")));

        NormalizedTransaction normalized = builder().build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        NormalizedTransaction.Flow transfer = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getQuantityDelta().doubleValue()).isEqualTo(-1.5);
        assertThat(transfer.getCounterpartyAddress()).isEqualTo(RAW_EXTERNAL_PEER);
    }

    @Test
    @DisplayName("RC-T1: inbound native from an own wallet (raw form) → INTERNAL_TRANSFER")
    void inboundNativeOwnPeerIsInternal() {
        AccountingUniverseService universe = mock(AccountingUniverseService.class);
        when(universe.isMember(any(), eq(NetworkId.TON))).thenReturn(true);

        Document tx = txDoc().append("in_msg", new Document("source", RAW_OWN_PEER)
                .append("destination", RAW_WALLET)
                .append("value", 1_000_000_000L));

        NormalizedTransaction normalized = builder(universe).build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(normalized.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("RC-T1: jetton source/dest (raw) match wallet (friendly) → EXTERNAL_TRANSFER_IN")
    void jettonInboundCanonicalEquality() {
        Document jt = new Document("jetton_master", USDT_TON_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "5000000");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt)), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        NormalizedTransaction.Flow transfer = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getCounterpartyAddress()).isEqualTo(RAW_EXTERNAL_PEER);
    }

    @Test
    @DisplayName("RC-T1: USDT-TON jetton with absent jetton_content resolves to 6 decimals + symbol USDT")
    void usdtTonDecimalsAndSymbolFromRegistry() {
        // 5,000,000 raw units → 5.0 USDT at 6 decimals (would be 0.005 at the wrong native default of 9).
        Document jt = new Document("jetton_master", USDT_TON_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "5000000");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt)), Instant.now());

        NormalizedTransaction.Flow transfer = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getAssetSymbol()).isEqualTo("USDT");
        assertThat(transfer.getQuantityDelta().doubleValue()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Outbound USDT jetton (raw source=wallet) → negative 6-decimal delta, EXTERNAL_TRANSFER_OUT, booked")
    void usdtTonOutboundSignedAndBooked() {
        // 30,725,310 raw units → 30.725310 USDT at 6 decimals; sent = negative delta to owner.
        Document jt = new Document("jetton_master", USDT_TON_MASTER)
                .append("source", RAW_WALLET)
                .append("destination", RAW_EXTERNAL_PEER)
                .append("amount", "30725310");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt)), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(normalized.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        NormalizedTransaction.Flow transfer = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getAssetSymbol()).isEqualTo("USDT");
        assertThat(transfer.getQuantityDelta().doubleValue()).isEqualTo(-30.725310);
        assertThat(transfer.getCounterpartyAddress()).isEqualTo(RAW_EXTERNAL_PEER);
    }

    @Test
    @DisplayName("Scam transfer_notification dust (no jetton transfer, no native value) → excluded from accounting, non-blocking")
    void scamNotificationExcludedFromAccounting() {
        // Unsolicited scam jetton transfer_notification: opcode jetton_notify, dust in_msg value=1
        // nanoTON, no out_msgs, no resolvable jettonTransfers. Must be non-blocking (excluded) but
        // still visible as NEEDS_REVIEW.
        Document tx = txDoc().append("in_msg", new Document("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("value", 1L)
                .append("decoded_opcode", "jetton_notify"));

        NormalizedTransaction normalized = builder().build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(normalized.getStatus())
                .isEqualTo(com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(normalized.getExcludedFromAccounting()).isTrue();
        assertThat(normalized.getAccountingExclusionReason())
                .isEqualTo(TonNormalizedTransactionBuilder.UNSUPPORTED_SCOPE_REASON);
        assertThat(normalized.getMissingDataReasons())
                .contains(TonNormalizedTransactionBuilder.UNSUPPORTED_SCOPE_REASON);
        // Fee leg preserved for observability.
        assertThat(normalized.getFlows()).anyMatch(f -> f.getRole() == NormalizedLegRole.FEE);
    }

    @Test
    @DisplayName("Resolved USDT jetton transfer is booked and NOT excluded from accounting")
    void resolvedJettonNotExcluded() {
        Document jt = new Document("jetton_master", USDT_TON_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "5000000");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt)), Instant.now());

        assertThat(normalized.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(normalized.getAccountingExclusionReason()).isNull();
    }

    @Test
    @DisplayName("RC-T1/RC-T2: jetton/DeFi opcode with no usable jetton evidence stays NEEDS_REVIEW + dropped-value marker")
    void unresolvedJettonValueStaysNeedsReview() {
        Document tx = txDoc().append("in_msg", new Document("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("value", 200_000_000L)
                .append("decoded_opcode", "jetton_notify"));

        NormalizedTransaction normalized = builder().build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(normalized.getMissingDataReasons())
                .contains(TonNormalizedTransactionBuilder.ONCHAIN_UNRESOLVED_VALUE);
    }

    @Test
    @DisplayName("Failed TON transaction (compute exit_code != 0) → NEEDS_REVIEW, fee leg preserved")
    void failedTransactionNeedsReview() {
        Document tx = txDoc().append("description", new Document("compute_ph", new Document("exit_code", 4)))
                .append("in_msg", new Document("source", RAW_EXTERNAL_PEER)
                        .append("destination", RAW_WALLET).append("value", 1_000_000_000L));

        NormalizedTransaction normalized = builder().build(raw(tx, null), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(normalized.getMissingDataReasons()).contains("TON_FAILED_TRANSACTION");
        assertThat(normalized.getFlows()).anyMatch(f -> f.getRole() == NormalizedLegRole.FEE);
    }

    @Test
    @DisplayName("sameTonAddress: friendly UQ and its raw 0:hex form are equal; distinct addresses are not")
    void sameTonAddressCanonicalIntersection() {
        assertThat(TonNormalizedTransactionBuilder.sameTonAddress(FRIENDLY_WALLET, RAW_WALLET)).isTrue();
        assertThat(TonNormalizedTransactionBuilder.sameTonAddress(FRIENDLY_WALLET, RAW_EXTERNAL_PEER)).isFalse();
        assertThat(TonNormalizedTransactionBuilder.sameTonAddress("0xabc", "0xdef")).isFalse();
    }

    // ---- B2: jetton fan-out deduplication (RULE 2) ----

    @Test
    @DisplayName("B2: N replicated jetton messages (same transaction_hash) within one row → one flow")
    void jettonFanoutWithinRowCollapsesToSingleFlow() {
        Document jt1 = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_A");
        Document jt2 = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_A");
        Document jt3 = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_A");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt1, jt2, jt3)), Instant.now());

        List<NormalizedTransaction.Flow> transfers = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).toList();
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).getQuantityDelta().doubleValue()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("B2: two distinct transfers (different transaction_hash, same amount) are NOT merged")
    void jettonDistinctTransfersNotMerged() {
        Document jt1 = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000")
                .append("transaction_hash", "TRACE_A").append("query_id", "1");
        Document jt2 = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000")
                .append("transaction_hash", "TRACE_B").append("query_id", "2");

        NormalizedTransaction normalized = builder().build(raw(txDoc(), List.of(jt1, jt2)), Instant.now());

        long transferCount = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).count();
        assertThat(transferCount).isEqualTo(2);
    }

    @Test
    @DisplayName("B2: non-canonical fan-out sibling (claim=false) is excluded, keeps fee only, no value")
    void jettonFanoutCrossRowSiblingExcluded() {
        Document jt = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_A");
        TonNormalizedTransactionBuilder.JettonFanoutClaim denyAll = (w, r, h) -> false;

        NormalizedTransaction normalized =
                builder().build(raw(txDoc(), List.of(jt)), Instant.now(), denyAll);

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(normalized.getExcludedFromAccounting()).isTrue();
        assertThat(normalized.getAccountingExclusionReason())
                .isEqualTo(TonNormalizedTransactionBuilder.JETTON_FANOUT_DUPLICATE_REASON);
        assertThat(normalized.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.TRANSFER);
        assertThat(normalized.getFlows()).anyMatch(f -> f.getRole() == NormalizedLegRole.FEE);
    }

    @Test
    @DisplayName("B2: canonical fan-out sibling (claim=true) books exactly one value flow")
    void jettonFanoutCrossRowCanonicalBooks() {
        Document jt = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_A");
        TonNormalizedTransactionBuilder.JettonFanoutClaim allowAll = (w, r, h) -> true;

        NormalizedTransaction normalized =
                builder().build(raw(txDoc(), List.of(jt)), Instant.now(), allowAll);

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(normalized.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        long transferCount = normalized.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).count();
        assertThat(transferCount).isEqualTo(1);
    }

    // ---- B3: own↔own symmetric carry + canonicalization (RULE 3) ----

    @Test
    @DisplayName("B3: own↔own jetton transfer → INTERNAL_TRANSFER on both sides with symmetric CARRY signs")
    void ownToOwnJettonSymmetricCarry() {
        AccountingUniverseService universe = mock(AccountingUniverseService.class);
        when(universe.isMember(any(), eq(NetworkId.TON))).thenReturn(true);

        String friendlyPeer = friendly(RAW_OWN_PEER);

        // Sender side (wallet == source): destination is another own wallet → outbound carry.
        Document jtSend = usdtJt(RAW_WALLET, RAW_OWN_PEER, "8774375").append("transaction_hash", "TRACE_OWN");
        NormalizedTransaction sender = builder(universe)
                .build(raw("sendHash", FRIENDLY_WALLET, txDoc(), List.of(jtSend)), Instant.now());

        // Receiver side (wallet == destination): same logical transfer on the peer wallet's row.
        Document jtRecv = usdtJt(RAW_WALLET, RAW_OWN_PEER, "8774375").append("transaction_hash", "TRACE_OWN");
        NormalizedTransaction receiver = builder(universe)
                .build(raw("recvHash", friendlyPeer, txDoc(), List.of(jtRecv)), Instant.now());

        assertThat(sender.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(receiver.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(sender.getContinuityCandidate()).isTrue();
        assertThat(receiver.getContinuityCandidate()).isTrue();

        NormalizedTransaction.Flow out = sender.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        NormalizedTransaction.Flow in = receiver.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();

        assertThat(out.getQuantityDelta().doubleValue()).isEqualTo(-8.774375);
        assertThat(in.getQuantityDelta().doubleValue()).isEqualTo(8.774375);
        assertThat(out.getQuantityDelta().negate()).isEqualByComparingTo(in.getQuantityDelta());
    }

    // ---- WS-2: Ston.fi / Dedust swaps, proxy-TON (pTON) netting ----

    /** Ston.fi pTON v2 proxy-TON master (registered in ton-protocol-registry.json). Evidence anchor. */
    private static final String PTON_V2_MASTER =
            "0:671963027F7F85659AB55B821671688601CDCF1EE674FC7FBBB1A776A18D34A3";
    /** XAUT0 jetton master (raw form as toncenter emits). */
    private static final String XAUT_MASTER =
            "0:3547F2EE4022C794C80EA354B81BB63B5B571DD05AC091B035D19ABBADD74AC6";

    private static Document stonfiSwapPayload() {
        return new Document("@type", "stonfi_swap_v2_forward_payload");
    }

    @Test
    @DisplayName("WS-2: Ston.fi swap (XAUT out, pTON in) → SWAP with SELL(XAUT)+BUY(TON); pTON netted to native TON, no phantom pTON")
    void stonfiSwapNetsProxyTonToNativeTonWithSellBuyLegs() {
        Document ptonIn = new Document("jetton_master", PTON_V2_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "8869967763")
                .append("transaction_hash", "TRACE_PTON");
        Document xautOut = new Document("jetton_master", XAUT_MASTER)
                .append("source", RAW_WALLET)
                .append("destination", RAW_EXTERNAL_PEER)
                .append("amount", "3008")
                .append("transaction_hash", "TRACE_XAUT")
                .append("decoded_forward_payload", stonfiSwapPayload());

        NormalizedTransaction tx = builder().build(raw(txDoc(), List.of(ptonIn, xautOut)), Instant.now());

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        // No phantom pTON asset anywhere (netted to native TON).
        assertThat(tx.getFlows()).noneMatch(f -> PTON_V2_MASTER.toLowerCase().equals(f.getAssetContract()));
        NormalizedTransaction.Flow buy = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        assertThat(buy.getAssetSymbol()).isEqualTo("TON");
        assertThat(buy.getAssetContract()).isEqualTo("TONCOIN");
        assertThat(buy.getQuantityDelta().doubleValue()).isEqualTo(8.869967763);
        NormalizedTransaction.Flow sell = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(XAUT_MASTER.toLowerCase());
        assertThat(sell.getQuantityDelta().signum()).isNegative();
    }

    @Test
    @DisplayName("WS-2: a bare inbound pTON leg (no swap sibling) books as native TON, never a phantom pTON jetton")
    void proxyTonInboundNetsToNativeTon() {
        Document ptonIn = new Document("jetton_master", PTON_V2_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "8869967763")
                .append("transaction_hash", "TRACE_PTON_ONLY");

        NormalizedTransaction tx = builder().build(raw(txDoc(), List.of(ptonIn)), Instant.now());

        assertThat(tx.getFlows()).noneMatch(f -> PTON_V2_MASTER.toLowerCase().equals(f.getAssetContract()));
        NormalizedTransaction.Flow ton = tx.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(ton.getAssetContract()).isEqualTo("TONCOIN");
        assertThat(ton.getAssetSymbol()).isEqualTo("TON");
        assertThat(ton.getQuantityDelta().doubleValue()).isEqualTo(8.869967763);
    }

    @Test
    @DisplayName("WS-2: swap-aware fan-out — non-canonical sibling (claim denies primary leg) is excluded, no double-book")
    void stonfiSwapNonCanonicalSiblingExcluded() {
        Document ptonIn = new Document("jetton_master", PTON_V2_MASTER)
                .append("source", RAW_EXTERNAL_PEER)
                .append("destination", RAW_WALLET)
                .append("amount", "8869967763")
                .append("transaction_hash", "TRACE_PTON");
        Document xautOut = new Document("jetton_master", XAUT_MASTER)
                .append("source", RAW_WALLET)
                .append("destination", RAW_EXTERNAL_PEER)
                .append("amount", "3008")
                .append("transaction_hash", "TRACE_XAUT")
                .append("decoded_forward_payload", stonfiSwapPayload());
        TonNormalizedTransactionBuilder.JettonFanoutClaim denyAll = (w, r, h) -> false;

        NormalizedTransaction tx = builder().build(raw(txDoc(), List.of(ptonIn, xautOut)), Instant.now(), denyAll);

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason())
                .isEqualTo(TonNormalizedTransactionBuilder.JETTON_FANOUT_DUPLICATE_REASON);
        assertThat(tx.getFlows()).noneMatch(f -> f.getRole() == NormalizedLegRole.BUY
                || f.getRole() == NormalizedLegRole.SELL);
    }

    @Test
    @DisplayName("B3: external jetton transfer (peer not own) stays EXTERNAL, not promoted to internal")
    void externalJettonTransferStaysExternal() {
        AccountingUniverseService universe = mock(AccountingUniverseService.class);
        when(universe.isMember(eq(RAW_WALLET), eq(NetworkId.TON))).thenReturn(true);
        when(universe.isMember(eq(FRIENDLY_WALLET), eq(NetworkId.TON))).thenReturn(true);
        when(universe.isMember(eq(RAW_EXTERNAL_PEER), eq(NetworkId.TON))).thenReturn(false);

        Document jt = usdtJt(RAW_EXTERNAL_PEER, RAW_WALLET, "5000000").append("transaction_hash", "TRACE_EXT");
        NormalizedTransaction normalized = builder(universe).build(raw(txDoc(), List.of(jt)), Instant.now());

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }
}
