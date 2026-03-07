package com.walletradar.domain.transaction.session;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTransactionSortKeyFactoryTest {

    @Test
    void fromNormalized_sameInput_producesStableKey() {
        NormalizedTransaction tx = tx("n-1", 9, 3);

        String first = SessionTransactionSortKeyFactory.fromNormalized(tx, SessionTransactionSourceType.CHAIN);
        String second = SessionTransactionSortKeyFactory.fromNormalized(tx, SessionTransactionSourceType.CHAIN);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void fromNormalized_usesMinLogIndexAndSourceOrdering() {
        NormalizedTransaction tx = tx("n-1", 9, 3);

        String chain = SessionTransactionSortKeyFactory.fromNormalized(tx, SessionTransactionSourceType.CHAIN);
        String manual = SessionTransactionSortKeyFactory.fromNormalized(tx, SessionTransactionSourceType.MANUAL);

        assertThat(chain).contains("|0000000003|00|");
        assertThat(manual).contains("|0000000003|01|");
    }

    @Test
    void fromNormalized_encodesNetworkOrderDeterministically() {
        NormalizedTransaction ethereumTx = tx("n-eth", 1);
        ethereumTx.setNetworkId(NetworkId.ETHEREUM);
        NormalizedTransaction bscTx = tx("n-bsc", 1);
        bscTx.setNetworkId(NetworkId.BSC);

        String ethKey = SessionTransactionSortKeyFactory.fromNormalized(ethereumTx, SessionTransactionSourceType.CHAIN);
        String bscKey = SessionTransactionSortKeyFactory.fromNormalized(bscTx, SessionTransactionSourceType.CHAIN);

        assertThat(ethKey).contains("|000|");
        assertThat(bscKey).contains("|005|");
    }

    private static NormalizedTransaction tx(String id, int... logIndexes) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash("0xabc");
        tx.setWalletAddress("0xwallet");
        tx.setNetworkId(NetworkId.BSC);
        tx.setBlockTimestamp(Instant.parse("2026-03-01T00:00:00Z"));

        List<NormalizedTransaction.Flow> flows = java.util.Arrays.stream(logIndexes)
                .mapToObj(i -> {
                    NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
                    f.setRole(NormalizedLegRole.TRANSFER);
                    f.setLogIndex(i);
                    return f;
                })
                .toList();
        tx.setFlows(flows);
        return tx;
    }
}
