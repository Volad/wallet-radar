package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;
import java.util.Set;

/**
 * Cycle/18 R9: Known bridge routers and reward distributors that must not be classified as
 * generic {@code EXTERNAL_TRANSFER_IN/OUT} with BUY/SELL semantics.
 */
public final class KnownBridgeRouterRegistry {

    private static final Set<String> BRIDGE_ROUTERS = Set.of(
            // Across V3 SpokePool (UNICHAIN and other networks)
            "0x943e6e07a7e8e791dafc44083e54041d743c46e9",
            // Squid / SushiXSwap router (ARBITRUM)
            "0x40f480f247f3ad2ff4c1463e84f03be3a9a03e15",
            // Relay bridge routers (OPTIMISM / BASE)
            "0x0a2854fbbd9b3ef66f17d47284e7f899b9509330",
            "0x303016b893a40134b9b82e6ae1804c61e96a9395",
            "0x6a7049ec66245b94833cb1de38bdf58578fc0fa8",
            "0x9dc9ff2fd5d9c39abe402641f310a44f67568dde",
            "0x6131b5fae19ea4f9d964eac0408e4408b66337b5",
            "0x8e8c3d4313fd5c5051a02b9e580415691a0f7951",
            "0x85a80afee867adf27b50bdb7b76da70f1e853062",
            "0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d",
            "0xf5042e6ffac5a625d4e7848e0b01373d8eb9e222",
            "0x16ac3457ce84e6c5f80b394c59ccb2fd17049a62",
            "0x00a55649e597d463fd212fbe48a3b40f0e227d06",
            "0x2659c6085d26144117d904c46b48b6d180393d27",
            "0x2a2c512beaa8eb15495726c235472d82effb7a6b",
            "0xba9dd716ba2a4b9fa7818802beb631f10bd28073",
            "0x223ec22d67716fca620aee72b25ffe4ece436f25",
            // LiFi Diamond (BASE / ETH / many EVM networks)
            "0xcd74f91e4d2a49903462d58d6951136a527a5dea",
            // LiFi / LayerZero executor (LINEA)
            "0x00000000aa467eba42a3d604b3d74d63b2b6c6cb",
            // Relay depository / bridge entry (BASE)
            "0x6ea77f83ec8693666866ece250411c974ab962a8",
            // Base transfer proxy bridge router
            "0x4446adc0b8136ffc55ddb7a488ba5509ace2a5ef",
            // Plasma bridge router
            "0x026f252016a7c47cdef1f05a3fc9e20c92a49c37",
            // WS-1: LI.FI Diamond — main multi-network entry (ETHEREUM/ARBITRUM/BASE/BSC/etc.)
            "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae",
            // WS-1: LI.FI Permit2Proxy — ARBITRUM/BASE/AVALANCHE/ETHEREUM
            "0x89c6340b1a1f4b25d36cd8b063d49045caf3f818",
            // WS-1: LI.FI Permit2Proxy — LINEA
            "0xe5a89411c7ef1502123d84ec1f9da9d8574f4293",
            // WS-1: LI.FI Permit2Proxy — KATANA
            "0x628d684d57c73a5d8ca77f455fdf2cc8bd503c16",
            // WS-1: LI.FI Permit2Proxy — UNICHAIN
            "0xa3681352316c030b06a83b04394a860a49b47706",
            // WS-1: LI.FI Permit2Proxy — PLASMA
            "0x3c6b2e0b7421254846c53c118e24c65d59eae75e",
            // WS-1: LI.FI Permit2Proxy — OPTIMISM/BASE
            "0x6307119078556fc8ad77781dfc67df20d75fb4f9",
            // WS-1: LI.FI Permit2Proxy — OPTIMISM
            "0x2270a09bfc9925e3aa083db3e19051fff8ada053",
            // WS-1: LI.FI GasZip proxy — UNICHAIN (underlying bridge = GasZip)
            "0x864b314d4c5a0399368609581d3e8933a63b9232"
    );

    private static final Set<String> REWARD_DISTRIBUTORS = Set.of(
            // Velodrome / Aerodrome-style reward distributors (OPTIMISM)
            "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad",
            "0x07ffde14ceaade84164fd8fea876aebdcb079362",
            "0x68051f9847ead8cf9c9d6bf918946f56d7827e7d",
            "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2",
            "0xfaf8fd17d9840595845582fcb047df13f006787d"
    );

    private KnownBridgeRouterRegistry() {
    }

    public static boolean isKnownBridgeRouter(String address) {
        return normalize(address) != null && BRIDGE_ROUTERS.contains(normalize(address));
    }

    public static boolean isKnownRewardDistributor(String address) {
        return normalize(address) != null && REWARD_DISTRIBUTORS.contains(normalize(address));
    }

    public static boolean touchesKnownBridgeRouter(Iterable<String> addresses) {
        if (addresses == null) {
            return false;
        }
        for (String address : addresses) {
            if (isKnownBridgeRouter(address)) {
                return true;
            }
        }
        return false;
    }

    public static boolean touchesKnownRewardDistributor(Iterable<String> addresses) {
        if (addresses == null) {
            return false;
        }
        for (String address : addresses) {
            if (isKnownRewardDistributor(address)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (!trimmed.startsWith("0x") && !trimmed.startsWith("0X")) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
