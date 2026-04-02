package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists session wallet settings and triggers wallet backfill.
 * Repeated add for the same sessionId replaces the full wallet set.
 */
@Service
@RequiredArgsConstructor
public class SessionCommandService {

    private final UserSessionRepository userSessionRepository;
    private final WalletBackfillService walletBackfillService;
    private final TrackedWalletProjectionService trackedWalletProjectionService;
    private final SessionPipelineStateService sessionPipelineStateService;

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
        session.setWallets(normalizedWallets);
        session.setUpdatedAt(now);
        session.setLastSeenAt(now);
        userSessionRepository.save(session);
        trackedWalletProjectionService.replaceSessionWallets(previousWallets, normalizedWallets, now);
        sessionPipelineStateService.markStageRunning(
                normalizedSessionId,
                UserSession.PipelineStage.BACKFILL,
                "Raw backfill started"
        );

        for (UserSession.SessionWallet wallet : normalizedWallets) {
            walletBackfillService.addWallet(wallet.getAddress(), wallet.getNetworks());
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
}
