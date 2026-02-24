package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicEventNormalizerTest {

    private EconomicEventNormalizer normalizer;
    private GasCostCalculator gasCalculator;

    @BeforeEach
    void setUp() {
        gasCalculator = new GasCostCalculator();
        normalizer = new EconomicEventNormalizer(gasCalculator);
    }

    @Test
    void normalize_swapBuy_setsGasIncludedInBasisTrue() {
        RawClassifiedEvent raw = new RawClassifiedEvent();
        raw.setEventType(EconomicEventType.SWAP_BUY);
        raw.setWalletAddress("0xWallet");
        raw.setAssetSymbol("ETH");
        raw.setAssetContract("0x0");
        raw.setQuantityDelta(BigDecimal.ONE);
        raw.setPriceUsd(BigDecimal.valueOf(2000));
        raw.setGasUsed(21_000L);
        raw.setGasPriceWei(BigInteger.valueOf(20_000_000_000L));

        EconomicEvent e = normalizer.normalize(raw, "0xTxHash", NetworkId.ETHEREUM, Instant.EPOCH, BigDecimal.valueOf(2000));

        assertThat(e.getEventType()).isEqualTo(EconomicEventType.SWAP_BUY);
        assertThat(e.getTxHash()).isEqualTo("0xTxHash");
        assertThat(e.getNetworkId()).isEqualTo(NetworkId.ETHEREUM);
        assertThat(e.getWalletAddress()).isEqualTo("0xWallet");
        assertThat(e.getQuantityDelta()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(e.getTotalValueUsd()).isEqualByComparingTo("2000");
        assertThat(e.isGasIncludedInBasis()).isTrue();
        assertThat(e.getGasCostUsd()).isEqualByComparingTo("0.84");
    }

    @Test
    void normalize_swapSell_setsGasIncludedInBasisFalse() {
        RawClassifiedEvent raw = new RawClassifiedEvent();
        raw.setEventType(EconomicEventType.SWAP_SELL);
        raw.setWalletAddress("0xWallet");
        raw.setQuantityDelta(BigDecimal.ONE.negate());
        raw.setPriceUsd(BigDecimal.valueOf(2000));

        EconomicEvent e = normalizer.normalize(raw, "0xTxHash", NetworkId.ETHEREUM, Instant.EPOCH, BigDecimal.valueOf(2000));

        assertThat(e.isGasIncludedInBasis()).isFalse();
        assertThat(e.getEventType()).isEqualTo(EconomicEventType.SWAP_SELL);
    }

    @Test
    void normalize_externalInbound_setsGasIncludedInBasisTrue() {
        RawClassifiedEvent raw = new RawClassifiedEvent();
        raw.setEventType(EconomicEventType.EXTERNAL_INBOUND);
        raw.setWalletAddress("0xWallet");
        raw.setQuantityDelta(BigDecimal.TEN);

        EconomicEvent e = normalizer.normalize(raw, "0xTxHash", NetworkId.ETHEREUM, Instant.EPOCH, null);

        assertThat(e.isGasIncludedInBasis()).isTrue();
    }

    @Test
    void normalizeAll_returnsListOfNormalizedEvents() {
        RawClassifiedEvent raw1 = new RawClassifiedEvent();
        raw1.setEventType(EconomicEventType.SWAP_BUY);
        raw1.setWalletAddress("0xW");
        raw1.setQuantityDelta(BigDecimal.ONE);
        RawClassifiedEvent raw2 = new RawClassifiedEvent();
        raw2.setEventType(EconomicEventType.SWAP_SELL);
        raw2.setWalletAddress("0xW");
        raw2.setQuantityDelta(BigDecimal.ONE.negate());

        List<EconomicEvent> events = normalizer.normalizeAll(
                List.of(raw1, raw2), "0xTx", NetworkId.ARBITRUM, Instant.EPOCH, null);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType()).isEqualTo(EconomicEventType.SWAP_BUY);
        assertThat(events.get(1).getEventType()).isEqualTo(EconomicEventType.SWAP_SELL);
        assertThat(events.get(0).getTxHash()).isEqualTo("0xTx");
        assertThat(events.get(1).getNetworkId()).isEqualTo(NetworkId.ARBITRUM);
    }
}
