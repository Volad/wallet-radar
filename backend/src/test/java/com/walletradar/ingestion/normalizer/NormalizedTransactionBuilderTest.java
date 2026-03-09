package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationStatus;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.normalized.PricingStatus;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedTransactionBuilderTest {

    private final NormalizedTransactionBuilder builder = new NormalizedTransactionBuilder();

    @Test
    @DisplayName("builds SWAP in PENDING_PRICE when both legs are present")
    void buildsSwapPendingPrice() {
        RawClassifiedEvent sell = raw(EconomicEventType.SWAP_SELL, "0xusdc", "USDC", new BigDecimal("-16"));
        RawClassifiedEvent buy = raw(EconomicEventType.SWAP_BUY, "0xeth", "ETH", new BigDecimal("0.004"));

        NormalizedTransaction tx = builder.build(
                "0xtx1",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(sell, buy),
                new BigDecimal("0.95")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getClassificationStatus()).isEqualTo(ClassificationStatus.CONFIRMED);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.PENDING);
        assertThat(tx.getMissingDataReasons()).isEmpty();
        assertThat(tx.getConfidence()).isEqualByComparingTo("0.95");
        assertThat(tx.getFlows()).hasSize(2);
        assertThat(tx.getFlows()).hasSize(2);
        assertThat(tx.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactlyInAnyOrder(NormalizedLegRole.SELL, NormalizedLegRole.BUY);
    }

    @Test
    @DisplayName("builds external transfer when only outbound transfer exists")
    void buildsExternalTransferOut() {
        RawClassifiedEvent out = raw(EconomicEventType.EXTERNAL_TRANSFER_OUT, "0xusdc", "USDC", new BigDecimal("-20"));

        NormalizedTransaction tx = builder.build(
                "0xtx2",
                NetworkId.ETHEREUM,
                "0xwallet",
                Instant.parse("2025-11-01T00:00:00Z"),
                List.of(out),
                new BigDecimal("0.88")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getFlows()).hasSize(1);
        assertThat(tx.getFlows()).hasSize(1);
        assertThat(tx.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("swap missing inbound leg goes to PENDING_CLARIFICATION")
    void swapMissingLegPendingClarification() {
        RawClassifiedEvent sellOnly = raw(EconomicEventType.SWAP_SELL, "0xwstusr", "wstUSR", new BigDecimal("-1914.54"));

        NormalizedTransaction tx = builder.build(
                "0xtx4",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(sellOnly),
                new BigDecimal("0.55")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(tx.getConfidence()).isEqualByComparingTo("0.55");
        assertThat(tx.getMissingDataReasons()).contains("MISSING_SWAP_LEG");
    }

    @Test
    @DisplayName("maps LP position exit event to LP_EXIT with TRANSFER flow role")
    void mapsLpPositionExitToLpExitType() {
        RawClassifiedEvent event = raw(EconomicEventType.LP_POSITION_EXIT, "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364", "PCS-V3-POS", new BigDecimal("1"));
        event.setPositionId("435853");

        NormalizedTransaction tx = builder.build(
                "0xtx-lp-pos-exit",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-15T01:05:39Z"),
                List.of(event),
                new BigDecimal("0.90")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(tx.getGroupId()).isEqualTo("LP_POSITION:BASE:0xwallet:435853");
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getFlows()).hasSize(1);
        assertThat(tx.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("maps LP fee claim to dedicated normalized type")
    void mapsLpFeeClaimType() {
        RawClassifiedEvent event = raw(EconomicEventType.LP_FEE_CLAIM, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", new BigDecimal("2.147331"));
        event.setPositionId("435853");

        NormalizedTransaction tx = builder.build(
                "0xtx-lp-fee-claim",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-15T01:06:11Z"),
                List.of(event),
                new BigDecimal("0.90")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
        assertThat(tx.getGroupId()).isEqualTo("LP_POSITION:BASE:0xwallet:435853");
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getFlows()).hasSize(1);
        assertThat(tx.getFlows().get(0).getQuantityDelta()).isEqualByComparingTo("2.147331");
    }

    @Test
    @DisplayName("maps LP adjust to dedicated normalized type and skips pricing stage")
    void mapsLpAdjustType() {
        RawClassifiedEvent event = raw(EconomicEventType.LP_ADJUST, "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364", "PCS-V3-POS", new BigDecimal("-1"));
        event.setPositionId("435853");

        NormalizedTransaction tx = builder.build(
                "0xtx-lp-adjust",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-15T01:06:11Z"),
                List.of(event),
                new BigDecimal("0.90")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ADJUST);
        assertThat(tx.getGroupId()).isEqualTo("LP_POSITION:BASE:0xwallet:435853");
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
        assertThat(tx.getFlows()).hasSize(1);
        assertThat(tx.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("prefers LP type when LP and transfer events are mixed")
    void prefersLpTypeOverExternalTransfers() {
        RawClassifiedEvent lpExit = raw(
                EconomicEventType.LP_EXIT,
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "USDC",
                new BigDecimal("897.031359")
        );
        RawClassifiedEvent externalInbound = raw(
                EconomicEventType.EXTERNAL_INBOUND,
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "USDC",
                new BigDecimal("897.031359")
        );

        NormalizedTransaction tx = builder.build(
                "0xtx-lp-mixed",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-15T01:06:11Z"),
                List.of(lpExit, externalInbound),
                new BigDecimal("0.90")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    @Test
    @DisplayName("does not treat same-asset inbound and outbound as SWAP")
    void sameAssetInboundOutboundIsNotSwap() {
        RawClassifiedEvent outbound = raw(
                EconomicEventType.EXTERNAL_TRANSFER_OUT,
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "USDC",
                new BigDecimal("-13")
        );
        RawClassifiedEvent inbound = raw(
                EconomicEventType.EXTERNAL_INBOUND,
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "USDC",
                new BigDecimal("0.003837")
        );

        NormalizedTransaction tx = builder.build(
                "0xtx-same-asset",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-15T01:06:11Z"),
                List.of(outbound, inbound),
                new BigDecimal("0.90")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(tx.getGroupId()).isNull();
    }

    @Test
    @DisplayName("does not assign groupId for LP transaction without position id")
    void noGroupIdWhenLpWithoutPositionId() {
        RawClassifiedEvent event = raw(EconomicEventType.LP_ENTRY, "0xbb00000000000000000000000000000000000001", "BPT", new BigDecimal("5"));

        NormalizedTransaction tx = builder.build(
                "0xtx-lp-no-position",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-11-01T00:00:00Z"),
                List.of(event),
                new BigDecimal("0.88")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(tx.getGroupId()).isNull();
    }

    @Test
    @DisplayName("builds WRAP in PENDING_STAT with pricing not required")
    void buildsWrapPendingStat() {
        RawClassifiedEvent out = raw(EconomicEventType.WRAP, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH", new BigDecimal("-1"));
        RawClassifiedEvent in = raw(EconomicEventType.WRAP, "0x4200000000000000000000000000000000000006", "WETH", new BigDecimal("1"));

        NormalizedTransaction tx = builder.build(
                "0xwrap",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(out, in),
                new BigDecimal("0.95")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.WRAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
        assertThat(tx.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsOnly(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("builds LEND_DEPOSIT in PENDING_STAT with pricing not required for receipt leg")
    void lendDepositSkipsPricingForReceiptLeg() {
        RawClassifiedEvent out = raw(EconomicEventType.LEND_DEPOSIT, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", new BigDecimal("-100"));
        RawClassifiedEvent in = raw(EconomicEventType.LEND_DEPOSIT, "0x078f358208685046a11c85e8ad32895ded33a249", "aUSDC", new BigDecimal("100"));

        NormalizedTransaction tx = builder.build(
                "0xlend-deposit",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(out, in),
                new BigDecimal("0.95")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LEND_DEPOSIT);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
    }

    @Test
    @DisplayName("builds LP_ENTRY in PENDING_STAT with pricing not required for receipt leg")
    void lpEntrySkipsPricingForReceiptLeg() {
        RawClassifiedEvent out = raw(EconomicEventType.LP_ENTRY, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", new BigDecimal("-100"));
        RawClassifiedEvent in = raw(EconomicEventType.LP_ENTRY, "0xbb00000000000000000000000000000000000001", "UNI-V2", new BigDecimal("5"));

        NormalizedTransaction tx = builder.build(
                "0xlp-entry",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(out, in),
                new BigDecimal("0.95")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
    }

    @Test
    @DisplayName("builds UNWRAP in PENDING_STAT with pricing not required")
    void buildsUnwrapPendingStat() {
        RawClassifiedEvent out = raw(EconomicEventType.UNWRAP, "0x4200000000000000000000000000000000000006", "WETH", new BigDecimal("-1"));
        RawClassifiedEvent in = raw(EconomicEventType.UNWRAP, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH", new BigDecimal("1"));

        NormalizedTransaction tx = builder.build(
                "0xunwrap",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-10-06T09:11:09Z"),
                List.of(out, in),
                new BigDecimal("0.95")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.UNWRAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
        assertThat(tx.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsOnly(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("builds UNCLASSIFIED when no classifier events are produced")
    void buildsUnclassifiedWhenEventsMissing() {
        NormalizedTransaction tx = builder.build(
                "0xtx-empty",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-11-01T00:00:00Z"),
                List.of(),
                new BigDecimal("0.35")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.UNCLASSIFIED);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(tx.getClassificationStatus()).isEqualTo(ClassificationStatus.NEEDS_REVIEW);
        assertThat(tx.getMissingDataReasons()).containsExactly("NO_CLASSIFICATION_EVIDENCE");
    }

    @Test
    @DisplayName("approval without economic legs does not enter clarification")
    void approvalWithoutLegsDoesNotEnterClarification() {
        RawClassifiedEvent approval = raw(EconomicEventType.APPROVAL, "0x1111111111111111111111111111111111111111", "", BigDecimal.ZERO);

        NormalizedTransaction tx = builder.build(
                "0xtx-approval",
                NetworkId.BASE,
                "0xwallet",
                Instant.parse("2025-11-01T00:00:00Z"),
                List.of(approval),
                new BigDecimal("0.88")
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.APPROVAL);
        assertThat(tx.getFlows()).isEmpty();
        assertThat(tx.getMissingDataReasons()).isEmpty();
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.NOT_REQUIRED);
        assertThat(tx.getClassificationStatus()).isEqualTo(ClassificationStatus.CONFIRMED);
    }

    private static RawClassifiedEvent raw(EconomicEventType type, String contract, String symbol, BigDecimal qty) {
        RawClassifiedEvent e = new RawClassifiedEvent();
        e.setEventType(type);
        e.setAssetContract(contract);
        e.setAssetSymbol(symbol);
        e.setQuantityDelta(qty);
        e.setWalletAddress("0xwallet");
        return e;
    }
}
