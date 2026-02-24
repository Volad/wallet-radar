package com.walletradar.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioSnapshotTest {

    @Test
    void walletAddressAlwaysSet() {
        PortfolioSnapshot snapshot = new PortfolioSnapshot();
        snapshot.setWalletAddress("0xabc");
        snapshot.setSnapshotTime(Instant.now());
        snapshot.setTotalValueUsd(new BigDecimal("1000"));

        assertThat(snapshot.getWalletAddress()).isEqualTo("0xabc");
    }

    @Test
    void assetsListIsUnmodifiable() {
        PortfolioSnapshot snapshot = new PortfolioSnapshot();
        AssetSnapshot asset = new AssetSnapshot();
        asset.setAssetSymbol("ETH");
        asset.setQuantity(new BigDecimal("1"));
        snapshot.setAssets(List.of(asset));

        assertThat(snapshot.getAssets()).hasSize(1);
        assertThat(snapshot.getAssets().get(0).getAssetSymbol()).isEqualTo("ETH");
        assertThatThrownBy(() -> snapshot.getAssets().add(new AssetSnapshot()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void totalValueUsdIsBigDecimal() {
        PortfolioSnapshot snapshot = new PortfolioSnapshot();
        snapshot.setTotalValueUsd(new BigDecimal("1234.56"));
        assertThat(snapshot.getTotalValueUsd()).isEqualByComparingTo("1234.56");
    }
}
