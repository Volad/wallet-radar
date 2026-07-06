package com.walletradar.ingestion.job.linking;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionPipelineActivityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkingDataGateServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private SessionPipelineActivityService sessionPipelineActivityService;
    @Mock
    private MongoOperations mongoOperations;

    @Test
    void snapshotBlocksWhileOnChainClarificationStillHasFreshActivity() {
        UserSession session = session("session-1", "0xabc");
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(
                new AccountingUniverseService.AccountingUniverseScope(
                        "universe-1",
                        List.of("0xabc"),
                        List.of("0xabc")
                )
        );
        when(mongoOperations.count(any(Query.class), eq("raw_transactions"))).thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.normalized.NormalizedTransaction.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.bybit.BybitExtractedEvent.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw.class)))
                .thenReturn(0L);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION), any()))
                .thenReturn(true);
        LinkingDataGateService.LinkingGateSnapshot snapshot = service().snapshot("session-1");

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.pendingOnChainClassificationCount()).isZero();
        assertThat(snapshot.pendingClarificationCount()).isZero();
        assertThat(snapshot.pendingBybitClassificationCount()).isZero();
        assertThat(snapshot.classificationStillRunning()).isTrue();
    }

    @Test
    void snapshotBlocksWhileOnChainRawStillPending() {
        UserSession session = session("session-1", "0xabc");
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(
                new AccountingUniverseService.AccountingUniverseScope(
                        "universe-1",
                        List.of("0xabc"),
                        List.of("0xabc")
                )
        );
        when(mongoOperations.count(any(Query.class), eq("raw_transactions"))).thenReturn(12L);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.BYBIT_NORMALIZATION), any()))
                .thenReturn(false);

        LinkingDataGateService.LinkingGateSnapshot snapshot = service().snapshot("session-1");

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.pendingOnChainClassificationCount()).isEqualTo(12L);
    }

    @Test
    void snapshotBlocksWhileFreshClassificationRunningStateIsPersisted() {
        UserSession session = session("session-1", "0xabc");
        UserSession.PipelineState pipelineState = new UserSession.PipelineState();
        pipelineState.setStage(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION);
        pipelineState.setStatus(UserSession.PipelineStatus.RUNNING);
        pipelineState.setUpdatedAt(Instant.now());
        session.setPipelineState(pipelineState);
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(
                new AccountingUniverseService.AccountingUniverseScope(
                        "universe-1",
                        List.of("0xabc"),
                        List.of("0xabc")
                )
        );
        when(mongoOperations.count(any(Query.class), eq("raw_transactions"))).thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.normalized.NormalizedTransaction.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.bybit.BybitExtractedEvent.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw.class)))
                .thenReturn(0L);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.BYBIT_NORMALIZATION), any()))
                .thenReturn(false);

        LinkingDataGateService.LinkingGateSnapshot snapshot = service().snapshot("session-1");

        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.classificationStillRunning()).isTrue();
    }

    @Test
    void snapshotIsReadyWhenNoPendingSourceWorkOrFreshClassificationActivityExists() {
        UserSession session = session("session-1", "0xabc");
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(
                new AccountingUniverseService.AccountingUniverseScope(
                        "universe-1",
                        List.of("0xabc"),
                        List.of("0xabc")
                )
        );
        when(mongoOperations.count(any(Query.class), eq("raw_transactions"))).thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.normalized.NormalizedTransaction.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.bybit.BybitExtractedEvent.class)))
                .thenReturn(0L);
        when(mongoOperations.count(any(Query.class), eq(com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw.class)))
                .thenReturn(0L);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION), any()))
                .thenReturn(false);
        when(sessionPipelineActivityService.hasFreshActivity(eq("session-1"), eq(UserSession.PipelineStage.BYBIT_NORMALIZATION), any()))
                .thenReturn(false);

        LinkingDataGateService.LinkingGateSnapshot snapshot = service().snapshot("session-1");

        assertThat(snapshot.ready()).isTrue();
        assertThat(snapshot.classificationStillRunning()).isFalse();
    }

    private LinkingDataGateService service() {
        return new LinkingDataGateService(
                userSessionRepository,
                accountingUniverseService,
                sessionPipelineActivityService,
                mongoOperations
        );
    }

    private static UserSession session(String sessionId, String walletAddress) {
        UserSession session = new UserSession();
        session.setId(sessionId);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(walletAddress);
        wallet.setNetworks(List.of(NetworkId.ETHEREUM));
        session.setWallets(List.of(wallet));
        return session;
    }
}
