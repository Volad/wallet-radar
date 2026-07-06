package com.walletradar.ingestion.config;

import com.walletradar.domain.common.NetworkId;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-044 D5 — feature-flagged, per-chain rollout of router-agnostic native-settlement recovery
 * (D2) and the classification-time native-settlement clarification trigger (D3).
 *
 * <p>Defaults <b>off</b> until the native-pool reconciliation gate (D4) is green on a clean rebuild
 * for the affected chains. Gating semantics:
 * <ul>
 *   <li>{@code enabled = false} — recovery/clarification disabled on every chain.</li>
 *   <li>{@code enabled = true} with an <b>empty</b> {@code chains} allow-list — enabled on every
 *       supported EVM chain.</li>
 *   <li>{@code enabled = true} with a non-empty {@code chains} allow-list — enabled only on the
 *       listed chains (per-chain rollout, e.g. BASE first).</li>
 * </ul>
 * Non-EVM natives (SOL/TON) are always excluded: there is no wrapped-native/{@code Withdrawal}
 * evidence surface for them.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.native-settlement-recovery")
@NoArgsConstructor
@Getter
@Setter
public class NativeSettlementRecoveryProperties {

    private static final Set<NetworkId> NON_EVM_NATIVES = EnumSet.of(NetworkId.SOLANA, NetworkId.TON);

    /** Global guardrail; defaults off (ADR-044 D5). */
    private boolean enabled = false;

    /** Optional per-chain allow-list. Empty means "all supported EVM chains" when {@link #enabled}. */
    private List<NetworkId> chains = new ArrayList<>();

    public void setChains(List<NetworkId> chains) {
        this.chains = chains == null ? new ArrayList<>() : chains;
    }

    /**
     * @return true when D2/D3 native-settlement recovery is active for the supplied chain.
     */
    public boolean isEnabledForChain(NetworkId networkId) {
        if (!enabled || networkId == null || NON_EVM_NATIVES.contains(networkId)) {
            return false;
        }
        return chains.isEmpty() || chains.contains(networkId);
    }

    /**
     * Convenience for callers that only have the raw network id string (e.g. tests / logging).
     */
    public boolean isEnabledForChain(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            return false;
        }
        try {
            return isEnabledForChain(NetworkId.valueOf(networkId.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
