package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SessionWalletAdjacencyService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiFiForeignDestinationReclassificationServiceTest {

    // Evidence anchors only: reused from OwnWalletBridgeMistypeCorrectionServiceTest fixtures.
    private static final String WALLET = "0xe612560000000000000000000000000000000001";
    private static final String TRACKED_MEMBER = "0xe612560000000000000000000000000000000002";
    private static final String FOREIGN_ADDRESS = "0xcd74f91e4d2a49903462d58d6951136a527a5dea";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private SessionWalletAdjacencyService sessionWalletAdjacencyService;
    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;

    private LiFiForeignDestinationReclassificationService service;

    @BeforeEach
    void setUp() {
        service = new LiFiForeignDestinationReclassificationService(
                mongoOperations,
                normalizedTransactionRepository,
                rawTransactionRepository,
                accountingUniverseService,
                sessionWalletAdjacencyService,
                userSessionRepository,
                syncStatusRepository
        );
        lenient().when(normalizedTransactionRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("(a) DONE+COMPLETED with a foreign toAddress reclassifies to EXTERNAL_TRANSFER_OUT/SELL, confidence EXACT")
    void reclassifiesForeignDestinationToExternalTransferOutSell() {
        NormalizedTransaction tx = bridgeOutCandidate();
        RawTransaction raw = rawWithStatus(tx.getId(), doneCompletedStatus(FOREIGN_ADDRESS));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(rawTransactionRepository.findById(tx.getId())).thenReturn(Optional.of(raw));
        when(accountingUniverseService.shareUniverseMembers(WALLET, FOREIGN_ADDRESS)).thenReturn(false);
        when(sessionWalletAdjacencyService.anySessionListsBothAddresses(WALLET, FOREIGN_ADDRESS)).thenReturn(false);
        when(userSessionRepository.findAllByWalletsAddress(WALLET))
                .thenReturn(List.of(sessionTargeting(NetworkId.BASE)));
        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN, WALLET, NetworkId.BASE.name()
        )).thenReturn(Optional.of(completeSyncStatus()));

        int changed = service.reclassifyForeignDestinations(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.SELL);
        assertThat(tx.getFlows().getFirst().getCounterpartyAddress()).isEqualTo(FOREIGN_ADDRESS);
        assertThat(tx.getCounterpartyAddress()).isEqualTo(FOREIGN_ADDRESS);
        assertThat(tx.getCounterpartyResolutionState()).isEqualTo(MetadataResolutionState.RESOLVED_EXACT);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getMissingDataReasons()).doesNotContain("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("(b) DONE+COMPLETED with toAddress matching a tracked wallet is unaffected")
    void leavesSameOwnerDestinationUntouched() {
        NormalizedTransaction tx = bridgeOutCandidate();
        RawTransaction raw = rawWithStatus(tx.getId(), doneCompletedStatus(TRACKED_MEMBER));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(rawTransactionRepository.findById(tx.getId())).thenReturn(Optional.of(raw));
        when(accountingUniverseService.shareUniverseMembers(WALLET, TRACKED_MEMBER)).thenReturn(true);

        int changed = service.reclassifyForeignDestinations(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("(c) PENDING status or DONE+PARTIAL substatus leaves the row untouched")
    void leavesUnsettledOrPartialStatusUntouched() {
        NormalizedTransaction pendingTx = bridgeOutCandidate();
        RawTransaction pendingRaw = rawWithStatus(
                pendingTx.getId(),
                new LiFiBridgeStatus("0xsourcehash", "0xdesthash", NetworkId.BASE, "PENDING", null, FOREIGN_ADDRESS)
        );
        NormalizedTransaction partialTx = bridgeOutCandidate();
        RawTransaction partialRaw = rawWithStatus(
                partialTx.getId(),
                new LiFiBridgeStatus("0xsourcehash", "0xdesthash", NetworkId.BASE, "DONE", "PARTIAL", FOREIGN_ADDRESS)
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(pendingTx, partialTx));
        when(rawTransactionRepository.findById(pendingTx.getId())).thenReturn(Optional.of(pendingRaw));
        when(rawTransactionRepository.findById(partialTx.getId())).thenReturn(Optional.of(partialRaw));

        int changed = service.reclassifyForeignDestinations(50);

        assertThat(changed).isZero();
        assertThat(pendingTx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(partialTx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("(d) destination network not yet fully backfilled for the session leaves the row untouched")
    void leavesIncompleteDestinationBackfillUntouched() {
        NormalizedTransaction tx = bridgeOutCandidate();
        RawTransaction raw = rawWithStatus(tx.getId(), doneCompletedStatus(FOREIGN_ADDRESS));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(rawTransactionRepository.findById(tx.getId())).thenReturn(Optional.of(raw));
        when(accountingUniverseService.shareUniverseMembers(WALLET, FOREIGN_ADDRESS)).thenReturn(false);
        when(sessionWalletAdjacencyService.anySessionListsBothAddresses(WALLET, FOREIGN_ADDRESS)).thenReturn(false);
        when(userSessionRepository.findAllByWalletsAddress(WALLET))
                .thenReturn(List.of(sessionTargeting(NetworkId.BASE)));
        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN, WALLET, NetworkId.BASE.name()
        )).thenReturn(Optional.of(incompleteSyncStatus()));

        int changed = service.reclassifyForeignDestinations(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("(e) re-running over an already-reclassified row is a no-op (idempotency)")
    void reRunningOverAlreadyReclassifiedRowIsNoOp() {
        NormalizedTransaction tx = bridgeOutCandidate();
        RawTransaction raw = rawWithStatus(tx.getId(), doneCompletedStatus(FOREIGN_ADDRESS));
        lenient().when(rawTransactionRepository.findById(tx.getId())).thenReturn(Optional.of(raw));
        lenient().when(accountingUniverseService.shareUniverseMembers(WALLET, FOREIGN_ADDRESS)).thenReturn(false);
        lenient().when(sessionWalletAdjacencyService.anySessionListsBothAddresses(WALLET, FOREIGN_ADDRESS))
                .thenReturn(false);
        lenient().when(userSessionRepository.findAllByWalletsAddress(WALLET))
                .thenReturn(List.of(sessionTargeting(NetworkId.BASE)));
        lenient().when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN, WALLET, NetworkId.BASE.name()
        )).thenReturn(Optional.of(completeSyncStatus()));

        Instant now = Instant.now();
        boolean firstRun = service.reclassifyIfForeignDestination(tx, now);
        boolean secondRun = service.reclassifyIfForeignDestination(tx, now);

        assertThat(firstRun).isTrue();
        assertThat(secondRun).isFalse();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
    }

    @Test
    @DisplayName("(f) a legacy status document with toAddress=null is treated as unresolved, not a false non-match")
    void leavesLegacyNullToAddressUntouched() {
        NormalizedTransaction tx = bridgeOutCandidate();
        // Legacy 5-arg constructor call site: predates the toAddress field entirely.
        RawTransaction raw = rawWithStatus(
                tx.getId(),
                new LiFiBridgeStatus("0xsourcehash", "0xdesthash", NetworkId.BASE, "DONE", "COMPLETED")
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(rawTransactionRepository.findById(tx.getId())).thenReturn(Optional.of(raw));

        int changed = service.reclassifyForeignDestinations(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private static NormalizedTransaction bridgeOutCandidate() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("normalized-1");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress(WALLET);
        tx.setContinuityCandidate(false);
        tx.setCorrelationId("bridge:lifi:0xsourcehash");
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("-1.5"));
        flow.setRole(NormalizedLegRole.TRANSFER);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private static RawTransaction rawWithStatus(String id, LiFiBridgeStatus status) {
        RawTransaction raw = new RawTransaction();
        raw.setId(id);
        raw.setClarificationEvidence(new Document("protocolStatus", status.toDocument()));
        return raw;
    }

    private static LiFiBridgeStatus doneCompletedStatus(String toAddress) {
        return new LiFiBridgeStatus("0xsourcehash", "0xdesthash", NetworkId.BASE, "DONE", "COMPLETED", toAddress);
    }

    private static UserSession sessionTargeting(NetworkId destinationNetworkId) {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(WALLET);
        wallet.setNetworks(new ArrayList<>(List.of(destinationNetworkId)));
        session.setWallets(new ArrayList<>(List.of(wallet)));
        return session;
    }

    private static SyncStatus completeSyncStatus() {
        SyncStatus status = new SyncStatus();
        status.setWalletAddress(WALLET);
        status.setNetworkId(NetworkId.BASE.name());
        status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        status.setBackfillComplete(true);
        status.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        return status;
    }

    private static SyncStatus incompleteSyncStatus() {
        SyncStatus status = new SyncStatus();
        status.setWalletAddress(WALLET);
        status.setNetworkId(NetworkId.BASE.name());
        status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        status.setBackfillComplete(false);
        status.setStatus(SyncStatus.SyncStatusValue.RUNNING);
        return status;
    }
}
