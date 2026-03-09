package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.classifier.lp.LpDecisionEngine;
import com.walletradar.ingestion.classifier.lp.LpEvidenceExtractor;
import com.walletradar.ingestion.classifier.lp.LpFlowAssembler;
import com.walletradar.ingestion.classifier.lp.LpProtocolRegistry;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic LP entry/exit classifier.
 * Strict mode:
 * - LP_ENTRY: wallet sends non-LP assets and receives LP token (mint/context/protocol whitelist).
 * - LP_EXIT: wallet sends LP token (burn/context/protocol whitelist) and receives non-LP assets.
 * Excludes obvious vault/lending flows handled by {@link LendClassifier}.
 */
@Component
@Order(95)
public class LpClassifier implements TxClassifier {

    private static final String TRANSFER_TOPIC = TransferClassifier.TRANSFER_TOPIC;
    private static final int ONE_NFT = 1;

    private static final Set<String> LP_SYMBOL_HINTS = Set.of(
            "lp", "uni-v2", "slp", "bpt", "g-uni", "cake-lp", "clp"
    );

    private static final Set<String> LP_NAME_HINTS = Set.of(
            "lp token",
            "liquidity pool",
            "pool token",
            "balancer pool token",
            "curve lp",
            "uniswap v2",
            "sushiswap lp",
            "pancake lp",
            "camelot lp",
            "trader joe lp"
    );

    private static final Set<String> NON_LP_RECEIPT_HINTS = Set.of(
            "vault", "share", "receipt", "staked", "staking", "aave", "morpho", "gauntlet", "evk"
    );

    private static final Set<String> LP_ENTRY_FUNCTION_HINTS = Set.of(
            "addliquidity", "joinpool", "mint", "increaseliquidity"
    );

    private static final Set<String> LP_ENTRY_NO_MINT_FUNCTION_HINTS = Set.of(
            "addliquidity", "joinpool", "increaseliquidity"
    );

    private static final Set<String> LP_ENTRY_NO_MINT_SELECTOR_HINTS = Set.of(
            "0xa3c7271a" // LFJ LB router addLiquidity((...))
    );

    private static final Set<String> LP_EXIT_FUNCTION_HINTS = Set.of(
            "removeliquidity", "exitpool", "burn", "decreaseliquidity"
    );

    private static final Set<String> LP_EXIT_NO_BURN_FUNCTION_HINTS = Set.of(
            "removeliquidity", "exitpool", "decreaseliquidity", "zapout"
    );

    private static final Set<String> LP_EXIT_NO_BURN_SELECTOR_HINTS = Set.of(
            "0xc22159b6", // LFJ LB router removeLiquidity(...)
            "0x8b284b0e"  // zapOutV3SingleToken(...)
    );

    private static final Set<String> LP_POSITION_ENTRY_SELECTOR_HINTS = Set.of(
            "0x88316456", // mint((...))
            "0x219f5d17", // increaseLiquidity((...))
            "0x42842e0e"  // safeTransferFrom(address,address,uint256)
    );

    private static final Set<String> LP_POSITION_EXIT_SELECTOR_HINTS = Set.of(
            "0x0c49ccbe", // decreaseLiquidity((...))
            "0x42966c68", // burn(uint256)
            "0x00f714ce"  // strategy-specific withdraw+unstake
    );

    private static final Set<String> LP_FEE_CLAIM_SELECTOR_HINTS = Set.of(
            "0xfc6f7865", // collect((...))
            "0x18fccc76"  // strategy-specific harvest
    );

    private static final Set<String> LP_FEE_CLAIM_FUNCTION_HINTS = Set.of(
            "collect", "harvest", "claim"
    );

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    private final LendClassifier lendClassifier;
    private final LpProtocolRegistry lpProtocolRegistry;
    private final LpEvidenceExtractor lpEvidenceExtractor;
    private final LpDecisionEngine lpDecisionEngine;
    private final LpFlowAssembler lpFlowAssembler;

