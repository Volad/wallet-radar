package com.walletradar.platform.networks.evm.rpc;

import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the V4 pool-state slot0 read primitives.
 *
 * <p>Guards the root-cause fix: V4 {@code PoolManager} exposes pool state only via
 * {@code extsload(bytes32)} (there is no external {@code getSlot0(bytes32)} on the PoolManager —
 * that lives on the periphery {@code StateView}). The {@code slot0} word of {@code pools[poolId]}
 * sits at {@code keccak256(abi.encodePacked(poolId, POOLS_SLOT))} with {@code POOLS_SLOT = 6}.
 */
class V4PoolStateReaderTest {

    /** Real Unichain USD₮0/ETH V4 pool id (ModifyLiquidity topic1 of tx 0x628c0047…). */
    private static final String POOL_ID =
            "04b7dd024db64cfbe325191c818266e4776918cd9eaf021c26949a859e654b16";

    @Test
    void keccakWiringUsesKeccak256NotSha3() {
        // Canonical Keccak-256 of the empty input (distinct from FIPS SHA3-256's empty hash).
        assertThat(EvmAbiSupport.keccak256Hex(""))
                .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
    }

    @Test
    void extsloadSelectorIsCanonical() {
        // keccak256("extsload(bytes32)")[0:4]
        assertThat(V4PoolStateReader.EXTSLOAD_SELECTOR).isEqualTo("0x1e2eaeaf");
    }

    @Test
    void poolStateSlotIsDeterministicAndPrefixInsensitive() {
        String withoutPrefix = V4PoolStateReader.poolStateSlot(POOL_ID);
        String withPrefix = V4PoolStateReader.poolStateSlot("0x" + POOL_ID);

        assertThat(withoutPrefix).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(withPrefix).isEqualTo(withoutPrefix);
    }

    @Test
    void poolStateSlotDiffersPerPoolId() {
        String slotA = V4PoolStateReader.poolStateSlot(POOL_ID);
        String slotB = V4PoolStateReader.poolStateSlot(
                "d10d359f50ba8d1e0b6c30974a65bf06895fba4bf2b692b2c75d987d3b6b863d");
        assertThat(slotA).isNotEqualTo(slotB);
    }

    @Test
    void poolStateSlotMatchesStateLibraryReference() {
        // Independent reconstruction of StateLibrary._getPoolStateSlot:
        //   keccak256(poolId(32B) || uint256(6)(32B))
        String poolIdWord = "0".repeat(64 - POOL_ID.length()) + POOL_ID;
        String poolsSlotWord = "0".repeat(63) + "6";
        String expected = EvmAbiSupport.keccak256Hex(poolIdWord + poolsSlotWord);

        assertThat(V4PoolStateReader.poolStateSlot(POOL_ID)).isEqualTo(expected);
    }
}
