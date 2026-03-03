package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
@RequiredArgsConstructor
public class LpClassifier implements TxClassifier {

    private static final String TRANSFER_TOPIC = TransferClassifier.TRANSFER_TOPIC;
    private static final String ZERO_ADDRESS_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000000";
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

    // Top LP/DEX protocol names whitelist (normalized, contains-match).
    private static final Set<String> KNOWN_LP_PROTOCOL_NAMES = Set.of(
            "uniswap v2",
            "uniswap v3",
            "uniswap",
            "sushiswap",
            "curve",
            "balancer",
            "pancakeswap",
            "camelot",
            "trader joe",
            "velodrome",
            "aerodrome",
            "quickswap",
            "spookyswap",
            "thena",
            "ramses",
            "zyberswap",
            "beethoven x",
            "wombat",
            "platypus",
            "syncswap",
            "maverick",
            "algebra",
            "solidly",
            "ellipsis",
            "apeswap",
            "biswap",
            "fraxswap",
            "dodo"
    );

    // Top LP routers/vaults (normalized addresses).
    private static final Set<String> KNOWN_LP_ROUTER_ADDRESSES = Set.of(
            "0x7a250d5630b4cf539739df2c5dacb4c659f2488d", // Uniswap V2 Router
            "0xe592427a0aece92de3edee1f18e0157c05861564", // Uniswap V3 Router
            "0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45", // Uniswap Universal/V3 Router
            "0xef1c6e67703c7bd7107eed8303fbe6ec2554bf6b", // Uniswap Universal Router (v1)
            "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad", // Uniswap Universal Router (v2)
            "0xd9e1ce17f2641f24ae83637ab66a2cca9c378b9f", // Sushi Router
            "0x1b02da8cb0d097eb8d57a175b88c7d8b47997506", // Sushi Router (v2)
            "0xba12222222228d8ba445958a75a0704d566bf2c8", // Balancer Vault
            "0x10ed43c718714eb63d5aa57b78b54704e256024e", // Pancake V2 Router
            "0x13f4ea83d0bd40e75c8222255bc855a974568dd4", // Pancake Universal Router
            "0xc873fecbd354f5a56e00e710b90ef4201db2448d", // Camelot Router
            "0x60ae616a2155ee3d9a68541ba4544862310933d4"  // Trader Joe Router
    );

    private static final Set<String> LP_ENTRY_FUNCTION_HINTS = Set.of(
            "addliquidity", "joinpool", "mint", "increaseliquidity"
    );

    private static final Set<String> LP_EXIT_FUNCTION_HINTS = Set.of(
            "removeliquidity", "exitpool", "burn", "decreaseliquidity"
    );

    // Pancake/Uniswap/Aerodrome CL position NFTs.
    private static final Set<String> KNOWN_LP_POSITION_NFT_CONTRACTS = Set.of(
            "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364", // Pancake V3 Position Manager (Base)
            "0xc36442b4a4522e871399cd717abdd847ab11fe88", // Uniswap V3 NonfungiblePositionManager
            "0x827922686190790b37229fd06084350e74485b72"  // Aerodrome Slipstream Position NFT
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

    private static final Set<String> LP_POSITION_ID_INPUT_SELECTORS = Set.of(
            "0x00f714ce", // strategy-specific withdraw+unstake(tokenId,...)
            "0x18fccc76", // strategy-specific harvest(tokenId)
            "0x219f5d17", // increaseLiquidity((tokenId,...))
            "0x0c49ccbe", // decreaseLiquidity((tokenId,...))
            "0xfc6f7865", // collect((tokenId,...))
            "0x42842e0e"  // safeTransferFrom(address,address,tokenId)
    );

    private static final Set<String> LP_FEE_CLAIM_FUNCTION_HINTS = Set.of(
            "collect", "harvest", "claim"
    );

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;

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
            return classifyLpPositionFromCalldataWithoutLogs(tx, walletAddress);
        }

        // Prevent overlap with receipt-token vault/lend flows.
        if (LendClassifier.isLikelyVaultDepositPattern(tx, walletAddress, logs)
                || LendClassifier.isLikelyVaultWithdrawalPattern(tx, walletAddress, logs)) {
            return List.of();
        }

