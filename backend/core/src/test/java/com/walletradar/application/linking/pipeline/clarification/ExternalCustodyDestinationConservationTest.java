package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WS-5 (ADR-072): a user-designated external custody destination is labeled as
 * {@link CounterpartyType#EXTERNAL_CUSTODY} and stamped {@code custodialOffChain}, while keeping
 * standard external-transfer accounting.
 *
 * <p><b>Conservation-gate proof (unit level):</b> a custody deposit stays
 * {@code EXTERNAL_TRANSFER_OUT} and a withdrawal stays {@code EXTERNAL_TRANSFER_IN}; because
 * {@code EXTERNAL_CUSTODY} is not {@code PERSONAL_WALLET}, the row is never promoted to
 * {@code INTERNAL_TRANSFER} and is never excluded from accounting. The destination is not a universe
 * member and creates no phantom balance, so the {@code PortfolioConservationGate} sees only a normal
 * external flow — it is unaffected. (TON is additionally out of the EVM NEC scope, ADR-067.)</p>
 */
@ExtendWith(MockitoExtension.class)
class ExternalCustodyDestinationConservationTest {

    private static final String WALLET = "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms";
    private static final String OPERATOR = "0:" + "ab".repeat(32);
    private static final String LABEL = "Telegram Wallet Earn";

    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    private TonCounterpartyResolver resolver() {
        return new TonCounterpartyResolver(accountingUniverseService, externalCustodyDestinationRegistry);
    }

    private static NormalizedTransaction tx(NormalizedTransactionType type) {
        NormalizedTransaction t = new NormalizedTransaction();
        t.setNetworkId(NetworkId.TON);
        t.setWalletAddress(WALLET);
        t.setType(type);
        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetSymbol("USDT");
        transfer.setQuantityDelta(new BigDecimal("-100"));
        transfer.setCounterpartyAddress(OPERATOR);
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("TON");
        fee.setQuantityDelta(new BigDecimal("-0.01"));
        t.setFlows(new ArrayList<>(List.of(transfer, fee)));
        return t;
    }

    private void stubCustodyMatch() {
        String canonical = com.walletradar.domain.common.ton.TonAddressCanonicalizer.preferredMemberRef(OPERATOR);
        lenient().when(accountingUniverseService.classify(OPERATOR, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));
        when(externalCustodyDestinationRegistry.match(OPERATOR, NetworkId.TON))
                .thenReturn(Optional.of(new ExternalCustodyDestinationRegistry.CustodyMatch(
                        canonical, LABEL, "TELEGRAM_EARN")));
    }

    @Test
    @DisplayName("deposit stays EXTERNAL_TRANSFER_OUT, labeled EXTERNAL_CUSTODY, custodialOffChain stamped, not promoted to internal")
    void depositLabeledAsCustodyKeepsExternalOut() {
        stubCustodyMatch();
        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);

        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(t.getCustodialOffChain()).isTrue();
        assertThat(t.getProtocolName()).isEqualTo(LABEL);
        assertThat(t.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        NormalizedTransaction.Flow transfer = t.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
    }

    @Test
    @DisplayName("withdrawal ('count on exit') stays EXTERNAL_TRANSFER_IN and is labeled EXTERNAL_CUSTODY")
    void withdrawalLabeledAsCustodyKeepsExternalIn() {
        stubCustodyMatch();
        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);

        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.EXTERNAL_CUSTODY);
        assertThat(t.getCustodialOffChain()).isTrue();
    }

    @Test
    @DisplayName("non-designated external peer is untouched (no custody flag, standard external transfer)")
    void nonDesignatedPeerNotFlagged() {
        when(accountingUniverseService.classify(OPERATOR, NetworkId.TON))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));
        when(externalCustodyDestinationRegistry.match(OPERATOR, NetworkId.TON)).thenReturn(Optional.empty());

        NormalizedTransaction t = tx(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        resolver().enrichInPlace(t, null, Instant.now());

        assertThat(t.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN_EOA);
        assertThat(t.getCustodialOffChain()).isNull();
    }
}
