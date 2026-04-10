package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.session.application.AccountUniverseSyncPlannerService;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persists session wallet settings and triggers wallet backfill.
 * Repeated add for the same sessionId replaces the full wallet set.
 */
@Service
@RequiredArgsConstructor
public class SessionCommandService {

    private final UserSessionRepository userSessionRepository;
    private final TrackedWalletProjectionService trackedWalletProjectionService;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final AccountUniverseSyncPlannerService accountUniverseSyncPlannerService;

    public SessionCommandResult addSession(String sessionId, List<SessionWalletPayload> walletEntries) {
        String normalizedSessionId = sessionId.trim();
        Instant now = Instant.now();
        List<UserSession.SessionWallet> normalizedWallets = normalizeWallets(walletEntries);

        UserSession session = userSessionRepository.findById(normalizedSessionId).orElseGet(() -> {
            UserSession created = new UserSession();
            created.setId(normalizedSessionId);
            created.setCreatedAt(now);
            return created;
        });

        if (session.getCreatedAt() == null) {
            session.setCreatedAt(now);
        }
        List<UserSession.SessionWallet> previousWallets = new ArrayList<>(session.getWallets());
        Set<String> previousUniverseKeys = sourceKeys(previousWallets, session.getIntegrations());
        session.setWallets(normalizedWallets);
        if (session.getIntegrations() == null) {
            session.setIntegrations(new ArrayList<>());
        }
        if (session.getSettings() == null) {
            session.setSettings(defaultSettings());
        } else {
            applySettingsDefaults(session.getSettings());
        }
        session.setUpdatedAt(now);
        session.setLastSeenAt(now);
        userSessionRepository.save(session);
        trackedWalletProjectionService.replaceSessionWallets(previousWallets, normalizedWallets, now);
        boolean universeChanged = !previousUniverseKeys.equals(sourceKeys(session.getWallets(), session.getIntegrations()));

        if (normalizedWallets.isEmpty()) {
            sessionPipelineStateService.markStageComplete(
                    normalizedSessionId,
                    UserSession.PipelineStage.BACKFILL,
                    "Empty session created"
            );
            return new SessionCommandResult(normalizedSessionId, "Session created");
        }

        if (universeChanged) {
            accountUniverseSyncPlannerService.sync(normalizedSessionId, now);
        }
        return new SessionCommandResult(normalizedSessionId, "Session saved, backfill started");
    }

    private List<UserSession.SessionWallet> normalizeWallets(List<SessionWalletPayload> walletEntries) {
        Map<String, UserSession.SessionWallet> merged = new LinkedHashMap<>();
        for (SessionWalletPayload entry : walletEntries) {
            String canonicalAddress = entry.address().trim().toLowerCase(Locale.ROOT);
            UserSession.SessionWallet wallet = merged.computeIfAbsent(canonicalAddress, ignored -> {
                UserSession.SessionWallet created = new UserSession.SessionWallet();
                created.setAddress(canonicalAddress);
                created.setNetworks(new ArrayList<>());
                return created;
            });
            wallet.setLabel(entry.label().trim());
            wallet.setColor(entry.color().trim().toLowerCase(Locale.ROOT));

            LinkedHashSet<NetworkId> combinedNetworks = new LinkedHashSet<>(wallet.getNetworks());
            combinedNetworks.addAll(entry.networks());
            wallet.setNetworks(new ArrayList<>(combinedNetworks));
        }
        return new ArrayList<>(merged.values());
    }

    private UserSession.SessionSettings defaultSettings() {
        UserSession.SessionSettings settings = new UserSession.SessionSettings();
        settings.setHideSmallAssets(Boolean.TRUE);
        settings.setShowReconciliationWarnings(Boolean.TRUE);
        return settings;
    }

    private void applySettingsDefaults(UserSession.SessionSettings settings) {
        if (settings.getHideSmallAssets() == null) {
            settings.setHideSmallAssets(Boolean.TRUE);
        }
        if (settings.getShowReconciliationWarnings() == null) {
            settings.setShowReconciliationWarnings(Boolean.TRUE);
        }
    }

    public record SessionWalletPayload(
            String address,
            String label,
            String color,
            List<NetworkId> networks
    ) {
    }

    public record SessionCommandResult(
            String sessionId,
            String message
    ) {
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
}
