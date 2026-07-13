package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decodes {@code DecreaseLiquidity} and {@code Collect} events from persisted receipt logs for
 * Uniswap V3 / Slipstream (Velodrome/Aerodrome) LP exits, enabling principal-vs-fee decomposition.
 *
 * <h3>Event ABIs (Uniswap V3 NonfungiblePositionManager)</h3>
 * <pre>
 * DecreaseLiquidity(uint256 indexed tokenId, uint128 liquidity, uint256 amount0, uint256 amount1)
 *   topic0  = 0x26f6a048ee9138f2c0ce266f322cb99228e8d619ae2bff30c67f8dcf9d2377b4
 *   data    = [liquidity(32B), amount0(32B), amount1(32B)]
 *
 * Collect(uint256 indexed tokenId, address recipient, uint256 amount0, uint256 amount1)
 *   topic0  = 0x40d0efd1a53d60ecbf40971b9daf7dc90178c3aadc7aab1765632738fa8b8f01
 *   data    = [recipient(32B), amount0Collected(32B), amount1Collected(32B)]
 * </pre>
 */
public final class LpExitFeeDecomposer {

    private static final Logger log = LoggerFactory.getLogger(LpExitFeeDecomposer.class);

    static final String DECREASE_LIQUIDITY_TOPIC =
            "0x26f6a048ee9138f2c0ce266f322cb99228e8d619ae2bff30c67f8dcf9d2377b4";
    static final String COLLECT_TOPIC =
            "0x40d0efd1a53d60ecbf40971b9daf7dc90178c3aadc7aab1765632738fa8b8f01";
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private static final MathContext MC = MathContext.DECIMAL128;

    private LpExitFeeDecomposer() {
    }