    public LpClassifier(
            ProtocolRegistry protocolRegistry,
            EvmTokenDecimalsResolver evmTokenDecimalsResolver,
            LendClassifier lendClassifier
    ) {
        this.protocolRegistry = protocolRegistry;
        this.evmTokenDecimalsResolver = evmTokenDecimalsResolver;
        this.lendClassifier = lendClassifier;
        this.lpProtocolRegistry = new LpProtocolRegistry();
        this.lpEvidenceExtractor = new LpEvidenceExtractor(this.lpProtocolRegistry, evmTokenDecimalsResolver);
        this.lpDecisionEngine = new LpDecisionEngine(this.lpProtocolRegistry, this.lpEvidenceExtractor);
        this.lpFlowAssembler = new LpFlowAssembler(protocolRegistry, evmTokenDecimalsResolver, this.lpProtocolRegistry, this.lpEvidenceExtractor);
    }

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || !tx.hasRawData() || walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        if (isFailedTx(tx)) {
            return List.of();
        }
        List<Document> logs = tx.logs();
        if (logs.isEmpty()) {
            if (lendClassifier.hasKnownLendSelector(tx)) {
                return List.of();
            }
            return classifyLpPositionFromCalldataWithoutLogs(tx, walletAddress);
        }

        // Prevent overlap with lend flows (vault, borrow/repay, one-leg allowlist, native gateway).
        if (lendClassifier.isLikelyLendPattern(tx, walletAddress, logs) || lendClassifier.hasKnownLendSelector(tx)) {
            return List.of();
        }

