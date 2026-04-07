package com.walletradar.domain.session;

import com.walletradar.domain.common.NetworkId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-specific wallet configuration for the UI (address + label/color + selected networks).
 * Session identity is provided by client-generated UUID (sessionId).
 */
@Document(collection = "user_sessions")
@CompoundIndex(name = "wallets_address_idx", def = "{'wallets.address': 1}")
@CompoundIndex(name = "accounting_universe_idx", def = "{'accountingUniverseId': 1}")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserSession {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String accountingUniverseId;
    private List<SessionWallet> wallets = new ArrayList<>();
    private PipelineState pipelineState;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSeenAt;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class SessionWallet {
        /** Canonical address form used for matching with sync_status. */
        private String address;
        private String label;
        private String color;
        private List<NetworkId> networks = new ArrayList<>();
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class PipelineState {
        private PipelineStage stage;
        private PipelineStatus status;
        private String message;
        private Instant updatedAt;
    }

    public enum PipelineStage {
        BACKFILL,
        ON_CHAIN_NORMALIZATION,
        ON_CHAIN_CLARIFICATION,
        BYBIT_NORMALIZATION,
        PRICING,
        ACCOUNTING_REPLAY
    }

    public enum PipelineStatus {
        RUNNING,
        BLOCKED,
        COMPLETE,
        FAILED
    }
}