        if (isLikelyLpExitFromPositionContext(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> exitFromPosition = classifyLpExitFromPositionContext(tx, walletAddress, logs);
            if (!exitFromPosition.isEmpty()) {
                return exitFromPosition;
            }
        }
        if (isLikelyLpEntryFromPositionContext(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> entryFromPosition = classifyLpEntryFromPositionContext(tx, walletAddress, logs);
            if (!entryFromPosition.isEmpty()) {
                return entryFromPosition;
            }
        }
        if (isLikelyLpFeeClaimPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> feeClaim = classifyLpFeeClaim(tx, walletAddress, logs);
            if (!feeClaim.isEmpty()) {
                return feeClaim;
            }
        }
        if (isLikelyLpPositionPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> positionEvents = classifyLpPositionNft(tx, walletAddress, logs);
            if (!positionEvents.isEmpty()) {
                return positionEvents;
            }
        }

        if (isLikelyLpEntryPattern(tx, walletAddress, logs)) {
            List<RawClassifiedEvent> entry = classifyLpEntry(tx, walletAddress, logs);
            if (!entry.isEmpty()) {
                return entry;
            }
        }
        if (isLikelyLpExitPattern(tx, walletAddress, logs)) {
            return classifyLpExit(tx, walletAddress, logs);
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
        if (!KNOWN_LP_POSITION_NFT_CONTRACTS.contains(to)) {
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

    static boolean isLikelyLpEntryPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isLpEntryContext(tx) && !isKnownLpRouterCall(tx)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Set<String> outflowContracts = new LinkedHashSet<>();
        Set<String> mintToWalletContracts = new LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outflowContracts.add(tokenAddress);
            } else if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                mintToWalletContracts.add(tokenAddress);
            }
        }
        return !outflowContracts.isEmpty() && !mintToWalletContracts.isEmpty();
    }

