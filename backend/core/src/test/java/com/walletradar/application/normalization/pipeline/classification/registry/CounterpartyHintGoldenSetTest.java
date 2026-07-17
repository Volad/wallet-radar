package com.walletradar.application.normalization.pipeline.classification.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader.CounterpartyKey;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader.LoadedCounterpartyHints;
import com.walletradar.application.normalization.pipeline.classification.support.KnownProtocolCounterpartyRegistry.ProtocolAttribution;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 §G.3 behavior-identity proof. The golden sets below are the authoritative snapshot of the
 * former hardcoded Java literals ({@code KnownBridgeRouterRegistry.BRIDGE_ROUTERS} /
 * {@code REWARD_DISTRIBUTORS}, {@code PortfolioConservationGate.KNOWN_BRIDGE_PAYOUT_ADDRESSES} /
 * {@code RELAY_SOURCE_ADDRESSES} / {@code KNOWN_LP_POOL_ADDRESSES}, and the four
 * {@code KnownProtocolCounterpartyRegistry} entries).
 *
 * <p>Any accidental add/drop or scoping change in {@code counterparty-hints.json} fails this test
 * before renormalization.</p>
 */
class CounterpartyHintGoldenSetTest {

    private final CounterpartyHintLoader loader = new CounterpartyHintLoader(new ObjectMapper());

    // ── Golden literals (frozen snapshot of the former Java sources) ─────────────────────────────

    private static final Set<String> GOLDEN_BRIDGE_ROUTERS = Set.of(
            "0x943e6e07a7e8e791dafc44083e54041d743c46e9",
            "0x40f480f247f3ad2ff4c1463e84f03be3a9a03e15",
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
            "0xcd74f91e4d2a49903462d58d6951136a527a5dea",
            "0x00000000aa467eba42a3d604b3d74d63b2b6c6cb",
            "0x6ea77f83ec8693666866ece250411c974ab962a8",
            "0x4446adc0b8136ffc55ddb7a488ba5509ace2a5ef",
            "0x026f252016a7c47cdef1f05a3fc9e20c92a49c37",
            "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae",
            "0x89c6340b1a1f4b25d36cd8b063d49045caf3f818",
            "0xe5a89411c7ef1502123d84ec1f9da9d8574f4293",
            "0x628d684d57c73a5d8ca77f455fdf2cc8bd503c16",
            "0xa3681352316c030b06a83b04394a860a49b47706",
            "0x3c6b2e0b7421254846c53c118e24c65d59eae75e",
            "0x6307119078556fc8ad77781dfc67df20d75fb4f9",
            "0x2270a09bfc9925e3aa083db3e19051fff8ada053",
            "0x864b314d4c5a0399368609581d3e8933a63b9232"
    );

    private static final Set<String> GOLDEN_REWARD_DISTRIBUTORS = Set.of(
            "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad",
            "0x07ffde14ceaade84164fd8fea876aebdcb079362",
            "0x68051f9847ead8cf9c9d6bf918946f56d7827e7d",
            "0x0f5212f63ba8eab0fabd94fc2071d461d9d6ddb2",
            "0xfaf8fd17d9840595845582fcb047df13f006787d"
    );

    private static final Set<String> GOLDEN_BRIDGE_PAYOUTS = Set.of(
            "0xcad97616f91872c02ba3553db315db4015cbe850",
            "0x7ff8bbf9c8ab106db589e7863fb100525f61cce5",
            "0xf70da97812cb96acdf810712aa562db8dfa3dbef",
            "0x91604f590d66ace8975eed6bd16cf55647d1c499",
            "0x8c826f795466e39acbff1bb4eeeb759609377ba1",
            "0xf5f93d26229482adca3e42f84d08d549cf131658",
            "0xc38e4e6a15593f908255214653d3d947ca1c2338",
            "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee",
            "0x00a55649e597d463fd212fbe48a3b40f0e227d06",
            "0x4c1d3fc3fc3c177c3b633427c2f769276c547463",
            "0x113a327221d2c4660684449bfc39bc14ad1aaf38",
            "0x875d6d37ec55c8cf220b9e5080717549d8aa8eca",
            "0x27a16dc786820b16e5c9028b75b99f6f604b5d26",
            "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64"
    );

