package com.walletradar.ingestion.wallet.query;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.walletradar.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioConservationGateTest {

    @Mock
    private CounterpartyBasisPoolRepository counterpartyBasisPoolRepository;
    @Mock
    private BorrowLiabilityRepository borrowLiabilityRepository;
    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;
    @Mock
    private MongoOperations mongoOperations;

    private PortfolioConservationGate gate;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        gate = new PortfolioConservationGate(
                counterpartyBasisPoolRepository,
                borrowLiabilityRepository,
                accountingUniverseRepository,
                mongoOperations
        );
        Logger logger = (Logger) LoggerFactory.getLogger(PortfolioConservationGate.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(PortfolioConservationGate.class);
        logger.detachAppender(logAppender);
    }

    @Test
    void necDeprecatedWidePathReturnsZeroWithoutUniverse() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of(
                memberPoolNoBackfill("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG", "10", "2")
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                externalIn("0xdzengi", "1000"),
                externalIn("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "500"),
                externalOut("0xhot", "200")
        ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithSolanaMember()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                new BigDecimal("10000"),
                new BigDecimal("-500"),
                new BigDecimal("200")
        ));

        // Cycle/9 S1: no wallet on these fixtures is a universe member (walletAddress is null),
        // so NEC and lifetime inflow are zero. Old wide path returning 1300 is gone.
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("0");
        assertThat(result.markToMarketUsd()).isEqualByComparingTo("10020");
    }

    @Test
    void fundDepositFromNonMemberCounterpartyCountsRegardlessOfAsset() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundDeposit("0xexternalhotwallet0000000000000000000001", "1000", "hash-a"),
                        fundDeposit("0xexternalhotwallet0000000000000000000002", "500", "hash-b")
                ))
                .thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndEvm()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("1500");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("1500");
    }

    @Test
    void fundDepositFromUniverseMemberCounterpartyExcluded() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundDeposit("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "900", "hash-own-evm")
                ))
                .thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndEvm()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("0");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
    }

    @Test
    void nonFundWalletDepositsExcluded() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndEvm()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("0");
    }

    @Test
    void externalVenueCounterpartyExcludedFromNetInflowAndOutflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundDeposit("0xexternalhotwallet0000000000000000000003", "300", "hash-real-in"),
                        fundDeposit("0xparadexdeposit", "900", "hash-venue-in")
                ))
                .thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithVenue()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("300");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("300");
    }

    @Test
    void netExternalCapitalEqualsInflowMinusOutflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundDeposit("0xexternalhotwallet0000000000000000000004", "1000", "hash-in")))
                .thenReturn(List.of(fundWithdraw("0xexternalhotwallet0000000000000000000005", "200", "hash-out")));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndEvm()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("1000");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("800");
    }

    @Test
    void protocolSwapFlowsDoNotContributeToNecEvenWhenPoolsExist() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of(
                externalPool("0xparaswap", "5000", "0")
        ));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.empty());

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                new BigDecimal("5000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
    }

    @Test
    void conservationBreachedEmitsStructuredWarnLog() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(
                fundDeposit("0xexternalhotwallet0000000000000000000099", "50000", "hash-breach")
        ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndEvm()));

        gate.evaluate(inputs(new BigDecimal("5000"), new BigDecimal("0"), new BigDecimal("0")));

        assertThat(logAppender.list).isNotEmpty();
        ILoggingEvent event = logAppender.list.getLast();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("conservationBreached");
    }

    @Test
    void openLiabilityReducesAdjustedMtm() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of(openLiability("100")));
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.empty());

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertThat(result.markToMarketUsd()).isEqualByComparingTo("900");
        assertThat(result.expectedPnlUsd()).isEqualByComparingTo("900");
    }

    @Test
    void thresholdUsesMaxOfFiftyAndOnePercentMtm() {
        assertThat(PortfolioConservationGate.conservationThreshold(new BigDecimal("3000")))
                .isEqualByComparingTo("50");
        assertThat(PortfolioConservationGate.conservationThreshold(new BigDecimal("20000")))
                .isEqualByComparingTo("200");
    }

    private static PortfolioConservationGate.ConservationInputs inputs(
            BigDecimal mtm,
            BigDecimal realised,
            BigDecimal unrealised
    ) {
        return new PortfolioConservationGate.ConservationInputs(
                "u1",
                mtm,
                realised,
                unrealised,
                List.of()
        );
    }

    private static final String FIAT_P2P = "FIAT:P2P";

    private static NormalizedTransaction fundFiatDeposit(String valueUsd, String txHash) {
        NormalizedTransaction transaction = fiatExternalTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                valueUsd
        );
        transaction.setWalletAddress("BYBIT:33625378:FUND");
        transaction.setCounterpartyAddress(FIAT_P2P);
        transaction.setTxHash(txHash);
        return transaction;
    }

    private static NormalizedTransaction utaFiatDeposit(String valueUsd, String txHash) {
        NormalizedTransaction transaction = fiatExternalTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                valueUsd
        );
        transaction.setWalletAddress("BYBIT:33625378:UTA");
        transaction.setCounterpartyAddress(FIAT_P2P);
        transaction.setTxHash(txHash);
        return transaction;
    }

    private static NormalizedTransaction fundFiatWithdraw(String valueUsd, String txHash) {
        NormalizedTransaction transaction = fiatExternalTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                valueUsd
        );
        transaction.setWalletAddress("BYBIT:33625378:FUND");
        transaction.setCounterpartyAddress(FIAT_P2P);
        transaction.setTxHash(txHash);
        return transaction;
    }

    private static NormalizedTransaction fundDeposit(String counterparty, String valueUsd, String txHash) {
        NormalizedTransaction transaction = externalTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                counterparty,
                valueUsd
        );
        transaction.setWalletAddress("BYBIT:33625378:FUND");
        transaction.setCounterpartyAddress(counterparty);
        transaction.setTxHash(txHash);
        return transaction;
    }

    private static NormalizedTransaction fundWithdraw(String counterparty, String valueUsd, String txHash) {
        NormalizedTransaction transaction = externalTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                counterparty,
                valueUsd
        );
        transaction.setWalletAddress("BYBIT:33625378:FUND");
        transaction.setCounterpartyAddress(counterparty);
        transaction.setTxHash(txHash);
        return transaction;
    }

    private static NormalizedTransaction fiatExternalTransfer(
            NormalizedTransactionType type,
            String valueUsd
    ) {
        return externalTransfer(type, FIAT_P2P, valueUsd);
    }

    private static NormalizedTransaction externalIn(String counterparty, String valueUsd) {
        return externalTransfer(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, counterparty, valueUsd);
    }

    private static NormalizedTransaction externalOut(String counterparty, String valueUsd) {
        return externalTransfer(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, counterparty, valueUsd);
    }

    private static NormalizedTransaction externalTransfer(
            NormalizedTransactionType type,
            String counterparty,
            String valueUsd
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
        transaction.setNetworkId(NetworkId.ETHEREUM);
        transaction.setCounterpartyAddress(counterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(counterparty);
        flow.setValueUsd(new BigDecimal(valueUsd));
        flow.setQuantityDelta(new BigDecimal(valueUsd));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private static CounterpartyBasisPool externalPool(String address, String lifetimeIn, String lifetimeOut) {
        CounterpartyBasisPool pool = new CounterpartyBasisPool();
        pool.setCounterpartyAddress(address);
        pool.setNetworkId(NetworkId.ETHEREUM);
        pool.setIsMemberAtLastTouch(false);
        pool.setLifetimeInBasisUsd(new BigDecimal(lifetimeIn));
        pool.setLifetimeOutBasisUsd(new BigDecimal(lifetimeOut));
        pool.setQtyHeld(BigDecimal.ZERO);
        return pool;
    }

    private static CounterpartyBasisPool memberPoolNoBackfill(String address, String qtyHeld, String avco) {
        CounterpartyBasisPool pool = new CounterpartyBasisPool();
        pool.setCounterpartyAddress(address);
        pool.setNetworkId(NetworkId.SOLANA);
        pool.setIsMemberAtLastTouch(true);
        pool.setQtyHeld(new BigDecimal(qtyHeld));
        pool.setAvcoUsd(new BigDecimal(avco));
        pool.setLifetimeInBasisUsd(BigDecimal.ZERO);
        pool.setLifetimeOutBasisUsd(BigDecimal.ZERO);
        return pool;
    }

    private static AccountingUniverse universeWithSolanaMember() {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("u1");
        AccountingUniverse.Member solana = new AccountingUniverse.Member();
        solana.setRef("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG");
        solana.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        solana.setBackfillEnabled(false);
        solana.setNetworks(List.of(NetworkId.SOLANA));
        universe.setMembers(List.of(solana));
        return universe;
    }

    private static AccountingUniverse universeWithVenue() {
        AccountingUniverse universe = universeWithFundAndEvm();
        AccountingUniverse.Member venue = new AccountingUniverse.Member();
        venue.setRef("0xparadexdeposit");
        venue.setType(AccountingUniverse.MemberType.EXTERNAL_VENUE);
        venue.setProvider("PARADEX");
        venue.setBackfillEnabled(false);
        venue.setNetworks(List.of(NetworkId.ETHEREUM));
        java.util.ArrayList<AccountingUniverse.Member> merged = new java.util.ArrayList<>(universe.getMembers());
        merged.add(venue);
        universe.setMembers(merged);
        return universe;
    }

    private static AccountingUniverse universeWithFundAndEvm() {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("u1");
        AccountingUniverse.Member bybit = new AccountingUniverse.Member();
        bybit.setRef("BYBIT:33625378");
        bybit.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        bybit.setProvider("BYBIT");
        AccountingUniverse.Member evm = new AccountingUniverse.Member();
        evm.setRef("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        evm.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        evm.setNetworks(List.of(NetworkId.ETHEREUM));
        universe.setMembers(List.of(bybit, evm));
        return universe;
    }

    private static com.walletradar.costbasis.domain.BorrowLiability openLiability(String marketValueBasis) {
        com.walletradar.costbasis.domain.BorrowLiability liability = new com.walletradar.costbasis.domain.BorrowLiability();
        liability.setQtyOpen(BigDecimal.ONE);
        liability.setPortfolioAvcoAtOpen(new BigDecimal(marketValueBasis));
        return liability;
    }
}
