package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maintains stable owner/accounting-universe membership for session-scoped
 * reads. The universe is broader than the current UI wallet subset and may
 * include exchange custody refs such as {@code BYBIT:<uid>}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingUniverseService {

    private static final String BYBIT_PROVIDER = "BYBIT";
    private static final String BYBIT_PREFIX = "BYBIT:";

    private final AccountingUniverseRepository accountingUniverseRepository;
    private final UserSessionRepository userSessionRepository;
    private final MongoOperations mongoOperations;

    public void ensureSessionWalletMembership(UserSession session, Instant now) {
        if (session == null || blank(session.getId())) {
            return;
        }
        String universeId = ensureUniverseId(session);
        AccountingUniverse universe = accountingUniverseRepository.findById(universeId)
                .orElseGet(() -> newUniverse(universeId, now));
        boolean changed = mergeWalletMembers(universe, session.getWallets(), now);
        if (changed) {
            universe.setUpdatedAt(now);
            accountingUniverseRepository.save(universe);
        }
    }

    public void ensureBybitMembership(String sessionId, Instant now) {
        if (blank(sessionId)) {
            return;
        }
        UserSession session = userSessionRepository.findById(sessionId.trim()).orElse(null);
        if (session == null) {
            return;
        }
        String universeId = ensureUniverseId(session);
        if (session.getAccountingUniverseId() == null) {
            session.setAccountingUniverseId(universeId);
            session.setUpdatedAt(now);
            userSessionRepository.save(session);
        }

        List<String> bybitRefs = discoverBybitRefs(session.getId());
        if (bybitRefs.isEmpty()) {
            return;
        }

        AccountingUniverse universe = accountingUniverseRepository.findById(universeId)
                .orElseGet(() -> newUniverse(universeId, now));
        boolean changed = false;
        for (String ref : bybitRefs) {
            changed |= mergeExchangeMember(universe, ref, now);
        }
        if (changed) {
            universe.setUpdatedAt(now);
            accountingUniverseRepository.save(universe);
        }
    }

    public AccountingUniverseScope resolveScope(UserSession session) {
        if (session == null || blank(session.getId())) {
            return AccountingUniverseScope.empty();
        }

        List<UserSession.SessionWallet> sessionWallets = session.getWallets() == null ? List.of() : session.getWallets();
        LinkedHashSet<String> currentWalletRefs = sessionWallets.stream()
                .map(UserSession.SessionWallet::getAddress)
                .map(this::normalizeWalletAddress)
                .filter(ref -> !ref.isBlank())
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        LinkedHashSet<String> memberRefs = new LinkedHashSet<>(currentWalletRefs);
        String universeId = defaultUniverseId(session.getId());
        if (!blank(session.getAccountingUniverseId())) {
            universeId = session.getAccountingUniverseId().trim();
        }

        accountingUniverseRepository.findById(universeId)
                .ifPresent(universe -> universe.getMembers().stream()
                        .map(AccountingUniverse.Member::getRef)
                        .map(this::normalizeMemberRef)
                        .filter(ref -> !ref.isBlank())
                        .forEach(memberRefs::add));

        discoverBybitRefs(session.getId()).stream()
                .map(this::normalizeMemberRef)
                .filter(ref -> !ref.isBlank())
                .forEach(memberRefs::add);

        return new AccountingUniverseScope(
                universeId,
                List.copyOf(memberRefs),
                List.copyOf(currentWalletRefs)
        );
    }

    @EventListener
    public void onBybitNormalizationCompleted(BybitNormalizationCompletedEvent event) {
        if (event == null || blank(event.sessionId())) {
            return;
        }
        ensureBybitMembership(event.sessionId(), Instant.now());
        log.info("Accounting universe synced from Bybit evidence: sessionId={}", event.sessionId());
    }

    private AccountingUniverse newUniverse(String universeId, Instant now) {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId(universeId);
        universe.setCreatedAt(now);
        universe.setUpdatedAt(now);
        universe.setMembers(new ArrayList<>());
        return universe;
    }

    private boolean mergeWalletMembers(
            AccountingUniverse universe,
            List<UserSession.SessionWallet> wallets,
            Instant now
    ) {
        if (wallets == null || wallets.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (UserSession.SessionWallet wallet : wallets) {
            if (wallet == null) {
                continue;
            }
            String ref = normalizeWalletAddress(wallet.getAddress());
            if (ref.isBlank()) {
                continue;
            }
            AccountingUniverse.Member member = findOrCreateMember(
                    universe,
                    ref,
                    AccountingUniverse.MemberType.ON_CHAIN_WALLET,
                    null,
                    now
            );
            changed |= mergeNetworks(member, wallet.getNetworks());
            changed |= touchMember(member, now);
        }
        return changed;
    }

    private boolean mergeExchangeMember(AccountingUniverse universe, String ref, Instant now) {
        String normalizedRef = normalizeMemberRef(ref);
        if (normalizedRef.isBlank()) {
            return false;
        }
        AccountingUniverse.Member member = findOrCreateMember(
                universe,
                normalizedRef,
                AccountingUniverse.MemberType.EXCHANGE_ACCOUNT,
                BYBIT_PROVIDER,
                now
        );
        return touchMember(member, now);
    }

    private AccountingUniverse.Member findOrCreateMember(
            AccountingUniverse universe,
            String ref,
            AccountingUniverse.MemberType type,
            String provider,
            Instant now
    ) {
        Map<String, AccountingUniverse.Member> byRef = new LinkedHashMap<>();
        for (AccountingUniverse.Member existing : universe.getMembers()) {
            if (existing == null || blank(existing.getRef())) {
                continue;
            }
            byRef.put(normalizeMemberRef(existing.getRef()), existing);
        }
        AccountingUniverse.Member existing = byRef.get(ref);
        if (existing != null) {
            if (existing.getType() == null) {
                existing.setType(type);
            }
            if (provider != null && blank(existing.getProvider())) {
                existing.setProvider(provider);
            }
            if (existing.getNetworks() == null) {
                existing.setNetworks(new ArrayList<>());
            }
            return existing;
        }

        AccountingUniverse.Member created = new AccountingUniverse.Member();
        created.setRef(ref);
        created.setType(type);
        created.setProvider(provider);
        created.setNetworks(new ArrayList<>());
        universe.getMembers().add(created);
        return created;
    }

    private boolean mergeNetworks(AccountingUniverse.Member member, List<NetworkId> networks) {
        if (member == null) {
            return false;
        }
        if (member.getNetworks() == null) {
            member.setNetworks(new ArrayList<>());
        }
        Set<NetworkId> merged = new LinkedHashSet<>(member.getNetworks());
        if (networks != null) {
            merged.addAll(networks.stream().filter(Objects::nonNull).toList());
        }
        List<NetworkId> updated = new ArrayList<>(merged);
        if (updated.equals(member.getNetworks())) {
            return false;
        }
        member.setNetworks(updated);
        return true;
    }

    private boolean touchMember(AccountingUniverse.Member member, Instant now) {
        if (member == null) {
            return false;
        }
        if (member.getFirstSeenAt() == null) {
            member.setFirstSeenAt(now);
        }
        if (Objects.equals(member.getLastSeenAt(), now)) {
            return false;
        }
        member.setLastSeenAt(now);
        return true;
    }

    private List<String> discoverBybitRefs(String sessionId) {
        if (blank(sessionId)) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("sessionId").is(sessionId.trim()));
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

    private String ensureUniverseId(UserSession session) {
        if (!blank(session.getAccountingUniverseId())) {
            return session.getAccountingUniverseId().trim();
        }
        String universeId = defaultUniverseId(session.getId());
        session.setAccountingUniverseId(universeId);
        return universeId;
    }

    private String defaultUniverseId(String sessionId) {
        return "ACCOUNTING_UNIVERSE:" + sessionId.trim();
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
