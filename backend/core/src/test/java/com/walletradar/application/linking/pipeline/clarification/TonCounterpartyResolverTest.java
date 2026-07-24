package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RC-T1.4 counterparty classification for {@link TonCounterpartyResolver} (ADR-066).
 *
 * <p>The TON builder sets each non-fee flow's {@code counterpartyAddress} to the raw peer; this
 * resolver classifies that peer via the accounting universe and reconciles the transaction-level
 * counterparty. Correct peer typing is what keeps cross-network moves economically continuous:
 * a CEX/own inbound is never booked as external-capital ACQUIRE, and an own-wallet transfer is
 * promoted to {@code INTERNAL_TRANSFER} so basis carries.</p>
 */
@ExtendWith(MockitoExtension.class)
class TonCounterpartyResolverTest {

    private static final String WALLET = "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms";
    private static final String PEER = "0:" + "44".repeat(32);

    // ADR-079 seed operators (Telegram Wallet custodial hot wallets), raw workchain:hex form.
    private static final String TG_WALLET_OPERATOR_1 =
            "0:DD6FF02C59634745529B99A8D5BEEEA9F6C38A9188E6A7E96A424E3820C8AC0A";
    private static final String TG_WALLET_OPERATOR_2 =
            "0:023895AEF955024920A291C6F3715E291DF1B3DD254EAFA8B09E21A2D58D5897";
    // Bybit highload hot wallet (classified EXCHANGE_ACCOUNT by the accounting universe, NOT custody).
    private static final String BYBIT_HIGHLOAD = "0:7F97F36D" + "0".repeat(56);
    // A highload operator that is NOT in the registry and not a universe member.
    private static final String UNREGISTERED_HIGHLOAD = "0:" + "ab".repeat(32);

    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    private TonCounterpartyResolver resolver() {
        return new TonCounterpartyResolver(accountingUniverseService, externalCustodyDestinationRegistry);
    }

    private static NormalizedTransaction tx(NormalizedTransactionType type) {
        return tx(type, PEER);
    }

    private static NormalizedTransaction tx(NormalizedTransactionType type, String peer) {
        NormalizedTransaction t = new NormalizedTransaction();
        t.setNetworkId(NetworkId.TON);
        t.setWalletAddress(WALLET);
        t.setType(type);
        t.setFlows(new ArrayList<>(List.of(transferFlow(peer), feeFlow())));
        return t;
    }

    private static NormalizedTransaction.Flow transferFlow() {
        return transferFlow(PEER);
    }

