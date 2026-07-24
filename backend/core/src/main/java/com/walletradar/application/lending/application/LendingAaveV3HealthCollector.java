package com.walletradar.application.lending.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.lending.config.LendingMarketRateProperties;
import com.walletradar.application.lending.spi.LendingLivePositionReader;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Live Aave V3 health-factor reader (ADR-026). Refactored (WS-3) into a
 * {@link LendingLivePositionReader} so the refresh service dispatches to it via
 * {@link #supports(String, String)} rather than a hardcoded {@code AAVE_PROTOCOL_KEY} filter. EVM
 * collateral already flows live through generic ERC-20 {@code balanceOf} (aTokens → on_chain_balances),
 * so this reader reports only the health factor / liquidation threshold / current LTV; the collateral
 * and debt legs stay empty (they are not re-contributed here — that would double-count).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LendingAaveV3HealthCollector implements LendingLivePositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HEALTH_FACTOR_SCALE = new BigDecimal("1000000000000000000");
    private static final BigDecimal BPS_SCALE = new BigDecimal("10000");
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(3);
    private static final String GET_USER_ACCOUNT_DATA_SELECTOR = EvmAbiSupport.selector("getUserAccountData(address)");
    private static final String PROTOCOL_MATCH = "AAVE";

    private final LendingMarketRateProperties properties;
    private final com.walletradar.platform.networks.config.IngestionNetworkProperties networkProperties;
    private final EvmRpcClient evmRpcClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String protocolKey, String networkId) {
        if (protocolKey == null || networkId == null
                || !protocolKey.trim().toUpperCase(Locale.ROOT).startsWith(PROTOCOL_MATCH)) {
            return false;
        }
        // Aave V3 is EVM-only and configured per network; a configured network is by definition EVM.
        return properties.getAaveV3().containsKey(networkId.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public Optional<LiveLendingPosition> read(LivePositionRequest request) {
        String networkKey = request.networkId() == null ? "" : request.networkId().trim().toUpperCase(Locale.ROOT);
        LendingMarketRateProperties.AaveV3NetworkConfig config = properties.getAaveV3().get(networkKey);
        if (config == null || !config.isEnabled() || isBlank(config.getPoolAddress())) {
            return Optional.empty();
        }
        if (!properties.isAaveV3HealthFetchEnabled(networkKey)) {
            return Optional.empty();
        }
        Optional<String> endpoint = endpoint(networkKey);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            String data = "0x" + GET_USER_ACCOUNT_DATA_SELECTOR + EvmAbiSupport.encodeAddress(request.walletAddress());
            String result = rpcResult(evmRpcClient.call(
                    endpoint.get(),
                    "eth_call",
                    List.of(java.util.Map.of("to", config.getPoolAddress(), "data", data), "latest")
            ).block(RPC_TIMEOUT));
            if (result == null || result.length() <= 2 || EvmAbiSupport.wordAt(result, 5) == null) {
                log.warn("Aave V3 health factor call returned empty payload network={} pool={} wallet={}",
                        networkKey, config.getPoolAddress(), request.walletAddress());
                return Optional.empty();
            }
            BigInteger healthFactorRaw = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(result, 5));
            BigDecimal healthFactor = new BigDecimal(healthFactorRaw).divide(HEALTH_FACTOR_SCALE, MC)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal liquidationThreshold = bpsFraction(result, 3);
            BigDecimal loanToValue = currentLoanToValue(result);
            LiveLendingPosition position = new LiveLendingPosition(
                    List.of(),
                    List.of(),
                    healthFactor,
                    liquidationThreshold,
                    loanToValue,
                    LendingHealthFactorSnapshotService.LIVE_PROTOCOL,
                    blockNumber(endpoint.get()).orElse(null),
                    config.getPoolAddress().toLowerCase(Locale.ROOT) + ":" + request.walletAddress()
            );
            return Optional.of(position);
        } catch (Exception error) {
            log.warn("Failed to collect Aave V3 health factor network={} wallet={} error={}",
                    networkKey, request.walletAddress(), error.toString());
            return Optional.empty();
        }
    }

    /** currentLiquidationThreshold (word 3) is in basis points; return as a fraction. */
    private BigDecimal bpsFraction(String result, int word) {
        try {
            String hex = EvmAbiSupport.wordAt(result, word);
            if (hex == null) {
                return null;
            }
            BigInteger bps = EvmAbiSupport.uintFromWord(hex);
            if (bps.signum() <= 0) {
                return null;
            }
            return new BigDecimal(bps).divide(BPS_SCALE, MC);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Current LTV = totalDebtBase (word 1) / totalCollateralBase (word 0). */
    private BigDecimal currentLoanToValue(String result) {
        try {
            String collatHex = EvmAbiSupport.wordAt(result, 0);
            String debtHex = EvmAbiSupport.wordAt(result, 1);
            if (collatHex == null || debtHex == null) {
                return null;
            }
            BigInteger collateral = EvmAbiSupport.uintFromWord(collatHex);
            BigInteger debt = EvmAbiSupport.uintFromWord(debtHex);
            if (collateral.signum() <= 0 || debt.signum() <= 0) {
                return null;
            }
            return new BigDecimal(debt).divide(new BigDecimal(collateral), MC).setScale(6, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return null;
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
        com.walletradar.platform.networks.config.IngestionNetworkProperties.NetworkIngestionEntry entry =
                networkProperties.getNetwork().get(networkKey);
        if (entry == null) {
            return Optional.empty();
        }
        return entry.getUrls().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
