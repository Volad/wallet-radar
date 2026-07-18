package com.walletradar.application.session.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.wallet.OnChainAddressClassifier;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
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
        List<String> leftCandidates = cexRefCandidates(normalizedLeft);
        List<String> rightCandidates = cexRefCandidates(normalizedRight);
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

            WalletRef walletRef = WalletRef.parse(ref);
            switch (walletRef.domain()) {
                case EVM -> builder.evm.put(walletRef.uid(), membership);
                case TON -> {
                    for (String key : TonAddressCanonicalizer.lookupKeys(ref)) {
                        builder.ton.put(key, membership);
                    }
                }
                case CEX -> registerCexMember(builder, walletRef, membership, member.getSubAccountUids());
                default -> builder.solana.put(ref, membership);
            }
        }
        return builder.build();
    }

    /**
     * Registers a CEX member in the index, keyed by {@code umbrellaKey.toLowerCase()}.
     *
     * <p>For venues with sub-account splits (Bybit), also registers additional sub-account UIDs
     * supplied in {@code subAccountUids} (if any) using the same umbrella prefix.</p>
     */
    private static void registerCexMember(
            UniverseIndex.Builder builder,
            WalletRef primaryRef,
            OwnMembership membership,
            List<String> subAccountUids
    ) {
        String umbrellaKey = primaryRef.umbrellaKey().toLowerCase(Locale.ROOT);
        if (!umbrellaKey.isBlank()) {
            builder.cex.put(umbrellaKey, membership);
        }
        if (subAccountUids != null) {
            String prefix = (primaryRef.providerPrefix() + ":").toLowerCase(Locale.ROOT);
            for (String subUid : subAccountUids) {
                if (subUid == null || subUid.isBlank()) {
                    continue;
                }
                String subKey = prefix + subUid.trim().toLowerCase(Locale.ROOT);
                builder.cex.put(subKey, membership);
            }
        }
    }

    private static String normalizeStoredRef(String ref) {
        return WalletRef.parse(ref).canonicalRef();
    }

    private List<String> cexRefCandidates(String normalizedRef) {
        WalletRef ref = WalletRef.parse(normalizedRef);
        if (ref.domain() == WalletDomainKind.CEX && ref.subAccount() != null) {
            // umbrellaKey() returns "BYBIT:<uid>" (uppercase prefix, no sub-account),
            // which matches the form stored in accounting_universes.members.ref.
            // Do NOT lowercase it — the universe stores the uppercase-prefixed canonical form.
            String umbrella = ref.umbrellaKey();
            if (!umbrella.equals(normalizedRef)) {
                return List.of(normalizedRef, umbrella);
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
            WalletRef walletRef = WalletRef.parse(trimmed);
            // Expand CEX refs that have sub-accounts but are stored without a sub-account suffix.
            // Bybit uses FUND / UTA / EARN sub-accounts; Dzengi is flat (no expansion needed).
            if (walletRef.domain() == WalletDomainKind.CEX && walletRef.subAccount() == null
                    && CorrelationContract.VENUE_BYBIT.equals(walletRef.providerPrefix())) {
                expanded.add(trimmed + CorrelationContract.WALLET_SUFFIX_UTA);
                expanded.add(trimmed + CorrelationContract.WALLET_SUFFIX_FUND);
                expanded.add(trimmed + CorrelationContract.WALLET_SUFFIX_EARN);
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
                .map(uid -> CorrelationContract.VENUE_BYBIT + ":" + uid.trim())
                .map(this::normalizeMemberRef)
                .filter(ref -> !ref.isBlank())
                .forEach(refs::add);
        return List.copyOf(refs);
    }

    private String normalizeWalletAddress(String address) {
        if (blank(address)) {
            return "";
        }
        return OnChainAddressClassifier.normalize(address);
    }

    private String normalizeMemberRef(String ref) {
        if (blank(ref)) {
            return "";
        }
        return WalletRef.parse(ref).canonicalRef();
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
        /** CEX wallets keyed by {@code umbrellaKey.toLowerCase()} (e.g. {@code bybit:123456}, {@code dzengi:abc}). */
        private final java.util.Map<String, OwnMembership> cex;

        private UniverseIndex(
                java.util.Map<String, OwnMembership> evm,
                java.util.Map<String, OwnMembership> solana,
                java.util.Map<String, OwnMembership> ton,
                java.util.Map<String, OwnMembership> cex
        ) {
            this.evm = evm;
            this.solana = solana;
            this.ton = ton;
            this.cex = cex;
        }

        static UniverseIndex empty() {
            return new UniverseIndex(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        }

        OwnMembership lookup(String address, NetworkId network) {
            if (blank(address)) {
                return NOT_MEMBER;
            }
            String trimmed = address.trim();
            // CEX lookup via WalletRef grammar (handles both Bybit and Dzengi correctly)
            WalletRef ref = WalletRef.parse(trimmed);
            if (ref.domain() == WalletDomainKind.CEX) {
                String umbrellaKey = ref.umbrellaKey().toLowerCase(Locale.ROOT);
                OwnMembership cexMatch = cex.get(umbrellaKey);
                return cexMatch != null ? cexMatch : NOT_MEMBER;
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
            private final java.util.Map<String, OwnMembership> cex = new java.util.LinkedHashMap<>();

            UniverseIndex build() {
                return new UniverseIndex(
                        java.util.Map.copyOf(evm),
                        java.util.Map.copyOf(solana),
                        java.util.Map.copyOf(ton),
                        java.util.Map.copyOf(cex)
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
