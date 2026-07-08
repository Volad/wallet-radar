package com.walletradar.domain.session;

import com.walletradar.domain.auth.IdentityProvider;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for user session wallet settings.
 */
public interface UserSessionRepository extends MongoRepository<UserSession, String> {

    List<UserSession> findAllByWalletsAddress(String address);

    List<UserSession> findAllByIntegrationsIntegrationId(String integrationId);

    /** Looks up the canonical session for a given identity provider + subject (Google sub). */
    Optional<UserSession> findByIdentityProviderAndIdentitySubject(
            IdentityProvider provider, String subject);
}
