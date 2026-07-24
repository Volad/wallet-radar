package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * WS-5 (ADR-072): {@link ExternalCustodyDestinationRegistry} resolves user-designated custody
 * destinations, family-aware, with no hardcoded address. Nothing here touches the accounting
 * universe — these are labeled counterparties only.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCustodyDestinationRegistryTest {

    private static final String SESSION_ID = "session-custody";
    private static final String TON_RAW = "0:" + "ab".repeat(32);
    private static final String EVM = "0xABCDef0000000000000000000000000000000001";
    private static final String SOLANA = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    @Mock
    private UserSessionRepository userSessionRepository;

    private ExternalCustodyDestinationRegistry registry() {
        return new ExternalCustodyDestinationRegistry(userSessionRepository);
    }

    private void stubSession(UserSession.ExternalCustodyDestination... destinations) {
        UserSession session = new UserSession();
        session.setId(SESSION_ID);
        UserSession.SessionSettings settings = new UserSession.SessionSettings();
        settings.setExternalCustodyDestinations(new ArrayList<>(List.of(destinations)));
        session.setSettings(settings);
        lenient().when(userSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    private static UserSession.ExternalCustodyDestination destination(String address, String label, String provider) {
        UserSession.ExternalCustodyDestination destination = new UserSession.ExternalCustodyDestination();
        destination.setAddress(address);
        destination.setLabel(label);
        destination.setProvider(provider);
        return destination;
    }

    @Test
    @DisplayName("no session bound → no match (safe no-op for tests / EVM path)")
    void noSessionBoundReturnsEmpty() {
        ExternalCustodyDestinationRegistry registry = registry();
        assertThat(registry.match(TON_RAW, NetworkId.TON)).isEmpty();
    }

    @Test
    @DisplayName("TON: friendly-designated destination matches the raw workchain:hex peer the normalizer stamps")
    void tonFriendlyMatchesRawPeer() {
        String friendly = TonAddressCanonicalizer.preferredMemberRef(TON_RAW);
        stubSession(destination(friendly, "Telegram Wallet Earn", "TELEGRAM_EARN"));
        ExternalCustodyDestinationRegistry registry = registry();
        registry.bindSession(SESSION_ID);

        Optional<ExternalCustodyDestinationRegistry.CustodyMatch> match = registry.match(TON_RAW, NetworkId.TON);

        assertThat(match).isPresent();
        assertThat(match.get().label()).isEqualTo("Telegram Wallet Earn");
        assertThat(match.get().provider()).isEqualTo("TELEGRAM_EARN");
        assertThat(match.get().canonicalAddress()).isEqualTo(friendly);
    }

    @Test
    @DisplayName("EVM matching is case-insensitive; Solana is case-sensitive exact")
    void evmCaseInsensitiveSolanaExact() {
        stubSession(
                destination(EVM.toLowerCase(java.util.Locale.ROOT), "EVM Vault", null),
                destination(SOLANA, "SOL Vault", null)
        );
        ExternalCustodyDestinationRegistry registry = registry();
        registry.bindSession(SESSION_ID);

        assertThat(registry.match(EVM.toUpperCase(java.util.Locale.ROOT), NetworkId.ETHEREUM)).isPresent();
        assertThat(registry.match(SOLANA, NetworkId.SOLANA)).isPresent();
        assertThat(registry.match(SOLANA.toLowerCase(java.util.Locale.ROOT), NetworkId.SOLANA)).isEmpty();
    }

    @Test
    @DisplayName("non-designated peer does not match")
    void unknownPeerDoesNotMatch() {
        stubSession(destination(TON_RAW, "Telegram Wallet Earn", "TELEGRAM_EARN"));
        ExternalCustodyDestinationRegistry registry = registry();
        registry.bindSession(SESSION_ID);

        assertThat(registry.match("0:" + "cd".repeat(32), NetworkId.TON)).isEmpty();
    }

    @Test
    @DisplayName("matchForSession is usable off-thread (read path) and honors invalidate()")
    void matchForSessionAndInvalidate() {
        stubSession(destination(TON_RAW, "Telegram Wallet Earn", "TELEGRAM_EARN"));
        ExternalCustodyDestinationRegistry registry = registry();

        assertThat(registry.matchForSession(SESSION_ID, TON_RAW, NetworkId.TON)).isPresent();
        registry.invalidate(SESSION_ID);
        assertThat(registry.matchForSession(SESSION_ID, TON_RAW, NetworkId.TON)).isPresent();
    }
}
