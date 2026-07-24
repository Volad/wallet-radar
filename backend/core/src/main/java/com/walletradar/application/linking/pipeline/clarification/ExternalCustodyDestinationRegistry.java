package com.walletradar.application.linking.pipeline.clarification;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.wallet.OnChainAddressClassifier;
import com.walletradar.domain.wallet.WalletDomainKind;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Resolves user-designated external custody destinations (WS-5, ADR-072) for the active session.
 *
 * <p>A custody destination is a labeled counterparty — an off-chain / custodial venue the user
 * cannot read into (e.g. Telegram Wallet "Доход"/Earn operator pool). It is <b>never</b> a member of
 * the {@code AccountingUniverse}: it does not affect Net External Capital, portfolio quantity, AVCO,
 * or the conservation gate. This registry only labels flows and feeds the informational custody
 * ledger. The operator address is supplied entirely through
 * {@link UserSession.SessionSettings#getExternalCustodyDestinations()} — nothing is hardcoded.</p>
 *
 * <p>The active session is bound per-thread by the normalization pipeline (mirroring
 * {@link com.walletradar.application.session.application.AccountingUniverseService}). Resolution is
 * family-aware: EVM refs are lowercased, Solana base58 keys are case-preserved, and TON addresses
 * match on every {@link TonAddressCanonicalizer#lookupKeys(String) canonical form} (friendly
 * {@code UQ…}/{@code EQ…} and raw {@code workchain:hex}).</p>
 */
@Service
public class ExternalCustodyDestinationRegistry {

    private final UserSessionRepository userSessionRepository;
    private final Cache<String, CustodyIndex> cache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();
    private final ThreadLocal<String> boundSessionId = new ThreadLocal<>();

    public ExternalCustodyDestinationRegistry(UserSessionRepository userSessionRepository) {
        this.userSessionRepository = userSessionRepository;
    }

    /** Binds the active session so {@link #match(String, NetworkId)} can resolve destinations. */
    public void bindSession(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            boundSessionId.remove();
            return;
        }
        boundSessionId.set(sessionId.trim());
    }

    public void clearSessionBinding() {
        boundSessionId.remove();
    }

    public void invalidate(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        cache.invalidate(sessionId.trim());
    }

    /**
     * Resolves a counterparty peer against the bound session's designated custody destinations.
     *
     * @return the matching destination label/provider, or empty when no session is bound or the peer
     * is not a designated custody destination.
     */
    public Optional<CustodyMatch> match(@Nullable String peerAddress, @Nullable NetworkId network) {
        String sessionId = boundSessionId.get();
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return matchForSession(sessionId, peerAddress, network);
    }

    /**
     * Session-explicit variant used by read paths (custody ledger) that are not bound to a pipeline
     * thread.
     */
    public Optional<CustodyMatch> matchForSession(
            @Nullable String sessionId,
            @Nullable String peerAddress,
            @Nullable NetworkId network
    ) {
        if (sessionId == null || sessionId.isBlank() || peerAddress == null || peerAddress.isBlank()) {
            return Optional.empty();
        }
        CustodyIndex index = cache.get(sessionId.trim(), this::loadIndex);
        return index.lookup(peerAddress, network);
    }

    private CustodyIndex loadIndex(String sessionId) {
        return userSessionRepository.findById(sessionId)
                .map(UserSession::getSettings)
                .map(UserSession.SessionSettings::getExternalCustodyDestinations)
                .map(CustodyIndex::build)
                .orElseGet(CustodyIndex::empty);
    }

    public record CustodyMatch(String canonicalAddress, String label, String provider) {
    }

    private static final class CustodyIndex {

        private final Map<String, CustodyMatch> byKey;

        private CustodyIndex(Map<String, CustodyMatch> byKey) {
            this.byKey = byKey;
        }

        static CustodyIndex empty() {
            return new CustodyIndex(Map.of());
        }

        static CustodyIndex build(List<UserSession.ExternalCustodyDestination> destinations) {
            if (destinations == null || destinations.isEmpty()) {
                return empty();
            }
            Map<String, CustodyMatch> byKey = new LinkedHashMap<>();
            for (UserSession.ExternalCustodyDestination destination : destinations) {
                if (destination == null || destination.getAddress() == null || destination.getAddress().isBlank()) {
                    continue;
                }
                String canonical = destination.getAddress().trim();
                CustodyMatch value = new CustodyMatch(
                        canonical,
                        labelOrFallback(destination, canonical),
                        destination.getProvider()
                );
                for (String key : lookupKeys(canonical)) {
                    byKey.putIfAbsent(key, value);
                }
            }
            return new CustodyIndex(Map.copyOf(byKey));
        }

        Optional<CustodyMatch> lookup(String peerAddress, NetworkId network) {
            for (String key : peerLookupKeys(peerAddress, network)) {
                CustodyMatch match = byKey.get(key);
                if (match != null) {
                    return Optional.of(match);
                }
            }
            return Optional.empty();
        }

        private static String labelOrFallback(UserSession.ExternalCustodyDestination destination, String canonical) {
            if (destination.getLabel() != null && !destination.getLabel().isBlank()) {
                return destination.getLabel().trim();
            }
            if (destination.getProvider() != null && !destination.getProvider().isBlank()) {
                return destination.getProvider().trim();
            }
            return canonical;
        }

        /**
         * Canonical index keys for a stored destination address (family-aware). TON registers every
         * friendly/raw form so it matches whatever the normalizer stamped on the flow peer.
         */
        private static List<String> lookupKeys(String address) {
            return keysForDomain(address, OnChainAddressClassifier.classifyDomain(address));
        }

        private static List<String> peerLookupKeys(String peerAddress, NetworkId network) {
            if (peerAddress == null || peerAddress.isBlank()) {
                return List.of();
            }
            WalletDomainKind domain = network == NetworkId.TON
                    ? WalletDomainKind.TON
                    : network == NetworkId.SOLANA
                    ? WalletDomainKind.SOLANA
                    : OnChainAddressClassifier.classifyDomain(peerAddress);
            return keysForDomain(peerAddress, domain);
        }

        private static List<String> keysForDomain(String address, WalletDomainKind domain) {
            String trimmed = address.trim();
            return switch (domain) {
                case EVM, CEX -> List.of(trimmed.toLowerCase(Locale.ROOT));
                case TON -> TonAddressCanonicalizer.lookupKeys(trimmed);
                case SOLANA -> List.of(trimmed);
            };
        }
    }
}
