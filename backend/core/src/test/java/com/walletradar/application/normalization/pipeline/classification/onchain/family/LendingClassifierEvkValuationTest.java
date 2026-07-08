package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.rpc.EvkVaultShareRateResolver;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.RpcRequest;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the U-2 EVK wiring inside {@link LendingClassifier}:
 *
 * <ul>
 *   <li>the {@code convertToAssets} value-equivalence guard rejects a grossly value-divergent Euler
 *       rebalance pairing (a ~$1,380 collateral relocation must not be paired with a ~$5 share mint,
 *       which would carry the full $1,380 basis onto a 5.11-share lot → ~$216/share);</li>
 *   <li>a genuine value-for-value rebalance still classifies;</li>
 *   <li>the guard is fail-safe (keeps the existing pattern) when no resolver is wired; and</li>
 *   <li>share legs are priced at their true {@code convertToAssets} underlying (≈ $1.04/share), not
 *       the depressed local-ratio inference.</li>
 * </ul>
 *
 * <p>Fixtures are real Avalanche on-chain reads (evidence anchors) at block 67399112 for the eUSDC-2
 * {@code 0x39de0f…} (~$1.028/share) and eUSDt-2 {@code 0xaba9d2…} (~$1.042/share) EVK vaults.
 */
class LendingClassifierEvkValuationTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String EULER_ROUTER = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    private static final String CONVERT_TO_ASSETS = "0x07a2d13a";
    private static final String ASSET = "0x38d52e0f";
    private static final String DECIMALS = "0x313ce567";

    private static final String EUSDT2 = "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4";
    private static final String USDT = "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7";
    private static final String EUSDC2 = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

    private static final long BLOCK = 67399112L;

    @Test
    void guardRejectsGrosslyValueDivergentRebalancePairing() {
        // eUSDC-2 out worth ~$1,380 vs eUSDt-2 in worth ~$5.33 — a mis-paired collateral relocation.
        List<RawLeg> legs = List.of(
                RawLeg.asset(EUSDC2, "eUSDC-2", new BigDecimal("-1341.798065")),
                RawLeg.asset(EUSDT2, "eUSDt-2", new BigDecimal("5.114971"))
        );
        LendingClassifier classifier = new LendingClassifier(resolver());

        Optional<ClassificationDecision> decision = classifier.classify(rebalanceContext(legs));

        // Guard fires: the pattern is not a value-for-value rebalance, so no LENDING_LOOP_REBALANCE basis is
        // booked onto the tiny lot. The Euler batch instead routes to clarification rather than fabricating.
        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isNotEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
    }

    @Test
    void guardKeepsGenuineValueForValueRebalance() {
        // eUSDC-2 out (~$1,380) replaced by eUSDt-2 in (~$1,380 ≈ 1324 shares at ~$1.042) — true rebalance.
        List<RawLeg> legs = List.of(
                RawLeg.asset(EUSDC2, "eUSDC-2", new BigDecimal("-1341.798065")),
                RawLeg.asset(EUSDT2, "eUSDt-2", new BigDecimal("1324.0"))
        );
        LendingClassifier classifier = new LendingClassifier(resolver());

        Optional<ClassificationDecision> decision = classifier.classify(rebalanceContext(legs));

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
    }

    @Test
    void guardFailsSafeKeepingPatternWhenNoResolverWired() {
        List<RawLeg> legs = List.of(
                RawLeg.asset(EUSDC2, "eUSDC-2", new BigDecimal("-1341.798065")),
                RawLeg.asset(EUSDT2, "eUSDt-2", new BigDecimal("5.114971"))
        );
        // No resolver -> cannot value -> must NOT fabricate a rejection from a missing rate.
        LendingClassifier classifier = new LendingClassifier();

        Optional<ClassificationDecision> decision = classifier.classify(rebalanceContext(legs));

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
    }

    @Test
    void loopOpenSharePricedAtConvertToAssetsUnderlyingNotLocalRatio() {
        // Anchor stable transfer is intentionally mis-scaled (10 USDT) so the depressed local ratio would be
        // ~$1.955/share; the convertToAssets rate is the truthful ~$1.042/share.
        List<RawLeg> legs = List.of(RawLeg.asset(EUSDT2, "eUSDt-2", new BigDecimal("5.114971")));
        LendingClassifier classifier = new LendingClassifier(resolver());

        ClassificationDecision decision = classifier.classify(loopOpenContext(legs)).orElseThrow();

        assertThat(decision.type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_OPEN);
        NormalizedTransaction.Flow shareFlow = decision.flows().stream()
                .filter(flow -> "eUSDt-2".equals(flow.getAssetSymbol()))
                .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                .findFirst()
                .orElseThrow();
        assertThat(shareFlow.getUnitPriceUsd())
                .isBetween(new BigDecimal("1.040"), new BigDecimal("1.045"));
    }

    @Test
    void loopOpenFallsBackToLocalRatioWhenNoResolverWired() {
        List<RawLeg> legs = List.of(RawLeg.asset(EUSDT2, "eUSDt-2", new BigDecimal("5.114971")));
        LendingClassifier classifier = new LendingClassifier();

        ClassificationDecision decision = classifier.classify(loopOpenContext(legs)).orElseThrow();

        NormalizedTransaction.Flow shareFlow = decision.flows().stream()
                .filter(flow -> "eUSDt-2".equals(flow.getAssetSymbol()))
                .filter(flow -> flow.getRole() == NormalizedLegRole.BUY)
                .findFirst()
                .orElseThrow();
        // 10 USDT anchor / 5.114971 shares = ~$1.955/share (depressed local ratio retained without RPC).
        assertThat(shareFlow.getUnitPriceUsd())
                .isBetween(new BigDecimal("1.95"), new BigDecimal("1.96"));
    }

    private OnChainClassificationContext rebalanceContext(List<RawLeg> legs) {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("explorer",
                new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        return new OnChainClassificationContext(view, null, semantics(NormalizedTransactionType.LENDING_LOOP_REBALANCE), legs);
    }

    private OnChainClassificationContext loopOpenContext(List<RawLeg> legs) {
        RawTransaction raw = baseRaw();
        List<Document> transfers = List.of(
                new Document("contractAddress", USDT)
                        .append("tokenSymbol", "USDT")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", EUSDT2)
                        .append("value", "10000000")
        );
        raw.getRawData().put("explorer",
                new Document("tokenTransfers", transfers).append("internalTransfers", List.of()));
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        return new OnChainClassificationContext(view, null, semantics(NormalizedTransactionType.LENDING_LOOP_OPEN), legs);
    }

    private ProtocolSemanticResult semantics(NormalizedTransactionType suggestedType) {
        return new ProtocolSemanticResult(List.of(new ProtocolSemanticHint(
                "euler",
                suggestedType.name(),
                "Euler",
                null,
                null,
                suggestedType,
                ConfidenceLevel.LOW
        )));
    }

    private RawTransaction baseRaw() {
        RawTransaction raw = new RawTransaction();
        raw.setId("0xevk:" + NetworkId.AVALANCHE.name() + ":" + WALLET);
        raw.setTxHash("0x08e6");
        raw.setNetworkId(NetworkId.AVALANCHE.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("blockNumber", String.valueOf(BLOCK))
                .append("from", WALLET)
                .append("to", EULER_ROUTER)
                .append("methodId", "0xc16ae7a4")
                .append("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable")
                .append("value", "0")
                .append("txreceipt_status", "1"));
        return raw;
    }

    private EvkVaultShareRateResolver resolver() {
        RpcEndpointRotator rotator =
                new RpcEndpointRotator(List.of("https://rpc.test/avax"), RetryPolicy.defaultPolicy());
        EvmRpcClient client = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                return Mono.justOrEmpty(respond(method, params));
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                throw new UnsupportedOperationException("batchCall not used in EVK resolver");
            }
        };
        return new EvkVaultShareRateResolver(
                client,
                Map.of(NetworkId.AVALANCHE.name(), rotator),
                rotator,
                new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static String respond(String method, Object params) {
        if (!"eth_call".equals(method)) {
            return null;
        }
        List<Object> list = (List<Object>) params;
        Map<String, String> tx = (Map<String, String>) list.get(0);
        String hex = resolveResult(tx.get("to"), tx.get("data"));
        if (hex == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    private static String resolveResult(String to, String data) {
        if (data.startsWith(CONVERT_TO_ASSETS)) {
            BigInteger shares = new BigInteger(data.substring(CONVERT_TO_ASSETS.length()), 16);
            if (EUSDT2.equalsIgnoreCase(to)) {
                return word(shares.multiply(new BigInteger("5331162")).divide(new BigInteger("5114971")));
            }
            if (EUSDC2.equalsIgnoreCase(to)) {
                return word(shares.multiply(new BigInteger("1379961197")).divide(new BigInteger("1341798065")));
            }
            return null;
        }
        if (ASSET.equals(data)) {
            if (EUSDT2.equalsIgnoreCase(to)) {
                return addressWord(USDT);
            }
            if (EUSDC2.equalsIgnoreCase(to)) {
                return addressWord(USDC);
            }
            return null;
        }
        if (DECIMALS.equals(data)) {
            if (USDT.equalsIgnoreCase(to) || USDC.equalsIgnoreCase(to)
                    || EUSDT2.equalsIgnoreCase(to) || EUSDC2.equalsIgnoreCase(to)) {
                return word(BigInteger.valueOf(6));
            }
            return null;
        }
        return null;
    }

    private static String word(BigInteger value) {
        String hex = value.toString(16);
        return "0x" + "0".repeat(64 - hex.length()) + hex;
    }

    private static String addressWord(String address) {
        String bare = address.startsWith("0x") ? address.substring(2) : address;
        return "0x" + "0".repeat(64 - bare.length()) + bare;
    }
}
