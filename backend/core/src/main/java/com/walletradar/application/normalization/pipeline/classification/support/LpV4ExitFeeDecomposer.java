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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R4 — Uniswap V4 / Pancake Infinity CL LP exit fee/principal split.
 *
 * <p>V4 does <b>not</b> emit a V3-style {@code Collect} event; {@code ModifyLiquidity} carries no
 * token amounts and {@code feesAccrued} is only a {@code modifyLiquidity} return value (never in
 * logs). So principal must be <i>derived</i> from concentrated-liquidity tick math, and the fee is
 * the residual of the received amount over the derived principal. Refs: Uniswap {@code v4-core}
 * {@code IPoolManager.ModifyLiquidity} / {@code PoolManager.modifyLiquidity}
 * ({@code callerDelta = principalDelta + feesAccrued}), and {@code StateLibrary.getSlot0}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Decode {@code ModifyLiquidity}: poolId (topic1), tickLower/tickUpper/liquidityDelta (data).
 *       Only exits ({@code liquidityDelta < 0}) are handled.</li>
 *   <li>Gather the wallet's received principal+fee amounts per pool token. ERC-20 legs come from
 *       {@code Transfer} logs; the <b>native-ETH</b> leg (V4 settles native ETH, currency0 = the
 *       zero address) comes from internal transfers and is keyed by the canonical wrapped-native
 *       (WETH↔ETH identity) so the materializer can split the native flow.</li>
 *   <li>Determine token0/token1 by V4 currency ordering (currency0 &lt; currency1 by address;
 *       native ETH sorts first as the zero address), then map the computed
 *       {@code (amount0, amount1)} to the received tokens by that ordering — never by raw magnitude
 *       (raw magnitudes across different-decimal tokens are not comparable).</li>
 *   <li><b>Dual-token (in-range):</b> fetch {@code sqrtPriceX96} at the exit block via
 *       {@link V4PoolStateLookupService} (Mongo write-once cache → archive RPC). If unavailable,
 *       fall back to {@code fee = 0} (flag {@code V4_FEE_SPLIT_UNRESOLVED}) — never fabricate income.</li>
 *   <li><b>Single-token (out-of-range):</b> principal is computable from {@code liquidityDelta} +
 *       ticks alone (no price needed); pick the degenerate slot whose amount is the largest that
 *       does not exceed the received amount.</li>
 *   <li>Conservation-safe clamp per token: {@code principal = min(computedPrincipal, received)},
 *       {@code fee = max(received − computedPrincipal, 0)} — clamping the principal (not just the
 *       fee) preserves {@code principal + fee == received} and never fabricates principal basis.</li>
 * </ol>
 *
 * <p>The returned map (token contract / canonical-WETH → fee fraction in {@code [0, 1)}) mirrors
 * {@link LpExitFeeDecomposer#feeFractionsForContracts}; the materializer applies each fraction to
 * the matching inbound flow leg's decimal quantity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LpV4ExitFeeDecomposer {

    /**
     * {@code ModifyLiquidity(bytes32,address,int24,int24,int256,bytes32)} event topic0.
     * Emitted by Uniswap V4 PoolManager and all compatible V4-fork PoolManagers (Pancake Infinity CL).
     */
    public static final String MODIFY_LIQUIDITY_TOPIC =
            "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";

    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /** V4 native currency0 is the zero address; it sorts before every ERC-20 contract. */
    private static final String NATIVE_ORDERING_ADDRESS = "0x0000000000000000000000000000000000000000";

    /** Fee-fraction scale, matching {@link LpExitFeeDecomposer} and the materializer split. */
    private static final int FEE_FRACTION_SCALE = 18;

    /**
     * Minimum {@code principal/received} ratio accepted on the no-price single-token path. Below
     * this the slot mapping is treated as ambiguous and we fall back to {@code fee = 0} rather than
     * risk fabricating a large fee from a mis-picked degenerate amount. Real CL exit fees are a
     * small fraction of principal, so a conservative 0.5 floor never rejects a legitimate split.
     */
    private static final BigDecimal MIN_PRINCIPAL_FRACTION_NO_PRICE = new BigDecimal("0.5");

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
     * Computes V4 fee fractions per received pool token for the given LP exit view.
     *
     * @param view raw transaction view (must have {@code persistedLogs} with {@code ModifyLiquidity})
     * @return map from token contract / canonical-WETH (lowercase, {@code 0x}-prefixed) to the fee
     *         fraction {@code [0, 1)}, or {@link Optional#empty()} when evidence is absent or the
     *         in-range price is unavailable (caller then treats the full received amount as principal)
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

        List<ReceivedToken> received = receivedTokens(view);
        if (received.isEmpty()) {
            return Optional.empty();
        }

        Map<String, BigDecimal> result = received.size() >= 2
                ? splitDualToken(view, decoded, absDelta, received)
                : splitSingleToken(view, decoded, absDelta, received.get(0));

        return (result == null || result.isEmpty())
                ? Optional.empty()
                : Optional.of(Collections.unmodifiableMap(result));
    }

    /**
     * Dual-token (in-range) exit: needs the historical {@code sqrtPriceX96}. Maps computed
     * {@code amount0/amount1} to the received tokens by V4 currency ordering.
     */
    private Map<String, BigDecimal> splitDualToken(
            OnChainRawTransactionView view,
            ModifyLiquidityDecoded decoded,
            BigInteger absDelta,
            List<ReceivedToken> received
    ) {
        Long block = view.blockNumber();
        if (block == null || block <= 0) {
            log.info("V4_FEE_SPLIT_UNRESOLVED: missing block number network={} poolId={} — fee=0 fallback",
                    view.networkId(), decoded.poolId());
            return Map.of();
        }
        Optional<BigInteger> sqrtPrice = poolStateLookupService.getSqrtPriceX96(
                view.networkId(), decoded.poolId(), block);
        if (sqrtPrice.isEmpty()) {
            log.info("V4_FEE_SPLIT_UNRESOLVED: sqrtPriceX96 unavailable network={} poolId={} block={} — fee=0 fallback",
                    view.networkId(), decoded.poolId(), block);
            return Map.of();
        }

        LpClMathSupport.Amounts principal = LpClMathSupport.getAmountsForLiquidity(
                sqrtPrice.get(), decoded.tickLower(), decoded.tickUpper(), absDelta);

        // V4 currency ordering: token0 = smallest address (native ETH sorts first), token1 = largest.
        List<ReceivedToken> ordered = new ArrayList<>(received);
        ordered.sort((a, b) -> a.orderingAddress().compareTo(b.orderingAddress()));
        ReceivedToken token0 = ordered.get(0);
        ReceivedToken token1 = ordered.get(ordered.size() - 1);

        Map<String, BigDecimal> result = new HashMap<>();
        putFeeFraction(result, token0, principal.amount0(), view, decoded);
        putFeeFraction(result, token1, principal.amount1(), view, decoded);
        return result;
    }

    /**
     * Single-token (out-of-range) exit. Principal is derived from {@code liquidityDelta} + ticks;
     * a price read is used when available to disambiguate the slot, otherwise the degenerate
     * out-of-range formulas are evaluated and the largest amount not exceeding the received quantity
     * is taken as principal.
     */
    private Map<String, BigDecimal> splitSingleToken(
            OnChainRawTransactionView view,
            ModifyLiquidityDecoded decoded,
            BigInteger absDelta,
            ReceivedToken token
    ) {
        BigInteger received = token.rawAmount();
        BigInteger computedPrincipal;

        Long block = view.blockNumber();
        Optional<BigInteger> sqrtPrice = (block != null && block > 0)
                ? poolStateLookupService.getSqrtPriceX96(view.networkId(), decoded.poolId(), block)
                : Optional.empty();

        if (sqrtPrice.isPresent()) {
            LpClMathSupport.Amounts amounts = LpClMathSupport.getAmountsForLiquidity(
                    sqrtPrice.get(), decoded.tickLower(), decoded.tickUpper(), absDelta);
            // Exactly one slot is non-zero for a truly one-sided position; if both are non-zero
            // (a boundary case), pick the largest amount that does not exceed the received quantity.
            computedPrincipal = bestPrincipalNotExceeding(received, amounts.amount0(), amounts.amount1());
        } else {
            BigInteger sqrtA = LpClMathSupport.getSqrtRatioAtTick(decoded.tickLower());
            BigInteger sqrtB = LpClMathSupport.getSqrtRatioAtTick(decoded.tickUpper());
            BigInteger amount0Below = LpClMathSupport.getAmount0ForLiquidity(sqrtA, sqrtB, absDelta);
            BigInteger amount1Above = LpClMathSupport.getAmount1ForLiquidity(sqrtA, sqrtB, absDelta);
            BigInteger best = bestPrincipalNotExceeding(received, amount0Below, amount1Above);
            // Guard: below the floor the slot mapping is ambiguous → conservative fee=0.
            BigDecimal floor = new BigDecimal(received).multiply(MIN_PRINCIPAL_FRACTION_NO_PRICE);
            if (best.signum() <= 0 || new BigDecimal(best).compareTo(floor) < 0) {
                log.info("V4_FEE_SPLIT_UNRESOLVED: single-token slot ambiguous (no price) network={} poolId={} "
                                + "received={} amount0={} amount1={} — fee=0 fallback",
                        view.networkId(), decoded.poolId(), received, amount0Below, amount1Above);
                return Map.of();
            }
            computedPrincipal = best;
        }

        Map<String, BigDecimal> result = new HashMap<>();
        putFeeFraction(result, token, computedPrincipal, view, decoded);
        return result;
    }

    /** Largest of the two computed amounts that does not exceed {@code received}; 0 if neither fits. */
    private static BigInteger bestPrincipalNotExceeding(BigInteger received, BigInteger a, BigInteger b) {
        BigInteger best = BigInteger.ZERO;
        if (a != null && a.signum() > 0 && a.compareTo(received) <= 0) {
            best = a;
        }
        if (b != null && b.signum() > 0 && b.compareTo(received) <= 0 && b.compareTo(best) > 0) {
            best = b;
        }
        return best;
    }

    /**
     * Applies the conservation-safe clamp for one token and records its fee fraction when positive.
     */
    private void putFeeFraction(
            Map<String, BigDecimal> result,
            ReceivedToken token,
            BigInteger computedPrincipal,
            OnChainRawTransactionView view,
            ModifyLiquidityDecoded decoded
    ) {
        BigInteger received = token.rawAmount();
        if (received == null || received.signum() <= 0) {
            return;
        }
        BigInteger computed = computedPrincipal == null ? BigInteger.ZERO : computedPrincipal.max(BigInteger.ZERO);
        if (computed.compareTo(received) > 0) {
            log.info("LP_FEE_CLAMPED: computedPrincipal>received network={} poolId={} key={} computed={} received={}",
                    view.networkId(), decoded.poolId(), token.mapKey(), computed, received);
        }
        BigInteger clampedPrincipal = computed.min(received);
        BigInteger fee = received.subtract(clampedPrincipal).max(BigInteger.ZERO);
        if (fee.signum() <= 0) {
            return;
        }
        BigDecimal fraction = new BigDecimal(fee)
                .divide(new BigDecimal(received), FEE_FRACTION_SCALE, RoundingMode.HALF_DOWN);
        result.put(token.mapKey(), fraction);
        log.info("V4 fee split: network={} poolId={} key={} received={} principal={} fee={} fraction={}",
                view.networkId(), decoded.poolId(), token.mapKey(), received, clampedPrincipal, fee, fraction);
    }

    /**
     * Collects the wallet's received pool tokens: ERC-20 {@code Transfer} legs (keyed by contract)
     * plus the native-ETH internal-transfer leg (keyed by canonical wrapped-native, ordered as the
     * zero address). Returns at most one entry per distinct pool token.
     */
    private List<ReceivedToken> receivedTokens(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return List.of();
        }
        Map<String, ReceivedToken> byKey = new java.util.LinkedHashMap<>();

        // ERC-20 principal+fee legs.
        for (Document logDoc : view.persistedLogs()) {
            List<String> topics = LpExitFeeDecomposer.normalizedTopics(logDoc);
            if (topics.size() < 3 || !ERC20_TRANSFER_TOPIC.equals(topics.get(0))) {
                continue;
            }
            if (!wallet.equals(topicAddress(topics.get(2)))) {
                continue;
            }
            String contract = OnChainRawTransactionView.normalizeAddress(stringValue(logDoc.get("address")));
            if (contract == null) {
                continue;
            }
            BigInteger amount = decodeUnsignedWord(logData(logDoc), 0);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            byKey.merge(contract, new ReceivedToken(contract, contract, amount),
                    (existing, add) -> new ReceivedToken(existing.mapKey(), existing.orderingAddress(),
                            existing.rawAmount().add(add.rawAmount())));
        }

        // Native-ETH leg (V4 currency0 = zero address); keyed by canonical wrapped-native so the
        // materializer's null-contract (native ETH) split path can look it up.
        BigInteger nativeWei = nativeEthReceived(view, wallet);
        if (nativeWei.signum() > 0) {
            String canonicalWeth = NativeWrappedTokenSupport.canonicalWeth(view.networkId());
            if (canonicalWeth != null) {
                byKey.merge(canonicalWeth,
                        new ReceivedToken(canonicalWeth, NATIVE_ORDERING_ADDRESS, nativeWei),
                        (existing, add) -> new ReceivedToken(existing.mapKey(), NATIVE_ORDERING_ADDRESS,
                                existing.rawAmount().add(add.rawAmount())));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Sums non-errored internal transfers directed to the wallet (native-ETH settlement, wei). */
    private static BigInteger nativeEthReceived(OnChainRawTransactionView view, String wallet) {
        BigInteger total = BigInteger.ZERO;
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            if (!wallet.equals(view.internalTransferTo(transfer))) {
                continue;
            }
            BigInteger value = parseWei(transfer.get("value"));
            if (value != null && value.signum() > 0) {
                total = total.add(value);
            }
        }
        return total;
    }

    private static BigInteger parseWei(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return new BigInteger(text.substring(2), 16);
            }
            return new BigInteger(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ModifyLiquidityDecoded decodeModifyLiquidity(OnChainRawTransactionView view) {
        for (Document logDoc : view.persistedLogs()) {
            List<String> topics = LpExitFeeDecomposer.normalizedTopics(logDoc);
            if (topics.size() < 2 || !MODIFY_LIQUIDITY_TOPIC.equals(topics.get(0))) {
                continue;
            }
            String poolId = topics.get(1).startsWith("0x") ? topics.get(1).substring(2) : topics.get(1);

            // data: tickLower (int24→32B), tickUpper (int24→32B), liquidityDelta (int256), salt (bytes32)
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
                continue; // only exits (liquidityDelta < 0)
            }
            return new ModifyLiquidityDecoded(poolId, tickLower, tickUpper, liquidityDelta);
        }
        return null;
    }

    private static int decodeInt24FromWord(String hexData, int wordIndex) {
        BigInteger raw = decodeSignedInt256FromWord(hexData, wordIndex);
        return raw == null ? 0 : raw.intValue();
    }

    private static BigInteger decodeSignedInt256FromWord(String hexData, int wordIndex) {
        if (hexData == null) {
            return null;
        }
        int start = wordIndex * 64;
        int end = start + 64;
        if (hexData.length() < end) {
            return null;
        }
        BigInteger unsigned = new BigInteger(hexData.substring(start, end), 16);
        if (unsigned.testBit(255)) {
            return unsigned.subtract(BigInteger.ONE.shiftLeft(256));
        }
        return unsigned;
    }

    private static BigInteger decodeUnsignedWord(String data, int wordIndex) {
        if (data == null) {
            return null;
        }
        String clean = data.startsWith("0x") ? data.substring(2) : data;
        int start = wordIndex * 64;
        int end = start + 64;
        if (clean.length() < end) {
            return null;
        }
        return new BigInteger(clean.substring(start, end), 16);
    }

    private static String firstTopic(Document logDoc) {
        List<String> t = LpExitFeeDecomposer.normalizedTopics(logDoc);
        return t.isEmpty() ? null : t.get(0);
    }

    private static String logData(Document logDoc) {
        if (logDoc == null) {
            return null;
        }
        Object d = logDoc.get("data");
        return d == null ? null : d.toString().trim();
    }

    private static String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private record ModifyLiquidityDecoded(String poolId, int tickLower, int tickUpper, BigInteger liquidityDelta) {
    }

    /**
     * A wallet-received pool token: {@code mapKey} is the split-map key consumed by the materializer
     * (ERC-20 contract, or canonical wrapped-native for a native-ETH leg); {@code orderingAddress}
     * is the V4 currency address used for token0/token1 ordering (zero address for native ETH).
     */
    private record ReceivedToken(String mapKey, String orderingAddress, BigInteger rawAmount) {
    }
}
