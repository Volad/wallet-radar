package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding 3 — value-conservation band on same-network custody round-trip pairing.
 *
 * <p>Same wallet / network / counterparty-set / family-set is necessary but not sufficient: a
 * dual-purpose router can be re-used for unrelated deposits. The pairing must additionally require
 * the returned value/quantity to be conserved within {@code [0.5×, 2×]}.
 *
 * <p>Evidence anchors: the Katana weETH+ETH vault round-trip (~$1,771 out vs ~$1,860 in,
 * composition-rebalanced) MUST link; the unrelated Avalanche 0.0013 AVAX out → 0.0482 AVAX in
 * (~37×, ~2 months apart) MUST abstain. Hashes/quantities are evidence anchors only, never runtime
 * keys.
 */
@ExtendWith(MockitoExtension.class)
class SameNetworkCustodyRoundTripLinkServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String WEETH_VAULT = "0xba9dd716ba2a4b9fa7818802beb631f10bd28073";
    private static final String ETH_VAULT = "0x223ec22d67716fca620aee72b25ffe4ece436f25";
    private static final String AVAX_ROUTER = "0x1111111111111111111111111111111111111111";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private SameNetworkCustodyRoundTripLinkService service;

    @BeforeEach
    void setUp() {
        service = new SameNetworkCustodyRoundTripLinkService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Finding 3: composition-rebalanced Katana round-trip (value conserved) links")
    void linksKatanaRoundTripWhenValueConserved() {
        NormalizedTransaction outbound = bridgeTx(NormalizedTransactionType.BRIDGE_OUT, NetworkId.KATANA,
                Instant.parse("2025-11-10T08:38:19Z"),
                leg("weETH", "-0.212586235193760390", WEETH_VAULT),
                leg("ETH", "-0.210631041768032693", ETH_VAULT));
        NormalizedTransaction inbound = bridgeTx(NormalizedTransactionType.BRIDGE_IN, NetworkId.KATANA,
                Instant.parse("2025-11-21T07:02:15Z"),
                leg("weETH", "0.144062214399527156", WEETH_VAULT),
                leg("ETH", "0.284650978143220617", ETH_VAULT));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound));

        boolean linked = service.pair(inbound);

        assertThat(linked).isTrue();
        assertThat(inbound.getCorrelationId()).startsWith(CorrelationContract.BRIDGE_CUSTODY_ROUNDTRIP_PREFIX);
        assertThat(outbound.getCorrelationId()).isEqualTo(inbound.getCorrelationId());
        assertThat(inbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getContinuityCandidate()).isTrue();
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("Finding 3: unrelated 37× AVAX pair abstains (value NOT conserved)")
    void abstainsWhenReturnedQuantityFarOutsideBand() {
        NormalizedTransaction outbound = bridgeTx(NormalizedTransactionType.BRIDGE_OUT, NetworkId.AVALANCHE,
                Instant.parse("2025-09-15T00:00:00Z"),
                leg("AVAX", "-0.0013", AVAX_ROUTER));
        NormalizedTransaction inbound = bridgeTx(NormalizedTransactionType.BRIDGE_IN, NetworkId.AVALANCHE,
                Instant.parse("2025-11-15T00:00:00Z"),
                leg("AVAX", "0.0482", AVAX_ROUTER));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound));

        boolean linked = service.pair(inbound);

        assertThat(linked).isFalse();
        // No custody-roundtrip correlation stamped (the leg stays orphan/unlinked).
        assertThat(inbound.getCorrelationId()).isNull();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private static NormalizedTransaction bridgeTx(
            NormalizedTransactionType type,
            NetworkId networkId,
            Instant timestamp,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(type + ":" + networkId + ":" + timestamp);
        tx.setTxHash("0x" + Integer.toHexString(System.identityHashCode(flows)) + networkId + type);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(networkId);
        tx.setBlockTimestamp(timestamp);
        tx.setCorrelationId(null);
        tx.setContinuityCandidate(false);
        tx.setFlows(new ArrayList<>(List.of(flows)));
        return tx;
    }

    private static NormalizedTransaction.Flow leg(String symbol, String signedQty, String counterparty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(signedQty.startsWith("-") ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(signedQty));
        flow.setCounterpartyAddress(counterparty);
        return flow;
    }
}
