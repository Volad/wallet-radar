package com.walletradar.application.lending.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.application.lending.config.LendingMarketRateProperties;
import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
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
public class LendingAaveV3MarketRateCollector implements LendingMarketRateReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String PROTOCOL_MATCH = "AAVE";
    private static final BigDecimal RAY = new BigDecimal("1000000000000000000000000000");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SECONDS_PER_YEAR = BigDecimal.valueOf(31_536_000L);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(20);
    private static final String UNDERLYING_ASSET_ADDRESS_SELECTOR = EvmAbiSupport.selector("UNDERLYING_ASSET_ADDRESS()");
    private static final String GET_RESERVE_DATA_SELECTOR = EvmAbiSupport.selector("getReserveData(address)");

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
        return properties.getAaveV3().containsKey(networkId.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public Optional<LendingMarketRateSnapshot> collect(LendingActiveMarketDiscoveryService.ActiveMarket market) {
        String networkKey = market.networkId() == null ? "" : market.networkId().trim().toUpperCase(Locale.ROOT);
        LendingMarketRateProperties.AaveV3NetworkConfig config = properties.getAaveV3().get(networkKey);
        if (config == null || !config.isEnabled() || isBlank(config.getPoolAddress())) {
            return Optional.of(unavailable(market, "AAVE_V3_ADDRESS_CONFIG_MISSING"));
        }
        Optional<String> endpoint = endpoint(networkKey);
        if (endpoint.isEmpty()) {
            return Optional.of(unavailable(market, "RPC_ENDPOINT_UNAVAILABLE"));
        }
        try {
            String underlying = underlyingAddress(endpoint.get(), market.assetContract());
            String reserveData = reserveData(endpoint.get(), config.getPoolAddress(), underlying);
            BigInteger liquidityRate = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reserveData, 2));
            BigInteger variableBorrowRate = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reserveData, 4));
            BigInteger lastUpdateTimestamp = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reserveData, 6));
            Long blockNumber = blockNumber(endpoint.get()).orElse(null);

            Instant capturedAt = Instant.now();
            LendingMarketRateSnapshot snapshot = new LendingMarketRateSnapshot()
                    .setId(snapshotId(market, capturedAt))
                    .setSessionId(market.sessionId())
                    .setProtocol("Aave")
                    .setNetworkId(networkKey)
                    .setMarketKey(market.marketKey())
                    .setWalletAddress(market.walletAddress())
                    .setAssetSymbol(market.assetSymbol())
                    .setUnderlyingSymbol(market.underlyingSymbol())
                    .setSide(market.side())
                    .setSupplyAprPct(rateAprPct(liquidityRate))
                    .setSupplyApyPct(rateApyPct(liquidityRate))
                    .setBorrowAprPct(rateAprPct(variableBorrowRate))
                    .setBorrowApyPct(rateApyPct(variableBorrowRate))
                    .setRewardAprStatus(LendingMarketRateStatus.UNAVAILABLE)
                    .setRewardAprUnavailableReason(LendingMarketRateStatus.REWARDS_COLLECTOR_NOT_IMPLEMENTED)
                    .setNetSupplyApyPct(rateApyPct(liquidityRate))
                    .setNetBorrowApyPct(rateApyPct(variableBorrowRate))
                    .setRateSource("AAVE_V3_POOL")
                    .setRateStatus(LendingMarketRateStatus.PROTOCOL_SNAPSHOT)
                    .setApyConvention(LendingMarketRateStatus.PER_SECOND_COMPOUNDING)
                    .setCapturedAt(capturedAt)
                    .setBlockNumber(blockNumber)
                    .setSourceTimestamp(timestamp(lastUpdateTimestamp))
                    .setRawSnapshotRef(config.getPoolAddress().toLowerCase(Locale.ROOT) + ":" + underlying);
            return Optional.of(snapshot);
        } catch (Exception error) {
            log.warn("Failed to collect Aave V3 market rate network={} market={} asset={} error={}",
                    networkKey, market.marketKey(), market.assetSymbol(), error.toString());
            return Optional.of(unavailable(market, "AAVE_V3_COLLECTOR_FAILED"));
        }
    }

    private String underlyingAddress(String endpoint, String receiptTokenAddress) throws Exception {
        String result = rpcResult(evmRpcClient.call(
                endpoint,
                "eth_call",
                List.of(
                        java.util.Map.of("to", receiptTokenAddress, "data", "0x" + UNDERLYING_ASSET_ADDRESS_SELECTOR),
                        "latest"
                )
        ).block(RPC_TIMEOUT));
        String address = EvmAbiSupport.addressFromWord(result);
        if (address == null) {
            throw new IllegalStateException("UNDERLYING_ASSET_ADDRESS returned no address");
        }
        return address;
    }

    private String reserveData(String endpoint, String poolAddress, String underlyingAddress) throws Exception {
        String data = "0x" + GET_RESERVE_DATA_SELECTOR + EvmAbiSupport.encodeAddress(underlyingAddress);
        return rpcResult(evmRpcClient.call(
                endpoint,
                "eth_call",
                List.of(java.util.Map.of("to", poolAddress, "data", data), "latest")
        ).block(RPC_TIMEOUT));
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

    private BigDecimal rateAprPct(BigInteger rayRate) {
        return new BigDecimal(rayRate).divide(RAY, MC).multiply(ONE_HUNDRED, MC).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal rateApyPct(BigInteger rayRate) {
        BigDecimal apr = new BigDecimal(rayRate).divide(RAY, MC);
        double apy = Math.pow(1.0d + apr.divide(SECONDS_PER_YEAR, MC).doubleValue(), SECONDS_PER_YEAR.doubleValue()) - 1.0d;
        return BigDecimal.valueOf(apy).multiply(ONE_HUNDRED, MC).setScale(8, RoundingMode.HALF_UP);
    }

    private Instant timestamp(BigInteger seconds) {
        if (seconds == null || seconds.signum() <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(seconds.longValue());
    }

    private LendingMarketRateSnapshot unavailable(
            LendingActiveMarketDiscoveryService.ActiveMarket market,
            String reason
    ) {
        Instant capturedAt = Instant.now();
        return new LendingMarketRateSnapshot()
                .setId(snapshotId(market, capturedAt))
                .setSessionId(market.sessionId())
                .setProtocol("Aave")
                .setNetworkId(market.networkId())
                .setMarketKey(market.marketKey())
                .setWalletAddress(market.walletAddress())
                .setAssetSymbol(market.assetSymbol())
                .setUnderlyingSymbol(market.underlyingSymbol())
                .setSide(market.side())
                .setRateSource("AAVE_V3_POOL")
                .setRateStatus(LendingMarketRateStatus.UNAVAILABLE)
                .setApyConvention(LendingMarketRateStatus.PER_SECOND_COMPOUNDING)
                .setRewardAprStatus(LendingMarketRateStatus.UNAVAILABLE)
                .setRewardAprUnavailableReason(LendingMarketRateStatus.REWARDS_COLLECTOR_NOT_IMPLEMENTED)
                .setCapturedAt(capturedAt)
                .setUnavailableReason(reason);
    }

    private String snapshotId(LendingActiveMarketDiscoveryService.ActiveMarket market, Instant capturedAt) {
        return String.join(":",
                market.sessionId(),
                "aave",
                nullToUnknown(market.networkId()),
                nullToUnknown(market.marketKey()),
                nullToUnknown(market.underlyingSymbol()),
                nullToUnknown(market.side()),
                String.valueOf(capturedAt.toEpochMilli())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
