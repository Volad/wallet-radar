package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.ConfidenceLevel;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.TransactionClarificationResolver;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Clarifies missing native inbound swap leg using tx.value and wrapped-native unwrap fallback from receipt logs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EvmNativeValueClarificationResolver implements TransactionClarificationResolver {

    private static final int SCALE = 18;
    private static final String EVM_NATIVE_CONTRACT = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private static final Map<NetworkId, String> NATIVE_SYMBOL = Map.of(
            NetworkId.ETHEREUM, "ETH",
            NetworkId.ARBITRUM, "ETH",
            NetworkId.OPTIMISM, "ETH",
            NetworkId.BASE, "ETH",
            NetworkId.POLYGON, "MATIC",
            NetworkId.BSC, "BNB",
            NetworkId.AVALANCHE, "AVAX",
            NetworkId.MANTLE, "MNT"
    );
    private static final Map<NetworkId, String> WRAPPED_NATIVE_CONTRACT = Map.of(
            NetworkId.ETHEREUM, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            NetworkId.ARBITRUM, "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
            NetworkId.OPTIMISM, "0x4200000000000000000000000000000000000006",
            NetworkId.BASE, "0x4200000000000000000000000000000000000006",
            NetworkId.POLYGON, "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270",
            NetworkId.BSC, "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
            NetworkId.AVALANCHE, "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7",
            NetworkId.MANTLE, "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8"
    );

    private final EvmRpcClient evmRpcClient;
    private final ObjectMapper objectMapper;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> evmRotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator evmDefaultRotator;
    @Qualifier("evmRpcRateLimiter")
    private final RateLimiter evmRpcRateLimiter;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId != null && networkId != NetworkId.SOLANA;
    }

    @Override
    public Optional<ClarificationResult> clarify(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getNetworkId() == null
                || transaction.getTxHash() == null
                || transaction.getType() != NormalizedTransactionType.SWAP) {
            return Optional.empty();
        }
        if (hasInboundLeg(transaction.getLegs()) || !hasOutboundLeg(transaction.getLegs())) {
            return Optional.empty();
        }
        RpcEndpointRotator rotator = evmRotatorsByNetwork.getOrDefault(transaction.getNetworkId().name(), evmDefaultRotator);
        String endpoint = rotator.getNextEndpoint();
        try {
            String json = callRpc(endpoint, "eth_getTransactionByHash", List.of(transaction.getTxHash()), transaction.getTxHash());
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                return Optional.empty();
            }
            Optional<NativeInference> inferenceOpt = inferNativeInbound(endpoint, transaction, result);
            if (inferenceOpt.isEmpty()) {
                return Optional.empty();
            }
            NativeInference inference = inferenceOpt.get();
            NormalizedTransaction.Leg leg = new NormalizedTransaction.Leg();
            leg.setRole(NormalizedLegRole.BUY);
            leg.setAssetContract(EVM_NATIVE_CONTRACT);
            leg.setAssetSymbol(NATIVE_SYMBOL.getOrDefault(transaction.getNetworkId(), "NATIVE"));
            leg.setQuantityDelta(inference.quantity());
            leg.setInferred(true);
            leg.setInferenceReason(inference.reason());
            leg.setConfidence(inference.confidence());
            return Optional.of(new ClarificationResult(List.of(leg), inference.reason(), inference.confidence()));
        } catch (Exception e) {
            log.debug("Clarification failed for tx {} on {}: {}",
                    transaction.getTxHash(), transaction.getNetworkId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<NativeInference> inferNativeInbound(String endpoint, NormalizedTransaction transaction, JsonNode txResult) {
        BigDecimal txValue = weiHexToNative(txResult.path("value").asText());
        if (txValue.compareTo(BigDecimal.ZERO) > 0) {
            return Optional.of(new NativeInference(txValue, "INFERRED_FROM_TX_VALUE", ConfidenceLevel.MEDIUM));
        }
        return inferFromWrappedNativeUnwrap(endpoint, transaction)
                .map(q -> new NativeInference(q, "INFERRED_FROM_WRAPPED_NATIVE_UNWRAP", ConfidenceLevel.MEDIUM));
    }

    private Optional<BigDecimal> inferFromWrappedNativeUnwrap(String endpoint, NormalizedTransaction transaction) {
        String wrappedNative = WRAPPED_NATIVE_CONTRACT.get(transaction.getNetworkId());
        if (wrappedNative == null || transaction.getWalletAddress() == null || transaction.getWalletAddress().isBlank()) {
            return Optional.empty();
        }
        String receiptJson = callRpc(endpoint, "eth_getTransactionReceipt", List.of(transaction.getTxHash()), transaction.getTxHash());
        if (receiptJson == null || receiptJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode receiptRoot = objectMapper.readTree(receiptJson);
            JsonNode receipt = receiptRoot.path("result");
            if (receipt.isMissingNode() || receipt.isNull()) {
                return Optional.empty();
            }
            JsonNode logs = receipt.path("logs");
            if (!logs.isArray() || logs.isEmpty()) {
                return Optional.empty();
            }
            String wallet = normalizeAddress(transaction.getWalletAddress());
            Set<String> counterparties = extractWalletCounterparties(logs, wallet);
            List<BurnCandidate> burns = extractWrappedNativeBurns(logs, wrappedNative);
            Optional<BurnCandidate> selected = selectBurnCandidate(counterparties, burns);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            BigDecimal quantity = new BigDecimal(selected.get().amount())
                    .divide(BigDecimal.TEN.pow(18), SCALE, RoundingMode.HALF_UP);
            return quantity.compareTo(BigDecimal.ZERO) > 0 ? Optional.of(quantity) : Optional.empty();
        } catch (Exception e) {
            log.debug("Wrapped-native unwrap parsing failed for tx {} on {}: {}",
                    transaction.getTxHash(), transaction.getNetworkId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BurnCandidate> selectBurnCandidate(Set<String> counterparties, List<BurnCandidate> burns) {
        if (burns.isEmpty()) {
            return Optional.empty();
        }
        if (!counterparties.isEmpty()) {
            List<BurnCandidate> linkedToCounterparty = burns.stream()
                    .filter(burn -> counterparties.contains(burn.fromAddress()))
                    .toList();
            if (linkedToCounterparty.size() == 1) {
                return Optional.of(linkedToCounterparty.get(0));
            }
            if (linkedToCounterparty.size() > 1) {
                return Optional.empty();
            }
        }
        return burns.size() == 1 ? Optional.of(burns.get(0)) : Optional.empty();
    }

    private Set<String> extractWalletCounterparties(JsonNode logs, String walletAddress) {
        Set<String> counterparties = new LinkedHashSet<>();
        for (JsonNode logNode : logs) {
            JsonNode topics = logNode.path("topics");
            if (!isTransferLog(topics)) {
                continue;
            }
            String fromAddress = extractAddressFromTopic(topics.get(1).asText());
            String toAddress = extractAddressFromTopic(topics.get(2).asText());
            if (walletAddress.equals(fromAddress) && !walletAddress.equals(toAddress)) {
                counterparties.add(toAddress);
            } else if (walletAddress.equals(toAddress) && !walletAddress.equals(fromAddress)) {
                counterparties.add(fromAddress);
            }
        }
        return counterparties;
    }

    private List<BurnCandidate> extractWrappedNativeBurns(JsonNode logs, String wrappedNativeContract) {
        List<BurnCandidate> burns = new ArrayList<>();
        String wrappedNative = normalizeAddress(wrappedNativeContract);
        for (JsonNode logNode : logs) {
            JsonNode topics = logNode.path("topics");
            if (!isTransferLog(topics)) {
                continue;
            }
            String contract = normalizeAddress(logNode.path("address").asText());
            if (!wrappedNative.equals(contract)) {
                continue;
            }
            String toAddress = extractAddressFromTopic(topics.get(2).asText());
            if (!ZERO_ADDRESS.equals(toAddress)) {
                continue;
            }
            BigInteger amount = uint256HexToBigInteger(logNode.path("data").asText());
            if (amount.signum() <= 0) {
                continue;
            }
            burns.add(new BurnCandidate(extractAddressFromTopic(topics.get(1).asText()), amount));
        }
        return burns;
    }

    private static boolean isTransferLog(JsonNode topics) {
        return topics != null
                && topics.isArray()
                && topics.size() >= 3
                && TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText());
    }

    private String callRpc(String endpoint, String method, Object params, String txHash) {
        if (!evmRpcRateLimiter.acquirePermission()) {
            log.debug("Clarification local limiter blocked {} for {}", method, txHash);
            return null;
        }
        return evmRpcClient.call(endpoint, method, params).block();
    }

    private static boolean hasInboundLeg(List<NormalizedTransaction.Leg> legs) {
        return legs != null && legs.stream()
                .map(NormalizedTransaction.Leg::getQuantityDelta)
                .filter(q -> q != null)
                .anyMatch(q -> q.signum() > 0);
    }

    private static boolean hasOutboundLeg(List<NormalizedTransaction.Leg> legs) {
        return legs != null && legs.stream()
                .map(NormalizedTransaction.Leg::getQuantityDelta)
                .filter(q -> q != null)
                .anyMatch(q -> q.signum() < 0);
    }

    private static BigDecimal weiHexToNative(String hexValue) {
        if (hexValue == null || hexValue.isBlank()) {
            return BigDecimal.ZERO;
        }
        String normalized = stripHexPrefix(hexValue);
        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigInteger raw = new BigInteger(normalized, 16);
        return new BigDecimal(raw).divide(BigDecimal.TEN.pow(18), SCALE, RoundingMode.HALF_UP);
    }

    private static BigInteger uint256HexToBigInteger(String hexValue) {
        String normalized = stripHexPrefix(hexValue);
        if (normalized.isBlank()) {
            return BigInteger.ZERO;
        }
        try {
            return new BigInteger(normalized, 16);
        } catch (NumberFormatException ex) {
            return BigInteger.ZERO;
        }
    }

    private static String normalizeAddress(String address) {
        String normalized = stripHexPrefix(address).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return "0x" + normalized;
    }

    private static String extractAddressFromTopic(String topic) {
        String normalized = stripHexPrefix(topic).toLowerCase(Locale.ROOT);
        if (normalized.length() < 40) {
            return "";
        }
        return "0x" + normalized.substring(normalized.length() - 40);
    }

    private static String stripHexPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return value.substring(2);
        }
        return value;
    }

    private record BurnCandidate(String fromAddress, BigInteger amount) {
    }

    private record NativeInference(BigDecimal quantity, String reason, ConfidenceLevel confidence) {
    }
}
