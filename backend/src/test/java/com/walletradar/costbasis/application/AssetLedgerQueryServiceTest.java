package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetLedgerQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AssetLedgerPointRepository assetLedgerPointRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private MongoOperations mongoOperations;

    @Test
    void sessionFamilyLedgerAggregatesWalletLevelPointsIntoOneTimeline() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet walletA = new UserSession.SessionWallet();
        walletA.setAddress("wallet-a");
        walletA.setNetworks(List.of(NetworkId.BASE));
        UserSession.SessionWallet walletB = new UserSession.SessionWallet();
        walletB.setAddress("wallet-b");
        walletB.setNetworks(List.of(NetworkId.ARBITRUM));
        session.setWallets(List.of(walletA, walletB));

        AssetLedgerPoint buy = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );
        AssetLedgerPoint bridgeOut = point(
                "2",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.SOURCE,
                "-1",
                "-100",
                "0",
                "0",
                "0",
                "0"
        );
        AssetLedgerPoint bridgeIn = point(
                "2",
                "wallet-b",
                NetworkId.ARBITRUM,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.BRIDGE,
                AssetLedgerPoint.LifecycleStage.DESTINATION,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );
        AssetLedgerPoint bybitBuy = point(
                "0",
                "BYBIT:33625378",
                null,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1.5",
                "3000",
                "0",
                "0",
                "1.5",
                "3000"
        );
        bybitBuy.setTxHash(null);
        bybitBuy.setProtocolName("Bybit");
        bybitBuy.setTransactionIndex(0);
        buy.setBlockTimestamp(Instant.parse("2026-04-05T10:10:00Z"));
        bridgeOut.setBlockTimestamp(Instant.parse("2026-04-05T10:20:00Z"));
        bridgeIn.setBlockTimestamp(Instant.parse("2026-04-05T10:20:01Z"));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("wallet-a", "wallet-b", "BYBIT:33625378"),
                List.of("wallet-a", "wallet-b")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-1",
                "FAMILY:ETH"
        )).thenReturn(List.of(bybitBuy, buy, bridgeOut, bridgeIn));
        when(normalizedTransactionRepository.findAllById(List.of("0", "1", "2")))
                .thenReturn(List.of(
                        normalized("0", "Bybit", "ETH", "BUY", "1.5", "2000"),
                        normalized("1", "Coinbase", "ETH", "BUY", "1", "100"),
                        normalized("2", "Across", "ETH", "TRANSFER", "-1", null)
                ));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "0"),
                balance("wallet-b", NetworkId.ARBITRUM, "ETH", "1")
        ));

        AssetLedgerQueryService service = new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                mongoOperations
        );
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-1", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(3);
        assertThat(view.timeline().get(0).protocolName()).isEqualTo("Bybit");
        assertThat(view.timeline().get(0).quantityAfter()).isEqualByComparingTo("1.5");
        assertThat(view.timeline().get(0).coveredQuantityAfter()).isEqualByComparingTo("1.5");
        assertThat(view.timeline().get(0).uncoveredQuantityAfter()).isZero();
        assertThat(view.timeline().get(2).quantityAfter()).isEqualByComparingTo("2.5");
        assertThat(view.timeline().get(2).coveredQuantityAfter()).isEqualByComparingTo("2.5");
        assertThat(view.timeline().get(2).uncoveredQuantityAfter()).isZero();
        assertThat(view.timeline().get(2).totalCostBasisAfterUsd()).isEqualByComparingTo("3100");
        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().coveredQuantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredQuantity()).isZero();
        assertThat(view.current().totalCostBasisUsd()).isEqualByComparingTo("100");
        assertThat(view.current().avcoUsd()).isEqualByComparingTo("100");
        assertThat(view.current().uncoveredBuckets()).isEmpty();
        assertThat(view.events()).hasSize(3);
        assertThat(view.events().get(0).protocolName()).isEqualTo("Bybit");
        assertThat(view.ledgerPoints()).hasSize(4);
    }

    @Test
    void sessionFamilyLedgerFallsBackToCurrentSessionWalletsWhenUniverseIsAbsent() {
        UserSession session = new UserSession();
        session.setId("session-2");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint buy = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                AssetLedgerPoint.LifecycleKind.SPOT,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "100",
                "0",
                "0",
                "1",
                "100"
        );

        when(userSessionRepository.findById("session-2")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-2",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-2",
                "FAMILY:ETH"
        )).thenReturn(List.of(buy));
        when(normalizedTransactionRepository.findAllById(List.of("1")))
                .thenReturn(List.of(normalized("1", "Coinbase", "ETH", "BUY", "1", "100")));
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(
                balance("wallet-a", NetworkId.BASE, "ETH", "1")
        ));

        AssetLedgerQueryService service = new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                mongoOperations
        );
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-2", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.timeline()).hasSize(1);
        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredBuckets()).isEmpty();
    }

    @Test
    void sessionFamilyLedgerExposesUncoveredBucketDiagnosticsForCurrentState() {
        UserSession session = new UserSession();
        session.setId("session-3");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint wrapPoint = point(
                "1",
                "wallet-a",
                NetworkId.BASE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                AssetLedgerPoint.LifecycleKind.WRAP,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "1",
                "0",
                "0",
                "0",
                "1",
                "0"
        );
        wrapPoint.setAssetSymbol("WETH");
        wrapPoint.setAssetContract("0x4200000000000000000000000000000000000006");
        wrapPoint.setAccountingAssetIdentity("0x4200000000000000000000000000000000000006");
        wrapPoint.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        wrapPoint.setUncoveredQuantityAfter(new BigDecimal("1"));
        wrapPoint.setHasIncompleteHistoryAfter(true);
        wrapPoint.setHasUnresolvedFlagsAfter(true);
        wrapPoint.setUnresolvedFlagCountAfter(2);

        when(userSessionRepository.findById("session-3")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-3",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-3",
                "FAMILY:ETH"
        )).thenReturn(List.of(wrapPoint));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        OnChainBalance wethBalance = balance("wallet-a", NetworkId.BASE, "WETH", "1");
        wethBalance.setAssetContract("0x4200000000000000000000000000000000000006");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(wethBalance));

        AssetLedgerQueryService service = new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                mongoOperations
        );
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-3", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().quantity()).isEqualByComparingTo("1");
        assertThat(view.current().coveredQuantity()).isZero();
        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("1");
        assertThat(view.current().uncoveredBuckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.assetSymbol()).isEqualTo("WETH");
            assertThat(bucket.uncoveredReason()).isEqualTo("coverage_gap");
            assertThat(bucket.latestNormalizedType()).isEqualTo("BRIDGE");
            assertThat(bucket.latestBasisEffect()).isEqualTo("CARRY_IN");
            assertThat(bucket.hasIncompleteHistory()).isTrue();
            assertThat(bucket.hasUnresolvedFlags()).isTrue();
            assertThat(bucket.unresolvedFlagCount()).isEqualTo(2);
        });
    }

    @Test
    void sessionFamilyLedgerLabelsInterestBearingCurrentDriftAsYieldAccrual() {
        UserSession session = new UserSession();
        session.setId("session-4");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("wallet-a");
        wallet.setNetworks(List.of(NetworkId.MANTLE));
        session.setWallets(List.of(wallet));

        AssetLedgerPoint lendingPoint = point(
                "1",
                "wallet-a",
                NetworkId.MANTLE,
                "FAMILY:ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                AssetLedgerPoint.LifecycleKind.LENDING,
                AssetLedgerPoint.LifecycleStage.SINGLE,
                "3.06",
                "6058.10",
                "0",
                "0",
                "3.06",
                "6058.10"
        );
        lendingPoint.setAssetSymbol("aManWETH");
        lendingPoint.setAssetContract("0xea00000000000000000000000000000000000000");
        lendingPoint.setAccountingAssetIdentity("0xea00000000000000000000000000000000000000");
        lendingPoint.setNormalizedType("LENDING_DEPOSIT");
        lendingPoint.setProtocolName("Aave");
        lendingPoint.setBasisBackedQuantityAfter(new BigDecimal("3.06"));
        lendingPoint.setUncoveredQuantityAfter(BigDecimal.ZERO);
        lendingPoint.setHasIncompleteHistoryAfter(false);
        lendingPoint.setHasUnresolvedFlagsAfter(false);
        lendingPoint.setUnresolvedFlagCountAfter(0);

        when(userSessionRepository.findById("session-4")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-4",
                List.of("wallet-a"),
                List.of("wallet-a")
        ));
        when(assetLedgerPointRepository.findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
                "ACCOUNTING_UNIVERSE:session-4",
                "FAMILY:ETH"
        )).thenReturn(List.of(lendingPoint));
        when(normalizedTransactionRepository.findAllById(List.of("1"))).thenReturn(List.of());
        OnChainBalance balance = balance("wallet-a", NetworkId.MANTLE, "aManWETH", "3.066185599746344040");
        balance.setAssetContract("0xea00000000000000000000000000000000000000");
        when(mongoOperations.find(any(), eq(OnChainBalance.class))).thenReturn(List.of(balance));

        AssetLedgerQueryService service = new AssetLedgerQueryService(
                userSessionRepository,
                assetLedgerPointRepository,
                normalizedTransactionRepository,
                accountingUniverseService,
                mongoOperations
        );
        AssetLedgerQueryService.SessionAssetLedgerView view = service.findSessionFamilyLedger("session-4", "FAMILY:ETH")
                .orElseThrow();

        assertThat(view.current().uncoveredQuantity()).isEqualByComparingTo("0.006185599746344040");
        assertThat(view.current().uncoveredBuckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.assetSymbol()).isEqualTo("aManWETH");
            assertThat(bucket.uncoveredReason()).isEqualTo("yield_accrual");
            assertThat(bucket.latestNormalizedType()).isEqualTo("LENDING_DEPOSIT");
            assertThat(bucket.latestBasisEffect()).isEqualTo("REALLOCATE_IN");
            assertThat(bucket.hasIncompleteHistory()).isFalse();
            assertThat(bucket.hasUnresolvedFlags()).isFalse();
        });
    }

    private NormalizedTransaction normalized(
            String id,
            String protocolName,
            String symbol,
            String role,
            String quantityDelta,
            String unitPriceUsd
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setProtocolName(protocolName);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.valueOf(role));
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private AssetLedgerPoint point(
            String normalizedTransactionId,
            String walletAddress,
            NetworkId networkId,
            String familyIdentity,
            AssetLedgerPoint.BasisEffect basisEffect,
            AssetLedgerPoint.LifecycleKind lifecycleKind,
            AssetLedgerPoint.LifecycleStage lifecycleStage,
            String quantityDelta,
            String costBasisDeltaUsd,
            String realisedPnlDeltaUsd,
            String gasDeltaUsd,
            String quantityAfter,
            String totalCostBasisAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(normalizedTransactionId + ":" + walletAddress + ":" + Objects.toString(networkId, "BYBIT"));
        point.setWalletAddress(walletAddress);
        point.setNetworkId(networkId);
        point.setAccountingAssetIdentity(networkId == null ? "BYBIT:ETH" : "NATIVE:" + networkId.name());
        point.setAccountingFamilyIdentity(familyIdentity);
        point.setFamilyDisplaySymbol("ETH");
        point.setAssetSymbol("ETH");
        point.setAssetContract(networkId == null ? "BYBIT:ETH" : "NATIVE:" + networkId.name());
        point.setNormalizedTransactionId(normalizedTransactionId);
        point.setTxHash("0x" + normalizedTransactionId);
        point.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        point.setReplaySequence(Long.parseLong(normalizedTransactionId));
        point.setTransactionIndex(0);
        point.setNormalizedType("BRIDGE");
        point.setLifecycleKind(lifecycleKind);
        point.setLifecycleStage(lifecycleStage);
        point.setBasisEffect(basisEffect);
        point.setProtocolName("Coinbase");
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setCostBasisDeltaUsd(new BigDecimal(costBasisDeltaUsd));
        point.setRealisedPnlDeltaUsd(new BigDecimal(realisedPnlDeltaUsd));
        point.setGasDeltaUsd(new BigDecimal(gasDeltaUsd));
        point.setQuantityAfter(new BigDecimal(quantityAfter));
        point.setTotalCostBasisAfterUsd(new BigDecimal(totalCostBasisAfterUsd));
        point.setBasisBackedQuantityAfter(new BigDecimal(quantityAfter));
        if (new BigDecimal(quantityAfter).signum() > 0) {
            point.setAvcoAfterUsd(new BigDecimal(totalCostBasisAfterUsd).divide(new BigDecimal(quantityAfter), java.math.MathContext.DECIMAL128));
        }
        point.setUncoveredQuantityDelta(BigDecimal.ZERO);
        point.setQuantityShortfallAfter(BigDecimal.ZERO);
        point.setUncoveredQuantityAfter(BigDecimal.ZERO);
        return point;
    }

    private OnChainBalance balance(
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String quantity
    ) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(walletAddress + ":" + networkId + ":" + assetSymbol);
        balance.setWalletAddress(walletAddress);
        balance.setNetworkId(networkId);
        balance.setAssetSymbol(assetSymbol);
        balance.setAssetContract("NATIVE:" + networkId.name());
        balance.setQuantity(new BigDecimal(quantity));
        balance.setCapturedAt(Instant.parse("2026-04-05T10:30:00Z"));
        return balance;
    }
}
