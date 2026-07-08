package com.walletradar.application.session.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitApiClient;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitSubMember;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Persists additive owner scope used by replay/history lineage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingUniverseSyncService {

    private static final String BYBIT_PREFIX = "BYBIT:";

    private final AccountingUniverseRepository accountingUniverseRepository;
    private final BybitApiClient bybitApiClient;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final ObjectMapper objectMapper;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final AccountingUniverseService accountingUniverseService;

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
                String ref = normalizeWalletRef(wallet.getAddress());
                AccountingUniverse.Member member = membersByRef.computeIfAbsent(ref, ignored -> newMember(
                        ref,
                        AccountingUniverse.MemberType.ON_CHAIN_WALLET,
                        resolveWalletProvider(wallet),
                        resolveDefaultBackfillEnabled(wallet),
                        now
                ));
                member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
                member.setProvider(resolveWalletProvider(wallet));
                member.setBackfillEnabled(resolveDefaultBackfillEnabled(wallet));
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
                        true,
                        now
                ));
                member.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
                member.setProvider(integration.getProvider() == null ? null : integration.getProvider().name());
                member.setBackfillEnabled(true);
                if (integration.getProvider() == UserSession.IntegrationProvider.BYBIT) {
                    mergeBybitSubAccountUids(member, integration);
                }
                member.setLastSeenAt(now);
            }
        }

        UserSession.SessionSettings settings = session.getSettings();
        if (settings != null && settings.getExternalVenues() != null) {
            for (UserSession.ExternalVenue venue : settings.getExternalVenues()) {
                if (venue == null || blank(venue.getAddress())) {
                    continue;
                }
                String ref = normalizeMemberRef(venue.getAddress());
                AccountingUniverse.Member member = membersByRef.computeIfAbsent(ref, ignored -> newMember(
                        ref,
                        AccountingUniverse.MemberType.EXTERNAL_VENUE,
                        venue.getProvider(),
                        false,
                        now
                ));
                member.setType(AccountingUniverse.MemberType.EXTERNAL_VENUE);
                member.setProvider(venue.getProvider());
                member.setBackfillEnabled(false);
                mergeNetworks(member, venue.getNetworks());
                member.setLastSeenAt(now);
            }
        }

        universe.setMembers(new ArrayList<>(membersByRef.values()));
        universe.setUpdatedAt(now);
        accountingUniverseRepository.save(universe);
        accountingUniverseService.invalidateClassifyCache(accountingUniverseId);
        session.setAccountingUniverseId(accountingUniverseId);
        return accountingUniverseId;
    }

    private void mergeBybitSubAccountUids(AccountingUniverse.Member member, UserSession.SessionIntegration integration) {
        if (integration.getEncryptedCredentials() == null) {
            return;
        }
        try {
            String decrypted = sessionSecretCryptoService.decrypt(integration.getEncryptedCredentials());
            JsonNode node = objectMapper.readTree(decrypted);
            String apiKey = textOrNull(node.path("apiKey"));
            String apiSecret = textOrNull(node.path("apiSecret"));
            if (blank(apiKey) || blank(apiSecret)) {
                return;
            }
            String masterUid = extractBybitUid(member.getRef());
            List<BybitSubMember> discovered = bybitApiClient.fetchSubMembers(masterUid, apiKey, apiSecret);
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            if (member.getSubAccountUids() != null) {
                member.getSubAccountUids().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(uid -> !uid.isBlank())
                        .forEach(merged::add);
            }
            discovered.stream()
                    .map(BybitSubMember::uid)
                    .filter(uid -> uid != null && !uid.isBlank())
                    .forEach(merged::add);
            member.setSubAccountUids(new ArrayList<>(merged));
            log.info(
                    "Merged Bybit sub-account UIDs into accounting universe: masterUid={}, subCount={}",
                    masterUid,
                    merged.size()
            );
        } catch (Exception ex) {
            log.warn(
                    "Unable to refresh Bybit sub-members for integration {}: {}",
                    integration.getIntegrationId(),
                    ex.getMessage()
            );
        }
    }

    private boolean resolveDefaultBackfillEnabled(UserSession.SessionWallet wallet) {
        List<NetworkId> networks = wallet.getNetworks();
        if (networks == null || networks.isEmpty()) {
            return ingestionNetworkProperties.defaultBackfillEnabled(NetworkId.ETHEREUM);
        }
        boolean anyFullIndex = false;
        for (NetworkId network : networks) {
            if (network != null && ingestionNetworkProperties.defaultBackfillEnabled(network)) {
                anyFullIndex = true;
                break;
            }
        }
        return anyFullIndex;
    }

    private String resolveWalletProvider(UserSession.SessionWallet wallet) {
        List<NetworkId> networks = wallet.getNetworks();
        if (networks != null) {
            if (networks.contains(NetworkId.TON)) {
                return "TON";
            }
            if (networks.contains(NetworkId.SOLANA)) {
                return "SOLANA";
            }
        }
        String ref = wallet.getAddress() == null ? "" : wallet.getAddress().trim();
        if (TonAddressCanonicalizer.looksLikeTon(ref)) {
            return "TON";
        }
        if (ref.startsWith("0x") || ref.startsWith("0X")) {
            return "EVM";
        }
        return "SOLANA";
    }

    private AccountingUniverse.Member newMember(
            String ref,
            AccountingUniverse.MemberType type,
            String provider,
            boolean backfillEnabled,
            Instant now
    ) {
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(ref);
        member.setType(type);
        member.setProvider(provider);
        member.setBackfillEnabled(backfillEnabled);
        member.setNetworks(new ArrayList<>());
        member.setSubAccountUids(new ArrayList<>());
        member.setFirstSeenAt(now);
        member.setLastSeenAt(now);
        return member;
    }

    private void mergeNetworks(AccountingUniverse.Member member, List<NetworkId> networks) {
        if (member.getNetworks() == null) {
            member.setNetworks(new ArrayList<>());
        }
        if (networks == null || networks.isEmpty()) {
            return;
        }
        for (NetworkId network : networks) {
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

    private String normalizeWalletRef(String address) {
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

    private static String extractBybitUid(String ref) {
        if (ref == null || !ref.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            return "";
        }
        String remainder = ref.substring(BYBIT_PREFIX.length()).trim();
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon).trim() : remainder.trim();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
