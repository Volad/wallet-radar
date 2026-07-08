package com.walletradar.application.session.application;

import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.UserSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackedWalletProjectionServiceTest {

    @Mock
    private MongoOperations mongoOperations;

    @InjectMocks
    private TrackedWalletProjectionService trackedWalletProjectionService;

    @Test
    @DisplayName("replaceSessionWallets upserts added addresses and does not decrement when session is new")
    void replaceSessionWallets_upsertsAddedAddresses() {
        Instant now = Instant.parse("2026-03-19T12:00:00Z");

        trackedWalletProjectionService.replaceSessionWallets(
                List.of(),
                List.of(wallet("0xaaa"), wallet("0xbbb")),
                now
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoOperations, times(2)).upsert(queryCaptor.capture(), updateCaptor.capture(), eq(TrackedWallet.class));
        verify(mongoOperations, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrackedWallet.class));

        assertThat(queryCaptor.getAllValues()).hasSize(2);
        assertThat(updateCaptor.getAllValues()).hasSize(2);
    }

    @Test
    @DisplayName("replaceSessionWallets touches retained, adds new, and deletes removed when refCount reaches zero")
    void replaceSessionWallets_updatesRetainedAndDeletesRemoved() {
        Instant now = Instant.parse("2026-03-19T12:00:00Z");
        TrackedWallet decremented = new TrackedWallet();
        decremented.setAddress("0xbbb");
        decremented.setRefCount(0);
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrackedWallet.class)))
                .thenReturn(decremented);

        trackedWalletProjectionService.replaceSessionWallets(
                List.of(wallet("0xaaa"), wallet("0xbbb")),
                List.of(wallet("0xaaa"), wallet("0xccc")),
                now
        );

        verify(mongoOperations).updateFirst(any(Query.class), any(Update.class), eq(TrackedWallet.class));
        verify(mongoOperations).upsert(any(Query.class), any(Update.class), eq(TrackedWallet.class));
        verify(mongoOperations).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrackedWallet.class));
        verify(mongoOperations).remove(any(Query.class), eq(TrackedWallet.class));
    }

    private static UserSession.SessionWallet wallet(String address) {
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(address);
        return wallet;
    }
}
