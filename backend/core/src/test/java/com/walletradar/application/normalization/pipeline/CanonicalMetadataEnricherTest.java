package com.walletradar.application.normalization.pipeline;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.linking.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.application.normalization.pipeline.metadata.ResolvedTokenMetadata;
import com.walletradar.application.normalization.pipeline.metadata.TokenMetadataResolutionService;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cluster C: the enrichment seam must guarantee a non-blank, non-raw-address {@code assetSymbol} for
 * SPL mints / TON jettons while never changing the contract-keyed accounting identity, and must
 * never overwrite the native TON symbol with a contract-derived placeholder.
 */
class CanonicalMetadataEnricherTest {

    // Ensures NetworkNativeAssets (nativeIdentity for TON) is bound before tests run.
    static {
        NetworkTestFixtures.registry();
    }

    private static final String SPL_MINT = "vPtS4ywrbEuufwPkBXsCYkeTBfpzCd6hF52p8kJGt9b";
    private static final String TON_JETTON = "0:3547f2ee4022c794c80ea354b81bb63b5b571dd05ac091b035d19abbadd74ac6";
    private static final String TON_NATIVE_CONTRACT = "TONCOIN";

    private final ProtocolNameEnrichmentService protocolNameEnrichmentService =
            mock(ProtocolNameEnrichmentService.class);
    private final CounterpartyEnrichmentService counterpartyEnrichmentService =
            mock(CounterpartyEnrichmentService.class);
    private final TokenMetadataResolutionService tokenMetadataResolutionService =
            mock(TokenMetadataResolutionService.class);

    private final CanonicalMetadataEnricher enricher = new CanonicalMetadataEnricher(
            protocolNameEnrichmentService, counterpartyEnrichmentService, tokenMetadataResolutionService);

    @Test
    @DisplayName("unresolved SPL mint (blank symbol) → SPL:xxxxxx; identity unchanged vs blank")
    void unresolvedSplGetsDeterministicFallback() {
        when(tokenMetadataResolutionService.resolve(eq(NetworkId.SOLANA), any()))
                .thenReturn(ResolvedTokenMetadata.unresolved());
        NormalizedTransaction tx = txWithFlow(NetworkId.SOLANA, SPL_MINT, null);

        enricher.enrichSolana(tx, null, Instant.now());

        String symbol = tx.getFlows().get(0).getAssetSymbol();
        assertThat(symbol).isEqualTo("SPL:kJGt9b");
        assertThat(symbol).isNotEqualTo(SPL_MINT);
        assertIdentityUnchangedVsBlank(symbol, SPL_MINT);
    }

    @Test
    @DisplayName("unresolved TON jetton (raw-address symbol) → JETTON:xxxxxx; identity unchanged")
    void unresolvedJettonGetsDeterministicFallback() {
        when(tokenMetadataResolutionService.resolve(eq(NetworkId.TON), any()))
                .thenReturn(ResolvedTokenMetadata.unresolved());
        // TON jetton flows are stored with the lowercased raw address; symbol seeded as the raw address.
        NormalizedTransaction tx = txWithFlow(NetworkId.TON, TON_JETTON, TON_JETTON);

        enricher.enrichTon(tx, null, Instant.now());

        String symbol = tx.getFlows().get(0).getAssetSymbol();
        assertThat(symbol).isEqualTo("JETTON:d74ac6");
        assertThat(symbol).isNotEqualTo(TON_JETTON);
        assertIdentityUnchangedVsBlank(symbol, TON_JETTON);
    }

    @Test
    @DisplayName("native TON (TONCOIN/TON) is never overwritten with a JETTON: placeholder")
    void nativeTonSymbolPreserved() {
        when(tokenMetadataResolutionService.resolve(eq(NetworkId.TON), any()))
                .thenReturn(ResolvedTokenMetadata.unresolved());
        NormalizedTransaction tx = txWithFlow(NetworkId.TON, TON_NATIVE_CONTRACT, "TON");

        enricher.enrichTon(tx, null, Instant.now());

        assertThat(tx.getFlows().get(0).getAssetSymbol()).isEqualTo("TON");
    }

    @Test
    @DisplayName("a live-resolved symbol upgrades the placeholder (real symbol wins over fallback)")
    void resolvedSymbolUpgradesPlaceholder() {
        when(tokenMetadataResolutionService.resolve(eq(NetworkId.SOLANA), any()))
                .thenReturn(new ResolvedTokenMetadata("GRAM", 6, ResolvedTokenMetadata.Source.LIVE_RESOLVER));
        NormalizedTransaction tx = txWithFlow(NetworkId.SOLANA, SPL_MINT, null);

        enricher.enrichSolana(tx, null, Instant.now());

        assertThat(tx.getFlows().get(0).getAssetSymbol()).isEqualTo("GRAM");
    }

    /**
     * The deterministic fallback must not change the accounting family identity, which is contract
     * keyed: a blank symbol and the fallback symbol must both fall through to the same identity.
     */
    private static void assertIdentityUnchangedVsBlank(String fallbackSymbol, String contract) {
        String withBlank = AccountingAssetFamilySupport.continuityIdentity(null, contract);
        String withFallback = AccountingAssetFamilySupport.continuityIdentity(fallbackSymbol, contract);
        assertThat(withFallback).isEqualTo(withBlank);
    }

    private static NormalizedTransaction txWithFlow(NetworkId networkId, String contract, String symbol) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetContract(contract);
        if (symbol != null) {
            flow.setAssetSymbol(symbol);
        }
        flow.setRole(NormalizedLegRole.TRANSFER);
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(networkId);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
