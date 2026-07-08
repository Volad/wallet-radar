package com.walletradar.application.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;

import java.util.ArrayList;

/**
 * Avoids lost updates when one service saves a {@link UserSession} loaded earlier while another
 * writer (e.g. {@link SessionSettingsCommandService}) updates {@code integrations} concurrently.
 * Full-document Mongo saves would otherwise overwrite fresh integrations with a stale empty list.
 */
public final class SessionWriteMergeSupport {

    private SessionWriteMergeSupport() {
    }

    /**
     * Re-reads {@code integrations} from the database and applies them to {@code draft} before save.
     * Call only from writers that do not intend to modify integrations in this transaction.
     */
    public static void refreshIntegrationsFromDatabase(
            UserSessionRepository userSessionRepository,
            String sessionId,
            UserSession draft
    ) {
        if (userSessionRepository == null || sessionId == null || sessionId.isBlank() || draft == null) {
            return;
        }
        userSessionRepository.findById(sessionId.trim()).ifPresent(fresh -> {
            if (fresh.getIntegrations() == null) {
                return;
            }
            draft.setIntegrations(new ArrayList<>(fresh.getIntegrations()));
        });
    }
}
