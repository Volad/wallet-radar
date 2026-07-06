package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAssetFamilySupportTest {

    @Test
    @DisplayName("CAKE on BASE and CAKE on BSC produce identical bridge identity SYMBOL:CAKE")
    void cakeOnBaseAndBscProduceIdenticalBridgeIdentity() {
        NormalizedTransaction.Flow cakeOnBase = flow("CAKE", "0x3055913c90fcc1a6ce9a358911721eeb942013a1", "100");
        NormalizedTransaction.Flow cakeOnBsc = flow("CAKE", "0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82", "100");

        String baseIdentity = BridgeAssetFamilySupport.continuityIdentity(cakeOnBase);
        String bscIdentity = BridgeAssetFamilySupport.continuityIdentity(cakeOnBsc);

        assertThat(baseIdentity).isEqualTo("SYMBOL:CAKE");
        assertThat(bscIdentity).isEqualTo("SYMBOL:CAKE");
        assertThat(baseIdentity).isEqualTo(bscIdentity);
    }

    @Test
    @DisplayName("USDT flow produces FAMILY:USDT key regardless of contract")
    void usdtProducesFamilyKey() {
        NormalizedTransaction.Flow usdtEth = flow("USDT", "0xdac17f958d2ee523a2206206994597c13d831ec7", "500");
        NormalizedTransaction.Flow usdtArb = flow("USDT", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "500");

        assertThat(BridgeAssetFamilySupport.continuityIdentity(usdtEth)).isEqualTo("FAMILY:USDT");
        assertThat(BridgeAssetFamilySupport.continuityIdentity(usdtArb)).isEqualTo("FAMILY:USDT");
    }

    @Test
    @DisplayName("USDC flow produces FAMILY:USDC key")
    void usdcProducesFamilyKey() {
        NormalizedTransaction.Flow usdc = flow("USDC", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "200");
        assertThat(BridgeAssetFamilySupport.continuityIdentity(usdc)).isEqualTo("FAMILY:USDC");
    }

    @Test
    @DisplayName("WETH flow produces FAMILY:ETH key")
    void wethProducesFamilyEthKey() {
        NormalizedTransaction.Flow weth = flow("WETH", "0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111", "1");
        assertThat(BridgeAssetFamilySupport.continuityIdentity(weth)).isEqualTo("FAMILY:ETH");
    }

    @Test
    @DisplayName("ETH flow produces FAMILY:ETH key")
    void ethProducesFamilyEthKey() {
        NormalizedTransaction.Flow eth = flow("ETH", null, "1");
        assertThat(BridgeAssetFamilySupport.continuityIdentity(eth)).isEqualTo("FAMILY:ETH");
    }

    @Test
    @DisplayName("null flow returns null")
    void nullFlowReturnsNull() {
        assertThat(BridgeAssetFamilySupport.continuityIdentity(null)).isNull();
    }

    @Test
    @DisplayName("flow with null symbol returns null")
    void nullSymbolFlowReturnsNull() {
        NormalizedTransaction.Flow f = flow(null, "0xabc", "1");
        assertThat(BridgeAssetFamilySupport.continuityIdentity(f)).isNull();
    }

    @Test
    @DisplayName("unknown token with contract address uses SYMBOL: key not contract")
    void unknownTokenUsesSybmolNotContract() {
        NormalizedTransaction.Flow myToken = flow("MYTOKEN", "0xcafecafecafecafecafecafecafecafecafecafe", "50");
        String identity = BridgeAssetFamilySupport.continuityIdentity(myToken);
        assertThat(identity).isEqualTo("SYMBOL:MYTOKEN");
        assertThat(identity).doesNotContain("cafecafe");
    }

    private static NormalizedTransaction.Flow flow(String symbol, String contract, String qty) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setAssetSymbol(symbol);
        f.setAssetContract(contract);
        f.setQuantityDelta(new BigDecimal(qty));
        f.setRole(NormalizedLegRole.TRANSFER);
        return f;
    }
}
