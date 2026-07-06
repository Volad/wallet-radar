package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingUniverseServiceTest {

    private static final String UNIVERSE_ID = "session-n19";

    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;
    @Mock
    private MongoOperations mongoOperations;

    private AccountingUniverseService accountingUniverseService;

    @BeforeEach
    void setUp() {
        accountingUniverseService = new AccountingUniverseService(accountingUniverseRepository, mongoOperations);
        when(accountingUniverseRepository.findById(UNIVERSE_ID)).thenReturn(Optional.of(seedUniverse()));
        accountingUniverseService.bindUniverse(UNIVERSE_ID);
    }

    @AfterEach
    void tearDown() {
        accountingUniverseService.clearUniverseBinding();
    }

    @Test
    void classifyEvmMemberWithBackfillEnabled() {
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                NetworkId.ETHEREUM
        );

        assertThat(membership.isMember()).isTrue();
        assertThat(membership.memberType()).isEqualTo(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        assertThat(membership.backfillEnabled()).isTrue();
        assertThat(membership.ref()).isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
    }

    @Test
    void classifySolanaMemberWithBackfillDisabled() {
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG",
                NetworkId.SOLANA
        );

        assertThat(membership.isMember()).isTrue();
        assertThat(membership.memberType()).isEqualTo(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        assertThat(membership.backfillEnabled()).isFalse();
    }

    @Test
    void classifyTonMemberWithBackfillDisabled() {
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms",
                NetworkId.TON
        );

        assertThat(membership.isMember()).isTrue();
        assertThat(membership.memberType()).isEqualTo(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        assertThat(membership.backfillEnabled()).isFalse();
    }

    @Test
    void classifyHotWalletAsNotMember() {
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                "0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d",
                NetworkId.ETHEREUM
        );

        assertThat(membership.isMember()).isFalse();
        assertThat(membership.memberType()).isNull();
    }

    @Test
    void classifyBybitSubLedgerRefAsExchangeAccount() {
        AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(
                "BYBIT:516601508:UTA",
                null
        );

        assertThat(membership.isMember()).isTrue();
        assertThat(membership.memberType()).isEqualTo(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        assertThat(membership.ref()).isEqualTo("BYBIT:33625378");
    }

    @Test
    void isMemberDelegatesToClassify() {
        assertThat(accountingUniverseService.isMember(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                NetworkId.ETHEREUM
        )).isTrue();
        assertThat(accountingUniverseService.isMember(
                "0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d",
                NetworkId.ETHEREUM
        )).isFalse();
    }

    private static AccountingUniverse seedUniverse() {
        AccountingUniverse universe = new AccountingUniverse();
        universe.setId(UNIVERSE_ID);
        List<AccountingUniverse.Member> members = new ArrayList<>();

        members.add(onChainWallet(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                "EVM",
                true,
                NetworkId.ETHEREUM
        ));
        members.add(onChainWallet(
                "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG",
                "SOLANA",
                false,
                NetworkId.SOLANA
        ));
        members.add(onChainWallet(
                "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms",
                "TON",
                false,
                NetworkId.TON
        ));

        AccountingUniverse.Member bybitMaster = new AccountingUniverse.Member();
        bybitMaster.setRef("BYBIT:33625378");
        bybitMaster.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        bybitMaster.setProvider("BYBIT");
        bybitMaster.setBackfillEnabled(true);
        bybitMaster.setSubAccountUids(List.of("516601508", "421768407", "421325298", "409666492"));
        bybitMaster.setFirstSeenAt(Instant.EPOCH);
        bybitMaster.setLastSeenAt(Instant.EPOCH);
        members.add(bybitMaster);

        universe.setMembers(members);
        return universe;
    }

    private static AccountingUniverse.Member onChainWallet(
            String ref,
            String provider,
            boolean backfillEnabled,
            NetworkId network
    ) {
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(ref);
        member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        member.setProvider(provider);
        member.setBackfillEnabled(backfillEnabled);
        member.setNetworks(new ArrayList<>(List.of(network)));
        member.setFirstSeenAt(Instant.EPOCH);
        member.setLastSeenAt(Instant.EPOCH);
        return member;
    }
}
