package com.walletradar.application.normalization.pipeline.onchain.repair;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Repairs one-sided wallet-local raw omissions for simple direct native transfers inside one accounting universe.
 * The repaired peer raw row is then normalized through the regular on-chain pipeline.
 */
@Service
@RequiredArgsConstructor
public class InternalTransferRawPeerRepairService {

    private final RawTransactionRepository rawTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;

    public int repairMissingPeers(Collection<RawTransaction> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        int repaired = 0;
        Set<String> plannedKeys = new LinkedHashSet<>();
        for (RawTransaction rawTransaction : batch) {
            RawTransaction repairedPeer = buildMissingPeer(rawTransaction, plannedKeys);
            if (repairedPeer == null) {
                continue;
            }
            rawTransactionRepository.save(repairedPeer);
            plannedKeys.add(rawKey(repairedPeer.getTxHash(), repairedPeer.getNetworkId(), repairedPeer.getWalletAddress()));
            repaired++;
        }
        return repaired;
    }

    private RawTransaction buildMissingPeer(RawTransaction rawTransaction, Set<String> plannedKeys) {
        if (rawTransaction == null) {
            return null;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (!isSimpleDirectNativeTransfer(view)) {
            return null;
        }

        String walletAddress = normalizeAddress(view.walletAddress());
        String fromAddress = normalizeAddress(view.fromAddress());
        String toAddress = normalizeAddress(view.toAddress());
        if (walletAddress == null || fromAddress == null || toAddress == null) {
            return null;
        }

        String peerWallet = null;
        if (walletAddress.equals(toAddress)
                && accountingUniverseService.shareUniverseMembers(walletAddress, fromAddress)) {
            peerWallet = fromAddress;
        } else if (walletAddress.equals(fromAddress)
                && accountingUniverseService.shareUniverseMembers(walletAddress, toAddress)) {
            peerWallet = toAddress;
        }
        if (peerWallet == null || peerWallet.equals(walletAddress)) {
            return null;
        }

        String peerKey = rawKey(rawTransaction.getTxHash(), rawTransaction.getNetworkId(), peerWallet);
        if (plannedKeys.contains(peerKey)) {
            return null;
        }
        Optional<RawTransaction> existingPeer = rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                rawTransaction.getTxHash(),
                rawTransaction.getNetworkId(),
                peerWallet
        );
        if (existingPeer.isPresent()) {
            return null;
        }

        return cloneForWallet(rawTransaction, peerWallet);
    }

    private boolean isSimpleDirectNativeTransfer(OnChainRawTransactionView view) {
        if (view == null
                || view.txHash() == null
                || view.networkId() == null
                || view.rawValue() == null
                || view.rawValue().signum() <= 0) {
            return false;
        }
        if (!"0x".equals(Objects.toString(view.methodId(), "0x"))) {
            return false;
        }
        String fromAddress = normalizeAddress(view.fromAddress());
        String toAddress = normalizeAddress(view.toAddress());
        if (fromAddress == null || toAddress == null || fromAddress.equals(toAddress)) {
            return false;
        }
        return view.explorerTokenTransfers().isEmpty()
                && view.explorerInternalTransfers().isEmpty();
    }

    private RawTransaction cloneForWallet(RawTransaction source, String walletAddress) {
        RawTransaction clone = new RawTransaction();
        clone.setId(canonicalRawId(source.getTxHash(), source.getNetworkId(), walletAddress));
        clone.setTxHash(source.getTxHash());
        clone.setNetworkId(source.getNetworkId());
        clone.setSyncMethod(source.getSyncMethod());
        clone.setWalletAddress(walletAddress);
        clone.setBlockNumber(source.getBlockNumber());
        clone.setSlot(source.getSlot());
        clone.setNormalizationStatus(NormalizationStatus.PENDING);
        clone.setRetryCount(0);
        clone.setLastError(null);
        clone.setNextRetryAt(null);
        clone.setCreatedAt(source.getCreatedAt() != null ? source.getCreatedAt() : Instant.now());
        clone.setRawData(copyDocument(source.getRawData()));
        clone.setClarificationEvidence(copyDocument(source.getClarificationEvidence()));
        return clone;
    }

    private Document copyDocument(Document source) {
        return source == null ? null : Document.parse(source.toJson());
    }

    private String canonicalRawId(String txHash, String networkId, String walletAddress) {
        return rawKey(txHash, networkId, walletAddress);
    }

    private String rawKey(String txHash, String networkId, String walletAddress) {
        return Objects.toString(txHash, "") + ":" + Objects.toString(networkId, "") + ":" + normalizeAddress(walletAddress);
    }

    private String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
