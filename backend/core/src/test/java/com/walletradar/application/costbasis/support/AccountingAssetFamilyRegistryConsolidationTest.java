package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 / Wave W9 — pins the six accounting-family surfaces after {@code SYMBOL_FAMILIES} was
 * consolidated onto the C1/C2 registry, leaving only the {@code AAVASAVAX} supplemental entry.
 *
 * <p>Each row asserts that resolution is identical to the pre-W9 behavior across every surface a
 * consumer can hit: {@code continuityIdentity} (family), {@code canonicalTokenIdentity} and
 * {@code continuityFamilyIdentity} (carry-vs-realize identity), {@code isC1SameAsset} /
 * {@code isC2DistinctAsset} (classification), and {@code normalizationClusterForSymbol}
 * (liquid-staking fusion). If the registry stops covering a formerly-mapped symbol, these break.</p>
 */
class AccountingAssetFamilyRegistryConsolidationTest {

    private static void assertSurfaces(
            String symbol,
            String expectedContinuityIdentity,
            String expectedCanonicalTokenIdentity,
            String expectedContinuityFamilyIdentity,
            boolean expectedC1,
            boolean expectedC2,
            String expectedCluster
    ) {
        assertThat(AccountingAssetFamilySupport.continuityIdentity(symbol, null))
                .as("continuityIdentity(%s)", symbol)
                .isEqualTo(expectedContinuityIdentity);
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity(symbol, null))
                .as("canonicalTokenIdentity(%s)", symbol)
                .isEqualTo(expectedCanonicalTokenIdentity);
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity(symbol, null))
                .as("continuityFamilyIdentity(%s)", symbol)
                .isEqualTo(expectedContinuityFamilyIdentity);
        assertThat(AccountingAssetClassificationSupport.isC1SameAsset(symbol))
                .as("isC1SameAsset(%s)", symbol)
                .isEqualTo(expectedC1);
        assertThat(AccountingAssetClassificationSupport.isC2DistinctAsset(symbol))
                .as("isC2DistinctAsset(%s)", symbol)
                .isEqualTo(expectedC2);
        assertThat(AccountingAssetClassificationSupport.normalizationClusterForSymbol(symbol))
                .as("normalizationClusterForSymbol(%s)", symbol)
                .isEqualTo(expectedCluster);
    }

    @Test
    void aaveSavaxResolvesViaSupplementalEntryNotClassifiedC1OrC2() {
        // The one genuine SYMBOL_FAMILIES residue: family (FAMILY:SAVAX) without C1/C2 classification.
        // ADR-083: aAvaSAVAX is an AVAX staking-cluster member, so normalizationClusterForSymbol now
        // resolves it to CLUSTER:AVAX_STAKING (via the supplemental cluster map) to keep normalization
        // and replay carry decisions in agreement.
        assertSurfaces("AAVASAVAX", "FAMILY:SAVAX", null, null, false, false, "CLUSTER:AVAX_STAKING");
    }

    @Test
    void aaveSavaxIsNotReroutedToAvaxFamilyByLendingInference() {
        // Regression: with the supplemental entry consulted BEFORE inferredFamilyIdentity, the
        // SAVAX->AVAX lending lifecycle cannot hijack aAvaSAVAX into FAMILY:AVAX.
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AAVASAVAX", null))
                .isEqualTo("FAMILY:SAVAX")
                .isNotEqualTo("FAMILY:AVAX");
    }

    @Test
    void subsumedC1BtcResolvesThroughRegistry() {
        assertSurfaces("WBTC", "FAMILY:BTC", "FAMILY:BTC", "FAMILY:BTC", true, false, null);
    }

    @Test
    void subsumedC1EthResolvesThroughRegistry() {
        assertSurfaces("AARBWETH", "FAMILY:ETH", "FAMILY:ETH", "FAMILY:ETH", true, false, "CLUSTER:ETH_STAKING");
    }

    @Test
    void subsumedC2SavaxResolvesThroughRegistry() {
        assertSurfaces("SAVAX", "FAMILY:SAVAX", "FAMILY:SAVAX", "FAMILY:SAVAX", false, true, "CLUSTER:AVAX_STAKING");
    }

    @Test
    void subsumedC2BbsolResolvesThroughRegistry() {
        assertSurfaces("BBSOL", "FAMILY:BBSOL", "FAMILY:BBSOL", "FAMILY:BBSOL", false, true, "CLUSTER:SOL_STAKING");
    }

    @Test
    void subsumedC1CmethResolvesThroughRegistry() {
        assertSurfaces("CMETH", "FAMILY:METH", "FAMILY:METH", "FAMILY:METH", true, false, "CLUSTER:ETH_STAKING");
    }

    @Test
    void eulerIndexedC2ReceiptResolvesThroughRegistry() {
        assertSurfaces("EWETH-1", "FAMILY:EWETH", "FAMILY:EWETH", "FAMILY:EWETH", false, true, "CLUSTER:ETH_STAKING");
    }
}
