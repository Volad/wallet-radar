package com.walletradar.session.application;

import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists additive owner scope used by replay/history lineage.
 */
@Service
@RequiredArgsConstructor
public class AccountingUniverseSyncService {

    private static final String BYBIT_PREFIX = "BYBIT:";

    private final AccountingUniverseRepository accountingUniverseRepository;

    public String sync(UserSession session, Instant observedAt) {
        if (session == null || blank(session.getId())) {
            return null;
        }
        Instant now = observedAt == null ? Instant.now() : observedAt;
        String accountingUniverseId = normalizedAccountingUniverseId(session);
        AccountingUniverse universe = accountingUniverseRepository.findById(accountingUniverseId).orElseGet(() -> {
            AccountingUniverse created = new AccountingUniverse();
            created.setId(accountingUniverseId);
            created.setCreatedAt(now);
            return created;
        });

        if (universe.getMembers() == null) {
            universe.setMembers(new ArrayList<>());
        }
        Map<String, AccountingUniverse.Member> membersByRef = new LinkedHashMap<>();
        for (AccountingUniverse.Member member : universe.getMembers()) {
            if (member == null || blank(member.getRef())) {
                continue;
            }
            membersByRef.put(normalizeMemberRef(member.getRef()), member);
        }

        if (session.getWallets() != null) {
            for (UserSession.SessionWallet wallet : session.getWallets()) {
                if (wallet == null || blank(wallet.getAddress())) {
                    continue;
                }
                String ref = normalizeWalletAddress(wallet.getAddress());
                AccountingUniverse.Member member = membersByRef.computeIfAbsent(ref, ignored -> newMember(
                        ref,
                        AccountingUniverse.MemberType.ON_CHAIN_WALLET,
                        null,
                        now
                ));
                member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
                member.setProvider(null);
                mergeNetworks(member, wallet.getNetworks());
                member.setLastSeenAt(now);
            }
        }

        if (session.getIntegrations() != null) {
            for (UserSession.SessionIntegration integration : session.getIntegrations()) {
                if (integration == null
                        || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                        || blank(integration.getAccountRef())) {
                    continue;
                }
                String ref = normalizeMemberRef(integration.getAccountRef());
                AccountingUniverse.Member member = membersByRef.computeIfAbsent(ref, ignored -> newMember(
                        ref,
                        AccountingUniverse.MemberType.EXCHANGE_ACCOUNT,
                        integration.getProvider() == null ? null : integration.getProvider().name(),
                        now
                ));
                member.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
                member.setProvider(integration.getProvider() == null ? null : integration.getProvider().name());
                member.setLastSeenAt(now);
            }
        }

        universe.setMembers(new ArrayList<>(membersByRef.values()));
        universe.setUpdatedAt(now);
        accountingUniverseRepository.save(universe);
        session.setAccountingUniverseId(accountingUniverseId);
        return accountingUniverseId;
    }

    private AccountingUniverse.Member newMember(
            String ref,
            AccountingUniverse.MemberType type,
            String provider,
            Instant now
    ) {
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(ref);
        member.setType(type);
        member.setProvider(provider);
        member.setNetworks(new ArrayList<>());
        member.setFirstSeenAt(now);
        member.setLastSeenAt(now);
        return member;
    }

    private void mergeNetworks(AccountingUniverse.Member member, List<com.walletradar.domain.common.NetworkId> networks) {
        if (member.getNetworks() == null) {
            member.setNetworks(new ArrayList<>());
        }
        if (networks == null || networks.isEmpty()) {
            return;
        }
        for (com.walletradar.domain.common.NetworkId network : networks) {
            if (network != null && !member.getNetworks().contains(network)) {
                member.getNetworks().add(network);
            }
        }
    }

    private String normalizedAccountingUniverseId(UserSession session) {
        if (session.getAccountingUniverseId() != null && !session.getAccountingUniverseId().isBlank()) {
            return session.getAccountingUniverseId().trim();
        }
        return session.getId().trim();
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
}
