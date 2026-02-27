package com.walletradar.ingestion.filter;

import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        assertThat(f.isScam(tx)).isFalse();
    }

    @Test
    @DisplayName("returns false when disabled")
    void disabled_returnsFalse() {
        ScamFilterProperties props = new ScamFilterProperties();
        props.setEnabled(false);
        props.setBlocklist(List.of("0xscamcontract1111111111111111111111111111"));
        ScamFilter f = new ScamFilter(props);
        RawTransaction tx = txWithTo("0xscamcontract1111111111111111111111111111");
        assertThat(f.isScam(tx)).isFalse();
    }

    @Test
    @DisplayName("returns true when tx to address is in blocklist")
    void toAddressInBlocklist_returnsTrue() {
        RawTransaction tx = txWithTo("0xscamcontract1111111111111111111111111111");
        assertThat(filter.isScam(tx)).isTrue();
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
        assertThat(filter.isScam(tx)).isTrue();
    }

    @Test
    @DisplayName("returns false when no address matches blocklist")
    void noMatch_returnsFalse() {
        RawTransaction tx = txWithTo("0xlegitcontract3333333333333333333333333333");
        assertThat(filter.isScam(tx)).isFalse();
    }

    @Test
    @DisplayName("blocklist check is case-insensitive")
    void caseInsensitive() {
        RawTransaction tx = txWithTo("0xSCAMCONTRACT1111111111111111111111111111");
        assertThat(filter.isScam(tx)).isTrue();
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

        assertThat(f.isScam(tx)).isTrue();
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

        assertThat(f.isScam(tx)).isFalse();
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

        assertThat(f.isScam(tx)).isFalse();
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

        assertThat(f.isScam(tx)).isTrue();
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

        assertThat(f.isScam(tx)).isTrue();
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

        assertThat(f.isScam(tx)).isFalse();
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

    private static Document transferLog(String token, String from, String to, String amount) {
        return new Document("address", token)
                .append("topics", List.of(
                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                        addressTopic(from),
                        addressTopic(to)
                ))
                .append("data", amount);
    }

    private static String addressTopic(String address) {
        String lower = address.toLowerCase();
        String hex = lower.startsWith("0x") ? lower.substring(2) : lower;
        return "0x000000000000000000000000" + hex;
    }

    private static String hexAddress(int seed) {
        return String.format("0x%040x", seed);
    }
}
