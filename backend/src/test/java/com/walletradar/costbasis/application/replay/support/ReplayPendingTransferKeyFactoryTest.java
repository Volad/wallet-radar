package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.ContinuityKey;
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
    @DisplayName("Uncorrelated same-UID Bybit INTERNAL_TRANSFER uses bybit-earn-carry key")
    void bybitUncorrelatedSameUidUsesEarnCarryKey() {
        NormalizedTransaction outbound = bybitInternalTransfer(
                "BYBIT:33625378:FUND", "BYBIT:33625378:EARN", null, "LDO", "-68.665"
        );
        NormalizedTransaction inbound = bybitInternalTransfer(
                "BYBIT:33625378:EARN", "BYBIT:33625378:FUND", null, "LDO", "68.665"
        );

        var outKey = factory.transferKey(outbound, outbound.getFlows().get(0));
        var inKey = factory.transferKey(inbound, inbound.getFlows().get(0));

        assertThat(outKey).isNotNull();
        assertThat(outKey.value()).startsWith("bybit-earn-carry:33625378:");
        assertThat(inKey).isNotNull();
        assertThat(outKey).isEqualTo(inKey);
    }

    @Test
    @DisplayName("Same-UID Bybit INTERNAL_TRANSFER with null counterparty still uses earn-carry key")
    void bybitNullCounterpartySameUidFallback() {
        NormalizedTransaction tx = bybitInternalTransfer(
                "BYBIT:33625378:FUND", null, null, "DOGE", "150"
        );

        var key = factory.transferKey(tx, tx.getFlows().get(0));

        assertThat(key).isNotNull();
        assertThat(key.value()).startsWith("bybit-earn-carry:33625378:");
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