    /**
     * Returns true when persisted logs already contain a {@code DecreaseLiquidity} or {@code Collect}
     * event, meaning the fee split can proceed without further clarification.
     */
    public static boolean hasFeeSplitEvidence(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        for (Document logDoc : view.persistedLogs()) {
            String topic0 = firstTopic(logDoc);
            if (DECREASE_LIQUIDITY_TOPIC.equals(topic0) || COLLECT_TOPIC.equals(topic0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes {@code DecreaseLiquidity} and {@code Collect} events from the view's persisted logs.
     * Returns {@link Optional#empty()} when either event is absent.
     */
    public static Optional<LpExitFeeAmounts> decode(OnChainRawTransactionView view) {
        if (view == null) {
            return Optional.empty();
        }
        BigInteger principal0 = null;
        BigInteger principal1 = null;
        BigInteger total0 = null;
        BigInteger total1 = null;

        for (Document logDoc : view.persistedLogs()) {
            String topic0 = firstTopic(logDoc);
            if (DECREASE_LIQUIDITY_TOPIC.equals(topic0)) {
                String data = logData(logDoc);
                // data words: [0]=liquidity, [1]=amount0, [2]=amount1
                principal0 = decodeUnsignedWord(data, 1);
                principal1 = decodeUnsignedWord(data, 2);
            } else if (COLLECT_TOPIC.equals(topic0)) {
                String data = logData(logDoc);
                // data words: [0]=recipient, [1]=amount0Collected, [2]=amount1Collected
                total0 = decodeUnsignedWord(data, 1);
                total1 = decodeUnsignedWord(data, 2);
            }
        }

        if (principal0 == null || principal1 == null || total0 == null || total1 == null) {
            return Optional.empty();
        }

        LpExitFeeAmounts amounts = new LpExitFeeAmounts(principal0, principal1, total0, total1);
        log.debug("Decoded LP exit fee amounts: principal0={} principal1={} total0={} total1={} fee0={} fee1={}",
                principal0, principal1, total0, total1, amounts.feeRaw0(), amounts.feeRaw1());
        return Optional.of(amounts);
    }

    /**
     * Builds a map from normalized {@code assetContract} (lowercase) to the fee fraction for that
     * token, using ERC-20 {@code Transfer} events directed to the wallet to resolve slot assignment.
     *
     * <p>Matching logic: an ERC-20 Transfer to the wallet with {@code rawAmount == totalRaw0} means
     * the emitting contract is the pool's token0; same for token1. The fee fraction is then
     * {@code feeRaw[slot] / totalRaw[slot]}, applied later to the decimal {@code quantityDelta} of
     * the matching flow leg without needing to know token decimals.</p>
     *
     * <p><strong>C2 — Native ETH support:</strong> When a V3/Slipstream pool's WETH leg is unwrapped
     * via {@code unwrapWETH9}, the wallet receives native ETH (no ERC-20 Transfer to wallet). A second
     * pass scans all WETH Transfer events (any recipient) from the canonical WETH contract for this
     * network. If one matches an unresolved slot, the fee fraction is emitted keyed by the WETH contract
     * address. {@link LpNftClFlowMaterializer#splitFeeFlows} then uses this WETH key when processing
     * the null-assetContract (native ETH) flow leg.</p>
     *
     * @param amounts decoded raw amounts
     * @param view    raw transaction view (provides persisted logs, wallet address, and networkId)
     * @return map of normalized contract address → fee fraction [0, 1]
     */
    public static Map<String, BigDecimal> feeFractionsForContracts(
            LpExitFeeAmounts amounts,
            OnChainRawTransactionView view
    ) {
        Map<BigInteger, Integer> rawToSlot = new HashMap<>();
        if (amounts.totalRaw0() != null && amounts.totalRaw0().signum() > 0) {
            rawToSlot.put(amounts.totalRaw0(), 0);
        }
        if (amounts.totalRaw1() != null && amounts.totalRaw1().signum() > 0) {
            rawToSlot.put(amounts.totalRaw1(), 1);
        }

        Map<String, BigDecimal> result = new HashMap<>();
        String walletAddr = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (walletAddr == null) {
            return result;
        }

        // Pass 1: match ERC-20 Transfers directed to the wallet.
        Set<Integer> resolvedSlots = new java.util.HashSet<>();
        for (Document logDoc : view.persistedLogs()) {
            List<String> topics = normalizedTopics(logDoc);
            if (topics.size() < 3) {
                continue;
            }
            if (!ERC20_TRANSFER_TOPIC.equals(topics.get(0))) {
                continue;
            }
            String to = topicAddress(topics.get(2));
            if (!walletAddr.equals(to)) {
                continue;
            }
            String tokenContract = normalizeAddress(stringValue(logDoc.get("address")));
            if (tokenContract == null) {
                continue;
            }
            BigInteger rawAmount = decodeUnsignedWord(logData(logDoc), 0);
            if (rawAmount == null || rawAmount.signum() <= 0) {
                continue;
            }
            Integer slot = rawToSlot.get(rawAmount);
            if (slot == null) {
                continue;
            }
            BigInteger feeRaw = slot == 0 ? amounts.feeRaw0() : amounts.feeRaw1();
            BigInteger totalRaw = slot == 0 ? amounts.totalRaw0() : amounts.totalRaw1();

            BigDecimal feeFraction = totalRaw.signum() > 0
                    ? new BigDecimal(feeRaw).divide(new BigDecimal(totalRaw), 18, RoundingMode.HALF_DOWN)
                    : BigDecimal.ZERO;
            result.put(tokenContract, feeFraction);
            resolvedSlots.add(slot);
            log.debug("LP exit fee fraction for contract={}: slot={} feeRaw={} totalRaw={} fraction={}",
                    tokenContract, slot, feeRaw, totalRaw, feeFraction);
        }

        // C2 — Pass 2: native ETH support via WETH Transfer detection.
        // When unwrapWETH9 is used, WETH never reaches the wallet as an ERC-20 Transfer; instead
        // native ETH arrives via a call. Detect the WETH Transfer event (to any address) from the
        // canonical WETH contract for this network and resolve the unmatched slot.
        if (resolvedSlots.size() < rawToSlot.size()) {
            String canonicalWeth = NativeWrappedTokenSupport.canonicalWeth(view.networkId());
            if (canonicalWeth != null) {
                for (Document logDoc : view.persistedLogs()) {
                    List<String> topics = normalizedTopics(logDoc);
                    if (topics.size() < 3 || !ERC20_TRANSFER_TOPIC.equals(topics.get(0))) {
                        continue;
                    }
                    String tokenContract = normalizeAddress(stringValue(logDoc.get("address")));
                    if (!canonicalWeth.equals(tokenContract)) {
                        continue;
                    }
                    BigInteger rawAmount = decodeUnsignedWord(logData(logDoc), 0);
                    if (rawAmount == null || rawAmount.signum() <= 0) {
                        continue;
                    }
                    Integer slot = rawToSlot.get(rawAmount);
                    if (slot == null || resolvedSlots.contains(slot)) {
                        continue;
                    }
                    BigInteger feeRaw = slot == 0 ? amounts.feeRaw0() : amounts.feeRaw1();
                    BigInteger totalRaw = slot == 0 ? amounts.totalRaw0() : amounts.totalRaw1();

                    BigDecimal feeFraction = totalRaw.signum() > 0
                            ? new BigDecimal(feeRaw).divide(new BigDecimal(totalRaw), 18, RoundingMode.HALF_DOWN)
                            : BigDecimal.ZERO;
                    // Key by WETH contract — the materializer maps null-assetContract (native ETH)
                    // flows to this key via NativeWrappedTokenSupport.canonicalWeth(networkId).
                    result.put(canonicalWeth, feeFraction);
                    resolvedSlots.add(slot);
                    log.debug("LP exit fee fraction (native ETH via WETH slot): slot={} feeRaw={} totalRaw={} fraction={}",
                            slot, feeRaw, totalRaw, feeFraction);
                    break;
                }
            }
        }

        return result;
    }

    // ---- private helpers ----

    private static String firstTopic(Document logDoc) {
        List<String> topics = normalizedTopics(logDoc);
        return topics.isEmpty() ? null : topics.get(0);
    }

    static List<String> normalizedTopics(Document logDoc) {
        if (logDoc == null) {
            return List.of();
        }
        Object topicsObj = logDoc.get("topics");
        if (!(topicsObj instanceof List<?> topicList)) {
            return List.of();
        }
        return topicList.stream()
                .map(LpExitFeeDecomposer::stringValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String logData(Document logDoc) {
        return stringValue(logDoc == null ? null : logDoc.get("data"));
    }

    private static String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private static String normalizeAddress(String value) {
        return OnChainRawTransactionView.normalizeAddress(value);
    }

    static BigInteger decodeUnsignedWord(String data, int wordIndex) {
        if (data == null) {
            return null;
        }
        String normalized = data.startsWith("0x") ? data.substring(2) : data;
        int start = wordIndex * 64;
        int end = start + 64;
        if (normalized.length() < end) {
            return null;
        }
        String word = normalized.substring(start, end);
        if (!word.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        return new BigInteger(word, 16);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
