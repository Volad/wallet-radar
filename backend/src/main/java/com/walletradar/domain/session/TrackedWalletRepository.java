package com.walletradar.domain.session;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for installation-wide tracked wallet projection.
 */
public interface TrackedWalletRepository extends MongoRepository<TrackedWallet, String> {

    List<TrackedWallet> findAllByOrderByAddressAsc();
}