    static boolean isLikelyLpExitPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isLpExitContext(tx) && !isKnownLpRouterCall(tx)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Set<String> burnFromWalletContracts = new LinkedHashSet<>();
        Set<String> inflowContracts = new LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic) && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                burnFromWalletContracts.add(tokenAddress);
            } else if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                inflowContracts.add(tokenAddress);
            }
        }
        return !burnFromWalletContracts.isEmpty() && !inflowContracts.isEmpty();
    }

    static boolean isLikelyLpPositionPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String nftContract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (nftContract == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(nftContract)) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            String walletTopic = tx.padAddressForTopic(walletAddress);
            if (walletTopic.equalsIgnoreCase(fromTopic) || walletTopic.equalsIgnoreCase(toTopic)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelyLpFeeClaimPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        // Exit context has higher priority than fee-only classification.
        if (isLpPositionExitContext(tx) || isLpExitContext(tx)) {
            return false;
        }
        if (!isLpFeeClaimContext(tx)) {
            return false;
        }
        if (isGenericClaimFunctionContext(tx) && !hasLpFeeClaimEvidence(tx, logs)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        boolean hasInboundErc20 = false;
        boolean hasOutboundErc20 = false;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (topics.size() >= 4) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(toTopic) && !walletTopic.equalsIgnoreCase(fromTopic)) {
                hasInboundErc20 = true;
            }
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                hasOutboundErc20 = true;
            }
        }
        return hasInboundErc20 && !hasOutboundErc20;
    }

    private static boolean isGenericClaimFunctionContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        return functionName.contains("claim")
                && !functionName.contains("collect")
                && !functionName.contains("harvest");
    }

    private static boolean hasLpFeeClaimEvidence(RawTransactionNormalizationView tx, List<Document> logs) {
        String txTo = tx.readRawOrExplorerAddress("to");
        if (txTo != null && (KNOWN_LP_POSITION_NFT_CONTRACTS.contains(txTo) || KNOWN_LP_ROUTER_ADDRESSES.contains(txTo))) {
            return true;
        }
        if (resolvePositionId(tx, logs) != null) {
            return true;
        }
        if (hasLpPositionNftTransferLog(tx, logs)) {
            return true;
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String selector : LP_FEE_CLAIM_SELECTOR_HINTS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        for (String selector : LP_POSITION_ID_INPUT_SELECTORS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLpPositionNftTransferLog(RawTransactionNormalizationView tx, List<Document> logs) {
        if (logs == null || logs.isEmpty()) {
            return false;
        }
        for (Document log : logs) {
            String logAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (logAddress == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(logAddress)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() >= 4 && TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelyLpExitFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isLpPositionExitContext(tx)) {
            return false;
        }

        String walletTopic = tx.padAddressForTopic(walletAddress);
        String managerAddress = tx.readRawOrExplorerAddress("to");
        String managerTopic = managerAddress != null ? tx.padAddressForTopic(managerAddress) : null;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            // ERC20 transfer only (3 topics): token inflow to wallet.
            if (topics.size() >= 4) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(toTopic)
                    && !walletTopic.equalsIgnoreCase(fromTopic)
                    && !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                return true;
            }
        }
        // Some exits (notably V3-style decreaseLiquidity+collect paths) can emit ERC20 transfer
        // to the position manager first, while wallet receipt happens via native unwrap/sweep.
        // Accept this as LP exit evidence when call context is position-exit and manager is known.
        if (managerAddress == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(managerAddress) || managerTopic == null) {
            return false;
        }
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (!isManagerInboundErc20Transfer(topics, managerTopic)) {
                continue;
            }
            String fromTopic = topics.get(1);
            if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && !walletTopic.equalsIgnoreCase(fromTopic)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelyLpEntryFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        if (isFailedTx(tx)) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isLpPositionEntryContext(tx)) {
            return false;
        }

        String walletTopic = tx.padAddressForTopic(walletAddress);
        boolean hasMintedPositionNftToWallet = false;
        boolean hasWalletOutboundErc20 = false;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (topics.size() >= 4) {
                if (address != null
                        && KNOWN_LP_POSITION_NFT_CONTRACTS.contains(address)
                        && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))
                        && walletTopic.equalsIgnoreCase(topics.get(2))) {
                    hasMintedPositionNftToWallet = true;
                }
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(1))
                    && !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                hasWalletOutboundErc20 = true;
            }
        }
        return hasMintedPositionNftToWallet && hasWalletOutboundErc20;
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
            if (nftContract == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(nftContract)) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            EconomicEventType eventType = null;
            BigDecimal quantityDelta = null;

            if (walletTopic.equalsIgnoreCase(toTopic)) {
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                    eventType = EconomicEventType.LP_POSITION_ENTRY;
                    quantityDelta = BigDecimal.valueOf(ONE_NFT);
                } else {
                    eventType = EconomicEventType.LP_POSITION_UNSTAKE;
                    quantityDelta = BigDecimal.valueOf(ONE_NFT);
                }
            } else if (walletTopic.equalsIgnoreCase(fromTopic)) {
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
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
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0)) || topics.size() >= 4) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (!walletTopic.equalsIgnoreCase(toTopic) || walletTopic.equalsIgnoreCase(fromTopic)) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(EconomicEventType.LP_FEE_CLAIM);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(tokenAddress);
            event.setAssetSymbol(resolveSymbol(tx, tokenAddress, null));
            event.setQuantityDelta(quantity);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, tokenAddress));
            event.setLogIndex(tx.getLogIndex(log));
            event.setPositionId(positionId);
            out.add(event);
        }
        return out;
    }

    private List<RawClassifiedEvent> classifyLpExitFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = resolvePositionId(tx, logs);
        EconomicEventType exitType = isFinalLpExitByBurn(tx, walletAddress, logs, positionId)
                ? EconomicEventType.LP_EXIT_FINAL
                : EconomicEventType.LP_EXIT_PARTIAL;

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0)) || topics.size() >= 4) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (!walletTopic.equalsIgnoreCase(toTopic) || walletTopic.equalsIgnoreCase(fromTopic)) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(exitType);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(tokenAddress);
            event.setAssetSymbol(resolveSymbol(tx, tokenAddress, null));
            event.setQuantityDelta(quantity);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, tokenAddress));
            event.setLogIndex(tx.getLogIndex(log));
            event.setPositionId(positionId);
            out.add(event);
        }
        if (!out.isEmpty()) {
            return out;
        }

        // Fallback: exit-like flows may land on manager first (pool -> manager),
        // while wallet receives native sweep via internal transfer not always available.
        String managerTopic = txTo != null ? tx.padAddressForTopic(txTo) : null;
        if (txTo == null || managerTopic == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(txTo)) {
            return out;
        }
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (!isManagerInboundErc20Transfer(topics, managerTopic)) {
                continue;
            }
            String fromTopic = topics.get(1);
            if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) || walletTopic.equalsIgnoreCase(fromTopic)) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(exitType);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(tokenAddress);
            event.setAssetSymbol(resolveSymbol(tx, tokenAddress, null));
            event.setQuantityDelta(quantity);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, tokenAddress));
            event.setLogIndex(tx.getLogIndex(log));
            event.setPositionId(positionId);
            out.add(event);
        }
        return out;
    }

    private static boolean isManagerInboundErc20Transfer(List<String> topics, String managerTopic) {
        if (topics == null || topics.size() < 3 || topics.size() >= 4) {
            return false;
        }
        if (!TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
            return false;
        }
        return managerTopic.equalsIgnoreCase(topics.get(2));
    }

    private List<RawClassifiedEvent> classifyLpEntryFromPositionContext(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0)) || topics.size() >= 4) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (!walletTopic.equalsIgnoreCase(fromTopic) || ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP).negate();
            RawClassifiedEvent event = new RawClassifiedEvent();
            event.setEventType(EconomicEventType.LP_ENTRY);
            event.setWalletAddress(walletAddress);
            event.setAssetContract(tokenAddress);
            event.setAssetSymbol(resolveSymbol(tx, tokenAddress, null));
            event.setQuantityDelta(quantity);
            event.setProtocolName(resolveProtocolName(txTo, txProtocol, tokenAddress));
            event.setLogIndex(tx.getLogIndex(log));
            event.setPositionId(positionId);
            out.add(event);
        }
        return out;
    }

    private List<RawClassifiedEvent> classifyLpEntry(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        FlowSummary summary = collectFlowSummary(tx, walletAddress, logs);
        if (summary.outflows.isEmpty() || summary.inflows.isEmpty()) {
            return List.of();
        }

        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        Set<String> lpIncoming = new LinkedHashSet<>();
        for (String contract : summary.inflows.keySet()) {
            if (summary.outflows.containsKey(contract)) {
                continue;
            }
            if (isLpLikeToken(tx, contract, summary.metaByContract.get(contract), txProtocol)) {
                lpIncoming.add(contract);
            }
        }
        if (lpIncoming.size() != 1) {
            return List.of();
        }
        String lpContract = lpIncoming.iterator().next();
        if (!summary.mintToWallet.contains(lpContract) && !isKnownLpRouterCall(tx) && !isLpEntryContext(tx)) {
            return List.of();
        }

        Set<String> nonLpOutflows = new LinkedHashSet<>(summary.outflows.keySet());
        nonLpOutflows.remove(lpContract);
        if (nonLpOutflows.isEmpty()) {
            return List.of();
        }

        // Strict: for LP entry, inbound wallet transfers should be LP receipt only.
        if (summary.inflows.size() != 1) {
            return List.of();
        }

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (String contract : nonLpOutflows) {
            BigDecimal qty = summary.outflows.get(contract);
            if (qty == null || qty.signum() >= 0) {
                continue;
            }
            RawClassifiedEvent leg = new RawClassifiedEvent();
            leg.setEventType(EconomicEventType.LP_ENTRY);
            leg.setWalletAddress(walletAddress);
            leg.setAssetContract(contract);
            leg.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract.get(contract)));
            leg.setQuantityDelta(qty);
            leg.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            leg.setLogIndex(summary.outflowLogIndex.get(contract));
            out.add(leg);
        }

        BigDecimal lpQty = summary.inflows.get(lpContract);
        if (lpQty == null || lpQty.signum() <= 0) {
            return List.of();
        }
        RawClassifiedEvent lpLeg = new RawClassifiedEvent();
        lpLeg.setEventType(EconomicEventType.LP_ENTRY);
        lpLeg.setWalletAddress(walletAddress);
        lpLeg.setAssetContract(lpContract);
        lpLeg.setAssetSymbol(resolveSymbol(tx, lpContract, summary.metaByContract.get(lpContract)));
        lpLeg.setQuantityDelta(lpQty);
        lpLeg.setProtocolName(resolveProtocolName(txTo, txProtocol, lpContract));
        lpLeg.setLogIndex(summary.inflowLogIndex.get(lpContract));
        out.add(lpLeg);
        return out;
    }

    private List<RawClassifiedEvent> classifyLpExit(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        FlowSummary summary = collectFlowSummary(tx, walletAddress, logs);
        if (summary.outflows.isEmpty() || summary.inflows.isEmpty()) {
            return List.of();
        }

        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));

        Set<String> lpOutgoing = new LinkedHashSet<>();
        for (String contract : summary.outflows.keySet()) {
            if (summary.inflows.containsKey(contract)) {
                continue;
            }
            if (isLpLikeToken(tx, contract, summary.metaByContract.get(contract), txProtocol)) {
                lpOutgoing.add(contract);
            }
        }
        if (lpOutgoing.size() != 1) {
            return List.of();
        }
        String lpContract = lpOutgoing.iterator().next();
        if (!summary.burnFromWallet.contains(lpContract) && !isKnownLpRouterCall(tx) && !isLpExitContext(tx)) {
            return List.of();
        }

        Set<String> nonLpIncoming = new LinkedHashSet<>(summary.inflows.keySet());
        nonLpIncoming.remove(lpContract);
        if (nonLpIncoming.isEmpty()) {
            return List.of();
        }

        // Strict: for LP exit, wallet outbound transfers should be LP token only.
        if (summary.outflows.size() != 1) {
            return List.of();
        }

        List<RawClassifiedEvent> out = new ArrayList<>();
        BigDecimal lpQty = summary.outflows.get(lpContract);
        if (lpQty == null || lpQty.signum() >= 0) {
            return List.of();
        }
        RawClassifiedEvent lpLeg = new RawClassifiedEvent();
        lpLeg.setEventType(EconomicEventType.LP_EXIT);
        lpLeg.setWalletAddress(walletAddress);
        lpLeg.setAssetContract(lpContract);
        lpLeg.setAssetSymbol(resolveSymbol(tx, lpContract, summary.metaByContract.get(lpContract)));
        lpLeg.setQuantityDelta(lpQty);
        lpLeg.setProtocolName(resolveProtocolName(txTo, txProtocol, lpContract));
        lpLeg.setLogIndex(summary.outflowLogIndex.get(lpContract));
        out.add(lpLeg);

        for (String contract : nonLpIncoming) {
            BigDecimal qty = summary.inflows.get(contract);
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            RawClassifiedEvent leg = new RawClassifiedEvent();
            leg.setEventType(EconomicEventType.LP_EXIT);
            leg.setWalletAddress(walletAddress);
            leg.setAssetContract(contract);
            leg.setAssetSymbol(resolveSymbol(tx, contract, summary.metaByContract.get(contract)));
            leg.setQuantityDelta(qty);
            leg.setProtocolName(resolveProtocolName(txTo, txProtocol, contract));
            leg.setLogIndex(summary.inflowLogIndex.get(contract));
            out.add(leg);
        }
        return out;
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

    private static boolean isFinalLpExitByBurn(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            String positionId
    ) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String positionTopic = positionId != null
                ? "0x" + String.format("%064x", new BigInteger(positionId))
                : null;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String nftContract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (nftContract == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(nftContract)) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                continue;
            }
            boolean isWalletSource = walletTopic.equalsIgnoreCase(fromTopic);
            boolean isSamePosition = positionTopic != null && positionTopic.equalsIgnoreCase(topics.get(3));
            if (isWalletSource || isSamePosition) {
                return true;
            }
        }
        return false;
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

    private FlowSummary collectFlowSummary(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> outflows = new LinkedHashMap<>();
        Map<String, BigDecimal> inflows = new LinkedHashMap<>();
        Map<String, Integer> outflowLogIndex = new LinkedHashMap<>();
        Map<String, Integer> inflowLogIndex = new LinkedHashMap<>();
        Set<String> mintToWallet = new LinkedHashSet<>();
        Set<String> burnFromWallet = new LinkedHashSet<>();
        Map<String, TokenMeta> metaByContract = tokenMetaByContract(tx);

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);

            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outflows.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                outflowLogIndex.putIfAbsent(tokenAddress, logIndex);
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                    burnFromWallet.add(tokenAddress);
                }
            } else if (walletTopic.equalsIgnoreCase(toTopic)) {
                inflows.merge(tokenAddress, quantity, BigDecimal::add);
                inflowLogIndex.putIfAbsent(tokenAddress, logIndex);
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                    mintToWallet.add(tokenAddress);
                }
            }
        }

        return new FlowSummary(
                outflows,
                inflows,
                outflowLogIndex,
                inflowLogIndex,
                mintToWallet,
                burnFromWallet,
                metaByContract
        );
    }

    private Map<String, TokenMeta> tokenMetaByContract(RawTransactionNormalizationView tx) {
        Map<String, TokenMeta> out = new LinkedHashMap<>();
        if (tx == null || !tx.hasRawData()) {
            return out;
        }
        List<Document> transfers = tx.explorerTokenTransfers();
        if (transfers.isEmpty()) {
            return out;
        }
        for (Document transfer : transfers) {
            String contract = tx.tokenTransferContract(transfer);
            if (contract == null) {
                continue;
            }
            String symbol = tx.normalizeTextValue(tx.tokenTransferSymbol(transfer));
            String name = tx.normalizeTextValue(tx.tokenTransferName(transfer));
            if (symbol != null || name != null) {
                out.putIfAbsent(contract, new TokenMeta(symbol, name));
            }
        }
        return out;
    }

    private String resolveSymbol(RawTransactionNormalizationView tx, String contract, TokenMeta meta) {
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
            TokenMeta meta,
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
        if (matchesKnownLpProtocol(byAssetProtocol)) {
            return true;
        }
        return matchesKnownLpProtocol(txProtocol) || isKnownLpRouterCall(tx);
    }

    private static boolean matchesKnownLpProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return false;
        }
        for (String known : KNOWN_LP_PROTOCOL_NAMES) {
            if (protocolName.contains(known)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownLpRouterCall(RawTransactionNormalizationView tx) {
        String to = tx.readRawOrExplorerAddress("to");
        return to != null && KNOWN_LP_ROUTER_ADDRESSES.contains(to);
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

    private static String resolvePositionId(RawTransactionNormalizationView tx, List<Document> logs) {
        String fromLogs = resolvePositionIdFromLogs(tx, logs);
        if (fromLogs != null) {
            return fromLogs;
        }
        return resolvePositionIdFromInput(tx);
    }

    private static String resolvePositionIdFromLogs(RawTransactionNormalizationView tx, List<Document> logs) {
        if (tx == null || logs == null || logs.isEmpty()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Document log : logs) {
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (address == null || !KNOWN_LP_POSITION_NFT_CONTRACTS.contains(address)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4) {
                continue;
            }
            String parsed = parsePositionIdFromTopic(topics.get(3));
            if (parsed != null) {
                ids.add(parsed);
            }
        }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    private static String resolvePositionIdFromInput(RawTransactionNormalizationView tx) {
        if (tx == null) {
            return null;
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.length() < 10) {
            return null;
        }
        String selector = input.substring(0, 10);
        if (LP_POSITION_ID_INPUT_SELECTORS.contains(selector)) {
            String direct = parsePositionIdForSelector(input, selector, 10);
            if (direct != null) {
                return direct;
            }
            return null;
        }
        if (!"0xac9650d8".equals(selector)) {
            return null;
        }
        String multicallHex = strip0x(input);
        if (multicallHex.length() <= 8) {
            return null;
        }
        Set<String> candidateIds = new LinkedHashSet<>();
        for (String nestedSelector : LP_POSITION_ID_INPUT_SELECTORS) {
            String selectorHex = strip0x(nestedSelector);
            int fromIndex = 0;
            while (true) {
                int idx = multicallHex.indexOf(selectorHex, fromIndex);
                if (idx < 0) {
                    break;
                }
                if (idx % 2 == 0) {
                    String candidate = parsePositionIdForSelector(
                            multicallHex,
                            "0x" + selectorHex,
                            idx + selectorHex.length()
                    );
                    if (candidate != null) {
                        candidateIds.add(candidate);
                    }
                }
                fromIndex = idx + selectorHex.length();
            }
        }
        return candidateIds.size() == 1 ? candidateIds.iterator().next() : null;
    }

    private static String parsePositionIdForSelector(String hexInput, String selector, int argsStart) {
        if (hexInput == null || selector == null || selector.isBlank()) {
            return null;
        }
        String normalizedSelector = selector.toLowerCase(Locale.ROOT);
        int offset = switch (normalizedSelector) {
            case "0x42842e0e" -> 64 * 2; // safeTransferFrom(address,address,tokenId)
            default -> 0; // tokenId is first tuple/value argument
        };
        return parsePositionIdFromWord(hexInput, argsStart + offset);
    }

    private static String parsePositionIdFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return parseHexToUnsignedDecimal(topic);
    }

    private static String parsePositionIdFromWord(String hexInput, int wordStart) {
        if (hexInput == null || wordStart < 0) {
            return null;
        }
        int adjustedWordStart = wordStart;
        if (hexInput.startsWith("0x")) {
            adjustedWordStart = wordStart - 2;
        }
        if (adjustedWordStart < 0) {
            return null;
        }
        String hex = strip0x(hexInput);
        if (hex == null || adjustedWordStart + 64 > hex.length()) {
            return null;
        }
        return parseHexToUnsignedDecimal(hex.substring(adjustedWordStart, adjustedWordStart + 64));
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

    private record TokenMeta(String symbol, String name) {}

    private record FlowSummary(
            Map<String, BigDecimal> outflows,
            Map<String, BigDecimal> inflows,
            Map<String, Integer> outflowLogIndex,
            Map<String, Integer> inflowLogIndex,
            Set<String> mintToWallet,
            Set<String> burnFromWallet,
            Map<String, TokenMeta> metaByContract
    ) {
    }
}
