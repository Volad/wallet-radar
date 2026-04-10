package com.walletradar.session.application;

import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves stable owner scope for replay/history while preserving current
 * session wallet subset for live on-chain balances.
 */
@Service
@RequiredArgsConstructor
public class AccountingUniverseService {

    private static final String BYBIT_PREFIX = "BYBIT:";

    private final AccountingUniverseRepository accountingUniverseRepository;
    private final MongoOperations mongoOperations;

    public AccountingUniverseScope resolveScope(UserSession session) {
        if (session == null || blank(session.getId())) {
            return AccountingUniverseScope.empty();
        }

        LinkedHashSet<String> onChainWalletRefs = currentOnChainWalletRefs(session);
        LinkedHashSet<String> memberRefs = new LinkedHashSet<>();
        String accountingUniverseId = normalizedAccountingUniverseId(session);

        if (!blank(accountingUniverseId)) {
            accountingUniverseRepository.findById(accountingUniverseId)
                    .map(AccountingUniverse::getMembers)
                    .stream()
                    .flatMap(List::stream)
                    .filter(Objects::nonNull)
                    .map(AccountingUniverse.Member::getRef)
                    .map(this::normalizeMemberRef)
                    .filter(ref -> !ref.isBlank())
                    .forEach(memberRefs::add);
        }

        memberRefs.addAll(onChainWalletRefs);
        enabledIntegrationRefs(session).stream()
                .map(this::normalizeMemberRef)
                .filter(ref -> !ref.isBlank())
                .forEach(memberRefs::add);

        if (memberRefs.equals(onChainWalletRefs)) {
            discoverLegacyBybitRefs(session.getId()).stream()
                    .map(this::normalizeMemberRef)
                    .filter(ref -> !ref.isBlank())
                    .forEach(memberRefs::add);
        }

        return new AccountingUniverseScope(
                accountingUniverseId,
                List.copyOf(memberRefs),
                List.copyOf(onChainWalletRefs)
        );
    }

    public boolean shareUniverseMembers(String leftRef, String rightRef) {
        String normalizedLeft = normalizeMemberRef(leftRef);
        String normalizedRight = normalizeMemberRef(rightRef);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank() || normalizedLeft.equals(normalizedRight)) {
            return false;
        }
        Query query = Query.query(Criteria.where("members.ref").all(List.of(normalizedLeft, normalizedRight)));
        return mongoOperations.exists(query, AccountingUniverse.class);
    }

    private LinkedHashSet<String> currentOnChainWalletRefs(UserSession session) {
        LinkedHashSet<String> onChainWalletRefs = new LinkedHashSet<>();
        if (session.getWallets() == null) {
            return onChainWalletRefs;
        }
        session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .map(this::normalizeWalletAddress)
                .filter(ref -> !ref.isBlank())
                .forEach(onChainWalletRefs::add);
        return onChainWalletRefs;
    }

    private List<String> enabledIntegrationRefs(UserSession session) {
        if (session.getIntegrations() == null) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .map(UserSession.SessionIntegration::getAccountRef)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<String> discoverLegacyBybitRefs(String sessionId) {
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        mongoOperations.findDistinct(query, "walletRef", ExternalLedgerRaw.class, String.class).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeMemberRef)
                .filter(ref -> !ref.isBlank())
                .forEach(refs::add);
        mongoOperations.findDistinct(query, "uid", ExternalLedgerRaw.class, String.class).stream()
                .filter(Objects::nonNull)
                .map(uid -> BYBIT_PREFIX + uid.trim())
                .map(this::normalizeMemberRef)
                .filter(ref -> !ref.isBlank())
                .forEach(refs::add);
        return List.copyOf(refs);
    }

    private String normalizeWalletAddress(String address) {
        if (blank(address)) {
            return "";
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeMemberRef(String ref) {
        if (blank(ref)) {
            return "";
        }
        String trimmed = ref.trim();
        if (trimmed.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            return BYBIT_PREFIX + trimmed.substring(BYBIT_PREFIX.length()).trim();
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }

    private String normalizedAccountingUniverseId(UserSession session) {
        if (!blank(session.getAccountingUniverseId())) {
            return session.getAccountingUniverseId().trim();
        }
        return session.getId().trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AccountingUniverseScope(
            String accountingUniverseId,
            List<String> memberRefs,
            List<String> onChainWalletRefs
    ) {
        private static AccountingUniverseScope empty() {
            return new AccountingUniverseScope(null, List.of(), List.of());
        }
    }
}
