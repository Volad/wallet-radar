package com.walletradar.application.session.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Creates, updates, and removes session-owned external integrations.
 */
@Service
@RequiredArgsConstructor
public class SessionIntegrationCommandService {

    private final UserSessionRepository userSessionRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final BybitApiClient bybitApiClient;
    private final IntegrationSyncStatusService integrationSyncStatusService;
    private final AccountUniverseSyncPlanScheduler accountUniverseSyncPlanScheduler;
    private final ObjectMapper objectMapper;

    public Optional<IntegrationCommandResult> upsertBybit(
            String sessionId,
            String displayName,
            String apiKey,
            String apiSecret
    ) {
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> saveBybit(session, displayName, apiKey, apiSecret));
    }

    public Optional<IntegrationRemovalResult> removeIntegration(String sessionId, String integrationId) {
        return userSessionRepository.findById(sessionId.trim()).map(session -> {
            Set<String> previousUniverseKeys = sourceKeys(session.getWallets(), session.getIntegrations());
            if (session.getIntegrations() == null) {
                throw new IllegalArgumentException("Integration not found");
            }
            UserSession.SessionIntegration existing = session.getIntegrations().stream()
                    .filter(integration -> integrationId.equals(integration.getIntegrationId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Integration not found"));
            session.getIntegrations().remove(existing);
            Instant now = Instant.now();
            session.setUpdatedAt(now);
            userSessionRepository.save(session);
            backfillSegmentRepository.deleteByIntegrationId(integrationId);
            integrationSyncStatusService.delete(integrationId);
            if (!previousUniverseKeys.equals(sourceKeys(session.getWallets(), session.getIntegrations()))) {
                accountUniverseSyncPlanScheduler.schedule(session.getId(), now);
            }
            return new IntegrationRemovalResult(integrationId, "Integration removed");
        });
    }

    private IntegrationCommandResult saveBybit(
            UserSession session,
            String displayName,
            String apiKey,
            String apiSecret
    ) {
        Instant now = Instant.now();
        Set<String> previousUniverseKeys = sourceKeys(session.getWallets(), session.getIntegrations());
        BybitApiClient.CredentialInfo credentialInfo;
        try {
            credentialInfo = bybitApiClient.validateCredentials(apiKey, apiSecret);
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException("Bybit credential validation failed: " + exception.getMessage(), exception);
        }
        if (credentialInfo.userId() == null || credentialInfo.userId().isBlank()) {
            throw new IllegalStateException("Bybit credential validation did not return userID");
        }

        String normalizedDisplayName = displayName == null || displayName.isBlank()
                ? "Bybit"
                : displayName.trim();
        String accountRef = "BYBIT:" + credentialInfo.userId().trim();
        String integrationId = "BYBIT-" + credentialInfo.userId().trim();
        String maskedKey = maskApiKey(apiKey);

        if (session.getIntegrations() == null) {
            session.setIntegrations(new ArrayList<>());
        }
        UserSession.SessionIntegration integration = session.getIntegrations().stream()
                .filter(candidate -> integrationId.equals(candidate.getIntegrationId()))
                .findFirst()
                .orElseGet(() -> {
                    UserSession.SessionIntegration created = new UserSession.SessionIntegration();
                    created.setIntegrationId(integrationId);
                    created.setCreatedAt(now);
                    session.getIntegrations().add(created);
                    return created;
                });

        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.CONNECTED);
        integration.setDisplayName(normalizedDisplayName);
        integration.setAccountRef(accountRef);
        integration.setReadOnly(credentialInfo.readOnly());
        integration.setEncryptedCredentials(sessionSecretCryptoService.encrypt(
                credentialsJson(apiKey, apiSecret),
                maskedKey
        ));
        integration.setCapabilities(extractCapabilities(credentialInfo));
        integration.setLastValidatedAt(now);
        integration.setUpdatedAt(now);
        integration.setLastError(null);
        integration.setSyncState(new UserSession.IntegrationSyncState());

        session.setUpdatedAt(now);
        session.setLastSeenAt(now);
        userSessionRepository.save(session);
        if (!previousUniverseKeys.equals(sourceKeys(session.getWallets(), session.getIntegrations()))) {
            accountUniverseSyncPlanScheduler.schedule(session.getId(), now);
        }

        return new IntegrationCommandResult(
                integration.getIntegrationId(),
                integration.getProvider().name(),
                integration.getStatus().name(),
                integration.getDisplayName(),
                integration.getAccountRef(),
                maskedKey,
                "Bybit integration saved, universe sync scheduled"
        );
    }

    private String credentialsJson(String apiKey, String apiSecret) {
        try {
            return objectMapper.writeValueAsString(new BybitCredentials(apiKey, apiSecret));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Bybit credentials", exception);
        }
    }

    private List<String> extractCapabilities(BybitApiClient.CredentialInfo info) {
        if (info.permissions() == null || info.permissions().isMissingNode() || info.permissions().isNull()) {
            return List.of();
        }
        List<String> capabilities = new ArrayList<>();
        info.permissions().fields().forEachRemaining(entry -> {
            if (entry.getValue().isArray() && !entry.getValue().isEmpty()) {
                capabilities.add(entry.getKey().toUpperCase(Locale.ROOT));
            } else if (entry.getValue().asBoolean(false)) {
                capabilities.add(entry.getKey().toUpperCase(Locale.ROOT));
            }
        });
        return List.copyOf(capabilities);
    }

    private String maskApiKey(String apiKey) {
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return trimmed;
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private Set<String> sourceKeys(
            List<UserSession.SessionWallet> wallets,
            List<UserSession.SessionIntegration> integrations
    ) {
        Set<String> keys = new LinkedHashSet<>();
        if (wallets != null) {
            for (UserSession.SessionWallet wallet : wallets) {
                if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                    continue;
                }
                for (NetworkId network : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                    if (network != null) {
                        keys.add("WALLET|" + wallet.getAddress().trim().toLowerCase(Locale.ROOT) + "|" + network.name());
                    }
                }
            }
        }
        if (integrations != null) {
            for (UserSession.SessionIntegration integration : integrations) {
                if (integration == null
                        || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                        || integration.getIntegrationId() == null
                        || integration.getIntegrationId().isBlank()) {
                    continue;
                }
                keys.add("INTEGRATION|" + integration.getIntegrationId().trim());
            }
        }
        return keys;
    }

    private record BybitCredentials(
            String apiKey,
            String apiSecret
    ) {
    }

    public record IntegrationCommandResult(
            String integrationId,
            String provider,
            String status,
            String displayName,
            String accountRef,
            String maskedKey,
            String message
    ) {
    }

    public record IntegrationRemovalResult(
            String integrationId,
            String message
    ) {
    }
}
