package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingAssetClassificationSupportTest {

    @Test
    void c2TokensHaveOwnContinuityFamilies() {
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("WSTETH", null))
                .isEqualTo("FAMILY:WSTETH");
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("EWETH-1", null))
                .isEqualTo("FAMILY:EWETH");
    }

    @Test
    void cmethIsC1SharingMethFamily() {
        // CMETH is a 1:1 Bybit receipt for METH staking — treated as C1 sharing FAMILY:METH.
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("CMETH", null))
                .isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("CMETH", null))
                .isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "METH", null, "CMETH", null
        )).isTrue();
    }

    @Test
    void methToCmethIsNotCrossCanonical() {
        // METH→CMETH staking deposit should route through LiquidStakingReplayHandler (REALLOCATE), not DISPOSE+ACQUIRE.
        NormalizedTransaction tx = stakingDeposit(
                flow("METH", "-1.0"),
                flow("CMETH", "1.0")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isFalse();
    }

    @Test
    void c1WrappersShareUnderlyingCanonicalIdentity() {
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("ETH", null))
                .isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("WETH", null))
                .isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("AMANWETH", null))
                .isEqualTo("FAMILY:ETH");
    }

    @Test
    void ethToCmethIsCrossCanonicalIdentityPair() {
        NormalizedTransaction tx = stakingDeposit(
                flow("ETH", "-1.0"),
                flow("CMETH", "0.97")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isTrue();
    }

    @Test
    void wethToAwethSharesCanonicalIdentity() {
        NormalizedTransaction tx = lendingDeposit(
                flow("WETH", "-1.0"),
                flow("AMANWETH", "1.0")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isFalse();
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "WETH", null, "AMANWETH", null
        )).isTrue();
    }

    @Test
    void cmethCorridorSameTokenSharesCanonicalIdentity() {
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "CMETH", null, "CMETH", null
        )).isTrue();
    }

    // ------------------------------------------------------------------
    // ADR-083 cluster resolution + isIntraClusterConversion predicate
    // ------------------------------------------------------------------

    @Test
    void clusterForFamilyIdentityMapsStakingFamilies() {
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:ETH"))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:METH"))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:WSTETH"))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:SOL"))
                .isEqualTo("CLUSTER:SOL_STAKING");
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:AVAX"))
                .isEqualTo("CLUSTER:AVAX_STAKING");
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:SAVAX"))
                .isEqualTo("CLUSTER:AVAX_STAKING");
    }

    @Test
    void clusterForNonStakingFamilyIsNull() {
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:USDT")).isNull();
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity("FAMILY:BTC")).isNull();
        assertThat(AccountingAssetClassificationSupport.clusterForFamilyIdentity(null)).isNull();
    }

    @Test
    void stakingClusterForFlowResolvesContractFirstAndSupplementalMembers() {
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("ETH", null))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("CMETH", null))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("SAVAX", null))
                .isEqualTo("CLUSTER:AVAX_STAKING");
        // Supplemental symbol members (family resolves to a raw mint/contract, outside C1/C2).
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("mSOL", null))
                .isEqualTo("CLUSTER:SOL_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("jitoSOL", null))
                .isEqualTo("CLUSTER:SOL_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("PT-cmETH", null))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("AAVASAVAX", null))
                .isEqualTo("CLUSTER:AVAX_STAKING");
        // Non-cluster / unmapped → null (fail-safe realize).
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("USDT", null)).isNull();
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("BTC", null)).isNull();
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("GM", "0x70d95587d40a2caf56bd97485ab3eec10bee6336"))
                .isNull();
    }

    @Test
    void normalizationClusterForSymbolStaysInSyncWithFamilyTable() {
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol("ETH"))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol("SAVAX"))
                .isEqualTo("CLUSTER:AVAX_STAKING");
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol("BBSOL"))
                .isEqualTo("CLUSTER:SOL_STAKING");
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol("WSTETH"))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol("USDT")).isNull();
    }

    @Test
    void intraClusterConversionTrueForCrossFamilySameCluster() {
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-0.709"), flow("METH", "0.66865026")))).isTrue();
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("AVAX", "-1.0"), flow("SAVAX", "0.95")))).isTrue();
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("SOL", "-20"), flow("mSOL", "18.5")))).isTrue();
    }

    @Test
    void intraClusterConversionTrueForDegenerateSameFamily() {
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("METH", "-1.0"), flow("CMETH", "1.0")))).isTrue();
        // C1↔C1 same-family (ETH↔WETH) is also intra-cluster.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("WETH", "1.0")))).isTrue();
    }

    @Test
    void intraClusterConversionTrueForMultiLegPooledSameCluster() {
        // WSTETH + ETH → WEETH (multi-leg pool+allocate), all ETH cluster.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("WSTETH", "-0.5"), flow("ETH", "-0.5"), flow("WEETH", "0.98")))).isTrue();
        // aSAVAX + AVAX → AVAX, all AVAX cluster.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("AAVASAVAX", "-0.5"), flow("AVAX", "-0.5"), flow("AVAX", "1.02")))).isTrue();
    }

    @Test
    void intraClusterConversionTrueIgnoringNonPrincipalFeeLeg() {
        // A non-cluster FEE/gas leg (USDT) is not a principal and does not defeat the carry.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("METH", "0.97"), feeFlow("USDT", "-0.5")))).isTrue();
    }

    @Test
    void intraClusterConversionFalseForCrossCluster() {
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("SOL", "20.0")))).isFalse();
    }

    @Test
    void intraClusterConversionFalseForClusterToNonCluster() {
        // ETH → USDT (exit/sale) and mixed cluster+non-cluster both realize.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("USDT", "3000.0")))).isFalse();
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("METH", "0.5"), flow("USDT", "1500.0")))).isFalse();
    }

    @Test
    void intraClusterConversionFalseForUnknownLstAndGmxGm() {
        // Unknown/unmapped instrument → null cluster → realize (fail-closed).
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-1.0"), flow("UNKNOWNLST", "0.9")))).isFalse();
        // GMX GM ETH/USD → raw-contract identity, no clean cluster → realize.
        NormalizedTransaction gmx = stakingDeposit(
                flow("ETH", "-1.0"),
                flowWithContract("GM", "0x70d95587d40a2caf56bd97485ab3eec10bee6336", "0.5"));
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(gmx)).isFalse();
    }

    @Test
    void intraClusterConversionRequiresBothLanes() {
        // Only an inbound (reward inflow) or only an outbound is not a conversion.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("METH", "0.1")))).isFalse();
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                stakingDeposit(flow("ETH", "-0.1")))).isFalse();
    }

    @Test
    void intraClusterConversionSwapRequiresCrossCanonicalPair() {
        // Cross-canonical intra-cluster SWAP (cmETH → ETH) carries.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                swap(sell("CMETH", "-1.0"), buy("ETH", "1.02")))).isTrue();
        // Same-canonical SWAP (WETH ↔ ETH, both FAMILY:ETH identity) stays on the family-equivalent
        // swap path (its own dust-fragment guard), NOT cluster-carry.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                swap(sell("WETH", "-1.0"), buy("ETH", "1.0")))).isFalse();
    }

    @Test
    void intraClusterConversionSwapCarriesRegistryExternalMembers() {
        // ADR-083 RC-1: a leg whose canonical identity is unknown to the C1/C2 registry (Pendle PT,
        // Solana SPL LST) must NOT short-circuit the SWAP to realize — the contract-first cluster test
        // decides. cmETH → PT-cmETH (maturity-suffixed) is an ETH-cluster conversion → carry.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                swap(sell("CMETH", "-1.0"), buy("PT-CMETH-18SEP2025", "0.99")))).isTrue();
        // SOL → mSOL SWAP (mSOL identity null in C1/C2) is a SOL-cluster conversion → carry.
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                swap(sell("SOL", "-20"), buy("mSOL", "18.5")))).isTrue();
        // A genuinely non-cluster leg still realizes (PT-cmETH → USDT exit).
        assertThat(AccountingAssetClassificationSupport.isIntraClusterConversion(
                swap(sell("PT-CMETH-18SEP2025", "-1.0"), buy("USDT", "4800")))).isFalse();
    }

    @Test
    void stakingClusterForFlowStripsPendleMaturitySuffix() {
        // ADR-083 RC-2: live Pendle symbols carry a maturity suffix the supplemental key omits.
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("PT-CMETH-18SEP2025", null))
                .isEqualTo("CLUSTER:ETH_STAKING");
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("PT-ETH-25DEC2025", null))
                .isEqualTo("CLUSTER:ETH_STAKING");
        // Non-maturity hyphenated non-cluster symbol is unaffected.
        assertThat(AccountingAssetClassificationSupport.stakingClusterForFlow("USD-COIN", null)).isNull();
    }

    private static NormalizedTransaction swap(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction.Flow sell(String symbol, String qty) {
        return flowWithRole(NormalizedLegRole.SELL, symbol, qty);
    }

    private static NormalizedTransaction.Flow buy(String symbol, String qty) {
        return flowWithRole(NormalizedLegRole.BUY, symbol, qty);
    }

    private static NormalizedTransaction.Flow feeFlow(String symbol, String qty) {
        return flowWithRole(NormalizedLegRole.FEE, symbol, qty);
    }

    private static NormalizedTransaction.Flow flowWithRole(NormalizedLegRole role, String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }

    private static NormalizedTransaction.Flow flowWithContract(String symbol, String contract, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }

    private static NormalizedTransaction stakingDeposit(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction lendingDeposit(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
