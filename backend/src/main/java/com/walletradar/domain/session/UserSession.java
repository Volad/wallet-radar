package com.walletradar.domain.session;

import com.walletradar.domain.common.NetworkId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-specific wallet configuration for the UI (address + label/color + selected networks).
 * Session identity is provided by client-generated UUID (sessionId).
 */
@Document(collection = "user_sessions")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserSession {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private List<SessionWallet> wallets = new ArrayList<>();
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
}
