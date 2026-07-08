package com.walletradar.application.cex.port;

/**
 * Design-ready ledger pagination contract (Track B1). Pages immutable extracted evidence
 * for one venue stream × account scope. Acquisition writes raw/extracted collections only.
 *
 * <p>Bybit reference: {@code BybitExtractionService} / {@code PendingBybitExtractedRowQueryService}
 * will migrate behind this SPI (A2).
 */
public interface CexLedgerSource {

    CexVenueProfile venueProfile();

    /** Stream within venue, e.g. {@code FUNDING_HISTORY}. */
    String streamId();

    /**
     * Fetch the next page of ledger events. {@code cursor} is opaque to consumers;
     * {@code null} starts at the beginning of the requested window.
     */
    CexLedgerPage fetchPage(CexLedgerCursor cursor);

    /** Opaque pagination cursor for {@link #fetchPage(CexLedgerCursor)}. */
    record CexLedgerCursor(String value) {
    }

    /** One page of extracted ledger events. */
    record CexLedgerPage(java.util.List<CexLedgerEvent> events, CexLedgerCursor nextCursor) {
    }
}
