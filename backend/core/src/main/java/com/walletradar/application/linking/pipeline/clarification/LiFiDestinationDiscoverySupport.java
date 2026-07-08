package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.BridgeSettlementSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * LI.FI-specific destination discovery helpers: wallet-relevance evidence ladder and
 * LiFi-corroborated settlement leg enrichment for calldata-only bridge settlements.
 */
final class LiFiDestinationDiscoverySupport {

    static final String LIFI_CORROBORATED_SETTLEMENT = "LIFI_CORROBORATED_SETTLEMENT";
    static final String DISCOVERY_PATH_FIELD = "lifiDestinationDiscoveryPath";

    private static final int MAX_CALLDATA_ADDRESS_WORDS = 256;
    private static final BigDecimal MAX_SYNTHETIC_QTY_RELATIVE_DRIFT = new BigDecimal("0.15");

    private LiFiDestinationDiscoverySupport() {
    }

    static Optional<LiFiDestinationDiscoveryPath> resolveWalletRelevance(
            RawTransaction rawTransaction,
            String walletAddress,
            LiFiBridgeStatus status,
            ProtocolRegistryService protocolRegistryService
    ) {
        if (rawTransaction == null || blank(walletAddress) || status == null) {
            return Optional.empty();
        }
        if (LiFiReceivingTransactionDiscoveryService.hasWalletTouchEvidence(rawTransaction, walletAddress)) {
            return Optional.of(LiFiDestinationDiscoveryPath.WALLET_TOUCH);
        }
        if (hasTraceWalletCredit(rawTransaction, walletAddress)) {
            return Optional.of(LiFiDestinationDiscoveryPath.TRACE);
        }
        if (hasLiFiCalldataBeneficiaryEvidence(rawTransaction, walletAddress, status, protocolRegistryService)) {
            return Optional.of(LiFiDestinationDiscoveryPath.LIFI_CALLDATA);
        }
        return Optional.empty();
    }

    static boolean enrichCalldataSettlementBeforeClassification(
            RawTransaction rawTransaction,
            String walletAddress,
            LiFiBridgeStatus status,
            LiFiDestinationDiscoveryPath discoveryPath,
            NormalizedTransaction sourceHint,
            NormalizedTransactionRepository normalizedTransactionRepository,
            ProtocolRegistryService protocolRegistryService
    ) {
        if (rawTransaction == null
                || discoveryPath != LiFiDestinationDiscoveryPath.LIFI_CALLDATA
                || !isDoneStatus(status)) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (LiFiReceivingTransactionDiscoveryService.hasWalletTouchEvidence(rawTransaction, walletAddress)) {
            return false;
        }
        Optional<NormalizedTransaction.Flow> sourcePrincipal = resolveSourceOutboundPrincipal(
                status,
                sourceHint,
                normalizedTransactionRepository
        );
        if (sourcePrincipal.isEmpty()) {
            return false;
        }
        NormalizedTransaction.Flow outbound = sourcePrincipal.orElseThrow();
        if (outbound.getQuantityDelta() == null || outbound.getQuantityDelta().signum() >= 0) {
            return false;
        }
        // Only inject a synthetic native-asset internal transfer for ETH (native) bridges.
        // Token bridges (ERC-20) deliver funds via ERC-20 token transfers — which the classifier
        // reads directly from explorer token-transfer logs. Injecting synthetic wei for a USDC
        // bridge quantity would create a phantom ETH position with a $1/ETH AVCO.
        if (!blank(outbound.getAssetContract())) {
            return false;
        }
        BigDecimal inboundQuantity = outbound.getQuantityDelta().abs();
        String bridgeSender = resolveBridgeSettlementSender(view, protocolRegistryLookup(view, protocolRegistryService))
                .orElse(view.toAddress());
        if (blank(bridgeSender)) {
            bridgeSender = view.fromAddress();
        }
        if (blank(bridgeSender)) {
            return false;
        }
        Document syntheticTransfer = new Document()
                .append("from", bridgeSender)
                .append("to", OnChainRawTransactionView.normalizeAddress(walletAddress))
                .append("value", inboundQuantity.movePointRight(18).setScale(0, RoundingMode.UNNECESSARY).toPlainString())
                .append("type", "call")
                .append("discoverySource", LIFI_CORROBORATED_SETTLEMENT);
        appendSyntheticInternalTransfer(rawTransaction, syntheticTransfer);
        stampDiscoveryPath(rawTransaction, discoveryPath);
        return true;
    }

