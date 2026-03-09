package com.walletradar.ingestion.classifier.lp;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LpProtocolRegistry {

    public static final String ZERO_ADDRESS_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000000";
    public static final String DEPOSIT_TOPIC = "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c";
    public static final String POOL_MINT_TOPIC = "0x7a53080ba414158be7ec69b987b5fb7d07dee101fe85488f0853ae16239d0bde";
    public static final String INCREASE_LIQUIDITY_TOPIC = "0x3067048beee31b25b2f1681f88dac838c8bba36af25bfb2b7cf7473a5847e35f";
    public static final String V4_POSITION_LIQUIDITY_TOPIC = "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";

    private static final Set<String> KNOWN_LP_PROTOCOL_NAMES = Set.of(
            "uniswap v2",
            "uniswap v3",
            "uniswap v4",
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
            "dodo",
            "slipstream"
    );

    private static final Set<String> KNOWN_LP_ROUTER_ADDRESSES = Set.of(
            "0x7a250d5630b4cf539739df2c5dacb4c659f2488d",
            "0xe592427a0aece92de3edee1f18e0157c05861564",
            "0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45",
            "0xef1c6e67703c7bd7107eed8303fbe6ec2554bf6b",
            "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad",
            "0xd9e1ce17f2641f24ae83637ab66a2cca9c378b9f",
            "0x1b02da8cb0d097eb8d57a175b88c7d8b47997506",
            "0xba12222222228d8ba445958a75a0704d566bf2c8",
            "0x10ed43c718714eb63d5aa57b78b54704e256024e",
            "0x13f4ea83d0bd40e75c8222255bc855a974568dd4",
            "0xc873fecbd354f5a56e00e710b90ef4201db2448d",
            "0x60ae616a2155ee3d9a68541ba4544862310933d4",
            "0x18556da13313f3532c54711497a8fedac273220e",
            "0x70f61901658aafb7ae57da0c30695ce4417e72b9"
    );

    private static final Set<String> KNOWN_POSITION_MANAGER_ADDRESSES = Set.of(
            "0x55f4c8aba71a1e923edc303eb4feff14608cc226",
            "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364",
            "0xa815e2ed7f7d5b0c49fda367f249232a1b9d2883",
            "0xc36442b4a4522e871399cd717abdd847ab11fe88",
            "0x943e6e07a7e8e791dafc44083e54041d743c46e9",
            "0x827922686190790b37229fd06084350e74485b72",
            "0x416b433906b1b72fa758e166e239c43d68dc6f29",
            "0x991d5546c4b442b4c5fdc4c8b8b8d131deb24702",
            "0x4529a01c7a0410167c5740c487a8de60232617bf"
    );

    private static final Set<String> KNOWN_CUSTODY_WRAPPER_ADDRESSES = Set.of(
            "0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3"
    );

    private static final Map<String, WrappedNativeAsset> WRAPPED_NATIVE_BY_NETWORK = Map.ofEntries(
            Map.entry("ETHEREUM", new WrappedNativeAsset("0xc02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH")),
            Map.entry("ARBITRUM", new WrappedNativeAsset("0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "WETH")),
            Map.entry("OPTIMISM", new WrappedNativeAsset("0x4200000000000000000000000000000000000006", "WETH")),
            Map.entry("BASE", new WrappedNativeAsset("0x4200000000000000000000000000000000000006", "WETH")),
            Map.entry("POLYGON", new WrappedNativeAsset("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270", "WMATIC")),
            Map.entry("BSC", new WrappedNativeAsset("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "WBNB")),
            Map.entry("AVALANCHE", new WrappedNativeAsset("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "WAVAX")),
            Map.entry("MANTLE", new WrappedNativeAsset("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8", "WMNT")),
            Map.entry("LINEA", new WrappedNativeAsset("0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f", "WETH")),
            Map.entry("UNICHAIN", new WrappedNativeAsset("0x4200000000000000000000000000000000000006", "WETH")),
            Map.entry("ZKSYNC", new WrappedNativeAsset("0x5aea5775959fbc2557cc8789bc1bf90a239d9a91", "WETH"))
    );

    public boolean matchesKnownLpProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return false;
        }
        String normalized = protocolName.trim().toLowerCase(Locale.ROOT);
        for (String known : KNOWN_LP_PROTOCOL_NAMES) {
            if (normalized.contains(known)) {
                return true;
            }
        }
        return false;
    }

    public boolean isKnownLpRouter(String address) {
        String normalized = normalize(address);
        return normalized != null && KNOWN_LP_ROUTER_ADDRESSES.contains(normalized);
    }

    public boolean isKnownPositionManager(String address) {
        String normalized = normalize(address);
        return normalized != null && KNOWN_POSITION_MANAGER_ADDRESSES.contains(normalized);
    }

    public boolean isKnownCustodyWrapper(String address) {
        String normalized = normalize(address);
        return normalized != null && KNOWN_CUSTODY_WRAPPER_ADDRESSES.contains(normalized);
    }

    public boolean isKnownLpSurfaceTarget(String address) {
        return isKnownPositionManager(address) || isKnownLpRouter(address) || isKnownCustodyWrapper(address);
    }

    public boolean isKnownLpLifecycleTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        return POOL_MINT_TOPIC.equalsIgnoreCase(topic)
                || INCREASE_LIQUIDITY_TOPIC.equalsIgnoreCase(topic)
                || V4_POSITION_LIQUIDITY_TOPIC.equalsIgnoreCase(topic);
    }

    public WrappedNativeAsset wrappedNativeOf(String networkId) {
        if (networkId == null) {
            return null;
        }
        return WRAPPED_NATIVE_BY_NETWORK.get(networkId.toUpperCase(Locale.ROOT));
    }

    public NativeAsset nativeAssetOf(String networkId) {
        if (networkId == null) {
            return new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        }
        return switch (networkId.toUpperCase(Locale.ROOT)) {
            case "POLYGON" -> new NativeAsset("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270", "MATIC");
            case "BSC" -> new NativeAsset("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "BNB");
            case "AVALANCHE" -> new NativeAsset("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "AVAX");
            case "MANTLE" -> new NativeAsset("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8", "MNT");
            default -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        };
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String value = address.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("0x")) {
            value = "0x" + value;
        }
        return value.length() == 42 ? value : null;
    }

    public record WrappedNativeAsset(String contract, String symbol) {
    }

    public record NativeAsset(String contract, String symbol) {
    }
}
