package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.session.UserSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingUniverseSyncServiceTest {

    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;

    @InjectMocks
    private AccountingUniverseSyncService accountingUniverseSyncService;

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

        accountingUniverseSyncService.sync(session, Instant.parse("2026-04-08T08:00:00Z"));

        ArgumentCaptor<AccountingUniverse> captor = ArgumentCaptor.forClass(AccountingUniverse.class);
        verify(accountingUniverseRepository).save(captor.capture());
        AccountingUniverse saved = captor.getValue();
        assertThat(saved.getMembers()).extracting(AccountingUniverse.Member::getRef)
                .containsExactly("0xold", "0xnew");
    }
}
