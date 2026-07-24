package com.walletradar.application.costbasis.application.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.ton.TonNetworkProperties;
import com.walletradar.platform.networks.ton.TonRpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TonOnChainBalanceProviderTest {

    private static final String OWNER = "UQAbcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRST";
    private static final String USDT_MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";
    private static final String UNKNOWN_MASTER = "EQUnknownJettonMaster00000000000000000000000000";

    @Mock
    private TonRpcClient rpcClient;

    private TonOnChainBalanceProvider provider() {
        return new TonOnChainBalanceProvider(rpcClient, new TonNetworkProperties(), new ObjectMapper());
    }

    @Test
    void enumeratesNativeTonAndSeededJettonSkippingUnresolvableDecimals() {
        lenient().when(rpcClient.get(eq("accountStates"), any()))
                .thenReturn("{\"accounts\":[{\"balance\":\"3000000000\"}]}");
        lenient().when(rpcClient.get(eq("jetton/wallets"), any()))
                .thenReturn("{\"jetton_wallets\":["
                        + "{\"jetton\":\"" + USDT_MASTER + "\",\"balance\":\"2500000\"},"
                        + "{\"jetton\":\"" + UNKNOWN_MASTER + "\",\"balance\":\"1000000000\"}"
                        + "],\"metadata\":{}}");

        List<OnChainBalanceProvider.ProviderBalance> balances = provider().fetchBalances(OWNER);

        // Native TON + seeded USDT jetton; the unknown jetton with no resolvable decimals is skipped.
        assertThat(balances).hasSize(2);
        assertThat(balances)
                .filteredOn(OnChainBalanceProvider.ProviderBalance::nativeAsset)
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.assetSymbol()).isEqualTo("TON");
                    assertThat(b.assetContract()).isEqualTo("TONCOIN");
                    assertThat(b.quantity()).isEqualByComparingTo("3");
                });
        assertThat(balances)
                .filteredOn(b -> !b.nativeAsset())
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.assetSymbol()).isEqualTo("USDT");
                    assertThat(b.assetContract()).isEqualTo(USDT_MASTER.toLowerCase(java.util.Locale.ROOT));
                    assertThat(b.quantity()).isEqualByComparingTo("2.5");
                });
    }

    @Test
    void resolvesJettonDecimalsFromResponseMetadataWhenNotSeeded() {
        lenient().when(rpcClient.get(eq("accountStates"), any()))
                .thenReturn("{\"accounts\":[]}");
        lenient().when(rpcClient.get(eq("jetton/wallets"), any()))
                .thenReturn("{\"jetton_wallets\":["
                        + "{\"jetton\":\"" + UNKNOWN_MASTER + "\",\"balance\":\"1000000000\"}"
                        + "],\"metadata\":{\"" + UNKNOWN_MASTER + "\":{\"token_info\":[{\"symbol\":\"NOT\",\"extra\":{\"decimals\":9}}]}}}");

        List<OnChainBalanceProvider.ProviderBalance> balances = provider().fetchBalances(OWNER);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.assetSymbol()).isEqualTo("NOT");
            assertThat(b.quantity()).isEqualByComparingTo("1");
        });
    }
}