        if (lpDecisionEngine.isLikelyLpExitFromPositionContext(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> exitFromPosition = classifyLpExitFromPositionContext(tx, walletAddress, logs);
            if (!exitFromPosition.isEmpty()) {
                return exitFromPosition;
            }
        }
        if (lpDecisionEngine.isLikelyLpEntryFromPositionContext(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> entryFromPosition = classifyLpEntryFromPositionContext(tx, walletAddress, logs);
            if (!entryFromPosition.isEmpty()) {
                return entryFromPosition;
            }
        }
        if (lpDecisionEngine.isLikelyLpFeeClaimPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> feeClaim = classifyLpFeeClaim(tx, walletAddress, logs);
            if (!feeClaim.isEmpty()) {
                return feeClaim;
            }
        }
        if (lpDecisionEngine.isLikelyLpPositionPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> positionEvents = classifyLpPositionNft(tx, walletAddress, logs);
            if (!positionEvents.isEmpty()) {
                return positionEvents;
            }
        }

        if (lpDecisionEngine.isLikelyLpEntryPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> entry = classifyLpEntry(tx, walletAddress, logs);
            if (!entry.isEmpty()) {
                return entry;
            }
        }
        if (lpDecisionEngine.isLikelyLpEntryWithoutMintPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> entryWithoutMint = classifyLpEntryWithoutMintEvidence(tx, walletAddress, logs);
            if (!entryWithoutMint.isEmpty()) {
                return entryWithoutMint;
            }
        }
        if (lpDecisionEngine.isLikelyLpExitPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> exit = classifyLpExit(tx, walletAddress, logs);
            if (!exit.isEmpty()) {
                return exit;
            }
        }
        if (lpDecisionEngine.isLikelyLpExitWithoutBurnPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> exitWithoutBurn = classifyLpExitWithoutBurnEvidence(tx, walletAddress, logs);
            if (!exitWithoutBurn.isEmpty()) {
                return exitWithoutBurn;
            }
        }
        return List.of();
    }

    private List<RawClassifiedEvent> classifyLpPositionFromCalldataWithoutLogs(
            RawTransactionNormalizationView tx,
            String walletAddress
    ) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        String sender = tx.readRawOrExplorerAddress("from");
        String to = tx.readRawOrExplorerAddress("to");
        if (wallet == null || sender == null || !wallet.equals(sender) || to == null) {
            return List.of();
        }
        if (!lpProtocolRegistry.isKnownPositionManager(to)) {
            return List.of();
        }
        if (!tx.hasSelector("0x42842e0e")) {
            return List.of();
        }
        RawTransactionNormalizationView.SafeTransferFromCall call = tx.decodeSafeTransferFrom();
        if (call == null || call.tokenId() == null || call.tokenId().isBlank()) {
            return List.of();
        }

        EconomicEventType eventType;
        BigDecimal quantity;
        if (wallet.equals(call.from()) && !wallet.equals(call.to())) {
            eventType = EconomicEventType.LP_POSITION_STAKE;
            quantity = BigDecimal.valueOf(-ONE_NFT);
        } else if (wallet.equals(call.to()) && !wallet.equals(call.from())) {
            eventType = EconomicEventType.LP_POSITION_UNSTAKE;
            quantity = BigDecimal.valueOf(ONE_NFT);
        } else {
            return List.of();
        }

        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(to).orElse(null));
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(eventType);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(to);
        event.setAssetSymbol(resolveSymbol(tx, to, null));
        event.setQuantityDelta(quantity);
        event.setProtocolName(resolveProtocolName(to, txProtocol, to));
        event.setPositionId(call.tokenId());
        return List.of(event);
    }

    private List<RawClassifiedEvent> classifyLpPositionNft(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String nftContract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(nftContract)) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            EconomicEventType eventType = null;
            BigDecimal quantityDelta = null;

            if (walletTopic.equalsIgnoreCase(toTopic)) {
                if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                    eventType = EconomicEventType.LP_POSITION_ENTRY;
                    quantityDelta = BigDecimal.valueOf(ONE_NFT);
                } else {
                    eventType = EconomicEventType.LP_POSITION_UNSTAKE;
                    quantityDelta = BigDecimal.valueOf(ONE_NFT);
                }
            } else if (walletTopic.equalsIgnoreCase(fromTopic)) {
                if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                    eventType = EconomicEventType.LP_POSITION_EXIT;
                    quantityDelta = BigDecimal.valueOf(-ONE_NFT);
                } else {
                    eventType = EconomicEventType.LP_POSITION_STAKE;
                    quantityDelta = BigDecimal.valueOf(-ONE_NFT);
                }
            }

            if (eventType == null || quantityDelta == null) {
                continue;
            }
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(eventType);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(nftContract);
            event.setAssetSymbol(resolveSymbol(tx, nftContract, null));
            event.setQuantityDelta(quantityDelta);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, nftContract));
            event.setLogIndex(tx.getLogIndex(log));
            event.setPositionId(parsePositionIdFromTopic(topics.get(3)));
            out.add(event);
        }
        return out;
    }

    private List<RawClassifiedEvent> classifyLpFeeClaim(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        return lpFlowAssembler.assembleFeeClaim(tx, walletAddress, logs);
    }

    private List<RawClassifiedEvent> classifyLpExitFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String positionId = lpEvidenceExtractor.resolvePositionId(tx, logs);
        EconomicEventType exitType = lpEvidenceExtractor.isFinalLpExitByBurn(tx, walletAddress, logs, positionId)
                ? EconomicEventType.LP_EXIT_FINAL
                : EconomicEventType.LP_EXIT_PARTIAL;
        return lpFlowAssembler.assemblePositionExit(tx, walletAddress, logs, exitType);
    }

    private List<RawClassifiedEvent> classifyLpEntryFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        return lpFlowAssembler.assemblePositionEntry(tx, walletAddress, logs);
    }

    private List<RawClassifiedEvent> classifyLpEntryWithoutMintEvidence(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        if (summary.outflows().size() < 2) {
            return List.of();
        }
        if (!summary.inflows().isEmpty() && !summary.outflows().keySet().containsAll(summary.inflows().keySet())) {
            return List.of();
        }

        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = lpEvidenceExtractor.resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (String contract : summary.outflows().keySet()) {
            BigDecimal outboundQty = summary.outflows().get(contract);
            if (outboundQty == null || outboundQty.signum() >= 0) {
                continue;
            }
            BigDecimal inboundQty = summary.inflows().getOrDefault(contract, BigDecimal.ZERO);
            BigDecimal netQty = outboundQty.add(inboundQty);
            if (netQty.signum() >= 0) {
                continue;
            }
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(EconomicEventType.LP_ENTRY);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(contract);
            event.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract().get(contract)));
            event.setQuantityDelta(netQty);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            event.setLogIndex(summary.outflowLogIndex().get(contract));
            event.setPositionId(positionId);
            out.add(event);
        }
        return out;
    }

    private List<RawClassifiedEvent> classifyLpEntry(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        if (summary.outflows().isEmpty() || summary.inflows().isEmpty()) {
            return List.of();
        }

        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        Set<String> lpIncoming = new LinkedHashSet<>();
        for (String contract : summary.inflows().keySet()) {
            if (summary.outflows().containsKey(contract)) {
                continue;
            }
            if (isLpLikeToken(tx, contract, summary.metaByContract().get(contract), txProtocol)) {
                lpIncoming.add(contract);
            }
        }
        if (lpIncoming.size() != 1) {
            return List.of();
        }
        String lpContract = lpIncoming.iterator().next();
        if (!summary.mintToWallet().contains(lpContract) && !lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to")) && !isLpEntryContext(tx)) {
            return List.of();
        }

        Set<String> nonLpOutflows = new LinkedHashSet<>(summary.outflows().keySet());
        nonLpOutflows.remove(lpContract);
        if (nonLpOutflows.isEmpty()) {
            return List.of();
        }

        // Strict: for LP entry, inbound wallet transfers should be LP receipt only.
        if (summary.inflows().size() != 1) {
            return List.of();
        }

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (String contract : nonLpOutflows) {
            BigDecimal qty = summary.outflows().get(contract);
            if (qty == null || qty.signum() >= 0) {
                continue;
            }
            RawClassifiedEvent leg = new RawClassifiedEvent();
            leg.setEventType(EconomicEventType.LP_ENTRY);
            leg.setWalletAddress(walletAddress);
            leg.setAssetContract(contract);
            leg.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract().get(contract)));
            leg.setQuantityDelta(qty);
            leg.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            leg.setLogIndex(summary.outflowLogIndex().get(contract));
            out.add(leg);
        }

        BigDecimal lpQty = summary.inflows().get(lpContract);
        if (lpQty == null || lpQty.signum() <= 0) {
            return List.of();
        }
        RawClassifiedEvent lpLeg = new RawClassifiedEvent();
        lpLeg.setEventType(EconomicEventType.LP_ENTRY);
        lpLeg.setWalletAddress(walletAddress);
        lpLeg.setAssetContract(lpContract);
        lpLeg.setAssetSymbol(resolveSymbol(tx, lpContract, summary.metaByContract().get(lpContract)));
        lpLeg.setQuantityDelta(lpQty);
        lpLeg.setProtocolName(resolveProtocolName(txTo, txProtocol, lpContract));
        lpLeg.setLogIndex(summary.inflowLogIndex().get(lpContract));
        out.add(lpLeg);
        return out;
    }

    private List<RawClassifiedEvent> classifyLpExit(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        if (summary.outflows().isEmpty() || summary.inflows().isEmpty()) {
            return List.of();
        }

        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        Set<String> lpOutgoing = new LinkedHashSet<>();
        for (String contract : summary.outflows().keySet()) {
            if (summary.inflows().containsKey(contract)) {
                continue;
            }
            if (isLpLikeToken(tx, contract, summary.metaByContract().get(contract), txProtocol)) {
                lpOutgoing.add(contract);
            }
        }
        if (lpOutgoing.size() != 1) {
            return List.of();
        }
        String lpContract = lpOutgoing.iterator().next();
        if (!summary.burnFromWallet().contains(lpContract) && !lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to")) && !isLpExitContext(tx)) {
            return List.of();
        }

        Set<String> nonLpIncoming = new LinkedHashSet<>(summary.inflows().keySet());
        nonLpIncoming.remove(lpContract);
        if (nonLpIncoming.isEmpty()) {
            return List.of();
        }

        // Strict: for LP exit, wallet outbound transfers should be LP token only.
        if (summary.outflows().size() != 1) {
            return List.of();
        }

        List<RawClassifiedEvent> out = new ArrayList<>();
        BigDecimal lpQty = summary.outflows().get(lpContract);
        if (lpQty == null || lpQty.signum() >= 0) {
            return List.of();
        }
        RawClassifiedEvent lpLeg = new RawClassifiedEvent();
        lpLeg.setEventType(EconomicEventType.LP_EXIT);
        lpLeg.setWalletAddress(walletAddress);
        lpLeg.setAssetContract(lpContract);
        lpLeg.setAssetSymbol(resolveSymbol(tx, lpContract, summary.metaByContract().get(lpContract)));
        lpLeg.setQuantityDelta(lpQty);
        lpLeg.setProtocolName(resolveProtocolName(txTo, txProtocol, lpContract));
        lpLeg.setLogIndex(summary.outflowLogIndex().get(lpContract));
        out.add(lpLeg);

        for (String contract : nonLpIncoming) {
            BigDecimal qty = summary.inflows().get(contract);
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            RawClassifiedEvent leg = new RawClassifiedEvent();
            leg.setEventType(EconomicEventType.LP_EXIT);
            leg.setWalletAddress(walletAddress);
            leg.setAssetContract(contract);
            leg.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract().get(contract)));
            leg.setQuantityDelta(qty);
            leg.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            leg.setLogIndex(summary.inflowLogIndex().get(contract));
            out.add(leg);
        }
        return out;
    }

    private List<RawClassifiedEvent> classifyLpExitWithoutBurnEvidence(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        LpEvidenceExtractor.FlowSummary summary = lpEvidenceExtractor.collectFlowSummary(tx, walletAddress, logs);
        boolean zapOutContext = isZapOutNoBurnContext(tx);
        if (summary.inflows().isEmpty() || (!zapOutContext && !summary.outflows().isEmpty())) {
            return List.of();
        }
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        if (zapOutContext) {
            Map<String, BigDecimal> netByContract = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : summary.outflows().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
            }
            for (Map.Entry<String, BigDecimal> e : summary.inflows().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
            }

            List<RawClassifiedEvent> out = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : netByContract.entrySet()) {
                String contract = e.getKey();
                BigDecimal net = e.getValue();
                if (net == null || net.signum() == 0) {
                    continue;
                }
                // Keep this path conservative: if any asset is net negative, do not force LP exit.
                if (net.signum() < 0) {
                    return List.of();
                }
                RawClassifiedEvent event = new RawClassifiedEvent();
                event.setEventType(EconomicEventType.LP_EXIT_PARTIAL);
                event.setWalletAddress(walletAddress);
                event.setAssetContract(contract);
                event.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract().get(contract)));
                event.setQuantityDelta(net);
                event.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
                event.setLogIndex(summary.inflowLogIndex().get(contract));
                out.add(event);
            }
            return out;
        }

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (String contract : summary.inflows().keySet()) {
            BigDecimal qty = summary.inflows().get(contract);
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(EconomicEventType.LP_EXIT);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(contract);
            event.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract().get(contract)));
            event.setQuantityDelta(qty);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            event.setLogIndex(summary.inflowLogIndex().get(contract));
            out.add(event);
        }
        return out;
    }

    private static boolean isZapOutNoBurnContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        return selector != null && selector.equalsIgnoreCase("0x8b284b0e");
    }

    private static boolean isLpPositionEntryContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            if (functionName.contains("mint") || functionName.contains("increaseliquidity")) {
                return true;
            }
            if (functionName.contains("transferfrom") || functionName.contains("safetransferfrom")) {
                return true;
            }
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null) {
            return false;
        }
        return LP_POSITION_ENTRY_SELECTOR_HINTS.stream().anyMatch(selector -> inputContainsSelector(input, selector));
    }

    private static boolean isLpPositionExitContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            if (functionName.contains("decreaseliquidity") || functionName.contains("withdraw")) {
                return true;
            }
            if (functionName.contains("burn") || functionName.contains("unstake")) {
                return true;
            }
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null) {
            return false;
        }
        return LP_POSITION_EXIT_SELECTOR_HINTS.stream().anyMatch(selector -> inputContainsSelector(input, selector));
    }

    private static boolean isLpFeeClaimContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_FEE_CLAIM_FUNCTION_HINTS) {
                if (functionName.contains(hint)) {
                    return true;
                }
            }
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null) {
            return false;
        }
        for (String selector : LP_FEE_CLAIM_SELECTOR_HINTS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        return false;
    }

    private String resolveSymbol(RawTransactionNormalizationView tx, String contract, LpEvidenceExtractor.TokenMeta meta) {
        String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), contract);
        if (symbol != null && !symbol.isBlank()) {
            return symbol;
        }
        if (meta != null && meta.symbol() != null && !meta.symbol().isBlank()) {
            return meta.symbol();
        }
        return "";
    }

    private String resolveProtocolName(String txTo, String txProtocol, String assetContract) {
        String byAsset = normalizeProtocol(protocolRegistry.getProtocolName(assetContract).orElse(null));
        if (byAsset != null) {
            return byAsset;
        }
        if (txProtocol != null) {
            return txProtocol;
        }
        return normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
    }

    private boolean isLpLikeToken(
            RawTransactionNormalizationView tx,
            String contract,
            LpEvidenceExtractor.TokenMeta meta,
            String txProtocol
    ) {
        String resolverSymbol = tx.normalizeTextValue(evmTokenDecimalsResolver.getSymbol(tx.networkId(), contract));
        String symbol = firstNonBlank(meta != null ? meta.symbol() : null, resolverSymbol);
        String name = meta != null ? meta.name() : null;
        String joined = (symbol == null ? "" : symbol) + " " + (name == null ? "" : name);
        String normalized = joined.trim();
        if (!normalized.isBlank()) {
            for (String banned : NON_LP_RECEIPT_HINTS) {
                if (normalized.contains(banned)) {
                    return false;
                }
            }
            for (String hint : LP_SYMBOL_HINTS) {
                if (symbol != null && symbol.contains(hint)) {
                    return true;
                }
            }
            for (String hint : LP_NAME_HINTS) {
                if (normalized.contains(hint)) {
                    return true;
                }
            }
        }

        String byAssetProtocol = normalizeProtocol(protocolRegistry.getProtocolName(contract).orElse(null));
        if (lpProtocolRegistry.matchesKnownLpProtocol(byAssetProtocol)) {
            return true;
        }
        return lpProtocolRegistry.matchesKnownLpProtocol(txProtocol)
                || lpProtocolRegistry.isKnownLpRouter(tx.readRawOrExplorerAddress("to"));
    }

    private static boolean isLpEntryContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null) {
            return false;
        }
        for (String hint : LP_ENTRY_FUNCTION_HINTS) {
            if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLpExitContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null) {
            return false;
        }
        for (String hint : LP_EXIT_FUNCTION_HINTS) {
            if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLpExitWithoutBurnContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null) {
            for (String known : LP_EXIT_NO_BURN_SELECTOR_HINTS) {
                if (known.equalsIgnoreCase(selector)) {
                    return true;
                }
            }
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_EXIT_NO_BURN_FUNCTION_HINTS) {
                if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String knownSelector : LP_EXIT_NO_BURN_SELECTOR_HINTS) {
            if (inputContainsSelector(input, knownSelector)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLpEntryWithoutMintContext(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null) {
            for (String known : LP_ENTRY_NO_MINT_SELECTOR_HINTS) {
                if (known.equalsIgnoreCase(selector)) {
                    return true;
                }
            }
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null) {
            for (String hint : LP_ENTRY_NO_MINT_FUNCTION_HINTS) {
                if (functionName.contains(hint.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String knownSelector : LP_ENTRY_NO_MINT_SELECTOR_HINTS) {
            if (inputContainsSelector(input, knownSelector)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFailedTx(RawTransactionNormalizationView tx) {
        if (tx == null || !tx.hasRawData()) {
            return false;
        }
        String isError = tx.readRawOrExplorerLower("isError");
        if ("1".equals(isError)) {
            return true;
        }
        String receiptStatus = tx.readRawOrExplorerLower("txreceipt_status");
        if ("0".equals(receiptStatus) || "0x0".equals(receiptStatus)) {
            return true;
        }
        String status = tx.readRawOrExplorerLower("status");
        return "0x0".equals(status);
    }

    private static String normalizeProtocol(String protocol) {
        return normalizeText(protocol);
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static boolean inputContainsSelector(String input, String selector) {
        if (input == null || selector == null || selector.isBlank()) {
            return false;
        }
        String normalizedSelector = selector.toLowerCase(Locale.ROOT);
        if (input.contains(normalizedSelector)) {
            return true;
        }
        if (normalizedSelector.startsWith("0x")) {
            return input.contains(normalizedSelector.substring(2));
        }
        return input.contains("0x" + normalizedSelector);
    }

    private static String parsePositionIdFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return parseHexToUnsignedDecimal(topic);
    }

    private static String parseHexToUnsignedDecimal(String hexValue) {
        String hex = strip0x(hexValue);
        if (hex == null || hex.isBlank()) {
            return null;
        }
        if (!hex.matches("[0-9a-f]+")) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(hex, 16);
            return value.signum() > 0 ? value.toString() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strip0x(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("0x")) {
            return value.substring(2);
        }
        return value;
    }
}
