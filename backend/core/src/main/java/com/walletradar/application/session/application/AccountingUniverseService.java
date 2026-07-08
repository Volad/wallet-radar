package com.walletradar.application.session.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Resolves stable owner scope for replay/history while preserving current
 * session wallet subset for live on-chain balances.
 */
@Service
@RequiredArgsConstructor
public class AccountingUniverseService {

    private static final String BYBIT_PREFIX = "BYBIT:";
    private static final OwnMembership NOT_MEMBER = new OwnMembership(false, null, false, null);

    private final AccountingUniverseRepository accountingUniverseRepository;
    private final MongoOperations mongoOperations;
    private final Cache<String, UniverseIndex> classifyIndexCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
    private final ThreadLocal<String> boundUniverseId = new ThreadLocal<>();

    public record OwnMembership(
            boolean isMember,
            AccountingUniverse.MemberType memberType,
            boolean backfillEnabled,
            String ref
    ) {
    }

    /**
     * Binds the active accounting universe for {@link #classify(String, NetworkId)} on this thread.
     */
    public void bindUniverse(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            boundUniverseId.remove();
            return;
        }
        boundUniverseId.set(accountingUniverseId.trim());
    }

    public void clearUniverseBinding() {
        boundUniverseId.remove();
    }

    public void invalidateClassifyCache(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return;
        }
        classifyIndexCache.invalidate(accountingUniverseId.trim());
    }

    public OwnMembership classify(String address, NetworkId network) {
        String universeId = boundUniverseId.get();
        if (universeId == null || universeId.isBlank()) {
            throw new IllegalStateException(
                    "No accounting universe bound; call bindUniverse(accountingUniverseId) or classify(accountingUniverseId, address, network)"
            );
        }
        return classify(universeId, address, network);
    }

    public OwnMembership classify(String accountingUniverseId, String address, NetworkId network) {
        if (blank(accountingUniverseId) || blank(address)) {
            return NOT_MEMBER;
        }
        UniverseIndex index = classifyIndexCache.get(
                accountingUniverseId.trim(),
                id -> accountingUniverseRepository.findById(id)
                        .map(AccountingUniverseService::buildIndex)
                        .orElse(UniverseIndex.empty())
        );
        return index.lookup(address, network);
    }

    public boolean isMember(String address, NetworkId network) {
        return classify(address, network).isMember();
    }

    public boolean isMember(String accountingUniverseId, String address, NetworkId network) {
        return classify(accountingUniverseId, address, network).isMember();
    }

    public boolean isBackfillEnabled(String accountingUniverseId, String walletRef, NetworkId network) {
        if (blank(accountingUniverseId) || blank(walletRef)) {
            return true;
        }
        OwnMembership membership = classify(accountingUniverseId, walletRef, network);
        if (!membership.isMember()) {
            return true;
        }
        return membership.backfillEnabled();
    }

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
        List<String> leftCandidates = bybitRefCandidates(normalizedLeft);
        List<String> rightCandidates = bybitRefCandidates(normalizedRight);
        Criteria[] orBranches = leftCandidates.stream()
                .flatMap(left -> rightCandidates.stream()
                        .map(right -> Criteria.where("members.ref").all(List.of(left, right))))
                .toArray(Criteria[]::new);
        Query query = Query.query(new Criteria().orOperator(orBranches));
        return mongoOperations.exists(query, AccountingUniverse.class);
    }

    private static boolean resolveBackfillEnabled(AccountingUniverse.Member member) {
        if (member.getBackfillEnabled() != null) {
            return member.getBackfillEnabled();
        }
        if (member.getNetworks() != null) {
            for (NetworkId network : member.getNetworks()) {
                if (network == NetworkId.SOLANA || network == NetworkId.TON) {
                    return false;
                }
            }
        }
        if ("SOLANA".equalsIgnoreCase(member.getProvider()) || "TON".equalsIgnoreCase(member.getProvider())) {
            return false;
        }
        return true;
    }

    private static UniverseIndex buildIndex(AccountingUniverse universe) {
        if (universe == null || universe.getMembers() == null || universe.getMembers().isEmpty()) {
            return UniverseIndex.empty();
        }
        UniverseIndex.Builder builder = new UniverseIndex.Builder();
        for (AccountingUniverse.Member member : universe.getMembers()) {
            if (member == null || blank(member.getRef())) {
                continue;
            }
            boolean backfillEnabled = resolveBackfillEnabled(member);
            AccountingUniverse.MemberType type = member.getType();
            String ref = member.getRef().trim();
            OwnMembership membership = new OwnMembership(true, type, backfillEnabled, normalizeStoredRef(ref));

            if (ref.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
                registerBybitMember(builder, ref, membership, member.getSubAccountUids());
                continue;
            }
            if (ref.startsWith("0x") || ref.startsWith("0X")) {
                builder.evm.put(ref.toLowerCase(Locale.ROOT), membership);
                continue;
            }
            if (TonAddressCanonicalizer.looksLikeTon(ref)) {
                for (String key : TonAddressCanonicalizer.lookupKeys(ref)) {
                    builder.ton.put(key, membership);
                }
                continue;
            }
            builder.solana.put(ref, membership);
        }
        return builder.build();
    }

    private static void registerBybitMember(
            UniverseIndex.Builder builder,
            String ref,
            OwnMembership membership,
            List<String> subAccountUids
    ) {
        String masterUid = extractBybitUid(ref);
        if (!masterUid.isBlank()) {
            builder.bybit.put(masterUid, membership);
        }
        if (subAccountUids != null) {
            for (String subUid : subAccountUids) {
                if (subUid == null || subUid.isBlank()) {
                    continue;
                }
                builder.bybit.put(subUid.trim(), membership);
            }
        }
    }

    private static String extractBybitUid(String ref) {
        if (ref == null || !ref.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            return "";
        }
        String remainder = ref.substring(BYBIT_PREFIX.length()).trim();
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon).trim() : remainder.trim();
    }

    private static String normalizeStoredRef(String ref) {
        if (ref.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            String uid = extractBybitUid(ref);
            return uid.isBlank() ? ref : BYBIT_PREFIX + uid;
        }
        if (ref.startsWith("0x") || ref.startsWith("0X")) {
            return ref.toLowerCase(Locale.ROOT);
        }
        if (TonAddressCanonicalizer.looksLikeTon(ref)) {
            return TonAddressCanonicalizer.preferredMemberRef(ref);
        }
        return ref;
    }

    private List<String> bybitRefCandidates(String normalizedRef) {
        if (normalizedRef.startsWith(BYBIT_PREFIX)) {
            int firstColon = normalizedRef.indexOf(':');
            int secondColon = normalizedRef.indexOf(':', firstColon + 1);
            if (secondColon > 0) {
                String root = normalizedRef.substring(0, secondColon);
                if (!root.equals(normalizedRef)) {
                    return List.of(normalizedRef, root);
                }
            }
        }
        return List.of(normalizedRef);
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
        LinkedHashSet<String> refs = session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .map(UserSession.SessionIntegration::getAccountRef)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String trimmed = ref.trim();
            if (trimmed.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())
                    && trimmed.split(":").length < 3) {
                expanded.add(trimmed + ":UTA");
                expanded.add(trimmed + ":FUND");
                expanded.add(trimmed + ":EARN");
                continue;
            }
            expanded.add(trimmed);
        }
        return List.copyOf(expanded);
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
        String trimmed = address.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        return trimmed;
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
        if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
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

    private static final class UniverseIndex {
        private final java.util.Map<String, OwnMembership> evm;
        private final java.util.Map<String, OwnMembership> solana;
        private final java.util.Map<String, OwnMembership> ton;
        private final java.util.Map<String, OwnMembership> bybit;

        private UniverseIndex(
                java.util.Map<String, OwnMembership> evm,
                java.util.Map<String, OwnMembership> solana,
                java.util.Map<String, OwnMembership> ton,
                java.util.Map<String, OwnMembership> bybit
        ) {
            this.evm = evm;
            this.solana = solana;
            this.ton = ton;
            this.bybit = bybit;
        }

        static UniverseIndex empty() {
            return new UniverseIndex(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        }

        OwnMembership lookup(String address, NetworkId network) {
            if (blank(address)) {
                return NOT_MEMBER;
            }
            String trimmed = address.trim();
            if (trimmed.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
                String uid = extractBybitUid(trimmed);
                OwnMembership bybitMatch = uid.isBlank() ? null : bybit.get(uid);
                return bybitMatch != null ? bybitMatch : NOT_MEMBER;
            }
            if (network == NetworkId.SOLANA) {
                return solana.getOrDefault(trimmed, NOT_MEMBER);
            }
            if (network == NetworkId.TON) {
                for (String key : TonAddressCanonicalizer.lookupKeys(trimmed)) {
                    OwnMembership match = ton.get(key);
                    if (match != null) {
                        return match;
                    }
                }
                return NOT_MEMBER;
            }
            if (network != null && network != NetworkId.SOLANA && network != NetworkId.TON) {
                if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                    return evm.getOrDefault(trimmed.toLowerCase(Locale.ROOT), NOT_MEMBER);
                }
                return NOT_MEMBER;
            }
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                OwnMembership evmMatch = evm.get(trimmed.toLowerCase(Locale.ROOT));
                if (evmMatch != null) {
                    return evmMatch;
                }
            }
            if (TonAddressCanonicalizer.looksLikeTon(trimmed)) {
                for (String key : TonAddressCanonicalizer.lookupKeys(trimmed)) {
                    OwnMembership match = ton.get(key);
                    if (match != null) {
                        return match;
                    }
                }
            }
            OwnMembership solanaMatch = solana.get(trimmed);
            return solanaMatch != null ? solanaMatch : NOT_MEMBER;
        }

        private static final class Builder {
            private final java.util.Map<String, OwnMembership> evm = new java.util.LinkedHashMap<>();
            private final java.util.Map<String, OwnMembership> solana = new java.util.LinkedHashMap<>();
            private final java.util.Map<String, OwnMembership> ton = new java.util.LinkedHashMap<>();
            private final java.util.Map<String, OwnMembership> bybit = new java.util.LinkedHashMap<>();

            UniverseIndex build() {
                return new UniverseIndex(
                        java.util.Map.copyOf(evm),
                        java.util.Map.copyOf(solana),
                        java.util.Map.copyOf(ton),
                        java.util.Map.copyOf(bybit)
                );
            }
        }
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
