package com.walletradar.application.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.session.application.AccountUniverseSyncPlanScheduler;
import com.walletradar.application.session.application.SessionPipelineStateService;
import com.walletradar.application.session.application.SessionWriteMergeSupport;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
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
 * Persists session wallet settings and schedules deferred universe sync planning (backfill windows + segments).
 * Repeated add for the same sessionId replaces the full wallet set.
 */
@Service
@RequiredArgsConstructor
public class SessionCommandService {

    private final UserSessionRepository userSessionRepository;
    private final TrackedWalletProjectionService trackedWalletProjectionService;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final AccountUniverseSyncPlanScheduler accountUniverseSyncPlanScheduler;

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
        SessionWriteMergeSupport.refreshIntegrationsFromDatabase(userSessionRepository, normalizedSessionId, session);
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
            accountUniverseSyncPlanScheduler.schedule(normalizedSessionId, now);
        }
        return new SessionCommandResult(normalizedSessionId, "Session saved, universe sync scheduled");
    }

    private List<UserSession.SessionWallet> normalizeWallets(List<SessionWalletPayload> walletEntries) {
        Map<String, UserSession.SessionWallet> merged = new LinkedHashMap<>();
        for (SessionWalletPayload entry : walletEntries) {
            String canonicalAddress = canonicalizeWalletAddress(entry.address());
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
            combinedNetworks.addAll(entry.networks());
            wallet.setNetworks(new ArrayList<>(combinedNetworks));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Cycle/6 D1/D2: Canonicalise wallet address per network family before persisting.
     * <ul>
     *     <li>EVM addresses (starts with {@code 0x}) → lower-case hex.</li>
     *     <li>TON addresses (UQ/EQ friendly form, raw {@code workchain:hex}) → canonical UQ form
     *         via {@link TonAddressCanonicalizer#preferredMemberRef(String)}.</li>
     *     <li>Solana base58 addresses → case-sensitive (do not lower-case).</li>
     * </ul>
     * Lower-casing case-sensitive base58 / base64 addresses produced lookup misses in
     * {@link com.walletradar.application.session.application.AccountingUniverseService#classify(String, NetworkId)}
     * and silently broke universe membership for SOL/TON wallets.
     */
    private static String canonicalizeWalletAddress(String rawAddress) {
        if (rawAddress == null) {
            return "";
        }
        String trimmed = rawAddress.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        // Solana base58 and other formats — preserve original case.
        return trimmed;
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
                String canonical = canonicalizeWalletAddress(wallet.getAddress());
                for (NetworkId network : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                    if (network != null) {
                        keys.add("WALLET|" + canonical + "|" + network.name());
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
