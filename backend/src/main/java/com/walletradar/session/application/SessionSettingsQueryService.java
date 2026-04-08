package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SessionSettingsQueryService {

    private final UserSessionRepository userSessionRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;

    public Optional<SessionSettingsView> findSessionSettings(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::toView);
    }

    private SessionSettingsView toView(UserSession session) {
        return new SessionSettingsView(
                session.getId(),
                session.getWallets() == null ? List.of() : session.getWallets().stream()
                        .map(wallet -> new WalletView(
                                wallet.getAddress(),
                                wallet.getLabel(),
                                wallet.getColor(),
                                wallet.getNetworks() == null ? List.of() : wallet.getNetworks().stream().map(Enum::name).toList()
                        ))
                        .toList(),
                session.getIntegrations() == null ? List.of() : session.getIntegrations().stream()
                        .map(this::toIntegrationView)
                        .toList(),
                session.getSettings() == null || session.getSettings().getHideSmallAssets() == null
                        ? Boolean.TRUE
                        : session.getSettings().getHideSmallAssets(),
                session.getSettings() == null || session.getSettings().getShowReconciliationWarnings() == null
                        ? Boolean.TRUE
                        : session.getSettings().getShowReconciliationWarnings()
        );
    }

    private IntegrationView toIntegrationView(UserSession.SessionIntegration integration) {
        long totalSegments = backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        long completedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        long failedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.FAILED
        );
        int progressPct = totalSegments == 0 ? 0 : (int) Math.round((double) completedSegments * 100.0 / totalSegments);
        return new IntegrationView(
                integration.getIntegrationId(),
                integration.getProvider() == null ? null : integration.getProvider().name(),
                integration.getStatus() == null ? null : integration.getStatus().name(),
                integration.getDisplayName(),
                integration.getAccountRef(),
                integration.getEncryptedCredentials() == null ? null : integration.getEncryptedCredentials().getMaskedKey(),
                integration.isReadOnly(),
                integration.getCapabilities() == null ? List.of() : List.copyOf(integration.getCapabilities()),
                integration.getLastValidatedAt(),
                integration.getLastSyncAt(),
                integration.getLastError(),
                (int) totalSegments,
                (int) completedSegments,
                (int) failedSegments,
                progressPct
        );
    }

    public record SessionSettingsView(
            String sessionId,
            List<WalletView> wallets,
            List<IntegrationView> integrations,
            Boolean hideSmallAssets,
            Boolean showReconciliationWarnings
    ) {
    }

    public record WalletView(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }

    public record IntegrationView(
            String integrationId,
            String provider,
            String status,
            String displayName,
            String accountRef,
            String maskedKey,
            boolean readOnly,
            List<String> capabilities,
            java.time.Instant lastValidatedAt,
            java.time.Instant lastSyncAt,
            String lastError,
            int totalSegments,
            int completedSegments,
            int failedSegments,
            int progressPct
    ) {
    }
}
