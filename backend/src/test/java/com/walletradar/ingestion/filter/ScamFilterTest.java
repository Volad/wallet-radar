package com.walletradar.ingestion.filter;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScamFilterTest {

    private ScamFilter filter;

    @BeforeEach
    void setUp() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        props.setBlocklist(List.of("0xscamcontract1111111111111111111111111111", "0xphishing2222222222222222222222222222"));
        filter = new ScamFilter(props);
    }

    @Test
    @DisplayName("returns false when blocklist is empty")
    void emptyBlocklist_returnsFalse() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        props.setBlocklist(List.of());
        ScamFilter f = new ScamFilter(props);
        RawTransaction tx = txWithTo("0xanything");
        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns false when disabled")
    void disabled_returnsFalse() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(false);
        props.setBlocklist(List.of("0xscamcontract1111111111111111111111111111"));
        ScamFilter f = new ScamFilter(props);
        RawTransaction tx = txWithTo("0xscamcontract1111111111111111111111111111");
        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true when tx to address is in blocklist")
    void toAddressInBlocklist_returnsTrue() {
        RawTransaction tx = txWithTo("0xscamcontract1111111111111111111111111111");
        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for approve transaction by methodId")
    void approveMethodId_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xapprove");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        raw.put("value", "0");
        raw.put("methodId", "0x095ea7b3");
        raw.put("input", "0x095ea7b30000000000000000000000006ad2488743e93bfa35baf54c688de78c00bed9f0");
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for approve transaction from explorer tx payload")
    void approveInExplorerTx_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xapprove-explorer");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        raw.put("value", "0");
        raw.put("explorer", new Document("tx", new Document()
                .append("methodId", "0x095ea7b3")
                .append("functionName", "approve(address spender, uint256 amount)")
                .append("input", "0x095ea7b30000000000000000000000006ad2488743e93bfa35baf54c688de78c00bed9f0")));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for approve transaction from explorer tx map payload")
    void approveInExplorerTxMap_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xapprove-explorer-map");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        raw.put("value", "0");
        raw.put("explorer", new LinkedHashMap<>(Map.of(
                "tx", new LinkedHashMap<>(Map.of(
                        "methodId", "0x095ea7b3",
                        "functionName", "approve(address spender, uint256 amount)",
                        "input", "0x095ea7b30000000000000000000000006ad2488743e93bfa35baf54c688de78c00bed9f0"
                ))
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("drops known inbound spam fingerprint for promotional claim tx")
    void knownInboundSpamFingerprintDropsPromotionalClaimTx() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilterProperties.InboundSpamFingerprint fingerprint = new ScamFilterProperties.InboundSpamFingerprint();
        fingerprint.setNetworkId("UNICHAIN");
        fingerprint.setTokenContract("0x309aed2eebf9db4d7e51a7d2fdf557f7bbfe774a");
        fingerprint.setMethodId("0xbf3b75a3");
        props.setKnownInboundSpamFingerprints(List.of(fingerprint));
        ScamFilter f = new ScamFilter(props);

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xclaimable");
        tx.setNetworkId("UNICHAIN");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x1f98400000000000000000000000000000000004")
                .append("to", "0x1111111111111111111111111111111111111111")
                .append("methodId", "0xbf3b75a3")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x309aed2eebf9db4d7e51a7d2fdf557f7bbfe774a")
                                .append("from", "0x1f98400000000000000000000000000000000004")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Claimable: unichain-token.com")
                                .append("tokenName", "UNC")
                                .append("value", "1000000000000000000")
                ))));

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop wallet initiated tx by inbound spam fingerprint rule")
    void walletInitiatedClaimLikeTxIsNotDroppedByInboundSpamRule() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilterProperties.InboundSpamFingerprint fingerprint = new ScamFilterProperties.InboundSpamFingerprint();
        fingerprint.setNetworkId("UNICHAIN");
        fingerprint.setTokenContract("0x309aed2eebf9db4d7e51a7d2fdf557f7bbfe774a");
        fingerprint.setMethodId("0xbf3b75a3");
        props.setKnownInboundSpamFingerprints(List.of(fingerprint));
        ScamFilter f = new ScamFilter(props);

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-claim");
        tx.setNetworkId("UNICHAIN");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                .append("to", "0x1111111111111111111111111111111111111111")
                .append("methodId", "0xbf3b75a3")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x309aed2eebf9db4d7e51a7d2fdf557f7bbfe774a")
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Claimable: unichain-token.com")
                                .append("tokenName", "UNC")
                                .append("value", "1000000000000000000")
                ))));

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("drops promotional visit token before normalization")
    void promotionalVisitTokenDropsBeforeNormalization() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xvisit-spam");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x1111111111111111111111111111111111111111")
                .append("to", "0x2222222222222222222222222222222222222222")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xaa84c8b6cd6c57cd364510a5b05358da63133529")
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "wsETH Visit www.wseth.vip to claim reward")
                                .append("tokenName", "Wrapped Scam ETH")
                                .append("value", "1000000000000000000")
                ))));

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("drops voucher promo token before normalization")
    void voucherPromoTokenDropsBeforeNormalization() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xvoucher-spam");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x4444444444444444444444444444444444444444")
                .append("value", "0")
                .append("logs", List.of(
                        transferLog(
                                "0x72fdc6006cf1bce5898f1c484cfefc66486abd8a",
                                "0x3333333333333333333333333333333333333333",
                                "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f",
                                "0x1"
                        ),
                        transferLog(
                                "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174",
                                "0x3333333333333333333333333333333333333333",
                                "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f",
                                "0x2625a0"
                        )
                ))
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x72fdc6006cf1bce5898f1c484cfefc66486abd8a")
                                .append("from", "0x3333333333333333333333333333333333333333")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Swap your Voucher on wr.do/s/ether")
                                .append("tokenName", "Voucher")
                                .append("value", "1000000000000000000")
                ))));

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("wallet initiated claim-like tx still survives promotional pattern rule")
    void walletInitiatedClaimLikeTxStillSurvives() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-promo");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                .append("to", "0x5555555555555555555555555555555555555555")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x72fdc6006cf1bce5898f1c484cfefc66486abd8a")
                                .append("from", "0x5555555555555555555555555555555555555555")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Claim Your Airdrop")
                                .append("tokenName", "Reward")
                                .append("value", "1000000000000000000")
                ))));

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("promo dust mixed with legit inbound token survives")
    void promotionalDustMixedWithLegitInboundTokenSurvives() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xpromo-plus-legit");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x4444444444444444444444444444444444444444")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x72fdc6006cf1bce5898f1c484cfefc66486abd8a")
                                .append("from", "0x3333333333333333333333333333333333333333")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Swap your Voucher on wr.do/s/ether")
                                .append("tokenName", "Voucher")
                                .append("value", "1000000000000000000"),
                        new Document("contractAddress", "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174")
                                .append("from", "0x3333333333333333333333333333333333333333")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("value", "2500000")
                ))));

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("promo dust with native internal value survives")
    void promotionalDustWithNativeInternalValueSurvives() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xpromo-plus-native");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        tx.setRawData(new Document("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x4444444444444444444444444444444444444444")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x72fdc6006cf1bce5898f1c484cfefc66486abd8a")
                                .append("from", "0x3333333333333333333333333333333333333333")
                                .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("tokenSymbol", "Swap your Voucher on wr.do/s/ether")
                                .append("tokenName", "Voucher")
                                .append("value", "1000000000000000000")
                ))
                        .append("internalTransfers", List.of(
                                new Document("from", "0x4444444444444444444444444444444444444444")
                                        .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                        .append("value", "100000000000000000")
                        ))));

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for failed swap without transfer effects")
    void failedSwapWithoutTransferEffects_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x7b6ba6a6f9f9a84212684182bdc8ce590ab77160fef5c522ace2419c699f720e");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0x6a000f20005980200259b80c5102003040001068");
        raw.put("isError", "1");
        raw.put("txreceipt_status", "0");
        raw.put("methodId", "0xe3ead59e");
        raw.put("functionName", "swapExactAmountIn(address executor,tuple swapData,uint256 partnerAndFee,bytes permit,bytes executorData)");
        raw.put("explorer", new Document()
                .append("tokenTransfers", List.of())
                .append("internalTransfers", List.of()));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop successful swap when transfer effects exist")
    void successfulSwapWithTransferEffects_notDroppedByFailedSwapRule() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xswap-success");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0x6a000f20005980200259b80c5102003040001068");
        raw.put("isError", "0");
        raw.put("txreceipt_status", "1");
        raw.put("methodId", "0xe3ead59e");
        raw.put("functionName", "swapExactAmountIn(address executor,tuple swapData,uint256 partnerAndFee,bytes permit,bytes executorData)");
        raw.put("explorer", new Document()
                .append("tokenTransfers", List.of(
                        new Document("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("to", "0x6a000f20005980200259b80c5102003040001068")
                                .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("value", "16000000")))
                .append("internalTransfers", List.of()));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for failed zero-value call without transfer effects")
    void failedZeroValueWithoutTransferEffects_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xa88884a031efc35aebc4bb99fdda4b13fa0193a032fdbdbfd36c460c92f29cb5");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13");
        raw.put("status", "0x0");
        raw.put("isError", "1");
        raw.put("txreceipt_status", "0");
        raw.put("value", "0");
        raw.put("methodId", "0x374f435d");
        raw.put("functionName", "multicall(tuple[] bundle)");
        raw.put("explorer", new Document()
                .append("tokenTransfers", List.of())
                .append("internalTransfers", List.of()));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop failed zero-value call when transfer effects exist")
    void failedZeroValueWithTransferEffects_notDroppedByZeroValueRule() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xfailed-zero-with-effects");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        Document raw = new Document();
        raw.put("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        raw.put("to", "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13");
        raw.put("status", "0x0");
        raw.put("isError", "1");
        raw.put("txreceipt_status", "0");
        raw.put("value", "0");
        raw.put("methodId", "0x374f435d");
        raw.put("functionName", "multicall(tuple[] bundle)");
        raw.put("explorer", new Document()
                .append("tokenTransfers", List.of(
                        new Document("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("to", "0x9954afb60bb5a222714c478ac86990f221788b88")
                                .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("value", "1000000")))
                .append("internalTransfers", List.of()));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for explorer token spoofing pattern without logs")
    void explorerTokenSpoofingWithoutLogs_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xec6c8b6d1b86582a6c427dba498b56c63f62ec8d10daaccff6258c5eb7e60746");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0x68bca627d2207eb98d33bc9f9cc63ffe8b665b7f";
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", recipient);
        raw.put("methodId", "0x0cf79e0a");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("value", "0"),
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x06d81db8464bc020051acfea2d734e90da611e1a")
                        .append("tokenSymbol", "ꓴꓢꓓС")
                        .append("tokenName", "ꓴꓢꓓС")
                        .append("value", "305796700"),
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x06d81db8464bc020051acfea2d734e90da611e1a")
                        .append("tokenSymbol", "ꓴꓢꓓС")
                        .append("tokenName", "ꓴꓢꓓС")
                        .append("value", "10000000"),
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for explorer token spoofing when tx sender is not wallet")
    void explorerTokenSpoofingWhenSenderIsNotWallet_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xspoof-external-sender");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0x68bca627d2207eb98d33bc9f9cc63ffe8b665b7f";
        Document raw = new Document();
        raw.put("from", "0xaea174f87e0222701cf9962d0b29ab9b2e7d4110");
        raw.put("to", recipient);
        raw.put("methodId", "0x12514bba");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("value", "0"),
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x06d81db8464bc020051acfea2d734e90da611e1a")
                        .append("tokenSymbol", "ꓴꓢꓓС")
                        .append("tokenName", "ꓴꓢꓓС")
                        .append("value", "305796700")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for outbound zero-value spoof when tx sender is not wallet")
    void outboundZeroValueSpoof_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xoutbound-zero-value-spoof");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0xcd74a7b56aaaba5b19996e4149267ed7919b5dea";
        Document raw = new Document();
        raw.put("from", "0xaea174f87e0222701cf9962d0b29ab9b2e7d4110");
        raw.put("to", "0xc3236716cbdc725b518ac0a5d830fbadcfd05032");
        raw.put("value", "0");
        raw.put("methodId", "12514bba");
        raw.put("functionName", "transfer(uint256 amount)");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USDC")
                        .append("tokenDecimal", "6")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for outbound zero-value spoof when explorer payload is map")
    void outboundZeroValueSpoofWithMapExplorer_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xoutbound-zero-value-spoof-map");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0xcd74a7b56aaaba5b19996e4149267ed7919b5dea";

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("from", wallet);
        transfer.put("to", recipient);
        transfer.put("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        transfer.put("tokenSymbol", "USDC");
        transfer.put("tokenName", "USDC");
        transfer.put("tokenDecimal", "6");
        transfer.put("value", "0");

        Map<String, Object> explorer = new LinkedHashMap<>();
        explorer.put("tokenTransfers", List.of(transfer));

        Document raw = new Document();
        raw.put("from", "0xaea174f87e0222701cf9962d0b29ab9b2e7d4110");
        raw.put("to", "0xc3236716cbdc725b518ac0a5d830fbadcfd05032");
        raw.put("value", "0");
        raw.put("methodId", "12514bba");
        raw.put("functionName", "transfer(uint256 amount)");
        raw.put("explorer", explorer);
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for known zero-value spoofing fingerprint pattern")
    void knownZeroValueSpoofingFingerprint_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x035780202678618833c2cec4080d29ff9a5d42180df8bbc0da7de7472df8ad4f");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        Document raw = new Document();
        raw.put("from", "0x822c4d483e01ebac74330fd612d28716dc4c33d9");
        raw.put("to", "0x27117f7e48e07f9e23042931ab39fe02a62ec587");
        raw.put("value", "0");
        raw.put("methodId", "0x0cf79e0a");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", "0x9f8b715510c25c815cb189c144573d99f7c61b62")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop known spoofing fingerprint when tx is wallet-initiated")
    void knownZeroValueSpoofingFingerprint_walletInitiated_notDropped() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-initiated-known-fingerprint");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", "0x27117f7e48e07f9e23042931ab39fe02a62ec587");
        raw.put("value", "0");
        raw.put("methodId", "0x0cf79e0a");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", "0x9f8b715510c25c815cb189c144573d99f7c61b62")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not drop known spoofing fingerprint when transfer value is non-zero")
    void knownZeroValueSpoofingFingerprint_nonZeroTransfer_notDropped() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xknown-fingerprint-non-zero");
        tx.setNetworkId("AVALANCHE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        Document raw = new Document();
        raw.put("from", "0x00007915d1f9ff1d8a6d8b011af6f0ae204f7800");
        raw.put("to", "0xd743caa0ad523bbeba05c29b666d66e05f18094d");
        raw.put("value", "0");
        raw.put("methodId", "0xa9059cbb");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", "0x2ea84921448af2a15d4bc442fd7fb09dfdbbac6d")
                        .append("contractAddress", "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7")
                        .append("value", "1")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for known inbound spam fingerprint")
    void knownInboundSpamFingerprint_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        props.setKnownInboundSpamFingerprints(List.of(inboundSpamFingerprint(
                "POLYGON",
                "0x1665c36475e0e15484460bc0603ea47ec7d57064",
                "729ad39e"
        )));
        ScamFilter f = new ScamFilter(props);

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x09768aa81f61758bf98c02b846a658439c212db401cb45c835d79f87a6ebcac1");
        tx.setNetworkId("POLYGON");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");

        Document raw = new Document();
        raw.put("from", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174");
        raw.put("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");
        raw.put("methodId", "0x729ad39e");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174")
                        .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                        .append("contractAddress", "0x1665c36475e0e15484460bc0603ea47ec7d57064")
                        .append("tokenSymbol", "780 $UЅDС - Redeem: (t.ly/cpool) - #38")
                        .append("tokenName", "$UЅDС (t.ly/cpool) TOKEN DISTRIBUTION")
                        .append("tokenDecimal", "0")
                        .append("value", "1")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop known inbound spam fingerprint when wallet initiated")
    void knownInboundSpamFingerprint_walletInitiated_notDropped() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        props.setKnownInboundSpamFingerprints(List.of(inboundSpamFingerprint(
                "BASE",
                "0x1e358596f48420fe4cd147dcc850661632125e21",
                "0xa06c1a33"
        )));
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-initiated-inbound-spam-fingerprint");
        tx.setNetworkId("BASE");
        tx.setWalletAddress(wallet);

        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", "0x8888888884f8b3a2f807bcab71274a6a70064d2d");
        raw.put("methodId", "a06c1a33");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", "0x8888888884f8b3a2f807bcab71274a6a70064d2d")
                        .append("to", wallet)
                        .append("contractAddress", "0x1e358596f48420fe4cd147dcc850661632125e21")
                        .append("tokenSymbol", "Telegram @TronVanity88_bot")
                        .append("tokenName", "Telegram @TronVanity88_bot")
                        .append("tokenDecimal", "0")
                        .append("value", "8888")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not drop known inbound spam fingerprint when wallet also transfers out")
    void knownInboundSpamFingerprint_mixedWalletFlow_notDropped() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        props.setKnownInboundSpamFingerprints(List.of(inboundSpamFingerprint(
                "BASE",
                "0x1e358596f48420fe4cd147dcc850661632125e21",
                "a06c1a33"
        )));
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xmixed-wallet-flow-known-inbound-spam");
        tx.setNetworkId("BASE");
        tx.setWalletAddress(wallet);

        Document raw = new Document();
        raw.put("from", "0x8888888884f8b3a2f807bcab71274a6a70064d2d");
        raw.put("to", wallet);
        raw.put("methodId", "a06c1a33");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", "0x8888888884f8b3a2f807bcab71274a6a70064d2d")
                        .append("to", wallet)
                        .append("contractAddress", "0x1e358596f48420fe4cd147dcc850661632125e21")
                        .append("tokenSymbol", "Telegram @TronVanity88_bot")
                        .append("tokenName", "Telegram @TronVanity88_bot")
                        .append("tokenDecimal", "0")
                        .append("value", "8888"),
                new Document("from", wallet)
                        .append("to", "0x1111111111111111111111111111111111111111")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("value", "1000")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not drop wallet-initiated euler batch transaction")
    void walletInitiatedEulerBatch_notDropped() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xe3f3c0eaffcf6870c6e7860982b32e04017fe2addfbb9920691acae8664491f5");
        tx.setNetworkId("UNICHAIN");
        tx.setWalletAddress("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f");

        String wallet = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", "0x2a1176964f5d7cae5406b627bf6166664fe83c60");
        raw.put("value", "0");
        raw.put("methodId", "0xc16ae7a4");
        raw.put("functionName", "batch(tuple[] items)");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("contractAddress", "0x1b0e3da51b2517e09ae74cd31b708e46b9158e8b")
                        .append("value", "0"),
                new Document("from", "0x1b0e3da51b2517e09ae74cd31b708e46b9158e8b")
                        .append("to", wallet)
                        .append("contractAddress", "0xe9c43e09c5fa733bcc2aeaa96063a4a60147aa09")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for wallet-initiated zero-value spoof with long transfer calldata")
    void walletInitiatedZeroValueSpoofWithLongInput_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-initiated-zero-value-spoof");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0xcd74a7b56aaaba5b19996e4149267ed7919b5dea";
        String longInput = "0x12514bba" + "00".repeat(1500);

        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", recipient);
        raw.put("value", "0");
        raw.put("methodId", "12514bba");
        raw.put("functionName", "transfer(uint256 amount)");
        raw.put("input", longInput);
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USDC")
                        .append("tokenDecimal", "6")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop wallet-initiated zero-value transfer with short calldata")
    void walletInitiatedZeroValueTransferWithShortInput_notDropped() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-initiated-zero-value-short");
        tx.setNetworkId("BASE");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0xcd74a7b56aaaba5b19996e4149267ed7919b5dea";

        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", recipient);
        raw.put("value", "0");
        raw.put("methodId", "12514bba");
        raw.put("functionName", "transfer(uint256 amount)");
        raw.put("input", "0x12514bba00000000");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USDC")
                        .append("tokenDecimal", "6")
                        .append("value", "0")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not drop regular explorer token transfers without spoofing")
    void regularExplorerTokenTransfersWithoutSpoofing_notDropped() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xlegit-explorer-transfer");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String recipient = "0x68bca627d2207eb98d33bc9f9cc63ffe8b665b7f";
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", recipient);
        raw.put("methodId", "0xa9059cbb");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("value", "12000000")
        )));
        tx.setRawData(raw);

        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true when log address is in blocklist")
    void logAddressInBlocklist_returnsTrue() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xabc");
        tx.setNetworkId("ETHEREUM");
        Document raw = new Document();
        raw.put("to", "0xlegit");
        raw.put("from", "0xwallet");
        raw.put("logs", List.of(
                new Document("address", "0xphishing2222222222222222222222222222"),
                new Document("address", "0xother")
        ));
        tx.setRawData(raw);
        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns false when no address matches blocklist")
    void noMatch_returnsFalse() {
        RawTransaction tx = txWithTo("0xlegitcontract3333333333333333333333333333");
        assertThat(filter.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("blocklist check is case-insensitive")
    void caseInsensitive() {
        RawTransaction tx = txWithTo("0xSCAMCONTRACT1111111111111111111111111111");
        assertThat(filter.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for unsolicited mass airdrop spam pattern")
    void unsolicitedMassAirdropSpam_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0x2610b04b069754c36e11e03d5ad266c1bfcd4951";
        String fakeSender = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xspam");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0x06f191ee1fe6115caf61c728d7087a258a12b13e");
        raw.put("to", token);
        raw.put("logs", List.of(
                transferLog(token, fakeSender, wallet, "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000001", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000002", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000003", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000004", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000005", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000006", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000007", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000008", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000009", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000a", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000b", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000c", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000d", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000e", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x000000000000000000000000000000000000000f", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000010", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000011", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000012", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000013", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000014", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000015", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000016", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000017", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, fakeSender, "0x0000000000000000000000000000000000000018", "0x0000000000000000000000000000000000000000000000000000000000000005")
        ));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for unsolicited phishing airdrop token metadata")
    void unsolicitedPhishingAirdropMetadata_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0x2610b04b069754c36e11e03d5ad266c1bfcd4951";
        String transferSender = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String txSender = "0x06f191ee1fe6115caf61c728d7087a258a12b13e";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xphishing-airdrop");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", txSender);
        raw.put("to", token);
        raw.put("value", "0");
        raw.put("functionName", "Airdrop(address ad,address[] receivers,uint256 amount)");
        raw.put("logs", List.of(
                transferLog(token, transferSender, wallet, "0x0000000000000000000000000000000000000000000000000000000000000005")
        ));
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", transferSender)
                        .append("to", wallet)
                        .append("contractAddress", token)
                        .append("value", "5")
                        .append("tokenName", "\u200B ETH-Tokens.us")
                        .append("tokenSymbol", "Visit https://eth-tokens.us to claim Airdrop")
                        .append("tokenDecimal", "0")
                        .append("functionName", "Airdrop(address ad,address[] receivers,uint256 amount)")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not drop unsolicited inbound without phishing token text")
    void unsolicitedInboundWithoutPhishingText_notDropped() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
        String txSender = "0x06f191ee1fe6115caf61c728d7087a258a12b13e";
        String transferSender = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xunsolicited-legit-like");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", txSender);
        raw.put("to", token);
        raw.put("value", "0");
        raw.put("functionName", "transfer(address to,uint256 amount)");
        raw.put("logs", List.of(
                transferLog(token, transferSender, wallet, "0x0000000000000000000000000000000000000000000000000000000000000005")
        ));
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", transferSender)
                        .append("to", wallet)
                        .append("contractAddress", token)
                        .append("value", "5")
                        .append("tokenName", "USD Coin")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for unsolicited multicall mass airdrop pattern")
    void unsolicitedMulticallMassAirdrop_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String sender = "0x20aebd617d2b0c20e32dbc3042e23570a416daac";
        String token = "0xc4ec2d73a871e484affaf9f875f6c341d0112c50";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xunsolicited-multicall");
        tx.setNetworkId("BASE");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", sender);
        raw.put("to", wallet);
        raw.put("methodId", "ac9650d8");
        raw.put("functionName", "multicall(bytes[] data)");
        raw.put("input", repeatTransferSelectorInput(24));
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", sender)
                        .append("to", wallet)
                        .append("contractAddress", token)
                        .append("tokenSymbol", "MOLT")
                        .append("tokenName", "Molt king")
                        .append("tokenDecimal", "18")
                        .append("value", "1000000000000000000")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not mark as scam for unsolicited inbound with long but legit token name")
    void unsolicitedInboundLongLegitTokenName_notDropped() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String sender = "0x06f191ee1fe6115caf61c728d7087a258a12b13e";
        String token = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xlong-legit-name");
        tx.setNetworkId("BASE");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", sender);
        raw.put("to", wallet);
        raw.put("methodId", "0xa9059cbb");
        raw.put("functionName", "transfer(address to,uint256 amount)");
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", sender)
                        .append("to", wallet)
                        .append("contractAddress", token)
                        .append("tokenSymbol", "USDT")
                        .append("tokenName", "L2 Standard Bridged USDT (Base)")
                        .append("tokenDecimal", "6")
                        .append("value", "1000000")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not mark as scam when wallet initiated multicall transfer")
    void walletInitiatedMulticall_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xc4ec2d73a871e484affaf9f875f6c341d0112c50";
        String recipient = "0x00000000000000000000000000000000000000aa";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xwallet-multicall");
        tx.setNetworkId("BASE");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", token);
        raw.put("methodId", "0xac9650d8");
        raw.put("functionName", "multicall(bytes[] data)");
        raw.put("input", repeatTransferSelectorInput(24));
        raw.put("explorer", new Document("tokenTransfers", List.of(
                new Document("from", wallet)
                        .append("to", recipient)
                        .append("contractAddress", token)
                        .append("tokenSymbol", "MOLT")
                        .append("tokenName", "Molt king")
                        .append("tokenDecimal", "18")
                        .append("value", "1000000000000000000")
        )));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not mark as scam when wallet initiated batch transfer")
    void walletInitiatedBatchTransfer_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0x2610b04b069754c36e11e03d5ad266c1bfcd4951";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xlegit");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", wallet);
        raw.put("to", token);
        raw.put("logs", List.of(
                transferLog(token, wallet, "0x0000000000000000000000000000000000000001", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000002", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000003", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000004", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000005", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000006", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000007", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000008", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000009", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000a", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000b", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000c", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000d", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000e", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x000000000000000000000000000000000000000f", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000010", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000011", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000012", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000013", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, wallet, "0x0000000000000000000000000000000000000014", "0x0000000000000000000000000000000000000000000000000000000000000005")
        ));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not mark as scam when transfer sender equals tx sender")
    void transferSenderEqualsTxSender_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0x2610b04b069754c36e11e03d5ad266c1bfcd4951";
        String sender = "0x06f191ee1fe6115caf61c728d7087a258a12b13e";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xlegit2");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", sender);
        raw.put("to", token);
        raw.put("logs", List.of(
                transferLog(token, sender, wallet, "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000001", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000002", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000003", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000004", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000005", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000006", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000007", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000008", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000009", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000a", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000b", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000c", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000d", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000e", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x000000000000000000000000000000000000000f", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000010", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000011", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000012", "0x0000000000000000000000000000000000000000000000000000000000000005"),
                transferLog(token, sender, "0x0000000000000000000000000000000000000013", "0x0000000000000000000000000000000000000000000000000000000000000005")
        ));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true for relay sweep spam when wallet is only transfer sender")
    void relaySweepSpam_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xrelay-spam");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        raw.put("to", "0x27117f7e48e07f9e23042931ab39fe02a62ec587");
        List<Document> logs = new ArrayList<>();
        logs.add(transferLog(token, wallet, "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "0x1"));
        for (int i = 1; i < 35; i++) {
            String from = hexAddress(i + 100);
            String to = hexAddress(i + 200);
            logs.add(transferLog(token, from, to, "0x1"));
        }
        raw.put("logs", logs);
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for relay sweep spam when logs are plain maps")
    void relaySweepSpamWithMapLogs_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xrelay-spam-map-logs");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        raw.put("to", "0x27117f7e48e07f9e23042931ab39fe02a62ec587");
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(mapTransferLog(token, wallet, "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "0x1"));
        for (int i = 1; i < 35; i++) {
            String from = hexAddress(i + 100);
            String to = hexAddress(i + 200);
            logs.add(mapTransferLog(token, from, to, "0x1"));
        }
        raw.put("logs", logs);
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("returns true for zero amount poisoning spam")
    void zeroAmountPoisoningSpam_returnsTrue() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xzero-poison");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0xcccccccccccccccccccccccccccccccccccccccc");
        raw.put("to", "0x27117f7e48e07f9e23042931ab39fe02a62ec587");
        List<Document> logs = new ArrayList<>();
        logs.add(transferLog(token, "0xdddddddddddddddddddddddddddddddddddddddd", wallet, "0x0"));
        for (int i = 1; i < 120; i++) {
            String from = hexAddress(i + 300);
            String to = hexAddress((i % 10) + 400);
            logs.add(transferLog(token, from, to, "0x0"));
        }
        raw.put("logs", logs);
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isTrue();
    }

    @Test
    @DisplayName("does not mark as scam for low-fanout relay transfer")
    void lowFanoutRelay_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xrelay-legit");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        raw.put("to", "0xbbbfd134e9b44bfb5123898ba36b01de7ab93d98");
        List<Document> logs = new ArrayList<>();
        logs.add(transferLog(token, "0xffffffffffffffffffffffffffffffffffffffff", wallet, "0x2"));
        for (int i = 1; i < 35; i++) {
            String from = hexAddress((i % 12) + 500);
            String to = hexAddress((i % 14) + 600);
            String amount = i % 2 == 0 ? "0x1" : "0x2";
            logs.add(transferLog(token, from, to, amount));
        }
        raw.put("logs", logs);
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not mark as scam for unsolicited relay payout with few transfer logs")
    void unsolicitedRelayPayoutFewTransfers_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String relay = "0xf5042e6ffac5a625d4e7848e0b01373d8eb9e222";
        String weth = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";
        String sender = "0xf70da97812cb96acdf810712aa562db8dfa3dbef";
        String amount = "0x00000000000000000000000000000000000000000000000000261a944954f754";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xfeca-like");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", sender);
        raw.put("to", relay);
        raw.put("logs", List.of(
                transferLog(weth, "0x0000000000000000000000000000000000000000", relay, amount),
                customLog(relay, "0x93485dcd31a905e3ffd7b012abe3438fa8fa77f98ddc9f50e879d3fa7ccdc324"),
                transferLog(weth, relay, relay, amount),
                customLog(relay, "0x93485dcd31a905e3ffd7b012abe3438fa8fa77f98ddc9f50e879d3fa7ccdc324"),
                transferLog(weth, relay, wallet, amount),
                customLog(relay, "0x93485dcd31a905e3ffd7b012abe3438fa8fa77f98ddc9f50e879d3fa7ccdc324")
        ));
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    @Test
    @DisplayName("does not mark as scam for unsolicited complex settlement below fanout threshold")
    void unsolicitedComplexSettlement_notScam() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(true);
        ScamFilter f = new ScamFilter(props);

        String wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String token = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1";

        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x2a158-like");
        tx.setNetworkId("ARBITRUM");
        tx.setWalletAddress(wallet);
        Document raw = new Document();
        raw.put("from", "0x5f4ac8e6f6f8f7abf8b91f4a1dc7e7c6f908ad2f");
        raw.put("to", "0xbbbfd134e9b44bfb5123898ba36b01de7ab93d98");

        List<Document> logs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String from = hexAddress(i + 1000);
            String to = hexAddress((i % 16) + 2000); // unique recipients stays below fanout threshold
            logs.add(transferLog(token, from, to, "0x2"));
        }
        logs.add(transferLog(token, hexAddress(3001), wallet, "0x3"));
        logs.add(customLog(hexAddress(4001), "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"));
        logs.add(customLog(hexAddress(4002), "0x93485dcd31a905e3ffd7b012abe3438fa8fa77f98ddc9f50e879d3fa7ccdc324"));
        raw.put("logs", logs);
        tx.setRawData(raw);

        assertThat(f.shouldDrop(tx)).isFalse();
    }

    private static RawTransaction txWithTo(String to) {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xabc");
        tx.setNetworkId("ETHEREUM");
        Document raw = new Document("to", to);
        raw.put("from", "0xwallet");
        tx.setRawData(raw);
        return tx;
    }

    private static ScamFilterProperties.InboundSpamFingerprint inboundSpamFingerprint(
            String networkId, String tokenContract, String methodId
    ) {
        ScamFilterProperties.InboundSpamFingerprint fingerprint =
                new ScamFilterProperties.InboundSpamFingerprint();
        fingerprint.setNetworkId(networkId);
        fingerprint.setTokenContract(tokenContract);
        fingerprint.setMethodId(methodId);
        return fingerprint;
    }

    private static Document transferLog(String token, String from, String to, String amount) {
        return new Document("address", token)
                .append("topics", List.of(
                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                        addressTopic(from),
                        addressTopic(to)
                ))
                .append("data", amount);
    }

    private static Document customLog(String address, String topic0) {
        return new Document("address", address)
                .append("topics", List.of(topic0))
                .append("data", "0x0");
    }

    private static Map<String, Object> mapTransferLog(String token, String from, String to, String amount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("address", token);
        out.put("topics", List.of(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                addressTopic(from),
                addressTopic(to)
        ));
        out.put("data", amount);
        return out;
    }

    private static String addressTopic(String address) {
        String lower = address.toLowerCase();
        String hex = lower.startsWith("0x") ? lower.substring(2) : lower;
        return "0x000000000000000000000000" + hex;
    }

    private static String hexAddress(int seed) {
        return String.format("0x%040x", seed);
    }

    private static String repeatTransferSelectorInput(int count) {
        StringBuilder sb = new StringBuilder("0xac9650d8");
        for (int i = 0; i < count; i++) {
            sb.append("00000000a9059cbb");
        }
        return sb.toString();
    }
}
