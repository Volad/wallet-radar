package com.walletradar.session.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.api.dto.PutSessionSettingsRequest;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.session.support.TonAddressCanonicalizer;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.ingestion.wallet.command.TrackedWalletProjectionService;
import com.walletradar.integration.bybit.BybitApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Overwrites user-session settings, wallets, and supported integrations.
 */
@Service
@RequiredArgsConstructor
public class SessionSettingsCommandService {

    private final UserSessionRepository userSessionRepository;
    private final TrackedWalletProjectionService trackedWalletProjectionService;
    private final AccountUniverseSyncPlanScheduler accountUniverseSyncPlanScheduler;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final BybitApiClient bybitApiClient;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final IntegrationSyncStatusService integrationSyncStatusService;
    private final ObjectMapper objectMapper;

    public Optional<UserSession> overwriteSessionSettings(String sessionId, PutSessionSettingsRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> save(session, request));
    }

    private UserSession save(UserSession session, PutSessionSettingsRequest request) {
        Instant now = Instant.now();
        List<UserSession.SessionWallet> previousWallets = session.getWallets() == null
                ? List.of()
                : new ArrayList<>(session.getWallets());
        Set<String> previousUniverseKeys = sourceKeys(
                previousWallets,
                session.getIntegrations(),
                session.getSettings() == null ? List.of() : session.getSettings().getExternalVenues()
        );
        List<UserSession.SessionWallet> normalizedWallets = normalizeWallets(request.wallets());

        session.setWallets(new ArrayList<>(normalizedWallets));
        session.setSettings(applySettings(session.getSettings(), request));

        syncIntegrations(
                session,
                request.integrations() == null ? List.of() : request.integrations(),
                now
        );

        session.setUpdatedAt(now);
        session.setLastSeenAt(now);
        userSessionRepository.save(session);

        trackedWalletProjectionService.replaceSessionWallets(previousWallets, normalizedWallets, now);
        Set<String> newUniverseKeys = sourceKeys(
                session.getWallets(),
                session.getIntegrations(),
                session.getSettings() == null ? List.of() : session.getSettings().getExternalVenues()
        );
        boolean universeChanged = !previousUniverseKeys.equals(newUniverseKeys);
        if (universeChanged) {
            accountUniverseSyncPlanScheduler.schedule(session.getId(), now);
        }

        return session;
    }

    private UserSession.SessionSettings applySettings(
            UserSession.SessionSettings existing,
            PutSessionSettingsRequest request
    ) {
        UserSession.SessionSettings settings = existing == null ? new UserSession.SessionSettings() : existing;
        settings.setHideSmallAssets(request.hideSmallAssets() == null ? Boolean.TRUE : request.hideSmallAssets());
        settings.setShowReconciliationWarnings(
                request.showReconciliationWarnings() == null ? Boolean.TRUE : request.showReconciliationWarnings()
        );
        settings.setExternalVenues(normalizeExternalVenues(request.externalVenues()));
        return settings;
    }

    private List<UserSession.ExternalVenue> normalizeExternalVenues(
            List<PutSessionSettingsRequest.ExternalVenueEntry> entries
    ) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, UserSession.ExternalVenue> merged = new LinkedHashMap<>();
        for (PutSessionSettingsRequest.ExternalVenueEntry entry : entries) {
            if (entry == null || entry.address() == null || entry.address().isBlank()) {
                continue;
            }
            NetworkId fallbackNetwork = (entry.networks() != null && !entry.networks().isEmpty())
                    ? entry.networks().get(0)
                    : NetworkId.ETHEREUM;
            String canonical = NetworkAddressFormat.canonicalAddress(fallbackNetwork, entry.address());
            if (canonical == null || canonical.isBlank()) {
                continue;
            }
            UserSession.ExternalVenue venue = merged.computeIfAbsent(canonical, ignored -> {
                UserSession.ExternalVenue created = new UserSession.ExternalVenue();
                created.setAddress(canonical);
                created.setNetworks(new ArrayList<>());
                return created;
            });
            if (entry.label() != null && !entry.label().isBlank()) {
                venue.setLabel(entry.label().trim());
            }
            if (entry.provider() != null && !entry.provider().isBlank()) {
                venue.setProvider(entry.provider().trim().toUpperCase(Locale.ROOT));
            }
            LinkedHashSet<NetworkId> combinedNetworks = new LinkedHashSet<>(
                    venue.getNetworks() == null ? List.of() : venue.getNetworks()
            );
            if (entry.networks() != null) {
                combinedNetworks.addAll(entry.networks());
            }
            venue.setNetworks(new ArrayList<>(combinedNetworks));
        }
        return new ArrayList<>(merged.values());
    }

    private void syncIntegrations(
            UserSession session,
            List<PutSessionSettingsRequest.IntegrationEntry> requestedIntegrations,
            Instant now
    ) {
        if (session.getIntegrations() == null) {
            session.setIntegrations(new ArrayList<>());
        }
        validateRequestedIntegrations(requestedIntegrations);

        UserSession.SessionIntegration existingBybit = session.getIntegrations().stream()
                .filter(integration -> integration.getProvider() == UserSession.IntegrationProvider.BYBIT)
                .findFirst()
                .orElse(null);

        PutSessionSettingsRequest.IntegrationEntry requestedBybit = requestedIntegrations.stream()
                .filter(integration -> "BYBIT".equalsIgnoreCase(integration.provider()))
                .findFirst()
                .orElse(null);

        if (requestedBybit == null) {
            if (existingBybit != null) {
                session.getIntegrations().remove(existingBybit);
                backfillSegmentRepository.deleteByIntegrationId(existingBybit.getIntegrationId());
                integrationSyncStatusService.delete(existingBybit.getIntegrationId());
            }
            return;
        }

        if (existingBybit == null) {
            session.getIntegrations().add(createBybitIntegration(session, requestedBybit, now));
            return;
        }

        if (!hasCredentials(requestedBybit)) {
            existingBybit.setDisplayName(normalizeDisplayName(requestedBybit.displayName(), existingBybit.getDisplayName()));
            existingBybit.setUpdatedAt(now);
            existingBybit.setLastError(null);
            return;
        }

        String previousIntegrationId = existingBybit.getIntegrationId();
        UserSession.SessionIntegration refreshed = createBybitIntegration(session, requestedBybit, now);
        copyIntegration(existingBybit, refreshed);
        if (previousIntegrationId != null && !previousIntegrationId.equals(existingBybit.getIntegrationId())) {
            backfillSegmentRepository.deleteByIntegrationId(previousIntegrationId);
            integrationSyncStatusService.delete(previousIntegrationId);
        }
    }

    private UserSession.SessionIntegration createBybitIntegration(
            UserSession session,
            PutSessionSettingsRequest.IntegrationEntry requestedIntegration,
            Instant now
    ) {
        requireCredentials(requestedIntegration);
        BybitApiClient.CredentialInfo credentialInfo;
        try {
            credentialInfo = bybitApiClient.validateCredentials(
                    requestedIntegration.apiKey().trim(),
                    requestedIntegration.apiSecret().trim()
            );
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException("Bybit credential validation failed: " + exception.getMessage(), exception);
        }
        if (credentialInfo.userId() == null || credentialInfo.userId().isBlank()) {
            throw new IllegalStateException("Bybit credential validation did not return userID");
        }

        String integrationId = "BYBIT-" + credentialInfo.userId().trim();
        String accountRef = "BYBIT:" + credentialInfo.userId().trim();
        String maskedKey = maskApiKey(requestedIntegration.apiKey());

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId(integrationId);
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.CONNECTED);
        integration.setDisplayName(normalizeDisplayName(requestedIntegration.displayName(), "Bybit"));
        integration.setAccountRef(accountRef);
        integration.setReadOnly(credentialInfo.readOnly());
        integration.setEncryptedCredentials(sessionSecretCryptoService.encrypt(
                credentialsJson(requestedIntegration.apiKey(), requestedIntegration.apiSecret()),
                maskedKey
        ));
        integration.setCapabilities(extractCapabilities(credentialInfo));
        integration.setSyncState(new UserSession.IntegrationSyncState());
        integration.setCreatedAt(now);
        integration.setUpdatedAt(now);
        integration.setLastValidatedAt(now);
        integration.setLastSyncAt(null);
        integration.setLastError(null);
        return integration;
    }

    private void copyIntegration(UserSession.SessionIntegration target, UserSession.SessionIntegration source) {
        target.setIntegrationId(source.getIntegrationId());
        target.setProvider(source.getProvider());
        target.setStatus(source.getStatus());
        target.setDisplayName(source.getDisplayName());
        target.setAccountRef(source.getAccountRef());
        target.setReadOnly(source.isReadOnly());
        target.setEncryptedCredentials(source.getEncryptedCredentials());
        target.setCapabilities(source.getCapabilities());
        target.setSyncState(source.getSyncState());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setLastValidatedAt(source.getLastValidatedAt());
        target.setLastSyncAt(source.getLastSyncAt());
        target.setLastError(source.getLastError());
    }

    private void validateRequestedIntegrations(List<PutSessionSettingsRequest.IntegrationEntry> requestedIntegrations) {
        Map<String, Long> byProvider = requestedIntegrations.stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.provider().trim().toUpperCase(Locale.ROOT),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        byProvider.forEach((provider, count) -> {
            if (count > 1) {
                throw new IllegalArgumentException("Duplicate integration provider: " + provider);
            }
            if (!"BYBIT".equals(provider)) {
                throw new IllegalArgumentException("Unsupported integration provider: " + provider);
            }
        });
    }

    private void requireCredentials(PutSessionSettingsRequest.IntegrationEntry requestedIntegration) {
        if (requestedIntegration.apiKey() == null || requestedIntegration.apiKey().isBlank()
                || requestedIntegration.apiSecret() == null || requestedIntegration.apiSecret().isBlank()) {
            throw new IllegalArgumentException("Bybit credentials are required");
        }
    }

    private boolean hasCredentials(PutSessionSettingsRequest.IntegrationEntry requestedIntegration) {
        boolean hasKey = requestedIntegration.apiKey() != null && !requestedIntegration.apiKey().isBlank();
        boolean hasSecret = requestedIntegration.apiSecret() != null && !requestedIntegration.apiSecret().isBlank();
        if (hasKey != hasSecret) {
            throw new IllegalArgumentException("Provide both API key and API secret");
        }
        return hasKey;
    }

    private String normalizeDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        return displayName.trim();
    }

    private String maskApiKey(String apiKey) {
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return trimmed;
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
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

    private static NetworkId resolvePrimaryWalletNetwork(PutSessionSettingsRequest.WalletEntry entry) {
        List<NetworkId> networks = entry.networks();
        if (networks != null) {
            if (networks.contains(NetworkId.SOLANA)) {
                return NetworkId.SOLANA;
            }
            if (networks.contains(NetworkId.TON)) {
                return NetworkId.TON;
            }
        }
        String raw = entry.address() == null ? "" : entry.address().trim();
        if (TonAddressCanonicalizer.looksLikeTon(raw)) {
            return NetworkId.TON;
        }
        if (!raw.startsWith("0x") && !raw.startsWith("0X") && raw.length() >= 32) {
            return NetworkId.SOLANA;
        }
        return NetworkId.ETHEREUM;
    }

    private static String canonicalWalletAddress(String address, NetworkId network) {
        String canonical = NetworkAddressFormat.canonicalAddress(network, address);
        return canonical == null ? "" : canonical;
    }

    private List<UserSession.SessionWallet> normalizeWallets(List<PutSessionSettingsRequest.WalletEntry> walletEntries) {
        if (walletEntries == null || walletEntries.isEmpty()) {
            return List.of();
        }
        Map<String, UserSession.SessionWallet> merged = new LinkedHashMap<>();
        for (PutSessionSettingsRequest.WalletEntry entry : walletEntries) {
            NetworkId primaryNetwork = resolvePrimaryWalletNetwork(entry);
            String canonicalAddress = canonicalWalletAddress(entry.address(), primaryNetwork);
            if (canonicalAddress.isBlank()) {
                continue;
            }
            UserSession.SessionWallet wallet = merged.computeIfAbsent(canonicalAddress, ignored -> {
                UserSession.SessionWallet created = new UserSession.SessionWallet();
                created.setAddress(canonicalAddress);
                created.setNetworks(new ArrayList<>());
                return created;
            });
            wallet.setLabel(entry.label().trim());
            wallet.setColor(entry.color().trim().toLowerCase(Locale.ROOT));

            LinkedHashSet<NetworkId> combinedNetworks = new LinkedHashSet<>(wallet.getNetworks());
            combinedNetworks.addAll(entry.networks() == null ? List.of() : entry.networks());
            wallet.setNetworks(new ArrayList<>(combinedNetworks));
        }
        return new ArrayList<>(merged.values());
    }

    private record BybitCredentials(
            String apiKey,
            String apiSecret
    ) {
    }

    private Set<String> sourceKeys(
            List<UserSession.SessionWallet> wallets,
            List<UserSession.SessionIntegration> integrations,
            List<UserSession.ExternalVenue> externalVenues
    ) {
        Set<String> keys = new LinkedHashSet<>();
        if (wallets != null) {
            for (UserSession.SessionWallet wallet : wallets) {
                if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                    continue;
                }
                for (NetworkId network : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                    if (network != null) {
                        keys.add("WALLET|" + wallet.getAddress().trim() + "|" + network.name());
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
        if (externalVenues != null) {
            for (UserSession.ExternalVenue venue : externalVenues) {
                if (venue == null || venue.getAddress() == null || venue.getAddress().isBlank()) {
                    continue;
                }
                keys.add("VENUE|" + venue.getAddress().trim());
            }
        }
        return keys;
    }
}
