package com.walletradar.application.custody.application;

import com.walletradar.application.linking.pipeline.clarification.ExternalCustodyDestinationRegistry;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WS-5 (ADR-072): the informational custody ledger tallies raw per-asset in/out flows to a
 * user-designated external custody destination. It never contributes to portfolio quantity/AVCO.
 * Cross-asset (USDT in → USDe out) is reported as-is, with no reconciliation.
 */
@ExtendWith(MockitoExtension.class)
class CustodyLedgerQueryServiceTest {

    private static final String SESSION_ID = "session-custody";
    private static final String WALLET = "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms";
    private static final String VENUE = "0:" + "ab".repeat(32);
    private static final String LABEL = "Telegram Wallet Earn";

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    private CustodyLedgerQueryService service() {
        return new CustodyLedgerQueryService(userSessionRepository, mongoOperations, externalCustodyDestinationRegistry);
    }

    private static NormalizedTransaction custodyRow(NormalizedTransactionType type, String asset, BigDecimal qty, BigDecimal usd) {
        NormalizedTransaction t = new NormalizedTransaction();
        t.setNetworkId(NetworkId.TON);
        t.setWalletAddress(WALLET);
        t.setType(type);
        t.setCustodialOffChain(true);
        t.setCounterpartyAddress(VENUE);
        t.setProtocolName(LABEL);
        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetSymbol(asset);
        transfer.setQuantityDelta(qty);
        transfer.setValueUsd(usd);
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("TON");
        fee.setQuantityDelta(new BigDecimal("-0.01"));
        t.setFlows(new ArrayList<>(List.of(transfer, fee)));
        return t;
    }

    @Test
    @DisplayName("tallies deposited X (out) and withdrawn Y (in) per asset for a custody venue; cross-asset reported as-is")
    void talliesDepositsAndWithdrawalsPerVenueAndAsset() {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(WALLET);
        session.setWallets(new ArrayList<>(List.of(wallet)));
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        List<NormalizedTransaction> rows = List.of(
                custodyRow(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, "USDT", new BigDecimal("-100"), new BigDecimal("100")),
                custodyRow(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, "USDT", new BigDecimal("-50"), new BigDecimal("50")),
                custodyRow(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, "USDE", new BigDecimal("61"), new BigDecimal("61.04"))
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(rows);
        lenient().when(externalCustodyDestinationRegistry.matchForSession(any(), any(), any())).thenReturn(Optional.empty());

        CustodyLedgerQueryService.SessionCustodyLedgerView view =
                service().findSessionCustodyLedger(SESSION_ID).orElseThrow();

        assertThat(view.venues()).hasSize(1);
        CustodyLedgerQueryService.CustodyVenueView venue = view.venues().get(0);
        assertThat(venue.venueAddress()).isEqualTo(VENUE);
        assertThat(venue.label()).isEqualTo(LABEL);

        CustodyLedgerQueryService.CustodyAssetView usdt = venue.assets().stream()
                .filter(a -> a.asset().equals("USDT")).findFirst().orElseThrow();
        assertThat(usdt.depositedQty()).isEqualByComparingTo("150");
        assertThat(usdt.withdrawnQty()).isEqualByComparingTo("0");
        assertThat(usdt.netQty()).isEqualByComparingTo("150");
        assertThat(usdt.depositedUsd()).isEqualByComparingTo("150");

        CustodyLedgerQueryService.CustodyAssetView usde = venue.assets().stream()
                .filter(a -> a.asset().equals("USDE")).findFirst().orElseThrow();
        assertThat(usde.depositedQty()).isEqualByComparingTo("0");
        assertThat(usde.withdrawnQty()).isEqualByComparingTo("61");
        assertThat(usde.netQty()).isEqualByComparingTo("-61");
        assertThat(usde.withdrawnUsd()).isEqualByComparingTo("61.04");
    }

    @Test
    @DisplayName("custody ledger query requires persisted custodialOffChain=true + CONFIRMED (ADR-072/ADR-079)")
    void queryFiltersOnPersistedCustodialOffChainFlag() {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(WALLET);
        session.setWallets(new ArrayList<>(List.of(wallet)));
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        org.mockito.ArgumentCaptor<Query> queryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
        when(mongoOperations.find(queryCaptor.capture(), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(custodyRow(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN, "USDT", new BigDecimal("13"), new BigDecimal("13"))));
        lenient().when(externalCustodyDestinationRegistry.matchForSession(any(), any(), any())).thenReturn(Optional.empty());

        CustodyLedgerQueryService.SessionCustodyLedgerView view =
                service().findSessionCustodyLedger(SESSION_ID).orElseThrow();

        // A row is only surfaced because its persisted custodialOffChain flag is true.
        assertThat(view.venues()).hasSize(1);
        // The read path filters on the exact field the fix restores; a dropped flag => empty ledger.
        String filter = queryCaptor.getValue().getQueryObject().toString();
        assertThat(filter).contains("custodialOffChain=true");
    }

    @Test
    @DisplayName("no wallets → empty ledger (nothing in portfolio)")
    void noWalletsEmptyLedger() {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        session.setWallets(new ArrayList<>());
        when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        CustodyLedgerQueryService.SessionCustodyLedgerView view =
                service().findSessionCustodyLedger(SESSION_ID).orElseThrow();
        assertThat(view.venues()).isEmpty();
    }
}
