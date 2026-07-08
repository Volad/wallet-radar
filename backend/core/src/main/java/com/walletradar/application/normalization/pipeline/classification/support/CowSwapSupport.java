package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bson.Document;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/**
 * Minimal audited CoW ETH Flow request / settlement decoder.
 */
public final class CowSwapSupport {

    public static final String PROTOCOL_NAME = "CoW Swap";
    public static final String ETH_FLOW_VERSION = "EthFlow";
    public static final String GPV2_VERSION = "GPv2";

    private static final String ETH_FLOW_CREATE_ORDER_SELECTOR = "0x322bba21";
    private static final String GPV2_SETTLEMENT_SELECTOR = "0x13d79a0b";
    private static final String ARBITRUM_ETH_FLOW_CONTRACT = "0xba3cb449bd2b4adddbc894d8697f5170800eadec";
    /** GPv2 settlement contract on Arbitrum (shared with Ethereum mainnet deployment). */
    public static final String GPV2_SETTLEMENT = "0x9008d19f58aabd9ed0d60971565aa8510560ab41";
    private static final String ARBITRUM_GPV2_SETTLEMENT_CONTRACT = GPV2_SETTLEMENT;
    private static final String ARBITRUM_WRAPPED_NATIVE = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
    private static final long ARBITRUM_CHAIN_ID = 42161L;

    private static final String GPV2_TRADE_TOPIC = "0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17";

    private static final byte[] DOMAIN_TYPE_HASH = keccakBytes(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );
    private static final byte[] DOMAIN_NAME = keccakBytes("Gnosis Protocol");
    private static final byte[] DOMAIN_VERSION = keccakBytes("v2");
    private static final byte[] ORDER_TYPE_HASH = hexToBytes(
            "d5a25ba2e97094ad7d83dc28a6572da797d6b3e7fc6663bd93efb789fc17e489"
    );
    private static final byte[] KIND_SELL = hexToBytes(
            "f3b277728b3fee749481eb3e0b3b48980dbbab78658fc419025cb16eee346775"
    );
    private static final byte[] BALANCE_ERC20 = hexToBytes(
            "5a28e9363bb942b639270062aa6bb295f434bcdfc42c97267bf003f272060dc9"
    );

    private CowSwapSupport() {
    }

    public static boolean isEthFlowRequest(OnChainRawTransactionView view) {
        DecodedEthFlowOrder order = decodeEthFlowOrder(view);
        return order != null;
    }

    public static String resolveEthFlowCorrelationId(OnChainRawTransactionView view) {
        DecodedEthFlowOrder order = decodeEthFlowOrder(view);
        if (order == null) {
            return null;
        }
        byte[] domainSeparator = domainSeparator(view.networkId(), settlementContract(view.networkId()));
        if (domainSeparator == null) {
            return null;
        }
        byte[] structEncoding = concat(
                ORDER_TYPE_HASH,
                encodeAddress(wrappedNativeToken(view.networkId())),
                encodeAddress(order.buyToken()),
                encodeAddress(order.receiver()),
                encodeUint(order.sellAmount()),
                encodeUint(order.buyAmount()),
                encodeUint(BigInteger.valueOf(0xffff_ffffL)),
                encodeBytes32(order.appData()),
                encodeUint(order.feeAmount()),
                KIND_SELL,
                encodeBool(order.partiallyFillable()),
                BALANCE_ERC20,
                BALANCE_ERC20
        );
        byte[] structHash = keccak(structEncoding);
        return "0x" + toHex(keccak(concat(new byte[]{0x19, 0x01}, domainSeparator, structHash)));
    }

    public static boolean isSettlementCandidate(OnChainRawTransactionView view) {
        if (view == null || view.networkId() != NetworkId.ARBITRUM) {
            return false;
        }
        if (hasTradeEvent(view)) {
            return true;
        }
        return ARBITRUM_GPV2_SETTLEMENT_CONTRACT.equals(view.toAddress())
                && GPV2_SETTLEMENT_SELECTOR.equals(view.methodId());
    }

