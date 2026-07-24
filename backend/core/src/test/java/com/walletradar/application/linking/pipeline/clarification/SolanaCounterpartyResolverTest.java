package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.solana.SolanaProgramIds;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SolanaCounterpartyResolverTest {

    private static final String WALLET = "6Rc7yKz3aT2j2n7f3Q8Q3zvz1n2u9Wq3rXyZabCdEfG";
    private static final String PEER = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    private SolanaCounterpartyResolver resolver() {
        return new SolanaCounterpartyResolver(
                protocolRegistryService,
                accountingUniverseService,
                externalCustodyDestinationRegistry
        );
    }

    private static RawTransaction raw(Document heliusParsed) {
        RawTransaction r = new RawTransaction();
        r.setWalletAddress(WALLET);
        r.setTxHash("sig1");
        r.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return r;
    }

    private static NormalizedTransaction.Flow flow(NormalizedLegRole role, String cp) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(role);
        f.setAssetSymbol("USDC");
        f.setQuantityDelta(BigDecimal.ONE);
        f.setCounterpartyAddress(cp);
        return f;
    }

    private static NormalizedTransaction.Flow feeFlow() {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(NormalizedLegRole.FEE);
        f.setAssetSymbol("SOL");
        f.setQuantityDelta(new BigDecimal("-0.000005"));
        return f;
    }

    @Test
    @DisplayName("RC-S2b: program ID resolves counterparty to PROTOCOL with the program as address")
    void programIdResolvesToProtocol() {
        ProtocolRegistryEntry entry = new ProtocolRegistryEntry(
                SolanaProgramIds.JUPITER_SWAP_V6,
                Set.of(NetworkId.SOLANA),
                ProtocolRegistryFamily.AGGREGATOR,
                ProtocolRegistryRole.ROUTER,
                null,
                ConfidenceLevel.HIGH,
                "Jupiter",
                "v6",
                false,
                null
        );
        when(protocolRegistryService.lookup(NetworkId.SOLANA, SolanaProgramIds.JUPITER_SWAP_V6))
                .thenReturn(Optional.of(entry));

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setWalletAddress(WALLET);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setFlows(new ArrayList<>(List.of(flow(NormalizedLegRole.BUY, null), feeFlow())));

        Document parsed = new Document("type", "SWAP")
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_SWAP_V6)));

        boolean changed = resolver().enrichInPlace(tx, raw(parsed), Instant.now());

        assertThat(changed).isTrue();
        assertThat(tx.getCounterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        assertThat(tx.getCounterpartyAddress()).isEqualTo(SolanaProgramIds.JUPITER_SWAP_V6);
        NormalizedTransaction.Flow buy = tx.getFlows().get(0);
        assertThat(buy.getCounterpartyAddress()).isEqualTo(SolanaProgramIds.JUPITER_SWAP_V6);
        assertThat(buy.getCounterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        NormalizedTransaction.Flow fee = tx.getFlows().get(1);
        assertThat(fee.getCounterpartyType()).isEqualTo(CounterpartyType.GENUINE_MISSING_SOURCE);
    }

    @Test
    @DisplayName("RC-S2b: unknown-external transfer peer classifies UNKNOWN_EOA")
    void transferPeerUnknownExternal() {
        when(accountingUniverseService.classify(PEER, NetworkId.SOLANA))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setWalletAddress(WALLET);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setFlows(new ArrayList<>(List.of(flow(NormalizedLegRole.TRANSFER, PEER), feeFlow())));

        Document parsed = new Document("type", "TRANSFER")
                .append("nativeTransfers", List.of(new Document("fromUserAccount", PEER)
                        .append("toUserAccount", WALLET).append("amount", 1_000_000_000L)));

        resolver().enrichInPlace(tx, raw(parsed), Instant.now());

        assertThat(tx.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(tx.getFlows().get(0).getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
    }

    @Test
    @DisplayName("RC-S2b: peer that is an owned wallet promotes EXTERNAL_TRANSFER_OUT to INTERNAL_TRANSFER")
    void ownedPeerPromotesToInternal() {
        when(accountingUniverseService.classify(PEER, NetworkId.SOLANA))
                .thenReturn(new AccountingUniverseService.OwnMembership(
                        true, AccountingUniverse.MemberType.ON_CHAIN_WALLET, true, "solana:" + PEER));

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setWalletAddress(WALLET);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setFlows(new ArrayList<>(List.of(flow(NormalizedLegRole.TRANSFER, PEER), feeFlow())));

        Document parsed = new Document("type", "TRANSFER")
                .append("nativeTransfers", List.of(new Document("fromUserAccount", WALLET)
                        .append("toUserAccount", PEER).append("amount", 1_000_000_000L)));

        resolver().enrichInPlace(tx, raw(parsed), Instant.now());

        assertThat(tx.getCounterpartyType()).isEqualTo(CounterpartyType.PERSONAL_WALLET);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }
}
