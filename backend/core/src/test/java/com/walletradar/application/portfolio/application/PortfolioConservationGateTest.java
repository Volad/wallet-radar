package com.walletradar.application.portfolio.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.walletradar.application.costbasis.application.ReplayToleranceProperties;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.persistence.HistoricalPriceCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private HistoricalPriceCacheService historicalPriceCacheService;

    private PortfolioConservationGate gate;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        gate = new PortfolioConservationGate(
                counterpartyBasisPoolRepository,
                borrowLiabilityRepository,
                accountingUniverseRepository,
                mongoOperations,
                historicalPriceCacheService,
                new ReplayToleranceProperties()
        );
        lenient().when(historicalPriceCacheService.findCanonicalQuote(any(), any(), any()))
                .thenReturn(Optional.empty());
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
    void fundDepositStablecoinFromNonMemberCounterpartyCountsInNecInflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundDeposit("0xexternalhotwallet0000000000000000000001", "1000", "hash-a"),
                        fundDeposit("0xexternalhotwallet0000000000000000000002", "500", "hash-b")
                ))
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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
    void fundDepositNonStablecoinFromNonMemberCounterpartyExcludedFromNecInflow() {
        // RC1 is encoded in externalCapitalBoundary: BybitVenueDescriptor.isEligibleInflowAsset
        // returns false for MNT/SOL/etc., so CexBoundaryContractStamper does NOT stamp INFLOW.
        // The gate queries by externalCapitalBoundary=INFLOW; non-stablecoin txs are not found.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW (MNT deposit not stamped → not in query result)
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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
    void fundDepositFromUniverseMemberCounterpartyExcluded() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(
                        fundDeposit("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "900", "hash-own-evm")
                ))
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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
                .thenReturn(List.of(fundDeposit("0xexternalhotwallet0000000000000000000004", "1000", "hash-in")))   // CEX INFLOW
                .thenReturn(List.of(fundWithdraw("0xexternalhotwallet0000000000000000000005", "200", "hash-out"))) // CEX OUTFLOW
                .thenReturn(List.of()); // EVM capital flows
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

    // ── Fix A: on-chain EVM external transfer flows ──────────────────────────────

    @Test
    void evmExternalTransferInFromNonUniverseCounterpartyAddedToNecInflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(fundDeposit("0xexternalhotwallet0000000000000000000006", "500", "hash-fund"))) // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmExternalIn(EVM_WALLET_A, "0xdeadbeefdeadbeefdeadbeefdeadbeef00000001", "700", "hash-evm-in-a"),
                        evmExternalIn(EVM_WALLET_B, "0xdeadbeefdeadbeefdeadbeefdeadbeef00000002", "300", "hash-evm-in-b")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // CEX INFLOW 500 + EVM wallet A 700 + EVM wallet B 300 = 1500
        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("1500");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("1500");
    }

    @Test
    void evmExternalTransferOutToNonUniverseCounterpartyAddedToNecOutflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows: 400 in, 250 out → net 150
                        evmExternalIn(EVM_WALLET_A, "0xdeadbeefdeadbeefdeadbeefdeadbeef00000003", "400", "hash-evm-in"),
                        evmExternalOut(EVM_WALLET_A, "0xdeadbeefdeadbeefdeadbeefdeadbeef00000004", "250", "hash-evm-out")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("400");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("150");
    }

    @Test
    void evmExternalTransferWithUniverseMemberCounterpartyExcludedFromNec() {
        // EXTERNAL_TRANSFER between two universe EVM wallets must not be double-counted.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmExternalIn(EVM_WALLET_A, EVM_WALLET_B, "600", "hash-internal-in")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
    }

    // ── Fix B: on-chain EVM bridge flows ─────────────────────────────────────────

    @Test
    void evmBridgeInFromExternalCounterpartyCountsAsNecInflow() {
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmBridgeIn(EVM_WALLET_A, "0x00000000000000000000000000externalbridge", "800", "hash-bridge-in", null)
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("800");
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("800");
    }

    @Test
    void pairedBridgeCorridorBetweenUniverseWalletsExcludedFromNec() {
        // Wallet A bridges to Wallet B — both universe members. correlationId links them.
        // Neither leg should contribute to NEC (carry semantics handle cost basis transfer).
        String corridorId = "corridor-xyz-001";
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows: paired corridor
                        evmBridgeOut(EVM_WALLET_A, "0x00000000000000000000000000bridgerouter01", "900", "hash-bridge-out", corridorId),
                        evmBridgeIn(EVM_WALLET_B, "0x00000000000000000000000000bridgerouter01", "890", "hash-bridge-in", corridorId)
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
        assertThat(result.lifetimeExternalInflowUsd()).isEqualByComparingTo("0");
    }

    @Test
    void unpairedBridgeOutToExternalCountsAsNecOutflow() {
        // BRIDGE_OUT with no matching BRIDGE_IN in the universe → genuine external outflow.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows: only BRIDGE_OUT, no matching BRIDGE_IN
                        evmBridgeOut(EVM_WALLET_A, "0x00000000000000000000000000externalchain", "1100", "hash-bridge-out-ext", "orphan-corr-001")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-1100");
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
        assertThat(gate.conservationThreshold(new BigDecimal("3000")))
                .isEqualByComparingTo("50");
        assertThat(gate.conservationThreshold(new BigDecimal("20000")))
                .isEqualByComparingTo("200");
    }

    // ── Fix 1: MULTI guard only suppresses BRIDGE_IN, not BRIDGE_OUT ─────────────

    @Test
    void bridgeOutWithMultiCounterpartyCountsAsNecOutflow() {
        // BRIDGE_OUT where the router counterparty is "MULTI" must NOT be suppressed —
        // assets departing to a multi-output bridge router are a genuine external outflow.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmBridgeOutWithMultiCp(EVM_WALLET_A, "1200", "hash-bridge-out-multi", "orphan-multi-001")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-1200");
    }

    @Test
    void bridgeInWithMultiCounterpartyStillSkipped() {
        // BRIDGE_IN from a "MULTI" counterparty is ambiguous source — must remain suppressed.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmBridgeInWithMultiCp(EVM_WALLET_A, "800", "hash-bridge-in-multi", null)
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("0");
    }

    @Test
    void bridgeOutEthFlowPricedViaCrossFlowFeePrice() {
        // BRIDGE_OUT where the principal ETH flow has no valueUsd/unitPriceUsd but the
        // sibling FEE flow carries a unit price for the same ETH-family asset.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(   // EVM capital flows
                        evmBridgeOutEthUnpricedWithFee(EVM_WALLET_A, "2", "2500", "hash-bridge-eth-fee", "orphan-eth-001")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // 2 ETH × $2500/ETH (from FEE flow) = $5000 outflow
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-5000");
    }

    // ── Fix 2: Stablecoin normalization ──────────────────────────────────────────

    @Test
    void usdT0BridgeOutFlowRecognizedAsStablecoin() {
        // USD₮0 uses Unicode tether sign ₮ (U+20AE); should normalize to USDT and be
        // valued 1:1 with quantity.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(
                        evmBridgeOutStableSymbol(EVM_WALLET_A, "USD\u20AE0", "493", "hash-usdt0-out", "corr-usdt0-001")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-493");
    }

    @Test
    void vbUsdcExternalTransferOutRecognizedAsStablecoin() {
        // vbUSDC is a vault-prefixed USDC; should strip "vb" prefix → USDC → stablecoin.
        when(counterpartyBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())  // CEX INFLOW
                .thenReturn(List.of())  // CEX OUTFLOW
                .thenReturn(List.of(
                        evmExternalOutStableSymbol(EVM_WALLET_A, "vbUSDC", "0xdeadbeefdeadbeefdeadbeefdeadbeef00000010", "21", "hash-vbusdc-out")
                ));
        when(borrowLiabilityRepository.findByUniverseId("u1")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u1")).thenReturn(Optional.of(universeWithFundAndTwoEvmWallets()));

        PortfolioConservationGate.ConservationResult result = gate.evaluate(inputs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-21");
    }

    // ── Stablecoin normalization unit tests ───────────────────────────────────────

    @Test
    void normalizeStablecoinSymbol_usdT0_normalizesToUsdt() {
        assertThat(PortfolioConservationGate.normalizeStablecoinSymbol("USD\u20AE0")).isEqualTo("USDT");
    }

    @Test
    void normalizeStablecoinSymbol_vbUsdc_normalizesToUsdc() {
        assertThat(PortfolioConservationGate.normalizeStablecoinSymbol("vbUSDC")).isEqualTo("USDC");
    }

    @Test
    void normalizeStablecoinSymbol_aUsdc_normalizesToUsdc() {
        assertThat(PortfolioConservationGate.normalizeStablecoinSymbol("aUSDC")).isEqualTo("USDC");
    }

    @Test
    void normalizeStablecoinSymbol_plainUsdt_unchanged() {
        assertThat(PortfolioConservationGate.normalizeStablecoinSymbol("USDT")).isEqualTo("USDT");
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

    // Two stable EVM member addresses for multi-wallet tests.
    private static final String EVM_WALLET_A = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String EVM_WALLET_B = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbe02";

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
        flow.setAssetSymbol("USDT");
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

    private static com.walletradar.application.costbasis.domain.BorrowLiability openLiability(String marketValueBasis) {
        com.walletradar.application.costbasis.domain.BorrowLiability liability = new com.walletradar.application.costbasis.domain.BorrowLiability();
        liability.setQtyOpen(BigDecimal.ONE);
        liability.setPortfolioAvcoAtOpen(new BigDecimal(marketValueBasis));
        return liability;
    }

    /** Universe with a Bybit FUND account and two EVM on-chain wallets (A + B). */
    private static AccountingUniverse universeWithFundAndTwoEvmWallets() {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("u1");
        AccountingUniverse.Member bybit = new AccountingUniverse.Member();
        bybit.setRef("BYBIT:33625378");
        bybit.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        bybit.setProvider("BYBIT");
        AccountingUniverse.Member evmA = new AccountingUniverse.Member();
        evmA.setRef(EVM_WALLET_A);
        evmA.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        evmA.setNetworks(List.of(NetworkId.ETHEREUM));
        AccountingUniverse.Member evmB = new AccountingUniverse.Member();
        evmB.setRef(EVM_WALLET_B);
        evmB.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        evmB.setNetworks(List.of(NetworkId.ARBITRUM));
        universe.setMembers(List.of(bybit, evmA, evmB));
        return universe;
    }

    private static NormalizedTransaction evmExternalIn(
            String walletAddress, String counterparty, String valueUsd, String txHash
    ) {
        return evmTransaction(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, walletAddress, counterparty, valueUsd, txHash, null);
    }

    private static NormalizedTransaction evmExternalOut(
            String walletAddress, String counterparty, String valueUsd, String txHash
    ) {
        return evmTransaction(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, walletAddress, counterparty, valueUsd, txHash, null);
    }

    private static NormalizedTransaction evmBridgeIn(
            String walletAddress, String counterparty, String valueUsd, String txHash, String correlationId
    ) {
        return evmTransaction(NormalizedTransactionType.BRIDGE_IN, walletAddress, counterparty, valueUsd, txHash, correlationId);
    }

    private static NormalizedTransaction evmBridgeOut(
            String walletAddress, String counterparty, String valueUsd, String txHash, String correlationId
    ) {
        return evmTransaction(NormalizedTransactionType.BRIDGE_OUT, walletAddress, counterparty, valueUsd, txHash, correlationId);
    }

    private static NormalizedTransaction evmTransaction(
            NormalizedTransactionType type,
            String walletAddress,
            String counterparty,
            String valueUsd,
            String txHash,
            String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(type);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress(counterparty);
        tx.setCorrelationId(correlationId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(counterparty);
        flow.setValueUsd(new BigDecimal(valueUsd));
        flow.setQuantityDelta(new BigDecimal(valueUsd));
        tx.setFlows(List.of(flow));
        return tx;
    }

    /** BRIDGE_OUT with counterpartyAddress = "MULTI" on the tx and flow. */
    private static NormalizedTransaction evmBridgeOutWithMultiCp(
            String walletAddress, String valueUsd, String txHash, String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress("MULTI");
        tx.setCorrelationId(correlationId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress("MULTI");
        flow.setValueUsd(new BigDecimal(valueUsd));
        flow.setQuantityDelta(new BigDecimal(valueUsd));
        tx.setFlows(List.of(flow));
        return tx;
    }

    /** BRIDGE_IN with counterpartyAddress = "MULTI" — must still be suppressed. */
    private static NormalizedTransaction evmBridgeInWithMultiCp(
            String walletAddress, String valueUsd, String txHash, String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_IN);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress("MULTI");
        tx.setCorrelationId(correlationId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress("MULTI");
        flow.setValueUsd(new BigDecimal(valueUsd));
        flow.setQuantityDelta(new BigDecimal(valueUsd));
        tx.setFlows(List.of(flow));
        return tx;
    }

    /**
     * BRIDGE_OUT with an unpriced ETH principal flow and a priced FEE flow
     * carrying the unit price for the same ETH-family asset.
     *
     * @param ethQty       quantity of ETH in the principal flow (no valueUsd/unitPriceUsd)
     * @param ethUnitPrice unit price carried by the FEE flow (valueUsd = qty × price)
     */
    private static NormalizedTransaction evmBridgeOutEthUnpricedWithFee(
            String walletAddress, String ethQty, String ethUnitPrice, String txHash, String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress("MULTI");
        tx.setCorrelationId(correlationId);

        // Principal TRANSFER flow: ETH, no pricing
        NormalizedTransaction.Flow principal = new NormalizedTransaction.Flow();
        principal.setRole(NormalizedLegRole.BUY);
        principal.setCounterpartyAddress("MULTI");
        principal.setAssetSymbol("ETH");
        principal.setQuantityDelta(new BigDecimal(ethQty));
        // valueUsd and unitPriceUsd intentionally null

        // FEE flow: ETH, has unit price
        BigDecimal feeQty = new BigDecimal("0.005");
        BigDecimal unitPrice = new BigDecimal(ethUnitPrice);
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(feeQty);
        fee.setUnitPriceUsd(unitPrice);
        fee.setValueUsd(feeQty.multiply(unitPrice));

        tx.setFlows(List.of(principal, fee));
        return tx;
    }

    /** BRIDGE_OUT with a stablecoin symbol (no USD value), for stablecoin normalization tests. */
    private static NormalizedTransaction evmBridgeOutStableSymbol(
            String walletAddress, String symbol, String qty, String txHash, String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress("0x00000000000000000000000000externalbridge");
        tx.setCorrelationId(correlationId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress("0x00000000000000000000000000externalbridge");
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        // valueUsd and unitPriceUsd intentionally null — stablecoin fallback must price it
        tx.setFlows(List.of(flow));
        return tx;
    }

    /** EXTERNAL_TRANSFER_OUT with a vault-prefixed stablecoin symbol (e.g. vbUSDC). */
    private static NormalizedTransaction evmExternalOutStableSymbol(
            String walletAddress, String symbol, String counterparty, String qty, String txHash
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress(counterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(counterparty);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        // valueUsd and unitPriceUsd intentionally null — stablecoin normalization must price it
        tx.setFlows(List.of(flow));
        return tx;
    }

    // ──────────────────────────── T-04: WETH/Mantle NEC pricing fallback ────────────────────────

    @Test
    @DisplayName("WETH BRIDGE_OUT on Mantle with only MNT FEE sibling → ETH price resolved via historical cache")
    void wethBridgeOutOnMantleWithMntFeeUsesHistoricalPriceFallback() {
        String walletAddress = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        Instant txTimestamp = Instant.parse("2024-11-10T12:00:00Z");
        BigDecimal ethUnitPrice = new BigDecimal("2800");

        // Mock: no ETH-family sibling; cache returns ETH price for BINANCE
        when(historicalPriceCacheService.findCanonicalQuote(
                any(Collection.class),
                eq(txTimestamp),
                eq(PriceSource.BINANCE)
        )).thenReturn(Optional.of(new PriceQuote(ethUnitPrice, PriceSource.BINANCE, txTimestamp, "USDT", "cache-id")));

        NormalizedTransaction bridgeOut = wethBridgeOutMantleWithMntFee(walletAddress, "0.5", txTimestamp, "0xmantletx", "external-counterparty");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(bridgeOut));
        when(borrowLiabilityRepository.findByUniverseId("u-mantle")).thenReturn(List.of());
        when(accountingUniverseRepository.findById("u-mantle")).thenReturn(Optional.of(universeWithEvmMember(walletAddress)));
        when(counterpartyBasisPoolRepository.findByUniverseId("u-mantle")).thenReturn(List.of());

        PortfolioConservationGate.ConservationResult result = gate.evaluate(new PortfolioConservationGate.ConservationInputs(
                "u-mantle",
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        ));

        // 0.5 WETH × $2800 = $1400 outflow → NEC = inflow - outflow = 0 - 1400 = -1400
        assertThat(result.netExternalCapitalUsd()).isEqualByComparingTo("-1400");
    }

    /** BRIDGE_OUT with an unpriced WETH principal flow and a MNT FEE sibling (Mantle scenario). */
    private static NormalizedTransaction wethBridgeOutMantleWithMntFee(
            String walletAddress, String wethQty, Instant blockTimestamp, String txHash, String counterparty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setTxHash(txHash);
        tx.setCounterpartyAddress(counterparty);
        tx.setBlockTimestamp(blockTimestamp);

        NormalizedTransaction.Flow wethFlow = new NormalizedTransaction.Flow();
        wethFlow.setRole(NormalizedLegRole.TRANSFER);
        wethFlow.setAssetSymbol("WETH");
        wethFlow.setAssetContract("0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111");
        wethFlow.setQuantityDelta(new BigDecimal(wethQty).negate());
        wethFlow.setCounterpartyAddress(counterparty);
        // No unitPriceUsd or valueUsd — must be resolved via cache fallback

        NormalizedTransaction.Flow mntFee = new NormalizedTransaction.Flow();
        mntFee.setRole(NormalizedLegRole.FEE);
        mntFee.setAssetSymbol("MNT");
        mntFee.setQuantityDelta(new BigDecimal("-0.01"));
        mntFee.setUnitPriceUsd(new BigDecimal("0.75"));

        tx.setFlows(List.of(wethFlow, mntFee));
        return tx;
    }

    private static AccountingUniverse universeWithEvmMember(String walletAddress) {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("u-mantle");
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(walletAddress);
        member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        member.setBackfillEnabled(true);
        universe.setMembers(List.of(member));
        return universe;
    }
}
