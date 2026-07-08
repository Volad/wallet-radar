package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.application.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
class UnmatchedBridgeInboundPricingFallbackServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String COUNTERPARTY = "0x2222222222222222222222222222222222222222";

    @Mock
    private MongoOperations mongoOperations;

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private PricingProperties pricingProperties;

    private UnmatchedBridgeInboundPricingFallbackService service;

    private List<NormalizedTransaction> stubbedInbounds = new ArrayList<>();
    private List<NormalizedTransaction> stubbedOutbounds = new ArrayList<>();
    private List<NormalizedTransaction> stubbedUpstream = new ArrayList<>();
    private List<NormalizedTransaction> stubbedDestinations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pricingProperties = new PricingProperties();
        service = new UnmatchedBridgeInboundPricingFallbackService(
                mongoOperations,
                normalizedTransactionRepository,
                pricingProperties
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        wireFindStub();
    }

    @Test
    @DisplayName("orphan bridge inbound without matching outbound is flipped to pending-price BUY")
    void orphanBridgeInboundIsResetForMarketPricing() {
        NormalizedTransaction orphan = bridgeIn("bridge:lifi:0xabc", "WETH", "0.5");
        stubbedInbounds = List.of(orphan);
        stubbedOutbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(orphan.getConfirmedAt()).isNull();
        assertThat(orphan.getContinuityCandidate()).isFalse();
        assertThat(orphan.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));
        assertThat(orphan.getCorrelationId()).isEqualTo("bridge:lifi:0xabc");

        ArgumentCaptor<List<NormalizedTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).containsExactly(orphan);
    }

    @Test
    @DisplayName("BR-2: non-peg orphan inbound market-priced as irreducible is flagged NON_PEG_BASIS_UNVERIFIED")
    void nonPegOrphanInboundIsFlaggedNotSilentlyAccepted() {
        NormalizedTransaction orphan = bridgeIn("bridge:lifi:0xeth", "WETH", "0.5");
        stubbedInbounds = List.of(orphan);
        stubbedOutbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(orphan.getMissingDataReasons())
                .contains(PegNeutralBridgeAssumptionSupport.NON_PEG_BASIS_UNVERIFIED_REASON);
    }

    @Test
    @DisplayName("BR-2: peg-neutral (USDC) orphan inbound is accepted without a non-peg flag")
    void pegNeutralOrphanInboundIsAcceptedWithoutFlag() {
        NormalizedTransaction orphan = bridgeIn("bridge:lifi:0xusdc", "USDC", "1000");
        stubbedInbounds = List.of(orphan);
        stubbedOutbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(orphan.getMissingDataReasons())
                .doesNotContain(PegNeutralBridgeAssumptionSupport.NON_PEG_BASIS_UNVERIFIED_REASON);
    }

    @Test
    @DisplayName("bridge inbound with matching outbound in the system is left as-is")
    void pairedBridgeInboundIsNotReset() {
        NormalizedTransaction paired = bridgeIn("bridge:lifi:0xpair", "USDC", "1000");
        NormalizedTransaction matchingOutbound = bridgeOutSummary("bridge:lifi:0xpair");
        stubbedInbounds = List.of(paired);
        stubbedOutbounds = List.of(matchingOutbound);

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isZero();
        assertThat(paired.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(paired.getContinuityCandidate()).isTrue();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("no bridge inbounds in the queue results in a no-op pass")
    void noOpWhenNoInbounds() {
        stubbedInbounds = List.of();

        int processed = service.reconcileOrphanInbounds();

        assertThat(processed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("bridge out without priced upstream is flipped to pending-price SELL")
    void bridgeOutWithoutPricedUpstreamIsRepriced() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xout",
                "USDC",
                "-1266.468083",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        stubbedOutbounds = List.of(outbound);
        stubbedUpstream = List.of();

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(outbound.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(outbound.getConfirmedAt()).isNull();
        assertThat(outbound.getContinuityCandidate()).isFalse();
        assertThat(outbound.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL));
        assertThat(outbound.getCorrelationId()).isEqualTo("bridge:lifi:0xout");
        verify(normalizedTransactionRepository).saveAll(List.of(outbound));
    }

    @Test
    @DisplayName("bridge out with priced upstream inflow on same wallet/network is preserved")
    void bridgeOutWithPricedUpstreamIsPreserved() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xout",
                "USDC",
                "-100",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction upstream = vaultWithdrawInflow(
                "USDC",
                "100",
                new BigDecimal("1.00"),
                Instant.parse("2026-04-27T03:15:59Z")
        );
        stubbedOutbounds = List.of(outbound);
        stubbedUpstream = List.of(upstream);

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isZero();
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("bridge out with only unpriced upstream is repriced")
    void bridgeOutWithOnlyUnpricedUpstreamIsRepriced() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xout",
                "USDC",
                "-1266",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction upstream = vaultWithdrawInflow(
                "USDC",
                "1266",
                null,
                Instant.parse("2026-04-27T03:15:59Z")
        );
        stubbedOutbounds = List.of(outbound);
        stubbedUpstream = List.of(upstream);

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isEqualTo(1);
        assertThat(outbound.getContinuityCandidate()).isFalse();
        assertThat(outbound.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.SELL));
    }

    @Test
    @DisplayName("bridge out with family-equivalent priced upstream is preserved")
    void bridgeOutWithFamilyEquivalentUpstreamIsPreserved() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xweth",
                "WETH",
                "-1.5",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction upstream = vaultWithdrawInflow(
                "ETH",
                "2",
                new BigDecimal("3200"),
                Instant.parse("2026-04-27T03:15:59Z")
        );
        stubbedOutbounds = List.of(outbound);
        stubbedUpstream = List.of(upstream);

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("shortfall: paired bridge IN is NOT repriced even without upstream priced inflow — relay carry basis instead")
    void shortfallPairedBridgeInNotRepricedEvenWithoutUpstream() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xshortfall",
                "USDC",
                "-100",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction pairedInbound = bridgeIn("bridge:lifi:0xshortfall", "USDC", "100");
        stubbedOutbounds = List.of(outbound);
        stubbedInbounds = List.of(pairedInbound);
        stubbedUpstream = List.of();

        int processed = service.reconcileUnsupportedOutbounds();

        // Properly linked move-basis pair: inbound is always skipped, replay carries basis.
        // Repricing was removed to prevent oscillation with BRIDGE_IN_SEALED_REPAIR.
        assertThat(processed).isZero();
        assertThat(pairedInbound.getContinuityCandidate()).isTrue();
        // Status unchanged — only BRIDGE_IN_SEALED_REPAIR sets PENDING_STAT; this service no longer touches it.
        assertThat(pairedInbound.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(outbound.getContinuityCandidate()).isTrue();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("shortfall: paired bridge IN is not repriced when source wallet has a priced upstream inflow")
    void shortfallPairedBridgeInNotRepricedWhenSourceHasBasis() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xshortfall2",
                "USDC",
                "-100",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction pairedInbound = bridgeIn("bridge:lifi:0xshortfall2", "USDC", "100");
        NormalizedTransaction upstream = vaultWithdrawInflow(
                "USDC",
                "200",
                new BigDecimal("1.00"),
                Instant.parse("2026-04-27T03:00:00Z")
        );
        stubbedOutbounds = List.of(outbound);
        stubbedInbounds = List.of(pairedInbound);
        stubbedUpstream = List.of(upstream);

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isZero();
        assertThat(pairedInbound.getContinuityCandidate()).isTrue();
        assertThat(pairedInbound.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(pairedInbound.getFlows()).allSatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("supplemental LI.FI source linked on shared destination is not repriced")
    void supplementalBridgeOutLinkedOnSharedDestinationIsNotRepriced() {
        String supplementalHash = "0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b";
        String destinationHash = "0x826189720417ce31b983c2c7bb79f04ba4e330df80a0c016dab2bbee2fd61269";
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:" + supplementalHash,
                "WETH",
                "-0.5",
                Instant.parse("2026-02-06T12:00:00Z")
        );
        outbound.setTxHash(supplementalHash);
        outbound.setMatchedCounterparty(destinationHash);

        NormalizedTransaction destination = bridgeIn("bridge:lifi:0xprincipal", "ETH", "100");
        destination.setTxHash(destinationHash);
        NormalizedTransaction.Flow linkedEthIn = new NormalizedTransaction.Flow();
        linkedEthIn.setAssetSymbol("ETH");
        linkedEthIn.setQuantityDelta(new BigDecimal("0.5"));
        linkedEthIn.setRole(NormalizedLegRole.TRANSFER);
        linkedEthIn.setCounterpartyAddress(BridgePairLinkSupport.supplementalLinkedCounterparty(supplementalHash));
        destination.setFlows(new ArrayList<>(List.of(linkedEthIn)));

        stubbedOutbounds = List.of(outbound);
        stubbedDestinations = List.of(destination);
        stubbedUpstream = List.of();

        int processed = service.reconcileUnsupportedOutbounds();

        assertThat(processed).isZero();
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("reprice preserves correlationId and fee leg on bridge out")
    void repricePreservesCorrelationIdAndFee() {
        NormalizedTransaction outbound = bridgeOut(
                "bridge:lifi:0xout",
                "USDC",
                "-100",
                Instant.parse("2026-04-27T05:48:47Z")
        );
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(new BigDecimal("-0.001"));
        outbound.getFlows().add(fee);
        stubbedOutbounds = List.of(outbound);
        stubbedUpstream = List.of();

        service.reconcileUnsupportedOutbounds();

        assertThat(outbound.getCorrelationId()).isEqualTo("bridge:lifi:0xout");
        assertThat(outbound.getFlows()).anySatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.FEE));
    }

    private void wireFindStub() {
        lenient().when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    String queryString = query.getQueryObject().toString();
                    if (queryString.contains("BRIDGE_IN")) {
                        return new ArrayList<>(stubbedInbounds);
                    }
                    if (queryString.contains("BRIDGE_OUT") && queryString.contains("continuityCandidate")) {
                        return new ArrayList<>(stubbedOutbounds);
                    }
                    if (queryString.contains("BRIDGE_OUT") && queryString.contains("correlationId")) {
                        return new ArrayList<>(stubbedOutbounds);
                    }
                    if (queryString.contains("walletAddress")) {
                        return new ArrayList<>(stubbedUpstream);
                    }
                    if (queryString.contains("txHash")) {
                        return new ArrayList<>(stubbedDestinations);
                    }
                    return new ArrayList<>();
                });
    }

    private NormalizedTransaction bridgeIn(String correlationId, String asset, String quantity) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(correlationId + ":in");
        tx.setWalletAddress(WALLET);
        tx.setMatchedCounterparty(COUNTERPARTY);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.BRIDGE_IN);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setConfirmedAt(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setBlockTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        tx.setTxHash("0x" + correlationId.hashCode());

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction bridgeOut(
            String correlationId,
            String asset,
            String quantity,
            Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(correlationId + ":out");
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setConfirmedAt(blockTimestamp);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setTxHash("0x" + correlationId.hashCode());

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction bridgeOutSummary(String correlationId) {
        NormalizedTransaction out = new NormalizedTransaction();
        out.setId(correlationId + ":out");
        out.setCorrelationId(correlationId);
        out.setType(NormalizedTransactionType.BRIDGE_OUT);
        return out;
    }

    private NormalizedTransaction vaultWithdrawInflow(
            String asset,
            String quantity,
            BigDecimal unitPriceUsd,
            Instant blockTimestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("vault:" + blockTimestamp);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setType(NormalizedTransactionType.VAULT_WITHDRAW);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(blockTimestamp);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setUnitPriceUsd(unitPriceUsd);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
