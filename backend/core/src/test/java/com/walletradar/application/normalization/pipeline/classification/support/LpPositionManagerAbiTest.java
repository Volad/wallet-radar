package com.walletradar.application.normalization.pipeline.classification.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LpPositionManagerAbiTest {

    @Test
    @DisplayName("golden set: shared ABI selectors load with the exact expected values")
    void goldenSelectors() {
        assertThat(LpPositionManagerAbi.MINT_SELECTOR).isEqualTo("0x88316456");
        assertThat(LpPositionManagerAbi.STRUCT_MINT_SELECTOR).isEqualTo("0xb5007d1f");
        assertThat(LpPositionManagerAbi.INCREASE_LIQUIDITY_SELECTOR).isEqualTo("0x4f1eb3d8");
        assertThat(LpPositionManagerAbi.MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR).isEqualTo("0x219f5d17");
        assertThat(LpPositionManagerAbi.DECREASE_LIQUIDITY_SELECTOR).isEqualTo("0x0c49ccbe");
        assertThat(LpPositionManagerAbi.COLLECT_SELECTOR).isEqualTo("0xfc6f7865");
        assertThat(LpPositionManagerAbi.BURN_SELECTOR).isEqualTo("0x00f714ce");
        assertThat(LpPositionManagerAbi.MULTICALL_SELECTOR).isEqualTo("0xac9650d8");
        assertThat(LpPositionManagerAbi.MODIFY_LIQUIDITIES_SELECTOR).isEqualTo("0xdd46508f");
        assertThat(LpPositionManagerAbi.STAKE_DEPOSIT_SELECTOR).isEqualTo("0xb6b55f25");
        assertThat(LpPositionManagerAbi.STAKE_WITHDRAW_SELECTOR).isEqualTo("0x2e1a7d4d");
        assertThat(LpPositionManagerAbi.AURA_WITHDRAW_AND_UNWRAP_SELECTOR).isEqualTo("0xc32e7202");
        assertThat(LpPositionManagerAbi.SAFE_TRANSFER_FROM_SELECTOR).isEqualTo("0x42842e0e");
        assertThat(LpPositionManagerAbi.SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR).isEqualTo("0xb88d4fde");
        assertThat(LpPositionManagerAbi.ROUTE_SINGLE_VAULT_SELECTOR).isEqualTo("0xb94c3609");
    }

    @Test
    @DisplayName("golden set: shared ABI event topics load with the exact expected values")
    void goldenTopics() {
        assertThat(LpPositionManagerAbi.ERC721_TRANSFER_TOPIC)
                .isEqualTo("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
        assertThat(LpPositionManagerAbi.MODIFY_LIQUIDITY_TOPIC)
                .isEqualTo("0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec");
    }

    @Test
    @DisplayName("all selectors are lowercase 4-byte selectors")
    void selectorsAreWellFormed() {
        assertThat(LpPositionManagerAbi.MINT_SELECTOR).matches("0x[0-9a-f]{8}");
        assertThat(LpPositionManagerAbi.MODIFY_LIQUIDITIES_SELECTOR).matches("0x[0-9a-f]{8}");
        assertThat(LpPositionManagerAbi.ERC721_TRANSFER_TOPIC).matches("0x[0-9a-f]{64}");
    }
}
