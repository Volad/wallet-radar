package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.SpoofTokenQuarantineSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpoofTokenDetectorTest {

    private static final String FAKE_CONTRACT = "0x000000000000000000000000000000000000dead";
    private static final String REAL_USDC_CONTRACT = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
    private static final String CYRILLIC_USDC = "U\u0405D\u0421";       // UЅDС
    private static final String LISU_USDC = "\uA4F4\uA4E2\uA4D3\u0421"; // ꓴꓢꓓС
    private static final String REAL_USDT0 = "USD\u20AE0";               // USD₮0 (allow-listed ₮)

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private SpoofTokenDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SpoofTokenDetector(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("confusable-symbol OUT transfer is quarantined")
    void quarantinesConfusableOutbound() {
        NormalizedTransaction tx = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                NetworkId.BASE, flow(NormalizedLegRole.SELL, FAKE_CONTRACT, CYRILLIC_USDC, "-107.315094"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int excluded = detector.detectAndExclude(50);

        assertThat(excluded).isEqualTo(1);
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason()).isEqualTo(SpoofTokenQuarantineSupport.REASON);
        assertThat(tx.getMissingDataReasons()).contains(SpoofTokenQuarantineSupport.REASON);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("confusable-symbol IN transfer and Lisu spoof are quarantined (direction-agnostic)")
    void quarantinesInboundAndLisu() {
        NormalizedTransaction in = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                NetworkId.BASE, flow(NormalizedLegRole.BUY, FAKE_CONTRACT, LISU_USDC, "12.5"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(in));

        int excluded = detector.detectAndExclude(50);

        assertThat(excluded).isEqualTo(1);
        assertThat(in.getExcludedFromAccounting()).isTrue();
    }

    @Test
    @DisplayName("real USDC transfer is NOT quarantined")
    void realUsdcNotQuarantined() {
        NormalizedTransaction tx = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                NetworkId.BASE, flow(NormalizedLegRole.SELL, REAL_USDC_CONTRACT, "USDC", "-107.315094"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int excluded = detector.detectAndExclude(50);

        assertThat(excluded).isZero();
        assertThat(tx.getExcludedFromAccounting()).isNull();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("real USD₮0 (whitelisted ₮) transfer is NOT quarantined")
    void realUsdt0NotQuarantined() {
        NormalizedTransaction tx = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                NetworkId.OPTIMISM, flow(NormalizedLegRole.BUY, FAKE_CONTRACT, REAL_USDT0, "25"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int excluded = detector.detectAndExclude(50);

        assertThat(excluded).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("no candidates -> idempotent no-op")
    void idempotentNoCandidates() {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        int excluded = detector.detectAndExclude(50);

        assertThat(excluded).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("predicate detects confusable spoof principal but ignores fee-only confusable usage")
    void predicateDetection() {
        NormalizedTransaction feeOnly = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                NetworkId.BASE, feeFlow(CYRILLIC_USDC, "-0.001"));
        feeOnly.getFlows().add(flow(NormalizedLegRole.SELL, REAL_USDC_CONTRACT, "USDC", "-10"));
        assertThat(detector.hasConfusableSpoofPrincipal(feeOnly)).isFalse();

        NormalizedTransaction spoof = onChain(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                NetworkId.BASE, flow(NormalizedLegRole.SELL, FAKE_CONTRACT, CYRILLIC_USDC, "-10"));
        assertThat(detector.hasConfusableSpoofPrincipal(spoof)).isTrue();
    }

    private static NormalizedTransaction onChain(NormalizedTransactionType type, NetworkId networkId,
                                                 NormalizedTransaction.Flow flow) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setNetworkId(networkId);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }

    private static NormalizedTransaction.Flow flow(NormalizedLegRole role, String contract, String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(contract);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }

    private static NormalizedTransaction.Flow feeFlow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.FEE);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