    static List<String> orderedTrackedWalletAddresses(
            List<String> trackedWalletAddresses,
            String preferredWalletAddress
    ) {
        if (trackedWalletAddresses == null || trackedWalletAddresses.isEmpty()) {
            return List.of();
        }
        String preferred = OnChainRawTransactionView.normalizeAddress(preferredWalletAddress);
        List<String> ordered = new ArrayList<>();
        if (preferred != null) {
            for (String candidate : trackedWalletAddresses) {
                if (preferred.equals(OnChainRawTransactionView.normalizeAddress(candidate))) {
                    ordered.add(candidate);
                }
            }
        }
        for (String candidate : trackedWalletAddresses) {
            if (ordered.stream().noneMatch(existing -> sameAddress(existing, candidate))) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    static Optional<String> resolvePreferredSourceWallet(
            LiFiBridgeStatus status,
            NormalizedTransaction sourceHint,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        if (sourceHint != null && !blank(sourceHint.getWalletAddress())) {
            return Optional.of(sourceHint.getWalletAddress().toLowerCase(Locale.ROOT));
        }
        return resolvePreferredSourceWallet(status, normalizedTransactionRepository);
    }

    static Optional<String> resolvePreferredSourceWallet(
            LiFiBridgeStatus status,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        if (status == null || blank(status.sendingTxHash()) || normalizedTransactionRepository == null) {
            return Optional.empty();
        }
        return normalizedTransactionRepository.findAllByTxHashAndSource(
                        status.sendingTxHash(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.getType() == NormalizedTransactionType.BRIDGE_OUT)
                .map(NormalizedTransaction::getWalletAddress)
                .filter(address -> !blank(address))
                .map(address -> address.toLowerCase(Locale.ROOT))
                .findFirst();
    }

    static void stampDiscoveryPath(RawTransaction rawTransaction, LiFiDestinationDiscoveryPath discoveryPath) {
        if (rawTransaction == null || discoveryPath == null) {
            return;
        }
        Document clarificationEvidence = rawTransaction.getClarificationEvidence();
        if (clarificationEvidence == null) {
            clarificationEvidence = new Document();
            rawTransaction.setClarificationEvidence(clarificationEvidence);
        }
        clarificationEvidence.put(DISCOVERY_PATH_FIELD, discoveryPath.name());
    }

    private static boolean hasLiFiCalldataBeneficiaryEvidence(
            RawTransaction rawTransaction,
            String walletAddress,
            LiFiBridgeStatus status,
            ProtocolRegistryService protocolRegistryService
    ) {
        if (!isDoneStatus(status)) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (!BridgeSettlementSupport.isSettlementSelector(view)) {
            return false;
        }
        String normalizedWallet = OnChainRawTransactionView.normalizeAddress(walletAddress);
        if (normalizedWallet == null) {
            return false;
        }
        boolean beneficiaryMatches = decodeSettlementBeneficiaryCandidates(view.inputData()).stream()
                .anyMatch(candidate -> normalizedWallet.equals(OnChainRawTransactionView.normalizeAddress(candidate)));
        if (!beneficiaryMatches) {
            return false;
        }
        // Registry hit strengthens the match; settlement selector allowlist + LiFi DONE is sufficient
        // for LayerZero/Stargate executors not yet present in protocol-registry.json.
        return protocolRegistryLookup(view, protocolRegistryService).isPresent()
                || BridgeSettlementSupport.isSettlementSelector(view);
    }

    private static boolean hasTraceWalletCredit(RawTransaction rawTransaction, String walletAddress) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        String normalizedWallet = OnChainRawTransactionView.normalizeAddress(walletAddress);
        if (normalizedWallet == null) {
            return false;
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            if (!normalizedWallet.equals(view.internalTransferTo(transfer))) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity != null && quantity.signum() > 0) {
                return true;
            }
        }
        return false;
    }

    private static Optional<ProtocolRegistryEntry> protocolRegistryLookup(
            OnChainRawTransactionView view,
            ProtocolRegistryService protocolRegistryService
    ) {
        if (view == null || protocolRegistryService == null || view.networkId() == null) {
            return Optional.empty();
        }
        NetworkId networkId = view.networkId();
        Optional<ProtocolRegistryEntry> toEntry = protocolRegistryService.lookup(networkId, view.toAddress())
                .filter(LiFiDestinationDiscoverySupport::isBridgeRegistryEntry);
        if (toEntry.isPresent()) {
            return toEntry;
        }
        return protocolRegistryService.lookup(networkId, view.fromAddress())
                .filter(LiFiDestinationDiscoverySupport::isBridgeRegistryEntry);
    }

    private static Optional<String> resolveBridgeSettlementSender(
            OnChainRawTransactionView view,
            Optional<ProtocolRegistryEntry> bridgeEntry
    ) {
        if (view == null) {
            return Optional.empty();
        }
        if (bridgeEntry.isPresent()
                && sameAddress(view.toAddress(), bridgeEntry.orElseThrow().contractAddress())) {
            return Optional.ofNullable(view.toAddress());
        }
        if (bridgeEntry.isPresent()
                && sameAddress(view.fromAddress(), bridgeEntry.orElseThrow().contractAddress())) {
            return Optional.ofNullable(view.fromAddress());
        }
        return Optional.ofNullable(view.fromAddress());
    }

    private static boolean isBridgeRegistryEntry(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.BRIDGE
                && (entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                || entry.role() == ProtocolRegistryRole.ROUTER);
    }

    static List<String> decodeSettlementBeneficiaryCandidates(String inputData) {
        if (inputData == null || !inputData.startsWith("0x") || inputData.length() < 10 + 64) {
            return List.of();
        }
        String payload = inputData.substring(2);
        if (payload.length() < 8) {
            return List.of();
        }
        String arguments = payload.substring(8);
        Set<String> candidates = new LinkedHashSet<>();
        appendAddressWordCandidates(arguments, candidates);
        return List.copyOf(candidates);
    }

    private static void appendAddressWordCandidates(String arguments, Set<String> candidates) {
        if (arguments == null || arguments.length() < 64) {
            return;
        }
        int maxOffset = Math.min(arguments.length() - 64, MAX_CALLDATA_ADDRESS_WORDS * 64);
        for (int offset = 0; offset <= maxOffset; offset += 2) {
            String slot = arguments.substring(offset, offset + 64);
            if (!slot.startsWith("000000000000000000000000")) {
                continue;
            }
            String address = OnChainRawTransactionView.normalizeAddress("0x" + slot.substring(24));
            if (address != null && !isZeroAddress(address)) {
                candidates.add(address);
            }
        }
    }

    private static Optional<NormalizedTransaction.Flow> resolveSourceOutboundPrincipal(
            LiFiBridgeStatus status,
            NormalizedTransaction sourceHint,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        if (sourceHint != null
                && sourceHint.getType() == NormalizedTransactionType.BRIDGE_OUT
                && matchesSendingTxHash(status, sourceHint)) {
            Optional<NormalizedTransaction.Flow> hinted = selectPrimaryOutboundPrincipal(sourceHint);
            if (hinted.isPresent()) {
                return hinted;
            }
        }
        return resolveSourceOutboundPrincipal(status, normalizedTransactionRepository);
    }

    private static boolean matchesSendingTxHash(LiFiBridgeStatus status, NormalizedTransaction sourceHint) {
        return status != null
                && !blank(status.sendingTxHash())
                && sourceHint != null
                && !blank(sourceHint.getTxHash())
                && status.sendingTxHash().equalsIgnoreCase(sourceHint.getTxHash().trim());
    }

    private static Optional<NormalizedTransaction.Flow> resolveSourceOutboundPrincipal(
            LiFiBridgeStatus status,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        if (status == null || blank(status.sendingTxHash())) {
            return Optional.empty();
        }
        return normalizedTransactionRepository.findAllByTxHashAndSource(
                        status.sendingTxHash(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.getType() == NormalizedTransactionType.BRIDGE_OUT)
                .map(LiFiDestinationDiscoverySupport::selectPrimaryOutboundPrincipal)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<NormalizedTransaction.Flow> selectPrimaryOutboundPrincipal(NormalizedTransaction source) {
        if (source == null || source.getFlows() == null) {
            return Optional.empty();
        }
        NormalizedTransaction.Flow selected = null;
        for (NormalizedTransaction.Flow flow : source.getFlows()) {
            if (flow == null
                    || flow.getRole() == NormalizedLegRole.FEE
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (selected == null
                    || flow.getQuantityDelta().abs().compareTo(selected.getQuantityDelta().abs()) > 0) {
                selected = flow;
            }
        }
        return Optional.ofNullable(selected);
    }

    private static void appendSyntheticInternalTransfer(RawTransaction rawTransaction, Document syntheticTransfer) {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            rawData = new Document();
            rawTransaction.setRawData(rawData);
        }
        Document explorer = rawData.get("explorer", Document.class);
        if (explorer == null) {
            explorer = new Document();
            rawData.put("explorer", explorer);
        }
        @SuppressWarnings("unchecked")
        List<Document> internalTransfers = explorer.get("internalTransfers", List.class);
        List<Document> updated = new ArrayList<>();
        if (internalTransfers != null) {
            updated.addAll(internalTransfers);
        }
        boolean alreadyPresent = updated.stream().anyMatch(existing ->
                LIFI_CORROBORATED_SETTLEMENT.equals(existing.getString("discoverySource")));
        if (!alreadyPresent) {
            updated.add(syntheticTransfer);
        }
        explorer.put("internalTransfers", updated);
    }

    static boolean isDoneStatus(LiFiBridgeStatus status) {
        return status != null
                && status.apiStatus() != null
                && "DONE".equalsIgnoreCase(status.apiStatus());
    }

    static boolean withinSyntheticQtyTolerance(BigDecimal sourceQty, BigDecimal destinationQty) {
        if (sourceQty == null || destinationQty == null || sourceQty.signum() <= 0 || destinationQty.signum() <= 0) {
            return false;
        }
        BigDecimal left = sourceQty.abs();
        BigDecimal right = destinationQty.abs();
        BigDecimal denominator = left.max(right);
        if (denominator.signum() == 0) {
            return false;
        }
        BigDecimal relativeDiff = left.subtract(right).abs().divide(denominator, 12, RoundingMode.HALF_UP);
        return relativeDiff.compareTo(MAX_SYNTHETIC_QTY_RELATIVE_DRIFT) <= 0;
    }

    private static boolean isZeroAddress(String address) {
        return "0x0000000000000000000000000000000000000000".equalsIgnoreCase(address);
    }

    private static boolean sameAddress(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
