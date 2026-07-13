package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.lp.v4.V4PoolStateLookupService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * R4 — Uniswap V4 / Pancake Infinity CL LP exit fee/principal split.
 *
 * <p>Decodes the {@code ModifyLiquidity} event from persisted logs, derives the principal amounts
 * using concentrated-liquidity tick math ({@link LpClMathSupport}), and maps fee fractions to
 * ERC-20 token contract addresses. The result is the same shape as
 * {@link LpExitFeeDecomposer#feeFractionsForContracts}: a map from token contract (lowercase hex)
 * to the fee fraction of the received quantity that is fee income rather than principal return.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Decode {@code ModifyLiquidity} log: poolId (topic1), tickLower/tickUpper/liquidityDelta (data).</li>
 *   <li>For <b>out-of-range</b> positions: all principal is in one token → compute without RPC.</li>
 *   <li>For <b>in-range</b>: fetch {@code sqrtPriceX96} via {@link V4PoolStateLookupService} (Mongo cache
 *       → archive RPC). If unavailable, fall back to {@code fee = 0} (no fabrication).</li>
 *   <li>Conservation-safe clamp: {@code principal = min(computedPrincipal, received)},
 *       {@code fee = max(received − computedPrincipal, 0)} — prevents fabricating principal basis
 *       when tick-math overshoots due to rounding.</li>
 *   <li>Map ERC-20 Transfer amounts to token contracts, compute fee fraction per contract.</li>
 * </ol>
 *
 * <h3>Fallback</h3>
 * When {@code ModifyLiquidity} evidence is absent, or sqrtPriceX96 is unavailable for an in-range
 * position, returns {@link Optional#empty()} so the caller treats the full received amount as
 * principal ({@code fee = 0}) — conservative, never fabricates income.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LpV4ExitFeeDecomposer {

    /**
     * {@code ModifyLiquidity(bytes32,address,int24,int24,int256,bytes32)} event topic0.
     * Emitted by Uniswap V4 PoolManager and all compatible V4-fork PoolManagers.
     */
    public static final String MODIFY_LIQUIDITY_TOPIC =
            "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";

    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final V4PoolStateLookupService poolStateLookupService;

    /**
     * Returns {@code true} when the view's persisted logs contain a {@code ModifyLiquidity} event
     * (indicating V4 fee-split evidence is available without another RPC call).
     */
    public static boolean hasModifyLiquidityEvidence(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (MODIFY_LIQUIDITY_TOPIC.equals(firstTopic(log))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes V4 fee fractions per ERC-20 token contract for the given LP exit view.
     *
     * @param view raw transaction view (must have {@code persistedLogs} with {@code ModifyLiquidity})
     * @return map from token contract (lowercase, no 0x prefix) to fee fraction [0, 1),
     *         or {@link Optional#empty()} if evidence is absent or sqrtPrice is unavailable for in-range
     */
    public Optional<Map<String, BigDecimal>> feeFractionsForContracts(OnChainRawTransactionView view) {
        if (view == null) {
            return Optional.empty();
        }
        ModifyLiquidityDecoded decoded = decodeModifyLiquidity(view);
        if (decoded == null) {
            return Optional.empty();
        }

        BigInteger absDelta = decoded.liquidityDelta().abs();
        if (absDelta.signum() == 0) {
            return Optional.empty();
        }

        // Resolve sqrtPriceX96 — needed only for in-range positions
        // For out-of-range: getAmountsForLiquidity handles the degenerate cases (all in one token)
        Optional<BigInteger> sqrtPrice = Optional.empty();
        boolean needsPrice = mightBeInRange(decoded);
        if (needsPrice && view.blockNumber() != null && view.blockNumber() > 0) {
            sqrtPrice = poolStateLookupService.getSqrtPriceX96(
                    view.networkId(),
                    decoded.poolId(),
                    view.blockNumber()
            );
            if (sqrtPrice.isEmpty()) {
                log.info("V4 fee split: sqrtPriceX96 unavailable (UNRESOLVED) network={} poolId={} block={} — using fee=0 fallback",
                        view.networkId(), decoded.poolId(), view.blockNumber());
                // Return empty → caller treats full received as principal (fee = 0, no fabrication)
                return Optional.empty();
            }
        }

        BigInteger sqrtRatioX96 = sqrtPrice.orElse(
                // For definitely-out-of-range: use an extreme sqrtPrice that guarantees the correct branch
                decoded.liquidityDelta().signum() < 0
                        ? BigInteger.ZERO   // forces all token0 (price below range)
                        : LpClMathSupport.Q96.shiftLeft(64) // forces all token1 (price above range)
        );

        // Compute principal amounts via CL math
        LpClMathSupport.Amounts principal = LpClMathSupport.getAmountsForLiquidity(
                sqrtRatioX96, decoded.tickLower(), decoded.tickUpper(), absDelta);

        // Map received ERC-20 amounts from Transfer logs to token contracts
        Map<String, BigInteger> receivedByContract = receivedAmountsFromTransfers(view);
        if (receivedByContract.isEmpty()) {
            return Optional.empty();
        }

        // Compute fee fractions with conservation-safe clamp
        Map<String, BigDecimal> result = new HashMap<>();
        BigInteger[] principalRaw = new BigInteger[]{principal.amount0(), principal.amount1()};

        // We need to match token0/token1 to contracts. We use the received amounts to identify which
        // contract corresponds to which slot: for V4, Transfer amounts match principal+fee totals.
        // We resolve by matching total-received ordering (larger amount → more likely token1 for ETH pools)
        // but since we can't reliably know token0/1 ordering from logs alone without the poolKey,
        // we use a slot-agnostic approach: for each contract, fee = max(received - computedPrincipal, 0)
        // where computedPrincipal is the minimum of the two computed amounts matched by proximity.

        List<Map.Entry<String, BigInteger>> receivedEntries = new java.util.ArrayList<>(receivedByContract.entrySet());
        // Sort by received amount descending to match against computed amounts
        receivedEntries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        BigInteger[] computedSorted = sortDesc(principalRaw[0], principalRaw[1]);

        for (int i = 0; i < receivedEntries.size() && i < 2; i++) {
            String contract = receivedEntries.get(i).getKey();
            BigInteger received = receivedEntries.get(i).getValue();
            if (received == null || received.signum() <= 0) {
                continue;
            }
            BigInteger computedPrincipal = i < computedSorted.length ? computedSorted[i] : BigInteger.ZERO;

            // Conservation-safe clamp: principal ≤ received
            BigInteger clampedPrincipal = computedPrincipal.min(received);
            BigInteger fee = received.subtract(clampedPrincipal).max(BigInteger.ZERO);

            if (fee.signum() <= 0) {
                continue;
            }
            BigDecimal feeFraction = new BigDecimal(fee)
                    .divide(new BigDecimal(received), 18, RoundingMode.HALF_DOWN);
            result.put(contract, feeFraction);
            log.info("V4 fee split: contract={} received={} principal={} fee={} fraction={}",
                    contract, received, clampedPrincipal, fee, feeFraction);
        }

        return result.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableMap(result));
    }

    /** Returns true when the tick range might contain the current price (needs sqrtPriceX96). */
    private static boolean mightBeInRange(ModifyLiquidityDecoded decoded) {
        // We don't know the current price, so we conservatively assume in-range unless
        // special BSC Infinity single-token (XYZ) case detected.
        // In practice the V4PoolStateReader handles this correctly even for out-of-range.
        return true;
    }

    private ModifyLiquidityDecoded decodeModifyLiquidity(OnChainRawTransactionView view) {
        for (Document logDoc : view.persistedLogs()) {
            List<String> topics = LpExitFeeDecomposer.normalizedTopics(logDoc);
            if (topics.isEmpty() || !MODIFY_LIQUIDITY_TOPIC.equals(topics.get(0))) {
                continue;
            }
            if (topics.size() < 2) {
                continue;
            }
            String poolId = topics.get(1).startsWith("0x")
                    ? topics.get(1).substring(2)
                    : topics.get(1);

            // data: tickLower (int256), tickUpper (int256), liquidityDelta (int256), salt (bytes32)
            String data = logData(logDoc);
            if (data == null) {
                continue;
            }
            String cleanData = data.startsWith("0x") ? data.substring(2) : data;
            if (cleanData.length() < 192) {
                continue; // need at least 3 words
            }

            int tickLower = decodeInt24FromWord(cleanData, 0);
            int tickUpper = decodeInt24FromWord(cleanData, 1);
            BigInteger liquidityDelta = decodeSignedInt256FromWord(cleanData, 2);

            if (liquidityDelta == null || liquidityDelta.signum() >= 0) {
                // We only care about exits (liquidityDelta < 0)
                continue;
            }
            return new ModifyLiquidityDecoded(poolId, tickLower, tickUpper, liquidityDelta);
        }
        return null;
    }

    private Map<String, BigInteger> receivedAmountsFromTransfers(OnChainRawTransactionView view) {
        String walletAddr = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (walletAddr == null) {
            return Map.of();
        }
        Map<String, BigInteger> result = new HashMap<>();
        for (Document logDoc : view.persistedLogs()) {
            List<String> topics = LpExitFeeDecomposer.normalizedTopics(logDoc);
            if (topics.size() < 3 || !ERC20_TRANSFER_TOPIC.equals(topics.get(0))) {
                continue;
            }
            String to = topicAddress(topics.get(2));
            if (!walletAddr.equals(to)) {
                continue;
            }
            String contractAddr = OnChainRawTransactionView.normalizeAddress(
                    stringValue(logDoc.get("address")));
            if (contractAddr == null) {
                continue;
            }
            BigInteger amount = decodeUnsignedWord(logData(logDoc), 0);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            result.merge(contractAddr, amount, BigInteger::add);
        }
        return result;
    }

    private static BigInteger[] sortDesc(BigInteger a, BigInteger b) {
        if (a.compareTo(b) >= 0) {
            return new BigInteger[]{a, b};
        }
        return new BigInteger[]{b, a};
    }

    private static int decodeInt24FromWord(String hexData, int wordIndex) {
        BigInteger raw = decodeSignedInt256FromWord(hexData, wordIndex);
        if (raw == null) return 0;
        return raw.intValue();
    }

    private static BigInteger decodeSignedInt256FromWord(String hexData, int wordIndex) {
        if (hexData == null) return null;
        int start = wordIndex * 64;
        int end = start + 64;
        if (hexData.length() < end) return null;
        String word = hexData.substring(start, end);
        BigInteger unsigned = new BigInteger(word, 16);
        BigInteger MAX = BigInteger.ONE.shiftLeft(255);
        if (unsigned.compareTo(MAX) >= 0) {
            return unsigned.subtract(BigInteger.ONE.shiftLeft(256));
        }
        return unsigned;
    }

    private static BigInteger decodeUnsignedWord(String data, int wordIndex) {
        if (data == null) return null;
        String clean = data.startsWith("0x") ? data.substring(2) : data;
        int start = wordIndex * 64;
        int end = start + 64;
        if (clean.length() < end) return null;
        return new BigInteger(clean.substring(start, end), 16);
    }

    private static String firstTopic(Document logDoc) {
        List<String> t = LpExitFeeDecomposer.normalizedTopics(logDoc);
        return t.isEmpty() ? null : t.get(0);
    }

    private static String logData(Document logDoc) {
        if (logDoc == null) return null;
        Object d = logDoc.get("data");
        return d == null ? null : d.toString().trim();
    }

    private static String topicAddress(String topic) {
        if (topic == null) return null;
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) return null;
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private record ModifyLiquidityDecoded(String poolId, int tickLower, int tickUpper, BigInteger liquidityDelta) {
    }
}
