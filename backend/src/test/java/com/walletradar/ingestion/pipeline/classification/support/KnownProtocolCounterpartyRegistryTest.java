package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnownProtocolCounterpartyRegistryTest {

    @Test
    @DisplayName("LI.FI Permit2 Proxy on BASE is found")
    void liFiPermit2ProxyOnBase() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("LI.FI");
        assertThat(result.get().counterpartyType()).isEqualTo("BRIDGE");
        assertThat(result.get().asBridge()).isFalse();
    }

    @Test
    @DisplayName("Relay Solver on BASE is found")
    void relaySolverOnBase() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.BASE, "0xf70da97812cb96acdf810712aa562db8dfa3dbef");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Relay");
    }

    @Test
    @DisplayName("rhino.fi on ZKSYNC is found with asBridge=true")
    void rhinoFiOnZkSync() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.ZKSYNC, "0x1fa66e2b38d0cc496ec51f81c3e05e6a6708986f");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("rhino.fi");
        assertThat(result.get().asBridge()).isTrue();
    }

    @Test
    @DisplayName("ZkSync Paymaster on ZKSYNC is found")
    void zkSyncPaymaster() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.ZKSYNC, "0x91604f590d66ace8975eed6bd16cf55647d1c499");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("ZkSync Paymaster");
        assertThat(result.get().counterpartyType()).isEqualTo("PROTOCOL");
    }

    @Test
    @DisplayName("unknown address returns empty")
    void unknownAddressReturnsEmpty() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.BASE, "0xaaaa000000000000000000000000000000000001");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("same address on wrong network returns empty")
    void wrongNetworkReturnsEmpty() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.ARBITRUM, "0x8c826f795466e39acbff1bb4eeeb759609377ba1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("lookup is case-insensitive")
    void caseInsensitiveLookup() {
        var result = KnownProtocolCounterpartyRegistry.lookup(
                NetworkId.BASE, "0x8C826F795466E39ACBFF1BB4EEEB759609377BA1");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("LI.FI");
    }

    @Test
    @DisplayName("null inputs return empty")
    void nullInputsReturnEmpty() {
        assertThat(KnownProtocolCounterpartyRegistry.lookup(null, "0xabc")).isEmpty();
        assertThat(KnownProtocolCounterpartyRegistry.lookup(NetworkId.BASE, null)).isEmpty();
        assertThat(KnownProtocolCounterpartyRegistry.lookup(NetworkId.BASE, "")).isEmpty();
    }
}
