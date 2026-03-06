package com.walletradar.domain.session;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for user session wallet settings.
 */
public interface UserSessionRepository extends MongoRepository<UserSession, String> {
}
