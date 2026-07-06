package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NftMintRetaggerTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private NftMintRetagger retagger;

    @BeforeEach
    void setUp() {
        retagger = new NftMintRetagger(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("ETH out with known mint selector and no inbound tokens → NFT_MINT")
    void ethOutWithMintSelectorReclassified() {
        RawTransaction raw = nftMintRaw("330000000000000", "0x2e4dbe8f", null);
        boolean matches = retagger.matchesNftMintSignature(raw);
        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("ETH out with unknown selector is NOT reclassified")
    void unknownSelectorNotReclassified() {
        RawTransaction raw = nftMintRaw("330000000000000", "0xdeadbeef", null);
        boolean matches = retagger.matchesNftMintSignature(raw);
        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("ETH out with mint selector but inbound token transfers is NOT reclassified")
    void inboundTokenTransfersBlockReclassification() {
        Document inboundToken = new Document()
                .append("contractAddress", "0xaaaa000000000000000000000000000000000001")
                .append("tokenSymbol", "USDC")
                .append("from", "0xbbbb000000000000000000000000000000000002")
                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                .append("value", "1000000");
        RawTransaction raw = nftMintRaw("330000000000000", "0x2e4dbe8f", List.of(inboundToken));
        boolean matches = retagger.matchesNftMintSignature(raw);
        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("zero-value ETH out is NOT reclassified")
    void zeroValueNotReclassified() {
        RawTransaction raw = nftMintRaw("0", "0x2e4dbe8f", null);
        boolean matches = retagger.matchesNftMintSignature(raw);
        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("full reclassification changes type to NFT_MINT")
    void fullReclassificationChangesType() {
        NormalizedTransaction tx = externalTransferOut(
                "0x2dc06caa",
                "ETH",
                "-0.00033"
        );
        RawTransaction raw = nftMintRaw("330000000000000", "0x2e4dbe8f", null);

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean reclassified = retagger.reclassifyIfNftMint(tx, Instant.now());

        assertThat(reclassified).isTrue();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.NFT_MINT);
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.SELL);
    }

    @Test
    @DisplayName("tx with inbound flow is NOT reclassified (swap, not mint)")
    void txWithInboundFlowNotReclassified() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("0xswap");
        tx.setTxHash("0xswap");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setBlockTimestamp(Instant.parse("2026-03-01T10:00:00Z"));

        NormalizedTransaction.Flow outFlow = new NormalizedTransaction.Flow();
        outFlow.setAssetSymbol("ETH");
        outFlow.setQuantityDelta(new BigDecimal("-0.01"));
        outFlow.setRole(NormalizedLegRole.SELL);

        NormalizedTransaction.Flow inFlow = new NormalizedTransaction.Flow();
        inFlow.setAssetSymbol("USDC");
        inFlow.setQuantityDelta(new BigDecimal("25.0"));
        inFlow.setRole(NormalizedLegRole.BUY);

        tx.setFlows(new ArrayList<>(List.of(outFlow, inFlow)));

        boolean reclassified = retagger.reclassifyIfNftMint(tx, Instant.now());
        assertThat(reclassified).isFalse();
    }

    private static RawTransaction nftMintRaw(String value, String methodId, List<Document> tokenTransfers) {
        RawTransaction raw = new RawTransaction();
        Document rawData = new Document();
        rawData.put("value", value);
        rawData.put("methodId", methodId);
        if (tokenTransfers != null) {
            Document explorer = new Document();
            explorer.put("tokenTransfers", tokenTransfers);
            rawData.put("explorer", explorer);
        }
        raw.setRawData(rawData);
        return raw;
    }

    private static NormalizedTransaction externalTransferOut(
            String id, String assetSymbol, String quantity
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(id);
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setBlockTimestamp(Instant.parse("2026-03-01T10:00:00Z"));
        tx.setCounterpartyAddress("MULTI");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.SELL);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }
}
