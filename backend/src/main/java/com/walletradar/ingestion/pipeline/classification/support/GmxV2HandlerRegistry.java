package com.walletradar.ingestion.pipeline.classification.support;

import java.util.Locale;
import java.util.Set;

/**
 * Known GMX V2 handler and vault contracts on Arbitrum that return unused execution fees
 * as tiny native ETH transfers to the order creator.
 *
 * <p>Sources: docs.gmx.io/docs/api/contracts/addresses/ (current + deprecated v2.1 set),
 * Arbiscan verified labels.</p>
 */
public final class GmxV2HandlerRegistry {

    private static final Set<String> ARBITRUM_HANDLERS = Set.of(
            // Current Arbitrum
            "0x63492b775e30a9e6b4b4761c12605eb9d071d5e9", // OrderHandler
            "0x33871b8568edc4adf33338cdd8cf52a0ecc84d42", // DepositHandler
            "0x11e9e7464f3bc887a7290ec41fcd22f619b177fd", // WithdrawalHandler
            "0x5f66cbb8d1766e6ce3c1ffba0987aede7a1dff53", // ShiftHandler
            "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5", // OrderVault
            "0xf89e77e8dc11691c9e8757e84aafbcd8a67d7a55", // DepositVault
            "0x0628d46b5d145f183adb6ef1f2c97ed1c4701c55", // WithdrawalVault
            "0xfe99609c4aa83ff6816b64563bdffd7fa68753ab", // ShiftVault
            // Deprecated v2.1 Arbitrum
            "0x352f684ab9e97a6321a13cf03a61316b681d9fd2", // OrderHandler v2.1 (Deprecated)
            "0xe68caaacdf6439628dfd2fe624847602991a31eb", // OrderHandler legacy v2.0
            "0x70d95587d40a2caf56bd97485ab3eec10bee6336", // DepositHandler (older)
            "0x63dc80ee90f26363b3fcd609007cc9e14c8991be", // WithdrawalHandler (older)
            "0x1eea01a3592b8943737977b93ed24be7842d2427", // OrderVault (older)
            // Multichain Arbitrum
            "0xd38111f8af1a7cd809457c8a2303e15ae2170724", // MultichainOrderRouter
            "0xc6782854a8639cc3b40f9497797d6b33797ca592", // MultichainGmRouter
            "0xceaadfaf6a8c489b250e407987877c5fdfcdbe6e"  // MultichainVault
    );

    private static final Set<String> AVALANCHE_HANDLERS = Set.of(
            "0x823b558b4bc0a2c4974a0d8d7885aa1102d15dec", // OrderHandler
            "0xcc2645e961514a694bca228686ec664933c70647", // DepositHandler
            "0x334237f7d75497a22b1443f44ddccf95e72904a0", // WithdrawalHandler
            "0x6adf7026d53057ced269dfda318103db4f0aa4ba", // ShiftHandler
            "0xd3d60d22d415ad43b7e64b510d86a30f19b1b12c", // OrderVault
            "0x90c670825d0c62ede1c5ee9571d6d9a17a722dff", // DepositVault
            "0xf5f30b10141e1f63fc11ed772931a8294a591996", // WithdrawalVault
            "0x7fc46ccb386e9bbbfb49a2639002734c3ec52b39"  // ShiftVault
    );

    private GmxV2HandlerRegistry() {
    }

    public static boolean isKnownGmxV2Handler(String address) {
        String normalized = normalize(address);
        if (normalized == null) {
            return false;
        }
        return ARBITRUM_HANDLERS.contains(normalized)
                || AVALANCHE_HANDLERS.contains(normalized);
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (!trimmed.startsWith("0x") && !trimmed.startsWith("0X")) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
