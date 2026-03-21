package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maintains the installation-wide tracked wallet projection used by canonical normalization.
 */
@Service
@RequiredArgsConstructor
public class TrackedWalletProjectionService {

    private final MongoOperations mongoOperations;

    public void replaceSessionWallets(
            List<UserSession.SessionWallet> previousWallets,
            List<UserSession.SessionWallet> currentWallets,
            Instant now
    ) {
        Set<String> previousAddresses = toAddressSet(previousWallets);
        Set<String> currentAddresses = toAddressSet(currentWallets);

        Set<String> retained = new TreeSet<>(currentAddresses);
        retained.retainAll(previousAddresses);

        Set<String> added = new TreeSet<>(currentAddresses);
        added.removeAll(previousAddresses);

        Set<String> removed = new TreeSet<>(previousAddresses);
        removed.removeAll(currentAddresses);

        retained.forEach(address -> touch(address, now));
        added.forEach(address -> incrementOrCreate(address, now));
        removed.forEach(address -> decrementOrDelete(address, now));
    }

    private Set<String> toAddressSet(List<UserSession.SessionWallet> wallets) {
        Set<String> addresses = new TreeSet<>();
        if (wallets == null) {
            return addresses;
        }
        for (UserSession.SessionWallet wallet : wallets) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            addresses.add(wallet.getAddress().trim().toLowerCase(Locale.ROOT));
        }
        return addresses;
    }

    private void touch(String address, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(address));
        Update update = new Update().set("lastSeenAt", now);
        mongoOperations.updateFirst(query, update, TrackedWallet.class);
    }

    private void incrementOrCreate(String address, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(address));
        Update update = new Update()
                .setOnInsert("_id", address)
                .setOnInsert("firstSeenAt", now)
                .set("lastSeenAt", now)
                .inc("refCount", 1);
        mongoOperations.upsert(query, update, TrackedWallet.class);
    }

    private void decrementOrDelete(String address, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(address));
        Update update = new Update()
                .set("lastSeenAt", now)
                .inc("refCount", -1);
        TrackedWallet updated = mongoOperations.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                TrackedWallet.class
        );
        if (updated != null && updated.getRefCount() <= 0) {
            Query deleteQuery = Query.query(Criteria.where("_id").is(address).and("refCount").lte(0));
            mongoOperations.remove(deleteQuery, TrackedWallet.class);
        }
    }
}
