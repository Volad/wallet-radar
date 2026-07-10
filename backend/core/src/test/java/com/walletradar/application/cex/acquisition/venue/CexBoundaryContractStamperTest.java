package com.walletradar.application.cex.acquisition.venue;

import com.walletradar.application.cex.acquisition.venue.bybit.BybitVenueDescriptor;
import com.walletradar.application.cex.port.VenueDescriptor;
import com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CexBoundaryContractStamperTest {

    private CexBoundaryContractStamper stamper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var bybitDescriptor = new BybitVenueDescriptor(null);
        ObjectProvider<VenueDescriptor> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(bybitDescriptor));
        VenueRegistry registry = new VenueRegistry(provider);
        stamper = new CexBoundaryContractStamper(registry);
    }

    @Test
    @DisplayName("BYBIT FUND EXTERNAL_TRANSFER_IN with USDT flow → INFLOW")
    void bybitFundUsdtTransferIn_stampsInflow() {
        NormalizedTransaction tx = makeTx("BYBIT:33625378:FUND", NormalizedTransactionType.EXTERNAL_TRANSFER_IN, "USDT");
        stamper.stamp(tx);
        assertThat(tx.getExternalCapitalBoundary()).isEqualTo(ExternalCapitalBoundary.INFLOW);
    }

    @Test
    @DisplayName("BYBIT FUND EXTERNAL_TRANSFER_IN with ETH flow (non-eligible) → null")
    void bybitFundEthTransferIn_noStamp() {
        NormalizedTransaction tx = makeTx("BYBIT:33625378:FUND", NormalizedTransactionType.EXTERNAL_TRANSFER_IN, "ETH");
        stamper.stamp(tx);
        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    @Test
    @DisplayName("BYBIT FUND EXTERNAL_TRANSFER_OUT → OUTFLOW")
    void bybitFundTransferOut_stampsOutflow() {
        NormalizedTransaction tx = makeTx("BYBIT:33625378:FUND", NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, "USDT");
        stamper.stamp(tx);
        assertThat(tx.getExternalCapitalBoundary()).isEqualTo(ExternalCapitalBoundary.OUTFLOW);
    }

    @Test
    @DisplayName("On-chain wallet → no stamp")
    void onChainWallet_noStamp() {
        NormalizedTransaction tx = makeTx("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", NormalizedTransactionType.EXTERNAL_TRANSFER_IN, "USDT");
        stamper.stamp(tx);
        assertThat(tx.getExternalCapitalBoundary()).isNull();
    }

    private static NormalizedTransaction makeTx(String walletAddress, NormalizedTransactionType type, String asset) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setWalletAddress(walletAddress);
        tx.setType(type);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(BigDecimal.valueOf(500));
        tx.setFlows(List.of(flow));
        return tx;
    }
}