    private static NormalizedTransaction.Flow transferFlow(String peer) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(NormalizedLegRole.TRANSFER);
        f.setAssetSymbol("TON");
        f.setQuantityDelta(BigDecimal.ONE);
        f.setCounterpartyAddress(peer);
        return f;
    }

    private static NormalizedTransaction.Flow feeFlow() {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setRole(NormalizedLegRole.FEE);
        f.setAssetSymbol("TON");
        f.setQuantityDelta(new BigDecimal("-0.003"));
        return f;
    }

    @Test
    @DisplayName("RC-T1.4: CEX peer classifies as CEX and inbound stays EXTERNAL_TRANSFER_IN (no market ACQUIRE)")
    void cexPeerClassifiesAsCex() {
        when(accountingUniverseService.classify(PEER, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(
                        true, AccountingUniverse.MemberType.EXCHANGE_ACCOUNT, false, "ton:" + PEER));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getFlows().get(0).getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
    }

    @Test
    @DisplayName("RC-T1.4: own-wallet peer promotes EXTERNAL_TRANSFER_OUT to INTERNAL_TRANSFER (basis continuity)")
    void ownWalletPeerPromotesToInternal() {
        when(accountingUniverseService.classify(PEER, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(
                        true, AccountingUniverse.MemberType.ON_CHAIN_WALLET, false, "ton:" + PEER));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.PERSONAL_WALLET);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    @Test
    @DisplayName("RC-T1.4: genuinely unknown peer classifies UNKNOWN_EOA and type is unchanged")
    void unknownPeerClassifiesUnknownEoa() {
        when(accountingUniverseService.classify(PEER, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getFlows().get(0).getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
    }

    @Test
    @DisplayName("RC-T1.4: FEE leg gets GENUINE_MISSING_SOURCE / UNKNOWN:NETWORK_FEE")
    void feeFlowGetsGenuineMissingSource() {
        when(accountingUniverseService.classify(PEER, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        resolver().enrichInPlace(t, null, Instant.now());

        NormalizedTransaction.Flow fee = t.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.FEE).findFirst().orElseThrow();
        assertThat(fee.getCounterpartyType()).isEqualTo(CounterpartyType.GENUINE_MISSING_SOURCE);
        assertThat(fee.getCounterpartyAddress()).isEqualTo("UNKNOWN:NETWORK_FEE");
    }

    // ==== Cluster B (ADR-079): Telegram-Wallet global custodial-operator registry ====

    @Test
    @DisplayName("ADR-079 B(i): inbound from seeded TG operator #1 -> EXTERNAL_CUSTODY, stays EXTERNAL_TRANSFER_IN, custodialOffChain")
    void telegramWalletOperatorInboundLabeledExternalCustody() {
        // Peer is NOT a universe member (classifyPeer would yield UNKNOWN_EOA); the GLOBAL registry
        // relabels it. tonapi/highload interface are offline seeding aids only — no runtime lookup.
        when(accountingUniverseService.classify(TG_WALLET_OPERATOR_1, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, TG_WALLET_OPERATOR_1);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(t.getProtocolName()).isEqualTo("Telegram Wallet");
        // MATERIAL (B2): stays EXTERNAL_TRANSFER_IN — never promoted to INTERNAL_TRANSFER.
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getCustodialOffChain()).isTrue();
        assertThat(t.getFlows().get(0).getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
    }

    @Test
    @DisplayName("ADR-079 B(i): inbound from seeded TG operator #2 -> EXTERNAL_CUSTODY (second seed address)")
    void telegramWalletOperatorTwoInboundLabeledExternalCustody() {
        when(accountingUniverseService.classify(TG_WALLET_OPERATOR_2, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, TG_WALLET_OPERATOR_2);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(t.getProtocolName()).isEqualTo("Telegram Wallet");
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getCustodialOffChain()).isTrue();
    }

    @Test
    @DisplayName("ADR-079 B(ii): Bybit highload stays EXCHANGE_ACCOUNT/CEX — registry does not override it as custody")
    void bybitHighloadStaysExchangeAccount() {
        when(accountingUniverseService.classify(BYBIT_HIGHLOAD, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(
                        true, AccountingUniverse.MemberType.EXCHANGE_ACCOUNT, false, "ton:" + BYBIT_HIGHLOAD));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, BYBIT_HIGHLOAD);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getCustodialOffChain()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("ADR-079 B(iii): highload operator NOT in the registry stays UNKNOWN_EOA — never guessed as Telegram")
    void unregisteredHighloadStaysUnknownEoa() {
        when(accountingUniverseService.classify(UNREGISTERED_HIGHLOAD, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, UNREGISTERED_HIGHLOAD);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getCustodialOffChain()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("ADR-079 B(iv): a DEX-router peer already classified PROTOCOL keeps priority and is not swept into custody")
    void dexRouterPeerKeepsProtocolClassification() {
        // Normalization plane (TonProtocolRegistry) already stamped the DEX router flow as PROTOCOL.
        // The resolver must not override that: classifyPeer only fills blank types, and the custody
        // registry does not contain DEX routers, so the peer stays PROTOCOL (no custody, no promotion).
        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, UNREGISTERED_HIGHLOAD);
        t.getFlows().get(0).setCounterpartyType(CounterpartyType.PROTOCOL);

        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getFlows().get(0).getCounterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.PROTOCOL);
        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(t.getCustodialOffChain()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("ADR-079 B(v): re-clarification is idempotent — same registry-only result, no further mutation on rerun")
    void telegramWalletCustodyIsIdempotent() {
        when(accountingUniverseService.classify(TG_WALLET_OPERATOR_1, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, TG_WALLET_OPERATOR_1);
        boolean firstChanged = resolver().enrichInPlace(t, null, Instant.now());
        String typeAfterFirst = t.getType().name();
        String counterpartyAfterFirst = t.getCounterpartyType();
        String protocolAfterFirst = t.getProtocolName();

        boolean secondChanged = resolver().enrichInPlace(t, null, Instant.now());

        assertThat(firstChanged).isTrue();
        // Second pass is a no-op: the deterministic, offline registry yields the identical labeling.
        assertThat(secondChanged).isFalse();
        assertThat(t.getType().name()).isEqualTo(typeAfterFirst);
        assertThat(t.getCounterpartyType()).isEqualTo(counterpartyAfterFirst);
        assertThat(t.getProtocolName()).isEqualTo(protocolAfterFirst);
        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(t.getCustodialOffChain()).isTrue();
    }
}
