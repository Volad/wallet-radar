package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
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
                List.of(sell, buy)
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getMissingDataReasons()).isEmpty();
        assertThat(tx.getLegs()).hasSize(2);
        assertThat(tx.getLegs()).extracting(NormalizedTransaction.Leg::getRole)
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
                List.of(out)
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getLegs()).hasSize(1);
        assertThat(tx.getLegs().get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("builds internal transfer for INTERNAL_TRANSFER classifier output")
    void buildsInternalTransfer() {
        RawClassifiedEvent inbound = raw(EconomicEventType.INTERNAL_TRANSFER, "0xweth", "WETH", new BigDecimal("1"));
        RawClassifiedEvent outbound = raw(EconomicEventType.INTERNAL_TRANSFER, "0xweth", "WETH", new BigDecimal("-1"));

        NormalizedTransaction tx = builder.build(
                "0xtx3",
                NetworkId.ARBITRUM,
                "0xwallet",
                Instant.parse("2025-11-01T00:00:00Z"),
                List.of(inbound, outbound)
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getLegs()).allSatisfy(leg -> assertThat(leg.getRole()).isEqualTo(NormalizedLegRole.TRANSFER));
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
                List.of(sellOnly)
        );

        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(tx.getMissingDataReasons()).contains("MISSING_SWAP_LEG");
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
