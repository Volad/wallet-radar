package com.walletradar.application.normalization.pipeline.onchain.repair;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.session.application.AccountingUniverseService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransferRawPeerRepairServiceTest {

    private static final String RECIPIENT = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String SENDER = "0xa0dd42c626b002778f93e1ab42cbed5f31c117b2";

    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    @Test
    @DisplayName("repairs missing sender-side raw peer for same-universe direct native transfer")
    void repairsMissingSenderSideRawPeer() {
        RawTransaction recipientRaw = directNativeRecipientRaw();
        when(accountingUniverseService.shareUniverseMembers(RECIPIENT, SENDER)).thenReturn(true);
        when(rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                recipientRaw.getTxHash(),
                recipientRaw.getNetworkId(),
                SENDER
        )).thenReturn(Optional.empty());

        InternalTransferRawPeerRepairService service = new InternalTransferRawPeerRepairService(
                rawTransactionRepository,
                accountingUniverseService
        );

        int repaired = service.repairMissingPeers(List.of(recipientRaw));

        assertThat(repaired).isEqualTo(1);
        ArgumentCaptor<RawTransaction> captor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(captor.capture());
        RawTransaction repairedPeer = captor.getValue();
        assertThat(repairedPeer.getId()).isEqualTo(recipientRaw.getTxHash() + ":" + recipientRaw.getNetworkId() + ":" + SENDER);
        assertThat(repairedPeer.getWalletAddress()).isEqualTo(SENDER);
        assertThat(repairedPeer.getNormalizationStatus()).isEqualTo(NormalizationStatus.PENDING);
        assertThat(repairedPeer.getRawData()).isNotSameAs(recipientRaw.getRawData());
        assertThat(repairedPeer.getRawData().get("explorer", Document.class).get("tx", Document.class))
                .isEqualTo(recipientRaw.getRawData().get("explorer", Document.class).get("tx", Document.class));
    }

    @Test
    @DisplayName("does not repair when peer raw row already exists")
    void doesNotRepairWhenPeerRawAlreadyExists() {
        RawTransaction recipientRaw = directNativeRecipientRaw();
        when(accountingUniverseService.shareUniverseMembers(RECIPIENT, SENDER)).thenReturn(true);
        when(rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                recipientRaw.getTxHash(),
                recipientRaw.getNetworkId(),
                SENDER
        )).thenReturn(Optional.of(new RawTransaction()));

        InternalTransferRawPeerRepairService service = new InternalTransferRawPeerRepairService(
                rawTransactionRepository,
                accountingUniverseService
        );

        int repaired = service.repairMissingPeers(List.of(recipientRaw));

        assertThat(repaired).isZero();
        verify(rawTransactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private RawTransaction directNativeRecipientRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xffc959c27972e84a0e69860e9ed312dce3db85aa6e23f2e90f22e7969b447ca1:MANTLE:" + RECIPIENT);
        rawTransaction.setTxHash("0xffc959c27972e84a0e69860e9ed312dce3db85aa6e23f2e90f22e7969b447ca1");
        rawTransaction.setNetworkId("MANTLE");
        rawTransaction.setWalletAddress(RECIPIENT);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setCreatedAt(Instant.parse("2026-04-09T10:00:00Z"));
        rawTransaction.setRawData(new Document("methodId", "0x")
                .append("value", "800000000000000000")
                .append("gasUsed", "51154710")
                .append("gasPrice", "20000000")
                .append("explorer", new Document("tx", new Document()
                        .append("from", SENDER)
                        .append("to", RECIPIENT)
                        .append("value", "800000000000000000")
                        .append("timeStamp", "1744971798")
                        .append("transactionIndex", "2"))));
        return rawTransaction;
    }
}