    public static String resolveSettlementCorrelationId(OnChainRawTransactionView view) {
        if (view == null || !view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!hasTopic(log, GPV2_TRADE_TOPIC)) {
                continue;
            }
            byte[] orderUid = decodeDynamicBytes(logData(log), 5);
            if (orderUid == null || orderUid.length < 32) {
                String trailingDigest = decodeTrailingDigest(logData(log));
                if (trailingDigest != null) {
                    return trailingDigest;
                }
                continue;
            }
            byte[] digest = new byte[32];
            System.arraycopy(orderUid, 0, digest, 0, 32);
            return "0x" + toHex(digest);
        }
        return null;
    }

    public static boolean hasTradeEvent(OnChainRawTransactionView view) {
        if (view == null || !view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (hasTopic(log, GPV2_TRADE_TOPIC)) {
                return true;
            }
        }
        return false;
    }

    private static DecodedEthFlowOrder decodeEthFlowOrder(OnChainRawTransactionView view) {
        if (view == null
                || view.networkId() != NetworkId.ARBITRUM
                || !ARBITRUM_ETH_FLOW_CONTRACT.equals(view.toAddress())
                || !ETH_FLOW_CREATE_ORDER_SELECTOR.equals(view.methodId())) {
            return null;
        }
        String inputData = view.inputData();
        String buyToken = decodeAddressArgument(inputData, 0);
        String receiver = decodeAddressArgument(inputData, 1);
        BigInteger sellAmount = decodeUintArgument(inputData, 2);
        BigInteger buyAmount = decodeUintArgument(inputData, 3);
        String appData = decodeBytes32Argument(inputData, 4);
        BigInteger feeAmount = decodeUintArgument(inputData, 5);
        BigInteger validTo = decodeUintArgument(inputData, 6);
        Boolean partiallyFillable = decodeBooleanArgument(inputData, 7);
        if (buyToken == null
                || receiver == null
                || sellAmount == null
                || buyAmount == null
                || appData == null
                || feeAmount == null
                || validTo == null
                || partiallyFillable == null) {
            return null;
        }
        BigInteger expectedValue = sellAmount.add(feeAmount);
        if (view.rawValue() == null || !expectedValue.equals(view.rawValue())) {
            return null;
        }
        return new DecodedEthFlowOrder(buyToken, receiver, sellAmount, buyAmount, appData, feeAmount, partiallyFillable);
    }

    private static String settlementContract(NetworkId networkId) {
        return networkId == NetworkId.ARBITRUM ? ARBITRUM_GPV2_SETTLEMENT_CONTRACT : null;
    }

    private static String wrappedNativeToken(NetworkId networkId) {
        return networkId == NetworkId.ARBITRUM ? ARBITRUM_WRAPPED_NATIVE : null;
    }

    private static byte[] domainSeparator(NetworkId networkId, String verifyingContract) {
        if (networkId != NetworkId.ARBITRUM || verifyingContract == null) {
            return null;
        }
        return keccak(concat(
                DOMAIN_TYPE_HASH,
                DOMAIN_NAME,
                DOMAIN_VERSION,
                encodeUint(BigInteger.valueOf(ARBITRUM_CHAIN_ID)),
                encodeAddress(verifyingContract)
        ));
    }

    private static boolean hasTopic(Document log, String topic) {
        if (log == null || topic == null) {
            return false;
        }
        List<?> rawTopics = (List<?>) log.get("topics");
        if (rawTopics == null || rawTopics.isEmpty()) {
            return false;
        }
        Object first = rawTopics.getFirst();
        return first != null && topic.equalsIgnoreCase(String.valueOf(first));
    }

    private static String logData(Document log) {
        if (log == null) {
            return null;
        }
        Object data = log.get("data");
        return data == null ? null : String.valueOf(data).toLowerCase(Locale.ROOT);
    }

    private static String decodeAddressArgument(String inputData, int argumentIndex) {
        String word = decodeWord(inputData, argumentIndex);
        if (word == null) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(word.substring(24));
    }

    private static BigInteger decodeUintArgument(String inputData, int argumentIndex) {
        String word = decodeWord(inputData, argumentIndex);
        if (word == null) {
            return null;
        }
        return new BigInteger(word, 16);
    }

    private static String decodeBytes32Argument(String inputData, int argumentIndex) {
        String word = decodeWord(inputData, argumentIndex);
        return word == null ? null : "0x" + word;
    }

    private static Boolean decodeBooleanArgument(String inputData, int argumentIndex) {
        BigInteger value = decodeUintArgument(inputData, argumentIndex);
        if (value == null) {
            return null;
        }
        return !BigInteger.ZERO.equals(value);
    }

    private static String decodeWord(String inputData, int argumentIndex) {
        if (inputData == null || !inputData.startsWith("0x") || argumentIndex < 0) {
            return null;
        }
        int start = 10 + (argumentIndex * 64);
        int end = start + 64;
        if (inputData.length() < end) {
            return null;
        }
        String word = inputData.substring(start, end);
        return word.matches("[0-9a-f]{64}") ? word : null;
    }

    private static byte[] decodeDynamicBytes(String hexData, int dynamicWordIndex) {
        if (hexData == null || !hexData.startsWith("0x")) {
            return null;
        }
        String payload = hexData.substring(2);
        if (payload.length() < (dynamicWordIndex + 1) * 64) {
            return null;
        }
        BigInteger offset = new BigInteger(payload.substring(dynamicWordIndex * 64, (dynamicWordIndex + 1) * 64), 16);
        int offsetHex = offset.intValueExact() * 2;
        if (offsetHex + 64 > payload.length()) {
            return null;
        }
        BigInteger length = new BigInteger(payload.substring(offsetHex, offsetHex + 64), 16);
        int lengthHex = length.intValueExact() * 2;
        int dataStart = offsetHex + 64;
        int dataEnd = dataStart + lengthHex;
        if (dataEnd > payload.length()) {
            return null;
        }
        return hexToBytes(payload.substring(dataStart, dataEnd));
    }

    private static String decodeTrailingDigest(String hexData) {
        if (hexData == null || !hexData.startsWith("0x") || hexData.length() < 66) {
            return null;
        }
        String payload = hexData.substring(2);
        String digest = payload.substring(payload.length() - 64);
        return digest.matches("[0-9a-f]{64}") ? "0x" + digest : null;
    }

    private static byte[] keccak(byte[] value) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        digest.update(value, 0, value.length);
        return digest.digest();
    }

    private static byte[] keccakBytes(String value) {
        return keccak(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] encodeAddress(String address) {
        String normalized = address == null ? null : address.toLowerCase(Locale.ROOT);
        if (normalized == null || !normalized.startsWith("0x") || normalized.length() != 42) {
            return new byte[32];
        }
        byte[] raw = hexToBytes(normalized.substring(2));
        byte[] encoded = new byte[32];
        System.arraycopy(raw, 0, encoded, 12, raw.length);
        return encoded;
    }

    private static byte[] encodeUint(BigInteger value) {
        byte[] encoded = new byte[32];
        if (value == null) {
            return encoded;
        }
        byte[] raw = value.toByteArray();
        int copyLength = Math.min(raw.length, 32);
        int rawStart = Math.max(0, raw.length - copyLength);
        int encodedStart = 32 - copyLength;
        System.arraycopy(raw, rawStart, encoded, encodedStart, copyLength);
        return encoded;
    }

    private static byte[] encodeBool(boolean value) {
        byte[] encoded = new byte[32];
        encoded[31] = value ? (byte) 1 : (byte) 0;
        return encoded;
    }

    private static byte[] encodeBytes32(String value) {
        String normalized = value == null ? null : value.toLowerCase(Locale.ROOT);
        if (normalized == null || !normalized.startsWith("0x") || normalized.length() != 66) {
            return new byte[32];
        }
        return hexToBytes(normalized.substring(2));
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part == null ? 0 : part.length;
        }
        byte[] concatenated = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null) {
                continue;
            }
            System.arraycopy(part, 0, concatenated, offset, part.length);
            offset += part.length;
        }
        return concatenated;
    }

    private static byte[] hexToBytes(String hex) {
        String normalized = hex == null ? "" : hex.trim();
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        int length = normalized.length();
        if ((length & 1) == 1) {
            normalized = "0" + normalized;
            length++;
        }
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            bytes[i] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private record DecodedEthFlowOrder(
            String buyToken,
            String receiver,
            BigInteger sellAmount,
            BigInteger buyAmount,
            String appData,
            BigInteger feeAmount,
            boolean partiallyFillable
    ) {
    }
}