    private static final Set<String> GOLDEN_RELAY_SOURCES = Set.of(
            "0x2ec2c4c3dc212c990d1bc2b48b0392a3951d926e"
    );

    private static final Set<String> GOLDEN_LP_POOLS = Set.of(
            "0x2a2c512beaa8eb15495726c235472d82effb7a6b",
            "0x2659c6085d26144117d904c46b48b6d180393d27",
            "0x223ec22d67716fca620aee72b25ffe4ece436f25",
            "0xba9dd716ba2a4b9fa7818802beb631f10bd28073"
    );

    private static final Map<CounterpartyKey, ProtocolAttribution> GOLDEN_SCOPED_COUNTERPARTIES = Map.of(
            new CounterpartyKey(NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1"),
            new ProtocolAttribution("LI.FI", "BRIDGE", false),
            new CounterpartyKey(NetworkId.BASE, "0xf70da97812cb96acdf810712aa562db8dfa3dbef"),
            new ProtocolAttribution("Relay", "BRIDGE", false),
            new CounterpartyKey(NetworkId.ZKSYNC, "0x1fa66e2b38d0cc496ec51f81c3e05e6a6708986f"),
            new ProtocolAttribution("rhino.fi", "BRIDGE", true),
            new CounterpartyKey(NetworkId.ZKSYNC, "0x91604f590d66ace8975eed6bd16cf55647d1c499"),
            new ProtocolAttribution("ZkSync Paymaster", "PROTOCOL", false)
    );

    // ── Category counts (guards against silent add/drop) ─────────────────────────────────────────

    @Test
    @DisplayName("loaded set sizes match the frozen golden counts (31/5/14/1/4/4)")
    void categoryCountsMatchGolden() {
        LoadedCounterpartyHints hints = loader.loadFromClasspath();
        assertThat(hints.bridgeRouters()).hasSize(31);
        assertThat(hints.rewardDistributors()).hasSize(5);
        assertThat(hints.bridgePayouts()).hasSize(14);
        assertThat(hints.relaySources()).hasSize(1);
        assertThat(hints.lpPools()).hasSize(4);
        assertThat(hints.scopedCounterparties()).hasSize(4);
    }

    // ── Exact set equality per category ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("bridgeRouters equal the former BRIDGE_ROUTERS literal exactly")
    void bridgeRoutersEqualGolden() {
        assertThat(loader.loadFromClasspath().bridgeRouters())
                .containsExactlyInAnyOrderElementsOf(GOLDEN_BRIDGE_ROUTERS);
    }

    @Test
    @DisplayName("rewardDistributors equal the former REWARD_DISTRIBUTORS literal exactly")
    void rewardDistributorsEqualGolden() {
        assertThat(loader.loadFromClasspath().rewardDistributors())
                .containsExactlyInAnyOrderElementsOf(GOLDEN_REWARD_DISTRIBUTORS);
    }

    @Test
    @DisplayName("bridgePayouts equal the former KNOWN_BRIDGE_PAYOUT_ADDRESSES literal exactly")
    void bridgePayoutsEqualGolden() {
        assertThat(loader.loadFromClasspath().bridgePayouts())
                .containsExactlyInAnyOrderElementsOf(GOLDEN_BRIDGE_PAYOUTS);
    }

    @Test
    @DisplayName("relaySources equal the former RELAY_SOURCE_ADDRESSES literal exactly")
    void relaySourcesEqualGolden() {
        assertThat(loader.loadFromClasspath().relaySources())
                .containsExactlyInAnyOrderElementsOf(GOLDEN_RELAY_SOURCES);
    }

    @Test
    @DisplayName("lpPools equal the former KNOWN_LP_POOL_ADDRESSES literal exactly")
    void lpPoolsEqualGolden() {
        assertThat(loader.loadFromClasspath().lpPools())
                .containsExactlyInAnyOrderElementsOf(GOLDEN_LP_POOLS);
    }

    @Test
    @DisplayName("scoped counterparties equal the former KnownProtocolCounterpartyRegistry map exactly")
    void scopedCounterpartiesEqualGolden() {
        assertThat(loader.loadFromClasspath().scopedCounterparties())
                .containsExactlyInAnyOrderEntriesOf(GOLDEN_SCOPED_COUNTERPARTIES);
    }

    // ── Network-agnostic ("*") membership semantics ──────────────────────────────────────────────

    @Test
    @DisplayName("bridge-router membership is network-agnostic (matches on any network context)")
    void bridgeRouterMembershipIsNetworkAgnostic() {
        CounterpartyHintService service = new CounterpartyHintService(loader);
        String liFiDiamond = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        // The set carries no networkId; the same address matches regardless of any network context.
        assertThat(service.isBridgeRouter(liFiDiamond)).isTrue();
        assertThat(service.isBridgeRouter(liFiDiamond.toUpperCase())).isTrue();
        assertThat(service.isBridgePayout("0xcad97616f91872c02ba3553db315db4015cbe850")).isTrue();
        assertThat(service.isLpPool("0x2a2c512beaa8eb15495726c235472d82effb7a6b")).isTrue();
        assertThat(service.isRelaySource("0x2ec2c4c3dc212c990d1bc2b48b0392a3951d926e")).isTrue();
    }

    // ── Network-scoped PROTOCOL_COUNTERPARTY semantics ───────────────────────────────────────────

    @Test
    @DisplayName("scoped counterparty resolves on its network but not on another")
    void scopedCounterpartySemantics() {
        CounterpartyHintService service = new CounterpartyHintService(loader);
        var onBase = service.lookupCounterparty(NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1");
        assertThat(onBase).isPresent();
        assertThat(onBase.get()).isEqualTo(new ProtocolAttribution("LI.FI", "BRIDGE", false));
        // Same address on a different network is not scoped → empty (pins network-scoped semantics).
        assertThat(service.lookupCounterparty(NetworkId.ARBITRUM, "0x8c826f795466e39acbff1bb4eeeb759609377ba1"))
                .isEmpty();
    }

    // ── Dual-purpose overlap (§C / §F): same address in multiple categories ───────────────────────

    @Test
    @DisplayName("dual-purpose addresses appear in every category they belong to")
    void dualPurposeOverlapPreserved() {
        LoadedCounterpartyHints hints = loader.loadFromClasspath();
        // 0x00a55649... is both a BRIDGE_ROUTER and a BRIDGE_PAYOUT.
        assertThat(hints.bridgeRouters()).contains("0x00a55649e597d463fd212fbe48a3b40f0e227d06");
        assertThat(hints.bridgePayouts()).contains("0x00a55649e597d463fd212fbe48a3b40f0e227d06");
        // 0x2a2c512b... / 0x2659c608... / 0xba9dd716... / 0x223ec22d... are BRIDGE_ROUTER + LP_POOL.
        for (String addr : GOLDEN_LP_POOLS) {
            assertThat(hints.bridgeRouters()).contains(addr);
            assertThat(hints.lpPools()).contains(addr);
        }
        // 0xf70da978... / 0x91604f59... / 0x8c826f79... are BRIDGE_PAYOUT + PROTOCOL_COUNTERPARTY.
        assertThat(hints.bridgePayouts()).contains(
                "0xf70da97812cb96acdf810712aa562db8dfa3dbef",
                "0x91604f590d66ace8975eed6bd16cf55647d1c499",
                "0x8c826f795466e39acbff1bb4eeeb759609377ba1");
        assertThat(hints.scopedCounterparties()).containsKeys(
                new CounterpartyKey(NetworkId.BASE, "0xf70da97812cb96acdf810712aa562db8dfa3dbef"),
                new CounterpartyKey(NetworkId.ZKSYNC, "0x91604f590d66ace8975eed6bd16cf55647d1c499"),
                new CounterpartyKey(NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1"));
    }
}
