package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * True when the same persisted {@link UserSession} lists two wallet addresses in {@code wallets[]}.
 * Used for FA-001 D1 internal transfer pairing authority (session-scoped, not global tracked_wallets).
 */
@Service
@RequiredArgsConstructor
public class SessionWalletAdjacencyService {

    private final MongoOperations mongoOperations;

    /**
     * @param addressA first hex wallet (0x…)
     * @param addressB second hex wallet (0x…)
     * @return true if some session contains both addresses (case-insensitive), distinct
     */
    public boolean anySessionListsBothAddresses(String addressA, String addressB) {
        String a = normalizeHex(addressA);
        String b = normalizeHex(addressB);
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("wallets").elemMatch(Criteria.where("address").regex(caseInsensitiveExact(a))),
                Criteria.where("wallets").elemMatch(Criteria.where("address").regex(caseInsensitiveExact(b)))
        ));
        return mongoOperations.exists(query, UserSession.class);
    }

    private static String normalizeHex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static Pattern caseInsensitiveExact(String lowerCaseHex) {
        return Pattern.compile("^" + Pattern.quote(lowerCaseHex) + "$", Pattern.CASE_INSENSITIVE);
    }
}
