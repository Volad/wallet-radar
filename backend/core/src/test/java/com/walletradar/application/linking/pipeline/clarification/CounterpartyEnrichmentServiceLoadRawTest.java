package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the family-aware raw lookup (RC-S2): Solana base58 identifiers must be preserved
 * byte-for-byte, EVM identifiers must keep their 0x-lowercase form.
 */
class CounterpartyEnrichmentServiceLoadRawTest {

    private CounterpartyEnrichmentQueryService queryService;
    private RawTransactionRepository rawTransactionRepository;
    private NormalizedTransactionRepository normalizedTransactionRepository;

    /** Accepts every network and always claims a change, so processNextBatch runs loadRaw exactly once. */
    private static final class AlwaysEnrichResolver implements CounterpartyResolver {
        @Override
        public boolean supports(@Nullable NetworkId networkId) {
            return true;
        }

        @Override
        public boolean enrichInPlace(NormalizedTransaction normalizedTransaction,
                                     @Nullable RawTransaction rawTransaction, Instant now) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        queryService = mock(CounterpartyEnrichmentQueryService.class);
        rawTransactionRepository = mock(RawTransactionRepository.class);
        normalizedTransactionRepository = mock(NormalizedTransactionRepository.class);
        when(rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(rawTransactionRepository.findById(any())).thenReturn(Optional.empty());
    }

    private CounterpartyEnrichmentService service() {
        return new CounterpartyEnrichmentService(
                queryService,
                rawTransactionRepository,
                normalizedTransactionRepository,
                List.of(new AlwaysEnrichResolver())
        );
    }

    private static NormalizedTransaction tx(NetworkId network, String txHash, String wallet) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(txHash + ":" + network.name() + ":" + wallet);
        tx.setTxHash(txHash);
        tx.setNetworkId(network);
        tx.setWalletAddress(wallet);
        return tx;
    }

    @Test
    @DisplayName("RC-S2: Solana base58 txHash and wallet are looked up with case preserved")
    void solanaPreservesBase58Case() {
        String txHash = "4kD9SigABCdefGHijkLMnopQRstuvWXyz1234567890Abc";
        String wallet = "6Rc7yKz3aT2j2n7f3Q8Q3zvz1n2u9Wq3rXyZabCdEfG";
        when(queryService.loadBatchAfterId(null, 1)).thenReturn(List.of(tx(NetworkId.SOLANA, txHash, wallet)));

        service().processNextBatch(1);

        ArgumentCaptor<String> txHashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> networkCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> walletCaptor = ArgumentCaptor.forClass(String.class);
        verify(rawTransactionRepository).findByTxHashAndNetworkIdAndWalletAddress(
                txHashCaptor.capture(), networkCaptor.capture(), walletCaptor.capture());

        assertThat(txHashCaptor.getValue()).isEqualTo(txHash);
        assertThat(networkCaptor.getValue()).isEqualTo("SOLANA");
        assertThat(walletCaptor.getValue()).isEqualTo(wallet);
    }

    @Test
    @DisplayName("RC-S2: EVM txHash and wallet are lowercased (behaviour unchanged)")
    void evmLowercasesIdentifiers() {
        String txHash = "0xABCdef0000000000000000000000000000000000000000000000000000000001";
        String wallet = "0xWALLET000000000000000000000000000000ABCD";
        when(queryService.loadBatchAfterId(null, 1)).thenReturn(List.of(tx(NetworkId.ETHEREUM, txHash, wallet)));

        service().processNextBatch(1);

        ArgumentCaptor<String> txHashCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> walletCaptor = ArgumentCaptor.forClass(String.class);
        verify(rawTransactionRepository).findByTxHashAndNetworkIdAndWalletAddress(
                txHashCaptor.capture(), any(), walletCaptor.capture());

        assertThat(txHashCaptor.getValue()).isEqualTo(txHash.toLowerCase());
        assertThat(walletCaptor.getValue()).isEqualTo(wallet.toLowerCase());
    }
}
