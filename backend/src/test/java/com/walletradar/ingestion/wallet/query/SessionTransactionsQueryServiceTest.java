package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionTransactionsQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private MongoOperations mongoOperations;

    private SessionTransactionsQueryService sessionTransactionsQueryService;

    @BeforeEach
    void setUp() {
        sessionTransactionsQueryService = new SessionTransactionsQueryService(
                userSessionRepository,
                accountingUniverseService,
                mongoOperations
        );
    }

    @Test
    void readsSessionTransactionsFromAccountingUniverseScope() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setAccountingUniverseId("ACCOUNTING_UNIVERSE:session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        NormalizedTransaction bridge = new NormalizedTransaction();
        bridge.setId("tx-bridge");
        bridge.setSource(NormalizedTransactionSource.ON_CHAIN);
        bridge.setWalletAddress(wallet.getAddress());
        bridge.setNetworkId(NetworkId.BASE);
        bridge.setTxHash("0xbridge");
        bridge.setBlockTimestamp(Instant.parse("2026-04-06T10:00:00Z"));
        bridge.setTransactionIndex(7);
        bridge.setType(NormalizedTransactionType.BRIDGE_OUT);
        bridge.setStatus(NormalizedTransactionStatus.CONFIRMED);
        bridge.setMatchedCounterparty("0xpaired");
        bridge.setFlows(List.of(flow(
                NormalizedLegRole.SELL,
                "ETH",
                new BigDecimal("-0.50"),
                new BigDecimal("2100"),
                new BigDecimal("-1050"),
                PriceSource.COINGECKO,
                new BigDecimal("12.50")
        )));

        NormalizedTransaction bybit = new NormalizedTransaction();
        bybit.setId("bybit-1");
        bybit.setSource(NormalizedTransactionSource.BYBIT);
        bybit.setWalletAddress("BYBIT:33625378");
        bybit.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        bybit.setTransactionIndex(0);
        bybit.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        bybit.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        bybit.setFlows(List.of(flow(
                NormalizedLegRole.BUY,
                "METH",
                new BigDecimal("0.66"),
                new BigDecimal("1900"),
                new BigDecimal("1254"),
                PriceSource.UNKNOWN,
                null
        )));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of(wallet.getAddress(), "BYBIT:33625378"),
                List.of(wallet.getAddress())
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(2L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(bridge, bybit));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        100,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null
                ))
                .orElseThrow();

        assertThat(result.offset()).isZero();
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.items()).hasSize(2);

        SessionTransactionsQueryService.ItemView bridgeView = result.items().get(0);
        assertThat(bridgeView.id()).isEqualTo("tx-bridge");
        assertThat(bridgeView.sourceType()).isEqualTo("CHAIN");
        assertThat(bridgeView.networkId()).isEqualTo("BASE");
        assertThat(bridgeView.type()).isEqualTo("EXTERNAL_TRANSFER_OUT");
        assertThat(bridgeView.status()).isEqualTo("CONFIRMED");
        assertThat(bridgeView.issue()).isNull();
        assertThat(bridgeView.bridgeStatus()).isEqualTo("MATCHED");
        assertThat(bridgeView.realisedPnlUsdTotal()).isEqualByComparingTo("12.50");
        assertThat(bridgeView.flows()).hasSize(1);
        assertThat(bridgeView.flows().getFirst().assetSymbol()).isEqualTo("ETH");

        SessionTransactionsQueryService.ItemView bybitView = result.items().get(1);
        assertThat(bybitView.walletAddress()).isEqualTo("BYBIT:33625378");
        assertThat(bybitView.txHash()).isNull();
        assertThat(bybitView.type()).isEqualTo("STAKE_DEPOSIT");
        assertThat(bybitView.status()).isEqualTo("PENDING_PRICE");
        assertThat(bybitView.issue()).isEqualTo("missing_price");
        assertThat(bybitView.bridgeStatus()).isNull();
    }

    @Test
    void supportsPagingAndSpamCriteria() {
        UserSession session = new UserSession();
        session.setId("session-1");

        NormalizedTransaction spam = new NormalizedTransaction();
        spam.setId("spam-1");
        spam.setSource(NormalizedTransactionSource.ON_CHAIN);
        spam.setWalletAddress("0x1");
        spam.setNetworkId(NetworkId.BASE);
        spam.setTxHash("0xspam");
        spam.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        spam.setType(NormalizedTransactionType.UNKNOWN);
        spam.setStatus(NormalizedTransactionStatus.CONFIRMED);
        spam.setExcludedFromAccounting(Boolean.TRUE);
        spam.setAccountingExclusionReason("PROMO_SPAM_PHISHING");
        spam.setFlows(List.of(flow(
                NormalizedLegRole.BUY,
                "SCAM",
                new BigDecimal("1"),
                null,
                null,
                PriceSource.UNKNOWN,
                null
        )));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("0x1"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(51L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(spam));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        25,
                        25,
                        "spam",
                        "ALL",
                        "SPAM_ONLY",
                        List.of("0x1"),
                        List.of(NetworkId.BASE)
                ))
                .orElseThrow();

        assertThat(result.offset()).isEqualTo(25);
        assertThat(result.limit()).isEqualTo(25);
        assertThat(result.totalCount()).isEqualTo(51);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.issue()).isEqualTo("spam");
            assertThat(item.walletAddress()).isEqualTo("0x1");
            assertThat(item.networkId()).isEqualTo("BASE");
        });
    }

    @Test
    void mapsRewardClaimAndHidesEmptyUnknownRowsFromVisibleList() {
        UserSession session = new UserSession();
        session.setId("session-1");

        NormalizedTransaction reward = new NormalizedTransaction();
        reward.setId("bybit-reward");
        reward.setSource(NormalizedTransactionSource.BYBIT);
        reward.setWalletAddress("BYBIT:33625378");
        reward.setBlockTimestamp(Instant.parse("2026-03-12T00:33:52Z"));
        reward.setType(NormalizedTransactionType.REWARD_CLAIM);
        reward.setStatus(NormalizedTransactionStatus.CONFIRMED);
        reward.setFlows(List.of(flow(
                NormalizedLegRole.BUY,
                "ARB",
                new BigDecimal("0.000798"),
                new BigDecimal("0.1001"),
                new BigDecimal("0.0000798798"),
                PriceSource.UNKNOWN,
                null
        )));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("BYBIT:33625378", "0x1"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(1L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(reward));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        50,
                        0,
                        null,
                        "ALL",
                        "HIDE_SPAM",
                        null,
                        null
                ))
                .orElseThrow();

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("REWARD_CLAIM");
            assertThat(item.walletAddress()).isEqualTo("BYBIT:33625378");
        });
    }

    @Test
    void doesNotMarkTransferOnlyBridgeRowsAsMissingPrice() {
        UserSession session = new UserSession();
        session.setId("session-1");

        NormalizedTransaction bridgeIn = new NormalizedTransaction();
        bridgeIn.setId("bridge-in-1");
        bridgeIn.setSource(NormalizedTransactionSource.ON_CHAIN);
        bridgeIn.setWalletAddress("0x1");
        bridgeIn.setNetworkId(NetworkId.ARBITRUM);
        bridgeIn.setTxHash("0x7d8c");
        bridgeIn.setBlockTimestamp(Instant.parse("2026-03-24T11:05:44Z"));
        bridgeIn.setType(NormalizedTransactionType.BRIDGE_IN);
        bridgeIn.setStatus(NormalizedTransactionStatus.CONFIRMED);
        bridgeIn.setMatchedCounterparty("0xpaired");
        bridgeIn.setFlows(List.of(flow(
                NormalizedLegRole.TRANSFER,
                "USDC",
                new BigDecimal("28.920966"),
                null,
                null,
                PriceSource.UNKNOWN,
                null
        )));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("0x1"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(1L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(bridgeIn));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        50,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null
                ))
                .orElseThrow();

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("EXTERNAL_INBOUND");
            assertThat(item.issue()).isNull();
            assertThat(item.bridgeStatus()).isEqualTo("MATCHED");
        });
    }

    @Test
    void doesNotMarkMatchedExternalTransferRowsAsMissingPrice() {
        UserSession session = new UserSession();
        session.setId("session-1");

        NormalizedTransaction matchedTransfer = new NormalizedTransaction();
        matchedTransfer.setId("external-in-1");
        matchedTransfer.setSource(NormalizedTransactionSource.ON_CHAIN);
        matchedTransfer.setWalletAddress("0x1");
        matchedTransfer.setNetworkId(NetworkId.ARBITRUM);
        matchedTransfer.setTxHash("0x8186");
        matchedTransfer.setBlockTimestamp(Instant.parse("2026-03-09T11:27:02Z"));
        matchedTransfer.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        matchedTransfer.setStatus(NormalizedTransactionStatus.CONFIRMED);
        matchedTransfer.setMatchedCounterparty("BYBIT:33625378");
        matchedTransfer.setFlows(List.of(flow(
                NormalizedLegRole.BUY,
                "USDC",
                new BigDecimal("326.955713"),
                null,
                null,
                PriceSource.UNKNOWN,
                null
        )));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("0x1", "BYBIT:33625378"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(1L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(matchedTransfer));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        50,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null
                ))
                .orElseThrow();

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("EXTERNAL_INBOUND");
            assertThat(item.issue()).isNull();
            assertThat(item.matchedCounterparty()).isEqualTo("BYBIT:33625378");
        });
    }

    @Test
    void treatsClaimLikeAirdropReasonsAsSpamLikeRows() {
        UserSession session = new UserSession();
        session.setId("session-1");

        NormalizedTransaction claimLikeSpam = new NormalizedTransaction();
        claimLikeSpam.setId("claim-like-spam");
        claimLikeSpam.setSource(NormalizedTransactionSource.ON_CHAIN);
        claimLikeSpam.setWalletAddress("0x1");
        claimLikeSpam.setNetworkId(NetworkId.BASE);
        claimLikeSpam.setTxHash("0x0931");
        claimLikeSpam.setBlockTimestamp(Instant.parse("2026-04-06T10:00:00Z"));
        claimLikeSpam.setType(NormalizedTransactionType.UNKNOWN);
        claimLikeSpam.setStatus(NormalizedTransactionStatus.CONFIRMED);
        claimLikeSpam.setMissingDataReasons(List.of("CLAIM_LIKE_SPAM_OR_AIRDROP"));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("0x1"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(1L);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(claimLikeSpam));

        SessionTransactionsQueryService.SessionTransactionsView result = sessionTransactionsQueryService
                .findSessionTransactions("session-1", SessionTransactionsQueryService.normalizeQuery(
                        50,
                        0,
                        null,
                        "ALL",
                        "SPAM_ONLY",
                        null,
                        null
                ))
                .orElseThrow();

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.issue()).isEqualTo("spam");
            assertThat(item.txHash()).isEqualTo("0x0931");
        });

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(queryCaptor.capture(), eq(NormalizedTransaction.class));
        assertThat(queryCaptor.getValue().toString()).contains("CLAIM_LIKE_SPAM_OR_AIRDROP");
    }

    @Test
    void rebuildReturnsCurrentProjectedTransactionCount() {
        UserSession session = new UserSession();
        session.setId("session-1");
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("0x1", "BYBIT:33625378"),
                List.of("0x1")
        ));
        when(mongoOperations.count(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(17L);

        SessionTransactionsQueryService.RebuildTransactionsView result = sessionTransactionsQueryService
                .rebuildSessionTransactions("session-1")
                .orElseThrow();

        assertThat(result.projectedTransactions()).isEqualTo(17);
        assertThat(result.message()).contains("canonical normalized transactions");
        verify(accountingUniverseService).ensureBybitMembership(eq("session-1"), any(Instant.class));
    }

    @Test
    void rejectsOutOfRangeLimit() {
        assertThatThrownBy(() -> SessionTransactionsQueryService.validateLimitOrThrow(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 500");
    }

    @Test
    void rejectsNegativeOffset() {
        assertThatThrownBy(() -> SessionTransactionsQueryService.validateOffsetOrThrow(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than or equal to 0");
    }

    @Test
    void rejectsUnknownSpamFilter() {
        assertThatThrownBy(() -> SessionTransactionsQueryService.validateSpamFilterOrThrow("maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spamFilter");
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            PriceSource priceSource,
            BigDecimal realisedPnlUsd
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(quantityDelta);
        flow.setUnitPriceUsd(unitPriceUsd);
        flow.setValueUsd(valueUsd);
        flow.setPriceSource(priceSource);
        flow.setRealisedPnlUsd(realisedPnlUsd);
        return flow;
    }
}
