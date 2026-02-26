package com.walletradar.ingestion.filter;

import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ScamFilterProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static RawTransaction txWithTo(String to) {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xabc");
        tx.setNetworkId("ETHEREUM");
        Document raw = new Document("to", to);
        raw.put("from", "0xwallet");
        tx.setRawData(raw);
        return tx;
    }
}
