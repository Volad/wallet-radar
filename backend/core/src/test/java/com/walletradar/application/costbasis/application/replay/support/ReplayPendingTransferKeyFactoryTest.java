package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayPendingTransferKeyFactoryTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String LP_TOKEN_CONTRACT = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String GHO_CONTRACT = "0x40d16fc0246ad3160ccc09b8d0d3a2cd28ae6c2f";

    private final ReplayAssetSupport assetSupport = new ReplayAssetSupport();
    private final ReplayPendingTransferKeyFactory factory = new ReplayPendingTransferKeyFactory(assetSupport);

    @Test
    @DisplayName("supplemental LINKED inbound on BRIDGE_IN shares bridge queue with supplemental LI.FI source")
    void supplementalLinkedInboundUsesSupplementalBridgeQueue() {
        String supplementalSource = "0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b";
        NormalizedTransaction destination = baseTransaction(NormalizedTransactionType.BRIDGE_IN);
        destination.setCorrelationId("bridge:lifi:0x8b471042fca30390a7d9b4a41463c01c2059b37df2d064cecc588a564e2ee032");
        destination.setContinuityCandidate(false);
        NormalizedTransaction.Flow ethInbound = transferFlow("ETH", null, "0.013689");
        ethInbound.setCounterpartyAddress("LINKED:" + supplementalSource);
        destination.setFlows(new ArrayList<>(List.of(ethInbound)));

        NormalizedTransaction supplementalOut = baseTransaction(NormalizedTransactionType.BRIDGE_OUT);
        supplementalOut.setCorrelationId("bridge:lifi:" + supplementalSource);
        supplementalOut.setContinuityCandidate(true);
        NormalizedTransaction.Flow wethOut = transferFlow("WETH", "0xdeadbeef", "-0.01371");
        supplementalOut.setFlows(List.of(wethOut));

        var inboundKey = factory.bridgeTransferKey(destination, ethInbound);
        var outboundKey = factory.bridgeTransferKey(supplementalOut, wethOut);

        assertThat(inboundKey).isNotNull();
        assertThat(outboundKey).isNotNull();
        assertThat(inboundKey).isEqualTo(outboundKey);
        assertThat(inboundKey.value()).contains("bridge:lifi:" + supplementalSource.toLowerCase());
    }

    @Test
    @DisplayName("LP_ENTRY: all source legs and the receipt share the lp:<lpReceiptIdentity> continuity bucket")
    void lpEntryRoutesAllLegsToCompositeBucket() {
        NormalizedTransaction lpEntry = lpEntryTransaction();

        List<ContinuityKey> keys = lpEntry.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(lpEntry, flow))
                .toList();

        assertThat(keys).allSatisfy(key -> {
            assertThat(key.walletAddress()).isEqualTo(WALLET);
            assertThat(key.networkId()).isEqualTo(NetworkId.ETHEREUM);
            assertThat(key.continuityIdentity()).isEqualTo("lp:" + LP_TOKEN_CONTRACT);
        });
        assertThat(keys).hasSize(4);
        assertThat(keys.stream().distinct().count())
                .as("multi-leg LP_ENTRY must collapse into a single composite bucket")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LP_EXIT with positive LP mint (misclassified entry) uses lp: receipt bucket")
    void misclassifiedLpExitWithPositiveReceiptUsesEntryCompositeBucket() {
        NormalizedTransaction tx = baseTransaction(NormalizedTransactionType.LP_EXIT);
        tx.setFlows(new ArrayList<>(List.of(
                transferFlow("GHO", GHO_CONTRACT, "-1000"),
                transferFlow("USDC", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", "-500"),
                transferFlow("AAVE-GHO/USDT/USDC", LP_TOKEN_CONTRACT, "3000")
        )));

        List<ContinuityKey> keys = tx.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(tx, flow))
                .toList();

        assertThat(keys).allSatisfy(key ->
                assertThat(key.continuityIdentity()).isEqualTo("lp:" + LP_TOKEN_CONTRACT));
    }

    @Test
    @DisplayName("LP_EXIT: burned LP receipt and restored underlyings share the same lp:<id> bucket")
    void lpExitRoutesAllLegsToCompositeBucket() {
        NormalizedTransaction lpExit = lpExitTransaction();

        List<ContinuityKey> keys = lpExit.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(lpExit, flow))
                .toList();

        assertThat(keys).allSatisfy(key -> {
            assertThat(key.continuityIdentity()).isEqualTo("lp:" + LP_TOKEN_CONTRACT);
        });
        assertThat(keys.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Multi-asset LENDING_DEPOSIT with a non-family receipt collapses into composite LP bucket")
    void multiAssetLendingDepositIsTreatedAsCompositeLp() {
        NormalizedTransaction multiAssetDeposit = baseTransaction(NormalizedTransactionType.LENDING_DEPOSIT);
        multiAssetDeposit.setFlows(new ArrayList<>(List.of(
                transferFlow("GHO", GHO_CONTRACT, "-1000"),
                transferFlow("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", "-1000"),
                transferFlow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "-1000"),
                transferFlow("AAVE GHO/USDT/USDC", LP_TOKEN_CONTRACT, "3000")
        )));

        List<ContinuityKey> keys = multiAssetDeposit.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(multiAssetDeposit, flow))
                .toList();

        assertThat(keys).allSatisfy(key ->
                assertThat(key.continuityIdentity()).isEqualTo("lp:" + LP_TOKEN_CONTRACT));
        assertThat(keys.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Two-leg LENDING_DEPOSIT LP→gauge uses wrapper:<gaugeContract> bucket")
    void lendingDepositLpToGaugeUsesWrapperBucket() {
        String gaugeContract = "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951";
        NormalizedTransaction stake = baseTransaction(NormalizedTransactionType.LENDING_DEPOSIT);
        stake.setNetworkId(NetworkId.AVALANCHE);
        stake.setFlows(new ArrayList<>(List.of(
                transferFlow("AAVE GHO/USDT/USDC", LP_TOKEN_CONTRACT, "-1000"),
                transferFlow("Aave GHO/USDT/USDC-gauge", gaugeContract, "1000")
        )));

        List<ContinuityKey> keys = stake.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(stake, flow))
                .toList();

        assertThat(keys).allSatisfy(key ->
                assertThat(key.continuityIdentity()).isEqualTo("wrapper:" + gaugeContract));
        assertThat(keys.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("LENDING_DEPOSIT LP→gauge: outbound and inbound share wrapper bucket even when LP maps to FAMILY")
    void lendingDepositWrapperBucketKeysMatchForOutboundAndInbound() {
        String gaugeContract = "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951";
        NormalizedTransaction stake = baseTransaction(NormalizedTransactionType.LENDING_DEPOSIT);
        stake.setNetworkId(NetworkId.AVALANCHE);
        NormalizedTransaction.Flow outbound = transferFlow("AAVE GHO/USDT/USDC", LP_TOKEN_CONTRACT, "-3000");
        NormalizedTransaction.Flow inbound = transferFlow("Aave GHO/USDT/USDC-gauge", gaugeContract, "3000");
        stake.setFlows(new ArrayList<>(List.of(outbound, inbound)));

        ContinuityKey outKey = factory.continuityKey(stake, outbound);
        ContinuityKey inKey = factory.continuityKey(stake, inbound);

        assertThat(outKey.continuityIdentity()).isEqualTo("wrapper:" + gaugeContract);
        assertThat(inKey).isEqualTo(outKey);
    }

    @Test
    @DisplayName("LP_EXIT gauge→LP round-trip prefers wrapper bucket when both lp and wrapper identities exist")
    void lpExitGaugeUnstakePrefersWrapperBucketWhenBothCompositeKeysExist() {
        String gaugeContract = "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951";
        NormalizedTransaction unstake = baseTransaction(NormalizedTransactionType.LP_EXIT);
        unstake.setNetworkId(NetworkId.AVALANCHE);
        NormalizedTransaction.Flow gaugeOut = transferFlow("Aave GHO/USDT/USDC-gauge", gaugeContract, "-1000");
        NormalizedTransaction.Flow lpIn = transferFlow("AAVE GHO/USDT/USDC", LP_TOKEN_CONTRACT, "1000");
        unstake.setFlows(new ArrayList<>(List.of(gaugeOut, lpIn)));

        ContinuityKey gaugeKey = factory.continuityKey(unstake, gaugeOut);
        ContinuityKey lpKey = factory.continuityKey(unstake, lpIn);

        assertThat(gaugeKey.continuityIdentity()).isEqualTo("wrapper:" + gaugeContract);
        assertThat(lpKey).isEqualTo(gaugeKey);
    }

    @Test
    @DisplayName("LP_POSITION_STAKE uses wrapper bucket keyed by inbound gauge token")
    void lpPositionStakeUsesWrapperBucket() {
        String gaugeContract = "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951";
        NormalizedTransaction stake = baseTransaction(NormalizedTransactionType.LP_POSITION_STAKE);
        stake.setNetworkId(NetworkId.AVALANCHE);
        stake.setFlows(new ArrayList<>(List.of(
                transferFlow("AAVE GHO/USDT/USDC", LP_TOKEN_CONTRACT, "-500"),
                transferFlow("Aave GHO/USDT/USDC-gauge", gaugeContract, "500")
        )));

        ContinuityKey inbound = factory.continuityKey(stake, stake.getFlows().get(1));
        assertThat(inbound.continuityIdentity()).isEqualTo("wrapper:" + gaugeContract);
    }

    @Test
    @DisplayName("Correlated same-UID Bybit bundle with continuityCandidate=true uses corr-family key")
    void bybitCorrelatedBundleUsesCorrFamilyKey() {
        NormalizedTransaction outbound = bybitInternalTransfer(
                "BYBIT:33625378:UTA", "BYBIT:33625378:EARN",
                "bybit-it-bundle-v1:BYBIT-33625378:EARN_FLEXIBLE_SAVINGS:LDO",
                "LDO", "-68.665"
        );

        var outKey = factory.transferKey(outbound, outbound.getFlows().get(0));

        assertThat(outKey).isNotNull();
        assertThat(outKey.value()).startsWith("corr-family:");
        assertThat(outKey.value()).doesNotContain("bybit-earn-carry");
    }

    @Test
    @DisplayName("Uncorrelated same-UID Bybit INTERNAL_TRANSFER with continuity candidate uses bybit-earn-carry key")
    void bybitUncorrelatedSameUidUsesEarnCarryKey() {
        NormalizedTransaction outbound = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN", null, "LDO", "-68.665"
        );
        outbound.setContinuityCandidate(true);
        NormalizedTransaction inbound = bybitInternalTransfer(
                "BYBIT:33625378:EARN", "BYBIT:33625378:FUND", null, "LDO", "68.665"
        );
        inbound.setContinuityCandidate(true);

        var outKey = factory.transferKey(outbound, outbound.getFlows().get(0));
        var inKey = factory.transferKey(inbound, inbound.getFlows().get(0));

        assertThat(outKey).isNotNull();
        assertThat(outKey.value()).startsWith("bybit-earn-carry:33625378:");
        assertThat(inKey).isNotNull();
        assertThat(outKey).isEqualTo(inKey);
    }

    @Test
    @DisplayName("Same-UID Bybit INTERNAL_TRANSFER with null counterparty does not use earn-carry key (RC-B)")
    void bybitNullCounterpartySameUidFallback() {
        NormalizedTransaction tx = bybitInternalTransfer(
                "BYBIT:33625378:FUND", null, null, "DOGE", "150"
        );

        assertThat(factory.transferKey(tx, tx.getFlows().get(0))).isNull();
    }

    @Test
    @DisplayName("Corridor Bybit transfer uses corr-family key, not earn-carry")
    void bybitCorridorTransferUsesCorrelationKey() {
        NormalizedTransaction tx = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "wallet-a",
                "BYBIT-CORRIDOR:ARBITRUM:0xabc",
                "ETH", "1.0"
        );
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setContinuityCandidate(true);

        var key = factory.transferKey(tx, tx.getFlows().get(0));

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:");
    }

    @Test
    @DisplayName("Paired Bybit earn principal uses corr-family key, not earn-carry FIFO")
    void bybitEarnPrincipalPairUsesCorrFamilyKey() {
        NormalizedTransaction earnOut = bybitInternalTransfer(
                "BYBIT:33625378:EARN", null, null, "ETH", "-0.15114744"
        );
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:abc123");

        var key = factory.transferKey(earnOut, earnOut.getFlows().get(0));

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:bybit-earn-principal-v1:");
        assertThat(factory.usesBybitVenueInternalCarryQueue(earnOut)).isFalse();
    }

    @Test
    @DisplayName("bybit-collapsed-v1: UTA/FUND pair uses corr-family key (via continuityCandidate path)")
    void bybitCollapsedUtaFundPairUsesCorrFamilyKey() {
        // UTA→FUND collapsed pairs have continuityCandidate=true; the generic second block in
        // transferKey() routes them to corr-family: before isBybitSameUidInternalTransfer is reached.
        NormalizedTransaction utaOut = bybitInternalTransfer(
                "BYBIT:33625378:UTA", "BYBIT:33625378:FUND",
                "bybit-collapsed-v1:cafe0011cafe0022",
                "USDC", "-901.50"
        );
        utaOut.setContinuityCandidate(true);
        NormalizedTransaction fundIn = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:33625378:UTA",
                "bybit-collapsed-v1:cafe0011cafe0022",
                "USDC", "901.50"
        );
        fundIn.setContinuityCandidate(true);

        var utaKey = factory.transferKey(utaOut, utaOut.getFlows().get(0));
        var fundKey = factory.transferKey(fundIn, fundIn.getFlows().get(0));

        assertThat(utaKey).isNotNull();
        assertThat(fundKey).isNotNull();
        assertThat(utaKey.value()).startsWith("corr-family:bybit-collapsed-v1:");
        assertThat(fundKey.value()).startsWith("corr-family:bybit-collapsed-v1:");
        assertThat(utaKey).isEqualTo(fundKey);
    }

    @Test
    @DisplayName("bybit-collapsed-v1: FUND/EARN pair uses corr-family key (both legs must share same queue)")
    void bybitCollapsedFundEarnPairUsesCorrFamilyKey() {
        // FUND→EARN collapsed pairs have continuityCandidate=true and isBybitEarnInternalTransfer
        // now excludes bybit-collapsed-v1: corrIds. Both the FUND debit and the EARN credit
        // route to corr-family: via the continuityCandidate path so they share the same queue.
        NormalizedTransaction fundOut = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN",
                "bybit-collapsed-v1:ondo-test-collapsed",
                "ONDO", "-100"
        );
        fundOut.setContinuityCandidate(true);
        NormalizedTransaction earnIn = bybitInternalTransfer(
                "BYBIT:33625378:EARN", "BYBIT:33625378:FUND",
                "bybit-collapsed-v1:ondo-test-collapsed",
                "ONDO", "100"
        );
        earnIn.setContinuityCandidate(true);

        var fundKey = factory.transferKey(fundOut, fundOut.getFlows().get(0));
        var earnKey = factory.transferKey(earnIn, earnIn.getFlows().get(0));

        assertThat(fundKey).isNotNull();
        assertThat(fundKey.value()).startsWith("corr-family:bybit-collapsed-v1:");
        assertThat(fundKey.value()).doesNotContain("bybit-earn-carry");
        assertThat(earnKey).isNotNull();
        assertThat(earnKey.value()).startsWith("corr-family:bybit-collapsed-v1:");
        assertThat(fundKey).isEqualTo(earnKey);
        assertThat(factory.usesBybitVenueInternalCarryQueue(fundOut)).isFalse();
        assertThat(factory.usesBybitVenueInternalCarryQueue(earnIn)).isFalse();
    }

    @Test
    @DisplayName("bybit-collapsed-v1: corrId returns false from isBybitEarnInternalTransfer even for EARN wallet")
    void bybitCollapsedV1CorrectlyExcludedFromEarnInternalTransferCheck() {
        // Regression guard: bybit-collapsed-v1: must be excluded from the EARN FIFO queue
        // so that FUND→EARN collapsed pairs route to corr-family: (matching their UTA debit).
        NormalizedTransaction earnCredit = bybitInternalTransfer(
                "BYBIT:33625378:EARN", "BYBIT:33625378:FUND",
                "bybit-collapsed-v1:abc",
                "USDT", "153.63"
        );
        earnCredit.setContinuityCandidate(true);

        assertThat(factory.usesBybitVenueInternalCarryQueue(earnCredit)).isFalse();
        var key = factory.transferKey(earnCredit, earnCredit.getFlows().get(0));
        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:bybit-collapsed-v1:abc:");
    }

    @Test
    @DisplayName("bybit-collapsed-v1: UTA/FUND pair — usesBybitVenueInternalCarryQueue returns false even when counterparty is same Bybit UID")
    void bybitCollapsedV1UtaFundDoesNotUseVenueInternalCarryQueue() {
        // After WS-3, FUND↔UTA collapsed pairs get real counterparties (not MULTI).
        // isBybitSameUidInternalTransfer must still return false for bybit-collapsed-v1: so that
        // permitUncoveredFallback=true and FUND inbounds enqueue even without a market price.
        NormalizedTransaction fundIn = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:33625378:UTA",
                "bybit-collapsed-v1:cafe0011cafe0022",
                "USDC", "901.50"
        );
        fundIn.setContinuityCandidate(true);

        assertThat(factory.usesBybitVenueInternalCarryQueue(fundIn)).isFalse();

        NormalizedTransaction utaOut = bybitInternalTransfer(
                "BYBIT:33625378:UTA", "BYBIT:33625378:FUND",
                "bybit-collapsed-v1:cafe0011cafe0022",
                "USDC", "-901.50"
        );
        utaOut.setContinuityCandidate(true);

        assertThat(factory.usesBybitVenueInternalCarryQueue(utaOut)).isFalse();
    }

    @Test
    @DisplayName("Bybit LENDING_WITHDRAW/LENDING_DEPOSIT share earn-carry FIFO key")
    void bybitEarnProductTransferUsesEarnCarryKey() {
        NormalizedTransaction earnOut = bybitInternalTransfer(
                "BYBIT:33625378:EARN", null, null, "ETH", "-0.15114744"
        );
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
        NormalizedTransaction fundIn = bybitInternalTransfer(
                "BYBIT:33625378:FUND", null, null, "ETH", "0.15114744"
        );
        fundIn.setType(NormalizedTransactionType.LENDING_WITHDRAW);

        var earnKey = factory.transferKey(earnOut, earnOut.getFlows().get(0));
        var fundKey = factory.transferKey(fundIn, fundIn.getFlows().get(0));

        assertThat(earnKey).isNotNull();
        assertThat(fundKey).isNotNull();
        assertThat(earnKey.value()).startsWith("bybit-earn-carry:33625378:");
        assertThat(earnKey).isEqualTo(fundKey);
        assertThat(factory.usesBybitVenueInternalCarryQueue(earnOut)).isTrue();
    }

    @Test
    @DisplayName("Cross-UID Bybit INTERNAL_TRANSFER uses correlation-based key, not earn-carry")
    void bybitCrossUidTransferUsesCorrelationKey() {
        NormalizedTransaction tx = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:409666:FUND",
                "bybit-it-pair-v1:xyz",
                "USDT", "-100"
        );
        tx.setContinuityCandidate(true);

        var key = factory.transferKey(tx, tx.getFlows().get(0));

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("corr-family:");
        assertThat(key.value()).doesNotContain("bybit-earn-carry");
    }

    private NormalizedTransaction bybitInternalTransfer(
            String wallet, String counterparty, String correlationId,
            String asset, String qty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(wallet);
        tx.setMatchedCounterparty(counterparty);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(correlationId != null);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(List.of(flow));
        return tx;
    }

    @Test
    @DisplayName("earnCarryFifoKey returns bybit-earn-carry scoped by UID and asset for bundle inbound")
    void earnCarryFifoKeyReturnsEarnCarryScopedByUidAndAsset() {
        NormalizedTransaction bundleIn = bybitInternalTransfer(
                "BYBIT:33625378:EARN", null,
                "bybit-it-bundle-v1:ONDO-BUNDLE-1",
                "ONDO", "100"
        );
        bundleIn.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = bundleIn.getFlows().get(0);

        var key = factory.earnCarryFifoKey(bundleIn, flow);

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("bybit-earn-carry:33625378:");
    }

    @Test
    @DisplayName("Non-LP transactions are NOT collapsed into the lp:<id> composite bucket")
    void nonLpTransactionsPreserveExistingBucketing() {
        NormalizedTransaction lendingDeposit = lendingDepositTransaction();

        List<ContinuityKey> keys = lendingDeposit.getFlows().stream()
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .map(flow -> factory.continuityKey(lendingDeposit, flow))
                .toList();

        assertThat(keys).noneSatisfy(key -> assertThat(key.continuityIdentity()).startsWith("lp:"));
        // Family-equivalent custody pairs (USDC <-> AETHUSDC) share FAMILY:USDC, which is the
        // intended behaviour for non-LP buckets — they remain per-family rather than per-LP.
        assertThat(keys).allSatisfy(key -> assertThat(key.continuityIdentity()).startsWith("FAMILY:"));
    }

    private NormalizedTransaction lpEntryTransaction() {
        NormalizedTransaction tx = baseTransaction(NormalizedTransactionType.LP_ENTRY);
        tx.setFlows(new ArrayList<>(List.of(
                transferFlow("GHO", GHO_CONTRACT, "-1000"),
                transferFlow("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", "-1000"),
                transferFlow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "-1000"),
                transferFlow("AAVE-GHO/USDT/USDC", LP_TOKEN_CONTRACT, "3000")
        )));
        return tx;
    }

    private NormalizedTransaction lpExitTransaction() {
        NormalizedTransaction tx = baseTransaction(NormalizedTransactionType.LP_EXIT);
        tx.setFlows(new ArrayList<>(List.of(
                transferFlow("AAVE-GHO/USDT/USDC", LP_TOKEN_CONTRACT, "-3000"),
                transferFlow("GHO", GHO_CONTRACT, "1000"),
                transferFlow("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", "1000"),
                transferFlow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "1000")
        )));
        return tx;
    }

    private NormalizedTransaction lendingDepositTransaction() {
        NormalizedTransaction tx = baseTransaction(NormalizedTransactionType.LENDING_DEPOSIT);
        tx.setFlows(new ArrayList<>(List.of(
                transferFlow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "-1000"),
                transferFlow("AETHUSDC", "0x98c23e9d8f34fefb1b7bd6a91b7ff122f4e16f5c", "1000")
        )));
        return tx;
    }

    @Test
    @DisplayName("cross-asset BRIDGE_IN (cc=false) with USD stablecoin + ETH gas refund: hasSinglePrincipal=true → settlement key produced")
    void crossAssetBridgeInWithEthGasRefundProducesSettlementKey() {
        NormalizedTransaction bridgeIn = baseTransaction(NormalizedTransactionType.BRIDGE_IN);
        bridgeIn.setCorrelationId("bridge:lifi:0x8b471042fca");
        bridgeIn.setMatchedCounterparty("0x8b471042fca");
        bridgeIn.setContinuityCandidate(false);
        NormalizedTransaction.Flow usdtFlow = transferFlow("USD\u20ae0", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "862.83");
        NormalizedTransaction.Flow ethFlow = transferFlow("ETH", null, "0.013689");
        bridgeIn.setFlows(new java.util.ArrayList<>(List.of(usdtFlow, ethFlow)));

        // settlement key must be non-null: ETH is treated as gas refund, USD₮0 is the single principal
        var key = factory.bridgeSettlementKey(bridgeIn, usdtFlow);
        assertThat(key).isNotNull();
        assertThat(key.value()).isEqualTo("bridge-settlement:bridge:lifi:0x8b471042fca");
    }

    @Test
    @DisplayName("NEW-08: shaped USDC BRIDGE_OUT and ETH BRIDGE_IN both emit the same bridge-settlement key")
    void crossAssetUsdcOutEthInEmitMatchingSettlementKeys() {
        String correlationId = "bridge:lifi:0xda7d556e558de7";

        NormalizedTransaction source = baseTransaction(NormalizedTransactionType.BRIDGE_OUT);
        source.setNetworkId(NetworkId.UNICHAIN);
        source.setCorrelationId(correlationId);
        source.setMatchedCounterparty("0xc0aaf96b5712c");
        source.setContinuityCandidate(false);
        NormalizedTransaction.Flow usdcOut = transferFlow("USDC", "0x078d782b760474a361dda0af3839290b0ef57ad6", "-2050.040045");
        source.setFlows(new java.util.ArrayList<>(List.of(usdcOut)));

        NormalizedTransaction destination = baseTransaction(NormalizedTransactionType.BRIDGE_IN);
        destination.setNetworkId(NetworkId.KATANA);
        destination.setCorrelationId(correlationId);
        destination.setMatchedCounterparty("0xda7d556e558de7");
        destination.setContinuityCandidate(false);
        NormalizedTransaction.Flow ethIn = transferFlow("ETH", null, "0.452894");
        destination.setFlows(new java.util.ArrayList<>(List.of(ethIn)));

        var outKey = factory.bridgeSettlementKey(source, usdcOut);
        var inKey = factory.bridgeSettlementKey(destination, ethIn);

        assertThat(outKey).isNotNull();
        assertThat(inKey).isNotNull();
        assertThat(outKey.value()).isEqualTo("bridge-settlement:" + correlationId);
        assertThat(inKey.value()).isEqualTo("bridge-settlement:" + correlationId);
        assertThat(outKey).isEqualTo(inKey);
        // Same-asset (cc=true) never emits a settlement key — it uses bridgeTransferKey ("bridge:").
        source.setContinuityCandidate(true);
        assertThat(factory.bridgeSettlementKey(source, usdcOut)).isNull();
    }

    @Test
    @DisplayName("cross-asset BRIDGE_IN (cc=false) with two stablecoin principals: settlement key null (ambiguous)")
    void crossAssetBridgeInWithTwoStablecoinsSettlementKeyIsNull() {
        NormalizedTransaction bridgeIn = baseTransaction(NormalizedTransactionType.BRIDGE_IN);
        bridgeIn.setCorrelationId("bridge:lifi:0xambig");
        bridgeIn.setMatchedCounterparty("0xambig");
        bridgeIn.setContinuityCandidate(false);
        NormalizedTransaction.Flow usdcFlow = transferFlow("USDC", null, "100");
        NormalizedTransaction.Flow usdtFlow = transferFlow("USDT", null, "100");
        bridgeIn.setFlows(new java.util.ArrayList<>(List.of(usdcFlow, usdtFlow)));

        assertThat(factory.bridgeSettlementKey(bridgeIn, usdcFlow)).isNull();
    }

    @Test
    @DisplayName("VAULT_WITHDRAW with TRANSFER principal + BUY yield: wrapper composite identity is the receipt token, not null")
    void wrapperCompositeBucketIdentity_vaultWithdrawWithBuyYieldFlow_shouldReturnReceiptIdentity() {
        // Simulates a cross-scale vault VAULT_WITHDRAW where the receipt token is NOT in any
        // asset family (symbol "vaultUSDC" has no FAMILY mapping). Withdrawal flows:
        //   Flow 0: vaultUSDC OUT (TRANSFER, burn)
        //   Flow 1: USDC IN     (TRANSFER, principal return)
        //   Flow 2: USDC IN     (BUY, yield/interest)
        // The BUY yield flow must NOT abort wrapper-composite detection.
        String vaultReceiptContract = "0x000000000000000000000000000000000000abcd";
        String usdcContract  = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

        NormalizedTransaction withdraw = baseTransaction(NormalizedTransactionType.VAULT_WITHDRAW);
        withdraw.setFlows(new java.util.ArrayList<>(List.of(
                transferFlow("vaultUSDC", vaultReceiptContract, "-926.43"),
                transferFlow("USDC",      usdcContract,          "926.43"),
                buyFlow("USDC",           usdcContract,           "73.05")
        )));

        assertThat(factory.usesWrapperCompositeBucket(withdraw)).isTrue();

        // continuityKey should route to wrapper:<vaultReceiptContract> for both TRANSFER flows
        List<ContinuityKey> keys = withdraw.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER)
                .map(f -> factory.continuityKey(withdraw, f))
                .toList();
        assertThat(keys).hasSize(2);
        assertThat(keys).allSatisfy(key ->
                assertThat(key.continuityIdentity()).isEqualTo("wrapper:" + vaultReceiptContract));
    }

    @Test
    @DisplayName("VAULT_DEPOSIT with only TRANSFER flows: wrapper composite identity is set (deposit direction)")
    void wrapperCompositeBucketIdentity_depositShapeWithTransferOnly_shouldReturnReceiptIdentity() {
        // Standard 2-leg deposit: non-family receipt token, no BUY flows.
        String vaultReceiptContract = "0x000000000000000000000000000000000000abcd";
        String usdcContract  = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

        NormalizedTransaction deposit = baseTransaction(NormalizedTransactionType.VAULT_DEPOSIT);
        deposit.setFlows(new java.util.ArrayList<>(List.of(
                transferFlow("USDC",      usdcContract,          "-998.84"),
                transferFlow("vaultUSDC", vaultReceiptContract,   "926.43")
        )));

        assertThat(factory.usesWrapperCompositeBucket(deposit)).isTrue();
    }

    @Test
    @DisplayName("VAULT_WITHDRAW with BUY outbound (not yield inbound): wrapper composite aborts, returns null")
    void wrapperCompositeBucketIdentity_withdrawShapeWithOutboundBuyFlow_shouldReturnNull() {
        // An outbound (negative qty) BUY flow on a withdrawal is not a yield pattern —
        // abort wrapper composite detection as before.
        String vaultReceiptContract = "0x000000000000000000000000000000000000abcd";
        String usdcContract  = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

        NormalizedTransaction withdraw = baseTransaction(NormalizedTransactionType.VAULT_WITHDRAW);
        NormalizedTransaction.Flow buyOut = buyFlow("USDC", usdcContract, "-50");
        withdraw.setFlows(new java.util.ArrayList<>(List.of(
                transferFlow("vaultUSDC", vaultReceiptContract, "-926.43"),
                transferFlow("USDC",      usdcContract,          "926.43"),
                buyOut
        )));

        assertThat(factory.usesWrapperCompositeBucket(withdraw)).isFalse();
    }

    private NormalizedTransaction.Flow buyFlow(String symbol, String contract, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.BUY);
        return flow;
    }

    private NormalizedTransaction baseTransaction(NormalizedTransactionType type) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setTxHash("0xabc");
        return tx;
    }

    private NormalizedTransaction.Flow transferFlow(String symbol, String contract, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        return flow;
    }
}
