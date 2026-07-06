package com.walletradar.session.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.integration.bybit.BybitApiClient;
import com.walletradar.integration.bybit.BybitSubMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingUniverseSyncServiceTest {

    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;
    @Mock
    private BybitApiClient bybitApiClient;
    @Mock
    private SessionSecretCryptoService sessionSecretCryptoService;
    @Mock
    private IngestionNetworkProperties ingestionNetworkProperties;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountingUniverseSyncService accountingUniverseSyncService;

    @org.junit.jupiter.api.BeforeEach
    void initService() {
        accountingUniverseSyncService = new AccountingUniverseSyncService(
                accountingUniverseRepository,
                bybitApiClient,
                sessionSecretCryptoService,
                objectMapper,
                ingestionNetworkProperties,
                accountingUniverseService
        );
    }

    @Test
    void syncCreatesAdditiveUniverseForWalletsAndIntegrations() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xAbC");
        wallet.setNetworks(List.of(NetworkId.BASE, NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        when(accountingUniverseRepository.findById("session-1")).thenReturn(Optional.empty());
        when(ingestionNetworkProperties.defaultBackfillEnabled(any())).thenReturn(true);

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("session-1");
        assertThat(session.getAccountingUniverseId()).isEqualTo("session-1");
        assertThat(saved.getMembers()).hasSize(2);
        assertThat(saved.getMembers()).anySatisfy(member -> {
            assertThat(member.getRef()).isEqualTo("0xabc");
            assertThat(member.getType()).isEqualTo(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
            assertThat(member.getNetworks()).containsExactly(NetworkId.BASE, NetworkId.ARBITRUM);
        });
        assertThat(saved.getMembers()).anySatisfy(member -> {
            assertThat(member.getRef()).isEqualTo("BYBIT:33625378");
            assertThat(member.getType()).isEqualTo(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
            assertThat(member.getProvider()).isEqualTo("BYBIT");
        });
    }

    @Test
    void syncPreservesExistingMembersAndAppendsNewScope() {
        UserSession session = new UserSession();
        session.setId("session-2");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xNew");
        wallet.setNetworks(List.of(NetworkId.ETHEREUM));
        session.setWallets(List.of(wallet));
        session.setIntegrations(new ArrayList<>());

        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("session-2");
        AccountingUniverse.Member existing = new AccountingUniverse.Member();
        existing.setRef("0xold");
        existing.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        existing.setNetworks(new ArrayList<>(List.of(NetworkId.BASE)));
        universe.setMembers(new ArrayList<>(List.of(existing)));

        when(accountingUniverseRepository.findById("session-2")).thenReturn(Optional.of(universe));
        when(ingestionNetworkProperties.defaultBackfillEnabled(any())).thenReturn(true);

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse saved = captor.getValue();
        assertThat(saved.getMembers()).extracting(AccountingUniverse.Member::getRef)
                .containsExactly("0xold", "0xnew");
    }

    @Test
    void syncSetsSolanaWalletBackfillDisabled() {
        UserSession session = new UserSession();
        session.setId("session-sol");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));
        session.setIntegrations(new ArrayList<>());

        when(accountingUniverseRepository.findById("session-sol")).thenReturn(Optional.empty());
        when(ingestionNetworkProperties.defaultBackfillEnabled(NetworkId.SOLANA)).thenReturn(false);

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse.Member member = captor.getValue().getMembers().getFirst();
        assertThat(member.getBackfillEnabled()).isFalse();
        assertThat(member.getProvider()).isEqualTo("SOLANA");
        assertThat(member.getNetworks()).containsExactly(NetworkId.SOLANA);
    }

    @Test
    void syncMirrorsExternalVenuesIntoUniverseMembersAsExternalVenueType() {
        UserSession session = new UserSession();
        session.setId("session-venue");
        session.setWallets(new ArrayList<>());
        session.setIntegrations(new ArrayList<>());
        UserSession.SessionSettings settings = new UserSession.SessionSettings();
        UserSession.ExternalVenue paradex = new UserSession.ExternalVenue();
        paradex.setAddress("0xParadexDeposit");
        paradex.setProvider("PARADEX");
        paradex.setLabel("Paradex deposit");
        paradex.setNetworks(new ArrayList<>(List.of(NetworkId.ETHEREUM)));
        settings.setExternalVenues(new ArrayList<>(List.of(paradex)));
        session.setSettings(settings);

        when(accountingUniverseRepository.findById("session-venue")).thenReturn(Optional.empty());

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse saved = captor.getValue();
        assertThat(saved.getMembers()).hasSize(1);
        AccountingUniverse.Member venue = saved.getMembers().getFirst();
        assertThat(venue.getRef()).isEqualTo("0xparadexdeposit");
        assertThat(venue.getType()).isEqualTo(AccountingUniverse.MemberType.EXTERNAL_VENUE);
        assertThat(venue.getProvider()).isEqualTo("PARADEX");
        assertThat(venue.getBackfillEnabled()).isFalse();
        assertThat(venue.getNetworks()).containsExactly(NetworkId.ETHEREUM);
    }

    @Test
    void syncMergesBybitSubAccountUidsAdditively() throws Exception {
        UserSession session = new UserSession();
        session.setId("session-bybit");
        session.setWallets(new ArrayList<>());
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setEncryptedCredentials(new UserSession.EncryptedSecret());
        session.setIntegrations(List.of(integration));

        AccountingUniverse universe = new AccountingUniverse();
        universe.setId("session-bybit");
        AccountingUniverse.Member existing = new AccountingUniverse.Member();
        existing.setRef("BYBIT:33625378");
        existing.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        existing.setSubAccountUids(new ArrayList<>(List.of("409666492")));
        universe.setMembers(new ArrayList<>(List.of(existing)));

        when(accountingUniverseRepository.findById("session-bybit")).thenReturn(Optional.of(universe));
        when(sessionSecretCryptoService.decrypt(integration.getEncryptedCredentials()))
                .thenReturn("{\"apiKey\":\"k\",\"apiSecret\":\"s\"}");
        when(bybitApiClient.fetchSubMembers(eq("33625378"), eq("k"), eq("s"))).thenReturn(List.of(
                new BybitSubMember("516601508", "sub-a", "USER_OWNED_SUB"),
                new BybitSubMember("421768407", "sub-b", "USER_OWNED_SUB")
        ));

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse.Member member = captor.getValue().getMembers().stream()
                .filter(m -> "BYBIT:33625378".equals(m.getRef()))
                .findFirst()
                .orElseThrow();
        assertThat(member.getSubAccountUids())
                .containsExactlyInAnyOrder("409666492", "516601508", "421768407");
        verify(accountingUniverseService).invalidateClassifyCache("session-bybit");
    }
}
