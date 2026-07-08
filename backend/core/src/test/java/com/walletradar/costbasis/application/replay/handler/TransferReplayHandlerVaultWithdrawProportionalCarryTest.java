package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * RC-3 — VAULT_WITHDRAW proportional carry in {@code TransferReplayHandler.restoreFullBucket()}.
 *
 * <p>For a 1:1-denomination vault (vault shares ≈ underlying units), the wrapper composite
 * bucket accumulates carry in the underlying quantity. When the vault returns slightly fewer
 * tokens (vault fee) or slightly more (yield), {@code restoreFullBucket} must:
 * <ul>
 *   <li>Vault fee path: apply proportional basis = deposited_basis × returned/deposited</li>
 *   <li>Yield path: cap ratio at 1.0 so excess qty enters at zero-cost</li>
 *   <li>Dust path: if |returned − deposited| / deposited &lt; 0.01%, use full carry</li>
 * </ul>
 */
class TransferReplayHandlerVaultWithdrawProportionalCarryTest {

    /**
     * Non-family vault receipt token contract — must not appear in any FAMILY:* mapping.
     * The 1:1 share scale means one receipt unit ≈ one underlying unit.
     */
    private static final String VAULT_RECEIPT_CONTRACT = "0x0000000000000000000000000000000000abcdef";
    private static final String USDC_CONTRACT = "0x0000000000000000000000000000000000000001";
    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;
    private ReplayPendingTransferKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine(null);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        var marketAuthority = mock(ReplayMarketAuthority.class);
        handler = TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now()));
    }

    /**
     * Vault fee scenario: 1,000 USDC deposited at basis $1,249 → 998 USDC returned.
     * Expected: restored basis ≈ $1,249 × 998/1000 ≈ $1,246.50; AVCO ≈ $1.249.
     */
    @Test
    @DisplayName("VAULT_WITHDRAW(998 from 1000) → proportional carry, basis ≈ $1,246.50, AVCO ≈ $1.249")
    void vaultFeeReducesCarryProportionally() {
        // Deposit 1,000 USDC (basis $1,249) into vault → receipt +1,000 shares (1:1 scale).
        NormalizedTransaction deposit = vaultDeposit("1000", "1000");

        // Seed USDC position with 1,000 qty and $1,249 basis.
        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("1000"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("1249"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(new BigDecimal("1.249"));

        // Run deposit: USDC outbound puts carry into wrapper bucket; receipt inbound consumes it.
        AssetKey receiptKey = new AssetKey(WALLET, NetworkId.AVALANCHE, VAULT_RECEIPT_CONTRACT, "vaultUSDC", VAULT_RECEIPT_CONTRACT);
        PositionState receiptPos = replayState.position(receiptKey);
        handler.applyTransfer(deposit, deposit.getFlows().get(0), 0, usdcPos, replayState);
        handler.applyTransfer(deposit, deposit.getFlows().get(1), 1, receiptPos, replayState);

        // Withdraw 998 USDC (vault fee).
        NormalizedTransaction withdraw = vaultWithdraw("1000", "998");
        AssetKey usdcReturnKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState returnedUsdcPos = replayState.position(usdcReturnKey);

        handler.applyTransfer(withdraw, withdraw.getFlows().get(0), 0, receiptPos, replayState);
        handler.applyTransfer(withdraw, withdraw.getFlows().get(1), 1, returnedUsdcPos, replayState);

        assertThat(returnedUsdcPos.totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1246.502"), within(new BigDecimal("0.01")));
        BigDecimal avco = returnedUsdcPos.totalCostBasisUsd()
                .divide(returnedUsdcPos.quantity(), java.math.MathContext.DECIMAL64);
        assertThat(avco).isCloseTo(new BigDecimal("1.249"), within(new BigDecimal("0.001")));
    }

    /**
     * Yield scenario: 1,000 USDC deposited at basis $1,249 → 1,010 USDC returned (yield).
     * The ratio cap at 1.0 means full basis $1,249 is restored; excess 10 USDC enters at $0 cost.
     */
    @Test
    @DisplayName("VAULT_WITHDRAW(1010 from 1000 = yield) → ratio capped at 1.0, full basis $1,249 restored")
    void vaultYieldCapsRatioAtOne() {
        NormalizedTransaction deposit = vaultDeposit("1000", "1000");

        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("1000"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("1249"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(new BigDecimal("1.249"));

        AssetKey receiptKey = new AssetKey(WALLET, NetworkId.AVALANCHE, VAULT_RECEIPT_CONTRACT, "vaultUSDC", VAULT_RECEIPT_CONTRACT);
        PositionState receiptPos = replayState.position(receiptKey);
        handler.applyTransfer(deposit, deposit.getFlows().get(0), 0, usdcPos, replayState);
        handler.applyTransfer(deposit, deposit.getFlows().get(1), 1, receiptPos, replayState);

        // Withdraw 1,010 USDC (1% yield above deposited).
        NormalizedTransaction withdraw = vaultWithdraw("1000", "1010");
        PositionState returnedUsdcPos = replayState.position(
                new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT));

        handler.applyTransfer(withdraw, withdraw.getFlows().get(0), 0, receiptPos, replayState);
        handler.applyTransfer(withdraw, withdraw.getFlows().get(1), 1, returnedUsdcPos, replayState);

        // Full deposited basis is restored (cap at 1.0 prevents overflow).
        assertThat(returnedUsdcPos.totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1249"), within(new BigDecimal("0.01")));
    }

    /**
     * Dust tolerance scenario: 1,000.0001 USDC returned from 1,000 deposited.
     * Relative difference = 0.00001% &lt; 0.01% tolerance → full basis $1,249 restored.
     */
    @Test
    @DisplayName("VAULT_WITHDRAW(1000.0001 from 1000) → within 0.01% tolerance, full basis restored")
    void dustDifferenceUsesFullCarryWithinTolerance() {
        NormalizedTransaction deposit = vaultDeposit("1000", "1000");

        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("1000"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("1249"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(new BigDecimal("1.249"));

        AssetKey receiptKey = new AssetKey(WALLET, NetworkId.AVALANCHE, VAULT_RECEIPT_CONTRACT, "vaultUSDC", VAULT_RECEIPT_CONTRACT);
        PositionState receiptPos = replayState.position(receiptKey);
        handler.applyTransfer(deposit, deposit.getFlows().get(0), 0, usdcPos, replayState);
        handler.applyTransfer(deposit, deposit.getFlows().get(1), 1, receiptPos, replayState);

        // Withdraw 1,000.0001 USDC — 0.00001% difference is well within 0.01% tolerance.
        NormalizedTransaction withdraw = vaultWithdraw("1000", "1000.0001");
        PositionState returnedUsdcPos = replayState.position(
                new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT));

        handler.applyTransfer(withdraw, withdraw.getFlows().get(0), 0, receiptPos, replayState);
        handler.applyTransfer(withdraw, withdraw.getFlows().get(1), 1, returnedUsdcPos, replayState);

        // Full deposited basis is restored — dust difference is within tolerance.
        assertThat(returnedUsdcPos.totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1249"), within(new BigDecimal("0.01")));
    }

    /**
     * Cross-scale vault scenario (non-FAMILY receipt token): 998.84 USDC deposited → 926.43
     * vault shares received. On withdrawal: 926.43 vault shares burnt, 926.43 USDC returned
     * as TRANSFER (principal) + 73.05 USDC as BUY (yield). Total returned USD ≈ $999.48,
     * deposited basis = $998.84.
     *
     * <p>Expected:
     * <ul>
     *   <li>USDC TRANSFER (926.43 USDC @ $1 = $926.43):
     *       costBasis = $998.84 × min($999.48/$998.84,1) × ($926.43/$999.48) ≈ $925.79 → AVCO ≈ $1.00</li>
     * </ul>
     */
    @Test
    @DisplayName("cross-scale vault VAULT_WITHDRAW with TRANSFER principal + BUY yield → USDC AVCO ≈ $1.00")
    void vaultWithdrawWithBuyYieldFlow_principalTransferAvcoIsAtPeg() {
        // Use the same non-FAMILY receipt contract from existing tests
        String vaultReceiptContract = VAULT_RECEIPT_CONTRACT;

        // Deposit: 998.84 USDC (basis $998.84) → 926.43 vault shares
        NormalizedTransaction deposit = vaultDepositCrossScale(
                "998.84", "926.43", vaultReceiptContract);

        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("998.84"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("998.84"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(BigDecimal.ONE);

        AssetKey receiptKey = new AssetKey(WALLET, NetworkId.AVALANCHE, vaultReceiptContract, "vaultUSDC", vaultReceiptContract);
        PositionState receiptPos = replayState.position(receiptKey);

        // Run deposit: USDC outbound → bucket; receipt inbound → restores from bucket
        handler.applyTransfer(deposit, deposit.getFlows().get(0), 0, usdcPos, replayState);
        handler.applyTransfer(deposit, deposit.getFlows().get(1), 1, receiptPos, replayState);

        // Withdraw: 926.43 vault shares burnt, 926.43 USDC TRANSFER + 73.05 USDC BUY
        NormalizedTransaction withdraw = vaultWithdrawMixedYield(
                "926.43", vaultReceiptContract, "926.43", "73.05");

        AssetKey usdcReturnKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState returnedUsdcPos = replayState.position(usdcReturnKey);

        // vault receipt outbound → drain to bucket; USDC TRANSFER inbound → restoreFullBucket (USD-proportional)
        handler.applyTransfer(withdraw, withdraw.getFlows().get(0), 0, receiptPos, replayState);
        handler.applyTransfer(withdraw, withdraw.getFlows().get(1), 1, returnedUsdcPos, replayState);

        // USDC AVCO must be ≈ $1.00 (peg), not $1.078 (the inflated pre-fix value)
        BigDecimal basis = returnedUsdcPos.totalCostBasisUsd();
        BigDecimal qty   = returnedUsdcPos.quantity();
        assertThat(basis).isNotNull();
        assertThat(qty).isNotNull().isPositive();
        BigDecimal avco = basis.divide(qty, java.math.MathContext.DECIMAL64);
        assertThat(avco).isCloseTo(BigDecimal.ONE, within(new BigDecimal("0.01")));
    }

    /**
     * Cross-scale deposit: underlying qty ≠ receipt qty (e.g. 998.84 USDC → 926.43 vault shares).
     * Uses a non-FAMILY-mapped symbol ("vaultUSDC") so the wrapper composite bucket path fires.
     */
    private NormalizedTransaction vaultDepositCrossScale(
            String usdcQty, String receiptQty, String receiptContract) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("vault-deposit-xscale-" + usdcQty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.VAULT_DEPOSIT);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        tx.setContinuityCandidate(false);

        NormalizedTransaction.Flow usdcOut = new NormalizedTransaction.Flow();
        usdcOut.setRole(NormalizedLegRole.TRANSFER);
        usdcOut.setAssetSymbol("USDC");
        usdcOut.setAssetContract(USDC_CONTRACT);
        usdcOut.setQuantityDelta(new BigDecimal(usdcQty).negate());
        usdcOut.setUnitPriceUsd(BigDecimal.ONE);
        usdcOut.setValueUsd(new BigDecimal(usdcQty));

        NormalizedTransaction.Flow receiptIn = new NormalizedTransaction.Flow();
        receiptIn.setRole(NormalizedLegRole.TRANSFER);
        receiptIn.setAssetSymbol("vaultUSDC");
        receiptIn.setAssetContract(receiptContract);
        receiptIn.setQuantityDelta(new BigDecimal(receiptQty));

        tx.setFlows(new ArrayList<>(List.of(usdcOut, receiptIn)));
        return tx;
    }

    /**
     * Creates a VAULT_WITHDRAW with three flows:
     * <ul>
     *   <li>Flow 0: vault receipt out (TRANSFER, negative, "vaultUSDC").</li>
     *   <li>Flow 1: USDC principal return (TRANSFER, positive, priced at $1).</li>
     *   <li>Flow 2: USDC yield (BUY, positive, priced at $1).</li>
     * </ul>
     */
    private NormalizedTransaction vaultWithdrawMixedYield(
            String receiptQty, String receiptContract,
            String usdcPrincipalQty, String usdcYieldQty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("vault-withdraw-mixed-" + receiptQty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.VAULT_WITHDRAW);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T11:00:00Z"));
        tx.setContinuityCandidate(false);

        NormalizedTransaction.Flow receiptOut = new NormalizedTransaction.Flow();
        receiptOut.setRole(NormalizedLegRole.TRANSFER);
        receiptOut.setAssetSymbol("vaultUSDC");
        receiptOut.setAssetContract(receiptContract);
        receiptOut.setQuantityDelta(new BigDecimal(receiptQty).negate());

        NormalizedTransaction.Flow usdcTransferIn = new NormalizedTransaction.Flow();
        usdcTransferIn.setRole(NormalizedLegRole.TRANSFER);
        usdcTransferIn.setAssetSymbol("USDC");
        usdcTransferIn.setAssetContract(USDC_CONTRACT);
        usdcTransferIn.setQuantityDelta(new BigDecimal(usdcPrincipalQty));
        usdcTransferIn.setUnitPriceUsd(BigDecimal.ONE);
        usdcTransferIn.setValueUsd(new BigDecimal(usdcPrincipalQty));

        NormalizedTransaction.Flow usdcBuyIn = new NormalizedTransaction.Flow();
        usdcBuyIn.setRole(NormalizedLegRole.BUY);
        usdcBuyIn.setAssetSymbol("USDC");
        usdcBuyIn.setAssetContract(USDC_CONTRACT);
        usdcBuyIn.setQuantityDelta(new BigDecimal(usdcYieldQty));
        usdcBuyIn.setUnitPriceUsd(BigDecimal.ONE);
        usdcBuyIn.setValueUsd(new BigDecimal(usdcYieldQty));

        tx.setFlows(new ArrayList<>(List.of(receiptOut, usdcTransferIn, usdcBuyIn)));
        return tx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a VAULT_DEPOSIT transaction with:
     * <ul>
     *   <li>Flow 0: USDC outbound (negative).</li>
     *   <li>Flow 1: vault receipt inbound (positive) — non-family, same scale as USDC.</li>
     * </ul>
     */
    private NormalizedTransaction vaultDeposit(String usdcQty, String receiptQty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("vault-deposit-" + usdcQty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.VAULT_DEPOSIT);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        tx.setContinuityCandidate(false);

        NormalizedTransaction.Flow usdcOut = new NormalizedTransaction.Flow();
        usdcOut.setRole(NormalizedLegRole.TRANSFER);
        usdcOut.setAssetSymbol("USDC");
        usdcOut.setAssetContract(USDC_CONTRACT);
        usdcOut.setQuantityDelta(new BigDecimal(usdcQty).negate());

        NormalizedTransaction.Flow receiptIn = new NormalizedTransaction.Flow();
        receiptIn.setRole(NormalizedLegRole.TRANSFER);
        receiptIn.setAssetSymbol("vaultUSDC");
        receiptIn.setAssetContract(VAULT_RECEIPT_CONTRACT);
        receiptIn.setQuantityDelta(new BigDecimal(receiptQty));

        tx.setFlows(new ArrayList<>(List.of(usdcOut, receiptIn)));
        return tx;
    }

    /**
     * Creates a VAULT_WITHDRAW transaction with:
     * <ul>
     *   <li>Flow 0: vault receipt outbound (negative) — same non-family contract.</li>
     *   <li>Flow 1: USDC inbound (positive) — possibly fewer/more than deposited.</li>
     * </ul>
     */
    private NormalizedTransaction vaultWithdraw(String receiptQty, String usdcReturnedQty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("vault-withdraw-" + receiptQty + "-" + usdcReturnedQty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.VAULT_WITHDRAW);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T11:00:00Z"));
        tx.setContinuityCandidate(false);

        NormalizedTransaction.Flow receiptOut = new NormalizedTransaction.Flow();
        receiptOut.setRole(NormalizedLegRole.TRANSFER);
        receiptOut.setAssetSymbol("vaultUSDC");
        receiptOut.setAssetContract(VAULT_RECEIPT_CONTRACT);
        receiptOut.setQuantityDelta(new BigDecimal(receiptQty).negate());

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract(USDC_CONTRACT);
        usdcIn.setQuantityDelta(new BigDecimal(usdcReturnedQty));

        tx.setFlows(new ArrayList<>(List.of(receiptOut, usdcIn)));
        return tx;
    }
}
