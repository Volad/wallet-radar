package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import com.walletradar.integration.IntegrationBackfillPlanner;
import com.walletradar.application.cex.config.DzengiIntegrationProperties;
import com.walletradar.application.cex.config.IntegrationBackfillProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces Dzengi-specific segment specs for the shared integration backfill planner.
 */
@Service
@RequiredArgsConstructor
public class DzengiBackfillSegmentPlanner implements IntegrationBackfillPlanner {

    private final DzengiIntegrationProperties dzengiIntegrationProperties;
    private final IntegrationBackfillProperties integrationBackfillProperties;
    private final DzengiApiClient dzengiApiClient;
    private final DzengiSymbolMetadataCache symbolMetadataCache;
    private final IntegrationRawEventRepository integrationRawEventRepository;
    private final DzengiLiveBalanceService dzengiLiveBalanceService;

    @Override
    public boolean supports(UserSession.IntegrationProvider provider) {
        return provider == UserSession.IntegrationProvider.DZENGI;
    }

    @Override
    public List<BackfillSegment> planInitialBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant plannedAt
    ) {
        Instant anchor = (plannedAt == null ? Instant.now() : plannedAt).truncatedTo(ChronoUnit.SECONDS);
        Instant from = anchor.minus(integrationBackfillProperties.getHistoryYears() * 365L, ChronoUnit.DAYS);
        return planSegments(sessionId, integration, from, anchor, plannedAt);
    }

    @Override
    public List<BackfillSegment> planIncrementalBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant from,
            Instant to,
            Instant plannedAt
    ) {
        Instant effectiveTo = (to == null ? Instant.now() : to).truncatedTo(ChronoUnit.SECONDS);
        Instant effectiveFrom = from == null ? effectiveTo : from.truncatedTo(ChronoUnit.SECONDS);
        if (!effectiveFrom.isBefore(effectiveTo)) {
            return List.of();
        }
        return planSegments(sessionId, integration, effectiveFrom, effectiveTo, plannedAt);
    }

    private List<BackfillSegment> planSegments(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant from,
            Instant to,
            Instant plannedAt
    ) {
        List<BackfillSegment> segments = new ArrayList<>();
        int index = 0;
        index = addTimeRangeSegments(sessionId, integration, DzengiIntegrationStream.LEDGER.name(), from, to,
                dzengiIntegrationProperties.getLedgerWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, DzengiIntegrationStream.DEPOSITS.name(), from, to,
                dzengiIntegrationProperties.getDepositsWithdrawalsWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, DzengiIntegrationStream.WITHDRAWALS.name(), from, to,
                dzengiIntegrationProperties.getDepositsWithdrawalsWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, DzengiIntegrationStream.TRADING_POSITIONS_HISTORY.name(), from, to,
                dzengiIntegrationProperties.getTradingPositionsWindowDays(), index, segments, plannedAt);
        segments.add(singleSegment(sessionId, integration, DzengiIntegrationStream.EXCHANGE_INFO.name(), index++, plannedAt));
        symbolMetadataCache.refreshAll();
        for (String symbol : discoverTradeSymbols(integration)) {
            String stream = "MY_TRADES:" + symbol;
            segments.add(buildSegment(sessionId, integration, stream, index++, from, to, plannedAt));
        }
        for (String symbol : discoverEquitySymbolsV2(integration)) {
            String stream = "MY_TRADES_V2:" + symbol;
            segments.add(buildSegment(sessionId, integration, stream, index++, from, to, plannedAt));
        }
        return List.copyOf(segments);
    }

    private Set<String> discoverTradeSymbols(UserSession.SessionIntegration integration) {
        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(discoverSymbolsFromPositionHistory(integration.getIntegrationId()));
        symbols.addAll(discoverSymbolsFromExchangeInfo());
        return symbols;
    }

    private Set<String> discoverSymbolsFromPositionHistory(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Set.of();
        }
        return integrationRawEventRepository.findByIntegrationIdAndStream(
                        integrationId,
                        DzengiIntegrationStream.TRADING_POSITIONS_HISTORY.name()
                ).stream()
                .map(this::symbolFromRawEvent)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .filter(symbol -> !symbol.endsWith("_LEVERAGE"))
                .filter(symbol -> !symbol.endsWith("."))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String symbolFromRawEvent(IntegrationRawEvent rawEvent) {
        if (rawEvent == null || rawEvent.getPayload() == null) {
            return null;
        }
        Object symbol = rawEvent.getPayload().get("symbol");
        return symbol == null ? null : String.valueOf(symbol);
    }
    private Set<String> discoverEquitySymbolsV2(UserSession.SessionIntegration integration) {
        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(discoverEquitySymbolsFromExchangeInfo());
        symbols.addAll(discoverEquitySymbolsFromPositionHistory(integration.getIntegrationId()));
        symbols.addAll(dzengiIntegrationProperties.getMyTradesV2AdditionalSymbols().stream()
                .map(DzengiBackfillSegmentPlanner::stripTrailingDot)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .toList());
        symbols.addAll(discoverEquitySymbolsFromLiveBalances(integration.getIntegrationId()));
        return symbols;
    }

    /**
     * Dynamic symbol discovery from live Dzengi balances: any asset symbol with qty &gt; 0 in
     * {@code dzengi_live_balances} is included as a MY_TRADES_V2 target. This is the primary
     * catch-all for tickers not yet covered by exchangeInfo (e.g. newly listed equities) or
     * position history (e.g. first-time buys since last backfill). No config needed.
     */
    private Set<String> discoverEquitySymbolsFromLiveBalances(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Set.of();
        }
        Map<String, BigDecimal> umbrella = dzengiLiveBalanceService.getUmbrellaBalances(integrationId);
        if (umbrella.isEmpty()) {
            return Set.of();
        }
        Set<String> symbols = new LinkedHashSet<>();
        for (Map.Entry<String, BigDecimal> entry : umbrella.entrySet()) {
            String sym = entry.getKey();
            if (sym == null || sym.isBlank()) {
                continue;
            }
            if ("USD".equalsIgnoreCase(sym) || "BYN".equalsIgnoreCase(sym)
                    || "USDT".equalsIgnoreCase(sym) || "USDC".equalsIgnoreCase(sym)) {
                continue;
            }
            BigDecimal qty = entry.getValue();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            symbols.add(sym.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return symbols;
    }

    private Set<String> discoverEquitySymbolsFromExchangeInfo() {
        Set<String> symbols = new LinkedHashSet<>();
        JsonNode rows = dzengiApiClient.fetchExchangeInfo().path("symbols");
        if (!rows.isArray()) {
            return symbols;
        }
        for (JsonNode row : rows) {
            String status = upper(row.path("status").asText(""));
            if (!"TRADING".equals(status)) {
                continue;
            }
            String symbol = row.path("symbol").asText(null);
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String marketType = upper(row.path("marketType").asText("SPOT"));
            if (!"LEVERAGE".equals(marketType) && !symbol.endsWith(".")) {
                continue;
            }
            String assetType = upper(row.path("assetType").asText(""));
            if (!isV2EquityAssetType(assetType)) {
                continue;
            }
            String v2Symbol = stripTrailingDot(symbol);
            if (!v2Symbol.isBlank()) {
                symbols.add(v2Symbol);
            }
        }
        return symbols;
    }

    private Set<String> discoverEquitySymbolsFromPositionHistory(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Set.of();
        }
        return integrationRawEventRepository.findByIntegrationIdAndStream(
                        integrationId,
                        DzengiIntegrationStream.TRADING_POSITIONS_HISTORY.name()
                ).stream()
                .map(this::symbolFromRawEvent)
                .filter(symbol -> symbol != null && symbol.endsWith("."))
                .map(DzengiBackfillSegmentPlanner::stripTrailingDot)
                .filter(symbol -> !symbol.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isV2EquityAssetType(String assetType) {
        return "EQUITY".equals(assetType) || "COMMODITY".equals(assetType) || "INDEX".equals(assetType);
    }

    private static String stripTrailingDot(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        return symbol.endsWith(".") ? symbol.substring(0, symbol.length() - 1) : symbol;
    }

    private Set<String> discoverSymbolsFromExchangeInfo() {
        Set<String> allowedQuotes = dzengiIntegrationProperties.getMyTradesQuoteAssets().stream()
                .map(DzengiBackfillSegmentPlanner::upper)
                .filter(quote -> quote != null && !quote.isBlank())
                .collect(Collectors.toSet());
        Set<String> symbols = new LinkedHashSet<>();
        JsonNode rows = dzengiApiClient.fetchExchangeInfo().path("symbols");
        if (!rows.isArray()) {
            return symbols;
        }
        for (JsonNode row : rows) {
            String status = upper(row.path("status").asText(""));
            if (!"TRADING".equals(status)) {
                continue;
            }
            String symbol = row.path("symbol").asText(null);
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String marketType = upper(row.path("marketType").asText("SPOT"));
            if ("LEVERAGE".equals(marketType) || symbol.endsWith("_LEVERAGE") || symbol.endsWith(".")) {
                // Leverage/CFD variants are captured via TRADING_POSITIONS_HISTORY, not myTrades.
                continue;
            }
            String quoteAsset = upper(row.path("quoteAsset").asText(""));
            if (!allowedQuotes.isEmpty() && !allowedQuotes.contains(quoteAsset)) {
                continue;
            }
            symbols.add(symbol);
        }
        return symbols;
    }

    private int addTimeRangeSegments(
            String sessionId,
            UserSession.SessionIntegration integration,
            String stream,
            Instant from,
            Instant to,
            int windowDays,
            int startIndex,
            List<BackfillSegment> target,
            Instant plannedAt
    ) {
        Instant cursor = from;
        int index = startIndex;
        while (cursor.isBefore(to)) {
            Instant segmentEnd = cursor.plus(windowDays, ChronoUnit.DAYS);
            if (segmentEnd.isAfter(to)) {
                segmentEnd = to;
            }
            target.add(buildSegment(sessionId, integration, stream, index, cursor, segmentEnd, plannedAt));
            index++;
            cursor = segmentEnd;
        }
        return index;
    }

    private BackfillSegment singleSegment(
            String sessionId,
            UserSession.SessionIntegration integration,
            String stream,
            int index,
            Instant plannedAt
    ) {
        Instant now = plannedAt == null ? Instant.now() : plannedAt;
        return buildSegment(sessionId, integration, stream, index, now, now, plannedAt);
    }

    private BackfillSegment buildSegment(
            String sessionId,
            UserSession.SessionIntegration integration,
            String stream,
            int index,
            Instant from,
            Instant to,
            Instant plannedAt
    ) {
        BackfillSegment segment = new BackfillSegment();
        segment.setId(integration.getIntegrationId() + ":" + stream + ":" + index);
        segment.setSessionId(sessionId);
        segment.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);
        segment.setSegmentKind(BackfillSegment.SegmentKind.TIME_RANGE);
        segment.setIntegrationId(integration.getIntegrationId());
        segment.setProvider(UserSession.IntegrationProvider.DZENGI.name());
        segment.setAccountRef(integration.getAccountRef());
        segment.setStream(stream);
        segment.setSegmentIndex(index);
        segment.setFromTime(from);
        segment.setToTime(to);
        segment.setStatus(BackfillSegment.SegmentStatus.PENDING);
        segment.setProgressPct(0);
        segment.setProcessedCount(0L);
        segment.setRetryCount(0);
        segment.setUpdatedAt(plannedAt == null ? Instant.now() : plannedAt);
        return segment;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
