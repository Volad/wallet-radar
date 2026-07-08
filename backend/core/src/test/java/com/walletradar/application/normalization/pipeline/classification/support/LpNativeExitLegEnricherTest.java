package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LpNativeExitLegEnricherTest {

    // Wallet that owns the LP position
    private static final String WALLET = "0xaabbccdd00000000000000000000000000000001";
    // LP position manager contract (NFPM), not the user
    private static final String LP_CONTRACT = "0x46a15b0b27311cedfa6fcd7a6fc77ba26de95f1e";

    // WETH Withdrawal topic
    private static final String WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    // multicall input containing decreaseLiquidity selector (0x0c49ccbe)
    private static final String MULTICALL_INPUT_DECREASE_LIQUIDITY =
            "0xac9650d8000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010c49ccbe0000000000000000000000000000000000000000000000000000000000000001";

    // multicall input containing burn selector (0x00f714ce)
    private static final String MULTICALL_INPUT_BURN =
            "0xac9650d8000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010000f714ce000000000000000000000000000000000000000000000000000000000000001";

    // input with no LP selector
    private static final String UNRELATED_INPUT =
            "0xd0e30db0";

    private final NativeAssetSymbolResolver resolver = new NativeAssetSymbolResolver(NetworkTestFixtures.registry());

    // ------------------------------------------------------------------ helpers

    private static OnChainRawTransactionView viewWithLogs(
            String walletAddress,
            NetworkId network,
            String inputData,
            List<Document> logs
    ) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(network.name());
        raw.setWalletAddress(walletAddress);
        raw.setRawData(new Document()
                .append("hash", "0xdeadbeef")
                .append("blockNumber", "1000000")
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "5")
                .append("input", inputData)
                .append("logs", logs));
        return OnChainRawTransactionView.wrap(raw);
    }

    /**
     * Build a WETH Withdrawal log Document.
     *
     * @param srcAddress the caller of WETH.withdraw (32-byte padded topic)
     * @param wadHex     wad as hex (e.g. "0x0b0db5e5a9a4c3ed")
     */
    private static Document withdrawalLog(String srcAddress, String wadHex) {
        // topic1: address padded to 32 bytes
        String normalized = srcAddress.toLowerCase().replace("0x", "");
        String paddedSrc = "0x" + "0".repeat(64 - normalized.length()) + normalized;
        // wad: uint256 padded to 32 bytes
        String wadNorm = wadHex.toLowerCase().replace("0x", "");
        String paddedWad = "0x" + "0".repeat(64 - wadNorm.length()) + wadNorm;
        return new Document()
                .append("topics", List.of(WITHDRAWAL_TOPIC, paddedSrc))
                .append("data", paddedWad);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void happyPath_decreaseLiquidityInInput_withdrawalLogFromLpContract_addsEthLeg() {
        // 2 ETH = 2_000_000_000_000_000_000 wei = 0x1BC16D674EC80000
        Document log = withdrawalLog(LP_CONTRACT, "0x1bc16d674ec80000");
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_DECREASE_LIQUIDITY, List.of(log));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).hasSize(1);
        RawLeg ethLeg = result.getFirst();
        assertThat(ethLeg.fee()).isFalse();
        assertThat(ethLeg.assetContract()).isNull();
        assertThat(ethLeg.assetSymbol()).isEqualTo("ETH");
        // 0x1BC16D674EC80000 = 2_000_000_000_000_000_000 wei = 2 ETH
        assertThat(ethLeg.quantityDelta())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("2.000000000000000000"));
    }

    @Test
    void happyPath_burnSelectorInInput_addsEthLeg() {
        Document log = withdrawalLog(LP_CONTRACT, "0x0de0b6b3a7640000"); // 1 ETH
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_BURN, List.of(log));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().assetSymbol()).isEqualTo("ETH");
        assertThat(result.getFirst().quantityDelta().signum()).isPositive();
    }

    @Test
    void skipped_whenNativeInboundLegAlreadyPresent() {
        Document log = withdrawalLog(LP_CONTRACT, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_DECREASE_LIQUIDITY, List.of(log));

        // Pre-existing inbound ETH leg
        RawLeg existingEth = RawLeg.nativeAsset("ETH", new BigDecimal("1.0"));
        List<RawLeg> input = List.of(existingEth);

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, input);

        assertThat(result).isSameAs(input);
        assertThat(result).hasSize(1);
    }

    @Test
    void skipped_whenInputHasNoLpSelector() {
        Document log = withdrawalLog(LP_CONTRACT, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, UNRELATED_INPUT, List.of(log));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void skipped_whenWithdrawalSrcIsUsersWallet() {
        // src = the tracked wallet itself → must be ignored (no LP intermediary)
        Document log = withdrawalLog(WALLET, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_DECREASE_LIQUIDITY, List.of(log));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void multipleWithdrawalLogs_sumsAllWads() {
        // Two separate Withdrawal logs from LP_CONTRACT (e.g. multi-range exit)
        Document log1 = withdrawalLog(LP_CONTRACT, "0x0de0b6b3a7640000"); // 1 ETH
        Document log2 = withdrawalLog(LP_CONTRACT, "0x06f05b59d3b20000"); // 0.5 ETH
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_DECREASE_LIQUIDITY, List.of(log1, log2));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).hasSize(1);
        // 1 ETH + 0.5 ETH = 1.5 ETH
        assertThat(result.getFirst().quantityDelta())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1.5"));
    }

    @Test
    void multipleWithdrawalLogs_skipsWalletSrcAndSumsOnlyLpContractLogs() {
        Document walletLog = withdrawalLog(WALLET, "0x0de0b6b3a7640000");       // user → skip
        Document lpLog = withdrawalLog(LP_CONTRACT, "0x06f05b59d3b20000");       // LP_CONTRACT → include
        OnChainRawTransactionView view = viewWithLogs(WALLET, NetworkId.BASE, MULTICALL_INPUT_DECREASE_LIQUIDITY, List.of(walletLog, lpLog));

        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().quantityDelta())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("500000000000000000").movePointLeft(18));
    }

    @Test
    void nullView_returnsOriginalLegs() {
        List<RawLeg> legs = List.of();
        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(null, resolver, legs);
        assertThat(result).isSameAs(legs);
    }

    @Test
    void nullInputData_returnsOriginalLegs() {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.BASE.name());
        raw.setWalletAddress(WALLET);
        // rawData has no "input" key → inputData() returns null
        raw.setRawData(new Document()
                .append("hash", "0xdeadbeef")
                .append("blockNumber", "1")
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1"));
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);

        List<RawLeg> legs = List.of();
        List<RawLeg> result = LpNativeExitLegEnricher.enrichLegs(view, resolver, legs);
        assertThat(result).isSameAs(legs);
    }
}
