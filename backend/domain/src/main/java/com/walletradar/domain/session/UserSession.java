package com.walletradar.domain.session;

import com.walletradar.domain.auth.IdentityProvider;
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
 * Authenticated sessions also carry an {@link IdentityBinding} linking them to a Google account.
 */
@Document(collection = "user_sessions")
@CompoundIndex(name = "wallets_address_idx", def = "{'wallets.address': 1}")
@CompoundIndex(name = "integration_account_ref_idx", def = "{'integrations.accountRef': 1}")
@CompoundIndex(name = "identity_idx", def = "{'identity.provider': 1, 'identity.subject': 1}", unique = true, sparse = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserSession {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String accountingUniverseId;
    private IdentityBinding identity;
    private List<SessionWallet> wallets = new ArrayList<>();
    private List<SessionIntegration> integrations = new ArrayList<>();
    private SessionSettings settings;
    private PipelineState pipelineState;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSeenAt;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class IdentityBinding {
        private IdentityProvider provider;
        /** Google "sub" claim — stable user identifier (idpId). */
        private String subject;
        private String email;
        private Boolean emailVerified;
        private String displayName;
        private String pictureUrl;
        private Instant linkedAt;
    }

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
    public static class SessionIntegration {
        private String integrationId;
        private IntegrationProvider provider;
        private IntegrationStatus status;
        private String displayName;
        private String accountRef;
        private String color;
        private EncryptedSecret encryptedCredentials;
        private boolean readOnly;
        private List<String> capabilities = new ArrayList<>();
        private IntegrationSyncState syncState;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant lastValidatedAt;
        private Instant lastSyncAt;
        private String lastError;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class EncryptedSecret {
        private String keyVersion;
        private String nonceB64;
        private String ciphertextB64;
        private String maskedKey;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class IntegrationSyncState {
        private Integer totalSegments;
        private Integer completedSegments;
        private Integer failedSegments;
        private Integer progressPct;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class SessionSettings {
        private Boolean hideSmallAssets;
        private Boolean showReconciliationWarnings;
        /**
         * Cycle/9 S2: counterparty addresses on third-party venues the user owns
         * (Paradex/MEX/BitGet/etc). Mirrored into {@code AccountingUniverse.members} with
         * {@code type=EXTERNAL_VENUE, backfillEnabled=false} so they are excluded from
         * Net Inflow/Outflow while {@code CounterpartyBasisPool} still carries AVCO.
         */
        private List<ExternalVenue> externalVenues = new ArrayList<>();
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class ExternalVenue {
        private String address;
        private String provider;
        private String label;
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
        ON_CHAIN_RECLASSIFICATION,
        BYBIT_NORMALIZATION,
        DZENGI_NORMALIZATION,
        LINKING,
        PRICING,
        ACCOUNTING_REPLAY,
        PORTFOLIO_SNAPSHOT_REFRESH
    }

    public enum PipelineStatus {
        RUNNING,
        BLOCKED,
        COMPLETE,
        FAILED
    }

    public enum IntegrationProvider {
        BYBIT,
        BINANCE,
        OKX,
        MEXC,
        DZENGI
    }

    public enum IntegrationStatus {
        CONNECTED,
        BACKFILLING,
        READY,
        ERROR,
        DISABLED
    }
}
