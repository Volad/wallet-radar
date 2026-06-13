package com.walletradar.lending.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.lending.config.LendingMarketRateProperties;
import com.walletradar.lending.persistence.LendingHealthFactorSnapshot;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LendingAaveV3HealthCollector {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HEALTH_FACTOR_SCALE = new BigDecimal("1000000000000000000");
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(3);
    private static final String GET_USER_ACCOUNT_DATA_SELECTOR = EvmAbiSupport.selector("getUserAccountData(address)");

    private final LendingMarketRateProperties properties;
    private final com.walletradar.ingestion.config.IngestionNetworkProperties networkProperties;
    private final EvmRpcClient evmRpcClient;
    private final ObjectMapper objectMapper;

    public Optional<LendingHealthFactorSnapshot> collect(ActiveBorrowGroup group) {
        String networkKey = group.networkId() == null ? "" : group.networkId().trim().toUpperCase(Locale.ROOT);
        LendingMarketRateProperties.AaveV3NetworkConfig config = properties.getAaveV3().get(networkKey);
        if (config == null || !config.isEnabled() || isBlank(config.getPoolAddress())) {
            return Optional.empty();
        }
        Optional<String> endpoint = endpoint(networkKey);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            String data = "0x" + GET_USER_ACCOUNT_DATA_SELECTOR + EvmAbiSupport.encodeAddress(group.walletAddress());
            String result = rpcResult(evmRpcClient.call(
                    endpoint.get(),
                    "eth_call",
                    List.of(java.util.Map.of("to", config.getPoolAddress(), "data", data), "latest")
            ).block(RPC_TIMEOUT));
            if (result == null || result.length() <= 2 || EvmAbiSupport.wordAt(result, 5) == null) {
                log.warn("Aave V3 health factor call returned empty payload network={} pool={} wallet={}",
                        networkKey, config.getPoolAddress(), group.walletAddress());
                return Optional.empty();
            }
            BigInteger healthFactorRaw = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(result, 5));
            BigDecimal healthFactor = new BigDecimal(healthFactorRaw).divide(HEALTH_FACTOR_SCALE, MC)
                    .setScale(4, RoundingMode.HALF_UP);
            Instant capturedAt = Instant.now();
            LendingHealthFactorSnapshot snapshot = new LendingHealthFactorSnapshot()
                    .setId(snapshotId(group, capturedAt))
                    .setSessionId(group.sessionId())
                    .setProtocolKey(group.protocolKey())
                    .setNetworkId(networkKey)
                    .setWalletAddress(group.walletAddress())
                    .setHealthFactor(healthFactor)
                    .setSource(LendingHealthFactorSnapshotService.LIVE_PROTOCOL)
                    .setCapturedAt(capturedAt)
                    .setBlockNumber(blockNumber(endpoint.get()).orElse(null))
                    .setRawSnapshotRef(config.getPoolAddress().toLowerCase(Locale.ROOT) + ":" + group.walletAddress());
            return Optional.of(snapshot);
        } catch (Exception error) {
            log.warn("Failed to collect Aave V3 health factor network={} wallet={} error={}",
                    networkKey, group.walletAddress(), error.toString());
            return Optional.empty();
        }
    }

    private Optional<Long> blockNumber(String endpoint) {
        try {
            String result = rpcResult(evmRpcClient.call(endpoint, "eth_blockNumber", List.of()).block(RPC_TIMEOUT));
            return Optional.of(new BigInteger(EvmAbiSupport.cleanHex(result), 16).longValue());
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    private String rpcResult(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException(error.toString());
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            throw new IllegalStateException("RPC result is missing");
        }
        return result.asText();
    }

    private Optional<String> endpoint(String networkKey) {
        com.walletradar.ingestion.config.IngestionNetworkProperties.NetworkIngestionEntry entry =
                networkProperties.getNetwork().get(networkKey);
        if (entry == null) {
            return Optional.empty();
        }
        return entry.getUrls().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private String snapshotId(ActiveBorrowGroup group, Instant capturedAt) {
        return String.join(":",
                group.sessionId(),
                "aave",
                nullToUnknown(group.networkId()),
                nullToUnknown(group.walletAddress()),
                String.valueOf(capturedAt.toEpochMilli())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ActiveBorrowGroup(
            String sessionId,
            String protocolKey,
            String networkId,
            String walletAddress
    ) {
    }
}
