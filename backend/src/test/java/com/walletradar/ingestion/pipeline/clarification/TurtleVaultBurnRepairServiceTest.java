package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurtleVaultBurnRepairServiceTest {

    /**
     * Real calldata for tx 0xc8b94615 on Avalanche.
     * redeem(2787570915679889720145, receiver, owner)
     * shares = 0x000000000000000000000000000000000000000000000000971d515229ce7f4751 — but that is 34 hex digits,
     * which is more than 32 bytes. The actual ABI-encoded uint256 is zero-padded on the left to 64 hex chars:
     * 0000000000000000000000000000000000000000000000971d515229ce7f4751
     */
    private static final String REAL_INPUT =
            "0xba087652"
                    + "0000000000000000000000000000000000000000000000971d515229ce7f4751"
                    + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                    + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    private static final BigDecimal EXPECTED_SHARES =
            new BigDecimal(new BigInteger("0000000000000000000000000000000000000000000000971d515229ce7f4751", 16))
                    .movePointLeft(18);

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private TurtleVaultBurnRepairService service;

    @BeforeEach
    void setUp() {
        service = new TurtleVaultBurnRepairService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path: VAULT_WITHDRAW with USDC flow only → vault token burn flow inserted at index 0")
    void happyPath_addsBurnFlow() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xc8b94615");
        RawTransaction raw = rawTxWithInput(tx.getTxHash(), REAL_INPUT);
        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isTrue();
        assertThat(tx.getFlows()).hasSize(2);

        NormalizedTransaction.Flow burnFlow = tx.getFlows().get(0);
        assertThat(burnFlow.getAssetSymbol()).isEqualTo("turtleAvalancheUSDC");
        assertThat(burnFlow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(burnFlow.getQuantityDelta()).isNegative();
        assertThat(burnFlow.getQuantityDelta().abs()).isEqualByComparingTo(EXPECTED_SHARES);
        assertThat(burnFlow.getCounterpartyAddress()).isEqualTo(TurtleVaultBurnRepairService.TURTLE_VAULT_ADDRESS);
        assertThat(burnFlow.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(burnFlow.getAssetContract()).isEqualTo(TurtleVaultBurnRepairService.TURTLE_VAULT_ADDRESS);
        assertThat(burnFlow.getIsInferred()).isTrue();
        assertThat(burnFlow.getInferenceReason()).isEqualTo("VAULT_TOKEN_BURN_SYNTHESIZED_FROM_CALLDATA");

        NormalizedTransaction.Flow usdcFlow = tx.getFlows().get(1);
        assertThat(usdcFlow.getAssetSymbol()).isEqualTo("USDC");
    }

    @Test
    @DisplayName("happy path: repairMissingVaultTokenBurn processes batch and saves")
    void batchRepair_savesResults() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xc8b94615");
        RawTransaction raw = rawTxWithInput(tx.getTxHash(), REAL_INPUT);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));
        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        int repaired = service.repairMissingVaultTokenBurn(10);

        assertThat(repaired).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    // ─── Idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("idempotency: tx already has turtleAvalancheUSDC flow → no double-add")
    void idempotency_alreadyHasVaultTokenFlow_skips() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xc8b94615");
        NormalizedTransaction.Flow existingBurn = new NormalizedTransaction.Flow();
        existingBurn.setAssetSymbol("turtleAvalancheUSDC");
        existingBurn.setRole(NormalizedLegRole.TRANSFER);
        existingBurn.setQuantityDelta(new BigDecimal("-2787.570915679889720145"));
        tx.getFlows().add(0, existingBurn);

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
        assertThat(tx.getFlows()).hasSize(2);
        verify(mongoOperations, never()).find(any(Query.class), eq(RawTransaction.class));
    }

    @Test
    @DisplayName("idempotency: case-insensitive — TURTLEAVALANCHEUSDC symbol also detected as duplicate")
    void idempotency_caseInsensitiveSymbolCheck() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xc8b94615");
        NormalizedTransaction.Flow existingBurn = new NormalizedTransaction.Flow();
        existingBurn.setAssetSymbol("TURTLEAVALANCHEUSDC");
        existingBurn.setRole(NormalizedLegRole.TRANSFER);
        existingBurn.setQuantityDelta(new BigDecimal("-100"));
        tx.getFlows().add(0, existingBurn);

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
    }

    // ─── Input parsing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("input parse error: null rawData → skip gracefully")
    void parseError_nullRawData_skips() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xbad1");
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xbad1");
        raw.setNetworkId("AVALANCHE");
        // rawData intentionally null

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
        assertThat(tx.getFlows()).hasSize(1);
    }

    @Test
    @DisplayName("input parse error: malformed calldata (too short) → skip gracefully")
    void parseError_inputTooShort_skips() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xbad2");
        RawTransaction raw = rawTxWithInput("0xbad2", "0xba087652deadbeef");

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
        assertThat(tx.getFlows()).hasSize(1);
    }

    @Test
    @DisplayName("input parse error: wrong method selector → skip gracefully")
    void parseError_wrongMethodSelector_skips() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xbad3");
        String wrongInput = "0xdeadbeef"
                + "0000000000000000000000000000000000000000000000971d515229ce7f4751"
                + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                + "0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        RawTransaction raw = rawTxWithInput("0xbad3", wrongInput);

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
        assertThat(tx.getFlows()).hasSize(1);
    }

    @Test
    @DisplayName("input parse error: no raw transaction found → skip gracefully")
    void parseError_rawNotFound_skips() {
        NormalizedTransaction tx = vaultWithdrawWithUsdcOnly("0xbad4");

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of());

        boolean changed = service.repairIfMissingBurn(tx, Instant.now());

        assertThat(changed).isFalse();
        assertThat(tx.getFlows()).hasSize(1);
    }

    @Test
    @DisplayName("parseShares: real calldata produces expected shares amount ~2787.57")
    void parseShares_realCalldata_producesExpectedAmount() {
        RawTransaction raw = rawTxWithInput("0xtest", REAL_INPUT);

        BigDecimal shares = service.parseShares(raw, "0xtest");

        assertThat(shares).isNotNull();
        assertThat(shares).isEqualByComparingTo(EXPECTED_SHARES);
        // sanity: ~2787.57 tokens
        assertThat(shares).isGreaterThan(new BigDecimal("2787"))
                .isLessThan(new BigDecimal("2788"));
    }

    @Test
    @DisplayName("parseShares: input from explorer.input sub-document also works")
    void parseShares_explorerSubDocument() {
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xtest");
        Document explorer = new Document("input", REAL_INPUT);
        raw.setRawData(new Document("explorer", explorer));

        BigDecimal shares = service.parseShares(raw, "0xtest");

        assertThat(shares).isNotNull().isEqualByComparingTo(EXPECTED_SHARES);
    }

    // ─── alreadyHasVaultTokenFlow ─────────────────────────────────────────────

    @Test
    @DisplayName("alreadyHasVaultTokenFlow: null flows returns false")
    void alreadyHasVaultToken_nullFlows_returnsFalse() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setFlows(null);

        assertThat(service.alreadyHasVaultTokenFlow(tx)).isFalse();
    }

    @Test
    @DisplayName("alreadyHasVaultTokenFlow: empty flows returns false")
    void alreadyHasVaultToken_emptyFlows_returnsFalse() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setFlows(new ArrayList<>());

        assertThat(service.alreadyHasVaultTokenFlow(tx)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static NormalizedTransaction vaultWithdrawWithUsdcOnly(String txHash) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(txHash);
        tx.setTxHash(txHash);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.VAULT_WITHDRAW);
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setBlockTimestamp(Instant.parse("2025-12-15T10:00:00Z"));

        NormalizedTransaction.Flow usdcFlow = new NormalizedTransaction.Flow();
        usdcFlow.setAssetSymbol("USDC");
        usdcFlow.setRole(NormalizedLegRole.TRANSFER);
        usdcFlow.setQuantityDelta(new BigDecimal("2831.199286"));
        usdcFlow.setCounterpartyAddress(TurtleVaultBurnRepairService.TURTLE_VAULT_ADDRESS);
        usdcFlow.setCounterpartyType("PROTOCOL");

        tx.setFlows(new ArrayList<>(List.of(usdcFlow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }

    private static RawTransaction rawTxWithInput(String txHash, String input) {
        RawTransaction raw = new RawTransaction();
        raw.setTxHash(txHash);
        raw.setNetworkId("AVALANCHE");
        raw.setRawData(new Document("input", input));
        return raw;
    }
}
