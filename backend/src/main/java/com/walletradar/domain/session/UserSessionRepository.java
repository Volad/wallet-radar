package com.walletradar.domain.session;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for user session wallet settings.
 */
public interface UserSessionRepository extends MongoRepository<UserSession, String> {

    List<UserSession> findAllByWalletsAddress(String address);
}
