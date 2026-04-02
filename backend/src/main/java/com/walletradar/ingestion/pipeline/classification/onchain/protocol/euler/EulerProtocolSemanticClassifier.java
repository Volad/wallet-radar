package com.walletradar.ingestion.pipeline.classification.onchain.protocol.euler;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Euler protocol-owned clarified batch lifecycle semantics.
 */
@Component
public class EulerProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    public static final String PROTOCOL_KEY = "euler";
    public static final String PROTOCOL_NAME = "Euler";
    public static final String PROTOCOL_VERSION = "v1";

    public static final String SEMANTIC_LENDING_DEPOSIT = "lending_deposit";
    public static final String SEMANTIC_LENDING_WITHDRAW = "lending_withdraw";
    public static final String SEMANTIC_LENDING_LOOP_OPEN = "lending_loop_open";
    public static final String SEMANTIC_LENDING_LOOP_REBALANCE = "lending_loop_rebalance";
    public static final String SEMANTIC_LENDING_LOOP_DECREASE = "lending_loop_decrease";
    public static final String SEMANTIC_LENDING_LOOP_CLOSE = "lending_loop_close";

    private static final String EULER_BATCH_ROUTER = "0xddcbe30a761edd2e19bba930a977475265f36fa1";
    private static final String EULER_CALL_WITH_CONTEXT_TOPIC =
            "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec";
    private static final String EULER_BORROW_EVENT_TOPIC =
            "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750";
    private static final String EULER_BORROW_SELECTOR = "4b3fd148";
    private static final String EULER_DEUSD_CONTRACT = "0xb57b25851fe2311cc3fe511c8f10e868932e0680";
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final ProtocolResourceDefinition resource;

    public EulerProtocolSemanticClassifier(ProtocolResourceCatalog protocolResourceCatalog) {
        this.resource = protocolResourceCatalog.find(PROTOCOL_NAME, PROTOCOL_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 130;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (context == null || context.view() == null || !isClarifiedBatchCandidate(context.view())) {
            return List.of();
        }

        OnChainRawTransactionView view = context.view();
        List<RawLeg> movementLegs = context.movementLegs();

        if (isEulerBorrowBackedCollateralOpen(view, movementLegs)
                && view.hasFullReceiptClarificationEvidence()
                && hasEulerClarifiedCollateralOpenLifecycle(view)) {
            return List.of(hint(SEMANTIC_LENDING_LOOP_OPEN));
        }

        if (detectEulerLoopRebalancePattern(movementLegs).isPresent()) {
            return List.of(hint(SEMANTIC_LENDING_LOOP_REBALANCE));
        }

        Optional<Document> shareOutboundTransfer = findEulerLoopShareOutboundTransfer(view);
        if (shareOutboundTransfer.isPresent()) {
            Optional<Document> returnedStableTransfer = findEulerLoopReturnedStableTransfer(view);
            if (returnedStableTransfer.isPresent()) {
                BigDecimal stableUnitPrice = resolveEulerStableLikeUnitPrice(returnedStableTransfer.orElseThrow());
                BigDecimal shareQuantity = view.tokenTransferQuantity(shareOutboundTransfer.orElseThrow());
                BigDecimal returnedQuantity = view.tokenTransferQuantity(returnedStableTransfer.orElseThrow());
                if (stableUnitPrice != null
                        && shareQuantity != null
                        && returnedQuantity != null
                        && shareQuantity.signum() > 0
                        && returnedQuantity.signum() > 0) {
                    return List.of(hint(
                            isZeroAddress(view.tokenTransferTo(shareOutboundTransfer.orElseThrow()))
                                    ? SEMANTIC_LENDING_LOOP_CLOSE
                                    : SEMANTIC_LENDING_LOOP_DECREASE
                    ));
                }
            }
        }

        if (!view.hasFullReceiptClarificationEvidence()) {
            return List.of();
        }

        boolean shareInbound = hasShareLikeMovement(movementLegs, true)
                || hasMintedFungibleTransferToWallet(view);
        boolean shareOutbound = hasShareLikeMovement(movementLegs, false)
                || hasBurnedFungibleTransferFromWallet(view);
        boolean principalInbound = hasNonShareMovement(movementLegs, true)
                || hasAnyInboundFungibleTransferToWallet(view);
        boolean principalOutbound = hasNonShareMovement(movementLegs, false)
                || hasAnyOutboundFungibleTransferFromWallet(view);

        if (shareInbound && principalOutbound) {
            return List.of(hint(SEMANTIC_LENDING_DEPOSIT));
        }
        if (shareOutbound && principalInbound) {
            return List.of(hint(SEMANTIC_LENDING_WITHDRAW));
        }

        return List.of();
    }

    private ProtocolSemanticHint hint(String semanticType) {
        return new ProtocolSemanticHint(
                PROTOCOL_KEY,
                semanticType,
                PROTOCOL_NAME,
                PROTOCOL_VERSION,
                null,
                suggestedType(semanticType),
                ConfidenceLevel.LOW
        );
    }

    private NormalizedTransactionType suggestedType(String semanticType) {
        return switch (semanticType) {
            case SEMANTIC_LENDING_DEPOSIT -> NormalizedTransactionType.LENDING_DEPOSIT;
            case SEMANTIC_LENDING_WITHDRAW -> NormalizedTransactionType.LENDING_WITHDRAW;
            case SEMANTIC_LENDING_LOOP_OPEN -> NormalizedTransactionType.LENDING_LOOP_OPEN;
            case SEMANTIC_LENDING_LOOP_REBALANCE -> NormalizedTransactionType.LENDING_LOOP_REBALANCE;
            case SEMANTIC_LENDING_LOOP_DECREASE -> NormalizedTransactionType.LENDING_LOOP_DECREASE;
            case SEMANTIC_LENDING_LOOP_CLOSE -> NormalizedTransactionType.LENDING_LOOP_CLOSE;
            default -> null;
        };
    }

    private boolean isClarifiedBatchCandidate(OnChainRawTransactionView view) {
        return matchesMethodSelector(view.methodId(), "batch", "0xc16ae7a4")
                || matchesFunctionMarker(view.functionName(), "batch", "batch");
    }

    private boolean isEulerBorrowBackedCollateralOpen(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return false;
        }
        if (!hasEulerBorrowCallContext(view) || !hasEulerBorrowEvent(view)) {
            return false;
        }
        return hasDebtLikeMovement(movementLegs, true)
                && hasNonDebtShareLikeMovement(movementLegs, true);
    }

    private boolean hasEulerBorrowCallContext(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (topics.isEmpty()
                    || !matchesEventTopic(topics.getFirst(), "callWithContext", EULER_CALL_WITH_CONTEXT_TOPIC)) {
                continue;
            }
            String data = logData(log);
            if (data != null && containsMethodSelector(data, "borrowSubcall", EULER_BORROW_SELECTOR)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerBorrowEvent(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (!topics.isEmpty() && matchesEventTopic(topics.getFirst(), "borrow", EULER_BORROW_EVENT_TOPIC)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerClarifiedCollateralOpenLifecycle(OnChainRawTransactionView view) {
        boolean debtMintToWallet = false;
        boolean shareMintToWallet = false;
        boolean protocolHop = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = view.tokenTransferSymbol(transfer);
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            if (matchesWalletAccount(view, to) && isZeroAddress(from)) {
                if (isDebtLikeSymbol(symbol)) {
                    debtMintToWallet = true;
                    continue;
                }
                if (isShareLikeSymbol(symbol)) {
                    shareMintToWallet = true;
                    continue;
                }
            }
            if (!matchesWalletAccount(view, from)
                    && !matchesWalletAccount(view, to)
                    && !isZeroAddress(from)
                    && !isZeroAddress(to)
                    && !isDebtLikeSymbol(symbol)
                    && !isShareLikeSymbol(symbol)) {
                protocolHop = true;
            }
        }
        return debtMintToWallet && shareMintToWallet && protocolHop;
    }

    private Optional<EulerLoopRebalancePattern> detectEulerLoopRebalancePattern(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerDebtLikeMovement)) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerNonShareEconomicMovement)) {
            return Optional.empty();
        }

        Map<String, RawLeg> outboundShares = aggregateEulerShareLegs(movementLegs, false);
        Map<String, RawLeg> inboundShares = aggregateEulerShareLegs(movementLegs, true);
        if (outboundShares.size() != 1 || inboundShares.isEmpty()) {
            return Optional.empty();
        }

        RawLeg sourceShare = outboundShares.values().iterator().next();
        String sourceAssetKey = rawLegAssetKey(sourceShare);
        Map<String, RawLeg> replacementCandidates = new LinkedHashMap<>(inboundShares);
        RawLeg sameAssetInbound = replacementCandidates.get(sourceAssetKey);
        RawLeg sourceRefund = null;
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) == 0
                && replacementCandidates.size() > 1) {
            replacementCandidates.remove(sourceAssetKey);
        }
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) != 0) {
            sourceRefund = sameAssetInbound;
            replacementCandidates.remove(sourceAssetKey);
        }
        if (replacementCandidates.size() != 1) {
            return Optional.empty();
        }

        RawLeg replacementShare = replacementCandidates.values().iterator().next();
        if (sameAsset(sourceShare, replacementShare)) {
            return Optional.empty();
        }

        return Optional.of(new EulerLoopRebalancePattern(sourceShare, replacementShare, sourceRefund));
    }

    private Map<String, RawLeg> aggregateEulerShareLegs(
            List<RawLeg> movementLegs,
            boolean inbound
    ) {
        Map<String, RawLeg> aggregated = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null
                    || leg.fee()
                    || leg.quantityDelta() == null
                    || leg.quantityDelta().signum() == 0
                    || isDebtLikeSymbol(leg.assetSymbol())
                    || !isShareLikeSymbol(leg.assetSymbol())
                    || (inbound ? leg.quantityDelta().signum() <= 0 : leg.quantityDelta().signum() >= 0)) {
                continue;
            }
            String assetKey = rawLegAssetKey(leg);
            RawLeg current = aggregated.get(assetKey);
            if (current == null) {
                aggregated.put(assetKey, leg);
                continue;
            }
            aggregated.put(assetKey, new RawLeg(
                    current.assetContract(),
                    current.assetSymbol(),
                    current.quantityDelta().add(leg.quantityDelta()),
                    false
            ));
        }
        return aggregated;
    }

    private Optional<Document> findEulerLoopShareOutboundTransfer(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))) {
                continue;
            }
            String to = view.tokenTransferTo(transfer);
            if (isZeroAddress(to) || isEulerControlledSubaccount(view, view.walletAddress(), to)) {
                return Optional.of(transfer);
            }
        }
        return Optional.empty();
    }

    private Optional<Document> findEulerLoopReturnedStableTransfer(OnChainRawTransactionView view) {
        Document best = null;
        BigDecimal bestQuantity = BigDecimal.ZERO;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferTo(transfer))
                    || !isEulerStableLikeTransfer(view, transfer)
                    || isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (quantity.compareTo(bestQuantity) > 0) {
                best = transfer;
                bestQuantity = quantity;
            }
        }
        return Optional.ofNullable(best);
    }

    private BigDecimal resolveEulerStableLikeUnitPrice(Document transfer) {
        return transfer != null ? BigDecimal.ONE : null;
    }

    private boolean isEulerStableLikeTransfer(OnChainRawTransactionView view, Document transfer) {
        if (transfer == null) {
            return false;
        }
        String contract = view.tokenTransferContract(transfer);
        if (contract != null && matchesExactAssetMarker(contract, "stableContracts", EULER_DEUSD_CONTRACT)) {
            return true;
        }
        String symbol = view.tokenTransferSymbol(transfer);
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return matchesExactAssetMarker(normalized, "stableSymbols", "USDC", "USDT", "USDT0", "USD₮0", "DEUSD");
    }

    private boolean isEulerDebtLikeMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && isDebtLikeSymbol(leg.assetSymbol());
    }

    private boolean isEulerNonShareEconomicMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && !isDebtLikeSymbol(leg.assetSymbol())
                && !isShareLikeSymbol(leg.assetSymbol());
    }

    private boolean sameAsset(RawLeg left, RawLeg right) {
        if (left == null || right == null) {
            return false;
        }
        String leftContract = normalizeContract(left.assetContract());
        String rightContract = normalizeContract(right.assetContract());
        if (leftContract != null || rightContract != null) {
            return leftContract != null && leftContract.equals(rightContract);
        }
        return normalizeSymbol(left.assetSymbol()).equals(normalizeSymbol(right.assetSymbol()));
    }

    private String rawLegAssetKey(RawLeg leg) {
        String contract = normalizeContract(leg.assetContract());
        return contract != null ? contract : "SYMBOL:" + normalizeSymbol(leg.assetSymbol());
    }

    private String normalizeContract(String contract) {
        return contract == null || contract.isBlank() ? null : contract.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.assetContract() != null && leg.quantityDelta() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasNonShareMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.assetContract() != null && leg.quantityDelta() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && !isShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean isShareLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        return matchesPrefixAssetMarker(normalized, "shareSymbolPrefixes", "a", "c", "s", "e", "gt", "syrup");
    }

    private boolean isDebtLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        return matchesFragmentAssetMarker(assetSymbol.trim().toLowerCase(Locale.ROOT), "debtSymbolFragments", "debt");
    }

    private boolean hasDebtLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.assetContract() != null && leg.quantityDelta() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasNonDebtShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.assetContract() != null && leg.quantityDelta() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbol(leg.assetSymbol())
                        && !isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasMintedFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 2)))
                    && isZeroAddress(topicAddress(topicAt(log, 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBurnedFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 1)))
                    && isZeroAddress(topicAddress(topicAt(log, 2)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyInboundFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyOutboundFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, from) && !isZeroAddress(to)) {
                return true;
            }
        }
        return false;
    }

    private boolean isErc20TransferLog(Document log) {
        if (log == null) {
            return false;
        }
        List<String> topics = normalizedTopics(log);
        return topics.size() >= 3 && ERC20_TRANSFER_TOPIC.equals(topics.getFirst());
    }

    private List<String> normalizedTopics(Document log) {
        if (log == null) {
            return List.of();
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics) || topics.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(topics.size());
        for (Object topic : topics) {
            String value = topic == null ? null : topic.toString();
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String topicAt(Document log, int index) {
        List<String> topics = normalizedTopics(log);
        if (index < 0 || index >= topics.size()) {
            return null;
        }
        return topics.get(index);
    }

    private String logData(Document log) {
        if (log == null) {
            return null;
        }
        Object data = log.get("data");
        return data == null ? null : data.toString();
    }

    private String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private boolean isZeroAddress(String address) {
        return "0x0000000000000000000000000000000000000000".equals(address);
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String address) {
        return safeEquals(
                OnChainRawTransactionView.normalizeAddress(view.walletAddress()),
                OnChainRawTransactionView.normalizeAddress(address)
        );
    }

    private boolean matchesWalletAccount(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        if (wallet == null || normalizedAddress == null) {
            return false;
        }
        if (wallet.equals(normalizedAddress)) {
            return true;
        }
        return isEulerControlledSubaccount(view, wallet, normalizedAddress);
    }

    private boolean isEulerControlledSubaccount(OnChainRawTransactionView view, String wallet, String candidate) {
        if (!NetworkId.AVALANCHE.equals(view.networkId()) && !NetworkId.ETHEREUM.equals(view.networkId())) {
            // Preserve legacy behavior by still allowing the method-id contract, not a chain allow-list.
        }
        if (!"0xc16ae7a4".equals(view.methodId())) {
            return false;
        }
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return false;
        }
        return wallet.length() == 42
                && candidate.length() == 42
                && wallet.substring(0, 40).equals(candidate.substring(0, 40));
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private boolean matchesMethodSelector(
            String selector,
            String group,
            String... fallbackSelectors
    ) {
        if (selector == null) {
            return false;
        }
        if (resource != null && resource.matchesMethodSelector(group, selector)) {
            return true;
        }
        for (String fallbackSelector : fallbackSelectors) {
            if (fallbackSelector != null && fallbackSelector.equalsIgnoreCase(selector)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesFunctionMarker(
            String functionName,
            String group,
            String... fallbackMarkers
    ) {
        if (functionName == null) {
            return false;
        }
        if (resource != null && resource.matchesFunctionMarker(group, functionName)) {
            return true;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        for (String fallbackMarker : fallbackMarkers) {
            if (fallbackMarker != null && normalized.contains(fallbackMarker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesEventTopic(
            String topic,
            String group,
            String... fallbackTopics
    ) {
        if (topic == null) {
            return false;
        }
        if (resource != null && resource.matchesEventTopic(group, topic)) {
            return true;
        }
        for (String fallbackTopic : fallbackTopics) {
            if (fallbackTopic != null && fallbackTopic.equalsIgnoreCase(topic)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMethodSelector(
            String data,
            String group,
            String... fallbackSelectors
    ) {
        if (data == null) {
            return false;
        }
        List<String> selectors = resource != null ? resource.methodSelectors(group) : List.of();
        if (!selectors.isEmpty()) {
            for (String selector : selectors) {
                String normalizedSelector = selector == null ? null : selector.replace("0x", "").toLowerCase(Locale.ROOT);
                if (normalizedSelector != null && data.toLowerCase(Locale.ROOT).contains(normalizedSelector)) {
                    return true;
                }
            }
        }
        for (String fallbackSelector : fallbackSelectors) {
            String normalizedSelector = fallbackSelector == null ? null : fallbackSelector.replace("0x", "").toLowerCase(Locale.ROOT);
            if (normalizedSelector != null && data.toLowerCase(Locale.ROOT).contains(normalizedSelector)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExactAssetMarker(
            String value,
            String group,
            String... fallbackMarkers
    ) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (resource != null) {
            for (String configured : resource.assetMarkers(group)) {
                if (configured != null && normalized.equals(configured.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        for (String fallbackMarker : fallbackMarkers) {
            if (fallbackMarker != null && normalized.equals(fallbackMarker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrefixAssetMarker(
            String value,
            String group,
            String... fallbackMarkers
    ) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (resource != null) {
            for (String configured : resource.assetMarkers(group)) {
                if (configured != null && normalized.startsWith(configured.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        for (String fallbackMarker : fallbackMarkers) {
            if (fallbackMarker != null && normalized.startsWith(fallbackMarker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesFragmentAssetMarker(
            String value,
            String group,
            String... fallbackMarkers
    ) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (resource != null) {
            for (String configured : resource.assetMarkers(group)) {
                if (configured != null && normalized.contains(configured.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        for (String fallbackMarker : fallbackMarkers) {
            if (fallbackMarker != null && normalized.contains(fallbackMarker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private record EulerLoopRebalancePattern(
            RawLeg sourceShare,
            RawLeg replacementShare,
            RawLeg sourceRefund
    ) {
    }
}
