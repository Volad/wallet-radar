package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.cex.acquisition.venue.bybit.BybitIntegrationStream;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * ADR-058 A7/A8 / C.2: deterministic observability-only attribution of {@code EXECUTION_SPOT} fills
 * to the per-uid Bybit Trading-Bot compartment.
 *
 * <p>For each uid that has {@code bybitType=Bot} {@code FUNDING_HISTORY} rows, the bot session window
 * is {@code [firstToBot, lastFromBot]}. Every {@code EXECUTION_SPOT} fill for that uid inside the
 * window is re-tagged from {@code :UTA} to the {@code :BOT} compartment. This is a normalization-stage
 * bulk pass (bounded aggregation + targeted update) rather than a per-fill query in the hot extraction
 * loop; it is idempotent (rows already ending {@code :BOT} are never re-tagged) and financially inert
 * ({@code :BOT} collapses to the {@code BYBIT:<uid>} umbrella exactly like {@code :UTA}, so the replay
 * position key is unchanged — a mis-tag on a mixed account can never move AVCO, ADR-058 risk row).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitBotExecutionAttributionService {

    private static final String UTA_SUFFIX = ":UTA";
    private static final String BOT_SUFFIX = ":BOT";

    private final MongoOperations mongoOperations;

    public int tagBotWindowExecutions() {
        List<BotWindow> windows = loadBotWindows();
        if (windows.isEmpty()) {
            return 0;
        }
        int tagged = 0;
        for (BotWindow window : windows) {
            tagged += tagWindow(window);
        }
        if (tagged > 0) {
            log.info("BYBIT_BOT_EXECUTION_ATTRIBUTION windows={} tagged={}", windows.size(), tagged);
        }
        return tagged;
    }

    private List<BotWindow> loadBotWindows() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(new Criteria().andOperator(
                        Criteria.where("sourceStream").is(BybitIntegrationStream.FUNDING_HISTORY.name()),
                        Criteria.where("bybitType").is("Bot"),
                        Criteria.where("uid").ne(null),
                        Criteria.where("timeUtc").ne(null)
                )),
                Aggregation.group("uid")
                        .min("timeUtc").as("firstTs")
                        .max("timeUtc").as("lastTs")
        );
        AggregationResults<BotWindow> results =
                mongoOperations.aggregate(aggregation, BybitExtractedEvent.class, BotWindow.class);
        return results.getMappedResults();
    }

    private int tagWindow(BotWindow window) {
        if (window == null || window.getId() == null || window.getFirstTs() == null || window.getLastTs() == null) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("uid").is(window.getId()),
                Criteria.where("sourceStream").is(BybitIntegrationStream.EXECUTION_SPOT.name()),
                Criteria.where("timeUtc").gte(window.getFirstTs()).lte(window.getLastTs())
        ));
        List<BybitExtractedEvent> fills = mongoOperations.find(query, BybitExtractedEvent.class);
        int tagged = 0;
        for (BybitExtractedEvent fill : fills) {
            if (!withinWindow(fill.getTimeUtc(), window)) {
                continue;
            }
            String walletRef = fill.getWalletRef();
            if (walletRef == null) {
                continue;
            }
            String trimmed = walletRef.trim();
            if (!trimmed.toUpperCase(Locale.ROOT).endsWith(UTA_SUFFIX)) {
                continue;
            }
            fill.setWalletRef(trimmed.substring(0, trimmed.length() - UTA_SUFFIX.length()) + BOT_SUFFIX);
            mongoOperations.save(fill);
            tagged++;
        }
        return tagged;
    }

    private static boolean withinWindow(Instant ts, BotWindow window) {
        return ts != null
                && !ts.isBefore(window.getFirstTs())
                && !ts.isAfter(window.getLastTs());
    }

    /** Aggregation projection: {@code _id} is the uid, plus the bot session window bounds. */
    public static class BotWindow {
        private String id;
        private Instant firstTs;
        private Instant lastTs;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Instant getFirstTs() {
            return firstTs;
        }

        public void setFirstTs(Instant firstTs) {
            this.firstTs = firstTs;
        }

        public Instant getLastTs() {
            return lastTs;
        }

        public void setLastTs(Instant lastTs) {
            this.lastTs = lastTs;
        }
    }
}
