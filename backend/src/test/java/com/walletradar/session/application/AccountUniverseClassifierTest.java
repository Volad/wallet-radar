package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyResolutionService;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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

/**
 * ADR-009 classifier walk order: universe membership steers {@link CounterpartyType}.
 */
@ExtendWith(MockitoExtension.class)
class AccountUniverseClassifierTest {

    private static final String UNIVERSE_ID = "session-n19";

    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;
    @Mock
    private MongoOperations mongoOperations;

    private AccountingUniverseService accountingUniverseService;
    private CounterpartyResolutionService counterpartyResolutionService;

    @BeforeEach
    void setUp() {
        accountingUniverseService = new AccountingUniverseService(accountingUniverseRepository, mongoOperations);
        counterpartyResolutionService = new CounterpartyResolutionService(null, accountingUniverseService);
        when(accountingUniverseRepository.findById(UNIVERSE_ID)).thenReturn(Optional.of(seedUniverse()));
        accountingUniverseService.bindUniverse(UNIVERSE_ID);
    }

    @AfterEach
    void tearDown() {
        accountingUniverseService.clearUniverseBinding();
    }

    @Test
    void solanaMemberClassifiesAsPersonalWallet() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);

        String type = counterpartyResolutionService.classifyCounterpartyType(
                tx,
                "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG"
        );

        assertThat(type).isEqualTo(CounterpartyType.PERSONAL_WALLET);
    }

    @Test
    void bybitSubLedgerClassifiesAsCex() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);

        String type = counterpartyResolutionService.classifyCounterpartyType(
                tx,
                "BYBIT:516601508:UTA"
        );

        assertThat(type).isEqualTo(CounterpartyType.CEX);
    }

    @Test
    void hotWalletClassifiesAsUnknownEoaWhenNotMember() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);

        String type = counterpartyResolutionService.classifyCounterpartyType(
                tx,
                "0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d"
        );

        assertThat(type).isEqualTo(CounterpartyType.UNKNOWN_EOA);
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

        AccountingUniverse.Member bybitMaster = new AccountingUniverse.Member();
        bybitMaster.setRef("BYBIT:33625378");
        bybitMaster.setType(AccountingUniverse.MemberType.EXCHANGE_ACCOUNT);
        bybitMaster.setProvider("BYBIT");
        bybitMaster.setBackfillEnabled(true);
        bybitMaster.setSubAccountUids(List.of("516601508"));
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
