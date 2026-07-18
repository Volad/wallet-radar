package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEventStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds Dzengi-specific extracted staging rows from immutable integration raw events.
 */
@Service
@RequiredArgsConstructor
public class DzengiExtractionService {

    private static final String DZENGI_PREFIX = "DZENGI:";

    private final ObjectMapper objectMapper;
    private final DzengiSymbolMetadataCache symbolMetadataCache;

    public List<DzengiExtractedEvent> extract(IntegrationRawEvent rawEvent) {
        if (rawEvent == null || rawEvent.getPayload() == null || rawEvent.getStream() == null) {
            return List.of();
        }
        String stream = rawEvent.getStream();
        if (stream.startsWith("MY_TRADES_V2:")) {
            return List.of(extractMyTradeV2(rawEvent, stream.substring("MY_TRADES_V2:".length())));
        }
        if (stream.startsWith("MY_TRADES:")) {
            return List.of(extractMyTrade(rawEvent, stream.substring("MY_TRADES:".length())));
        }
        DzengiIntegrationStream integrationStream;
        try {
            integrationStream = DzengiIntegrationStream.valueOf(stream);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        JsonNode payload = objectMapper.convertValue(rawEvent.getPayload(), JsonNode.class);
        return switch (integrationStream) {
            case LEDGER -> List.of(extractLedger(rawEvent, payload));
            case DEPOSITS -> List.of(extractDeposit(rawEvent, payload));
            case WITHDRAWALS -> List.of(extractWithdrawal(rawEvent, payload));
            case TRADING_POSITIONS_HISTORY -> List.of(extractTradingPosition(rawEvent, payload));
            case EXCHANGE_INFO, MY_TRADES, MY_TRADES_V2 -> List.of();
        };
    }

    private DzengiExtractedEvent extractLedger(IntegrationRawEvent rawEvent, JsonNode payload) {
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        String type = upper(text(payload, "type"));
        event.setDzengiType(type);
        event.setAssetSymbol(upper(text(payload, "currency")));
        event.setQuantityRaw(decimal(payload, "amount"));
        event.setCommission(decimal(payload, "commission"));
        event.setPaymentMethod(text(payload, "paymentMethod"));
        event.setCanonicalType(mapLedgerCanonicalType(type));
        event.setBasisRelevant(isLedgerBasisRelevant(type));
        return event;
    }

    private DzengiExtractedEvent extractDeposit(IntegrationRawEvent rawEvent, JsonNode payload) {
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        event.setDzengiType("deposit");
        event.setAssetSymbol(upper(text(payload, "currency")));
        event.setQuantityRaw(decimal(payload, "amount"));
        event.setCommission(decimal(payload, "commission"));
        event.setPaymentMethod(text(payload, "paymentMethod"));
        event.setTxHash(lower(text(payload, "blockchainTransactionHash")));
        event.setCanonicalType("EXTERNAL_TRANSFER_IN");
        event.setBasisRelevant(true);
        return event;
    }

    private DzengiExtractedEvent extractWithdrawal(IntegrationRawEvent rawEvent, JsonNode payload) {
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        event.setDzengiType("withdrawal");
        event.setAssetSymbol(upper(text(payload, "currency")));
        BigDecimal amount = decimal(payload, "amount");
        event.setQuantityRaw(amount == null ? null : amount.abs().negate());
        event.setCommission(decimal(payload, "commission"));
        event.setPaymentMethod(text(payload, "paymentMethod"));
        event.setTxHash(lower(text(payload, "blockchainTransactionHash")));
        event.setCanonicalType("EXTERNAL_TRANSFER_OUT");
        event.setBasisRelevant(true);
        return event;
    }

    private DzengiExtractedEvent extractMyTradeV2(IntegrationRawEvent rawEvent, String symbol) {
        JsonNode payload = objectMapper.convertValue(rawEvent.getPayload(), JsonNode.class);
        String metadataSymbol = symbol.endsWith(".") ? symbol : symbol + ".";
        DzengiSymbolMetadataCache.SymbolMetadata metadata = symbolMetadataCache.resolve(metadataSymbol);
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        event.setTradingSymbol(symbol);
        event.setMarketType(metadata.marketType());
        event.setAssetType(metadata.assetType());
        boolean buyer = payload.path("isBuyer").asBoolean(payload.path("buyer").asBoolean(false));
        event.setIsBuyer(buyer);
        event.setPrice(decimal(payload, "price"));
        BigDecimal qty = decimal(payload, "qty");
        String base = stripTrailingDot(metadata.baseAsset() != null ? metadata.baseAsset() : symbol);
        String quote = metadata.quoteAsset();
        if (quote == null || quote.isBlank()) {
            quote = "USD";
        }
        event.setAssetSymbol(base);
        event.setQuoteAsset(quote);
        event.setQuantityRaw(buyer ? qty : (qty == null ? null : qty.negate()));
        event.setCommission(decimal(payload, "commission"));
        event.setCommissionAsset(upper(text(payload, "commissionAsset")));
        event.setDzengiType(buyer ? "BUY" : "SELL");
        event.setCanonicalType(buyer ? "BUY" : "SELL");
        event.setBasisRelevant(true);
        return event;
    }

    private DzengiExtractedEvent extractMyTrade(IntegrationRawEvent rawEvent, String symbol) {
        JsonNode payload = objectMapper.convertValue(rawEvent.getPayload(), JsonNode.class);
        DzengiSymbolMetadataCache.SymbolMetadata metadata = symbolMetadataCache.resolve(symbol);
        if (metadata.leverageOrCfd()) {
            return excluded(rawEvent, payload, "LEVERAGE_FILL_EXCLUDED");
        }
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        event.setTradingSymbol(symbol);
        event.setMarketType(metadata.marketType());
        event.setAssetType(metadata.assetType());
        boolean buyer = payload.path("isBuyer").asBoolean(payload.path("buyer").asBoolean(false));
        event.setIsBuyer(buyer);
        event.setPrice(decimal(payload, "price"));
        BigDecimal qty = decimal(payload, "qty");
        String base = metadata.baseAsset();
        String quote = metadata.quoteAsset();
        event.setAssetSymbol(base);
        event.setQuoteAsset(quote);
        event.setQuantityRaw(buyer ? qty : (qty == null ? null : qty.negate()));
        event.setCommission(decimal(payload, "commission"));
        event.setCommissionAsset(upper(text(payload, "commissionAsset")));
        event.setDzengiType(buyer ? "BUY" : "SELL");
        event.setCanonicalType(buyer ? "BUY" : "SELL");
        event.setBasisRelevant(true);
        return event;
    }

    private DzengiExtractedEvent extractTradingPosition(IntegrationRawEvent rawEvent, JsonNode payload) {
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        String symbol = text(payload, "symbol");
        event.setTradingSymbol(symbol);
        event.setMarketType("LEVERAGE");
        event.setAssetType("DERIVATIVE");
        event.setPositionId(text(payload, "positionId"));
        event.setAssetSymbol(upper(text(payload, "accountCurrency")));
        event.setRealizedPnl(decimal(payload, "rplConverted"));
        if (event.getRealizedPnl() == null) {
            event.setRealizedPnl(decimal(payload, "rpl"));
        }
        event.setFeePaid(decimal(payload, "fee"));
        event.setDzengiType("POSITION_SETTLEMENT");
        event.setCanonicalType("CEX_DERIVATIVE_SETTLEMENT");
        event.setBasisRelevant(true);
        event.setQuantityRaw(event.getRealizedPnl());
        long execTs = payload.path("execTimestamp").asLong(payload.path("createdTimestamp").asLong(0L));
        if (execTs > 0L) {
            event.setTimeUtc(Instant.ofEpochMilli(execTs));
        }
        return event;
    }

    private DzengiExtractedEvent baseEvent(IntegrationRawEvent rawEvent, JsonNode payload) {
        DzengiExtractedEvent event = new DzengiExtractedEvent();
        String integrationId = rawEvent.getIntegrationId();
        String providerEventKey = rawEvent.getProviderEventKey();
        event.setId(integrationId + ":" + rawEvent.getStream() + ":" + providerEventKey);
        event.setIntegrationRawEventId(rawEvent.getId());
        event.setProviderEventKey(providerEventKey);
        event.setSourceStream(rawEvent.getStream());
        event.setSessionId(rawEvent.getSessionId());
        event.setIntegrationId(integrationId);
        String accountRef = rawEvent.getAccountRef();
        String userId = accountRef != null && accountRef.startsWith(DZENGI_PREFIX)
                ? accountRef.substring(DZENGI_PREFIX.length())
                : accountRef;
        event.setUserId(userId);
        event.setWalletRef(accountRef);
        event.setTimeUtc(Instant.ofEpochMilli(payload.path("timestamp").asLong(
                payload.path("time").asLong(rawEvent.getOccurredAt() == null ? 0L : rawEvent.getOccurredAt().toEpochMilli())
        )));
        event.setStatus(DzengiExtractedEventStatus.RAW);
        event.setImportedAt(Instant.now());
        event.setOutOfScope(false);
        return event;
    }

    private DzengiExtractedEvent excluded(IntegrationRawEvent rawEvent, JsonNode payload, String reason) {
        DzengiExtractedEvent event = baseEvent(rawEvent, payload);
        event.setCanonicalType("EXCLUDED");
        event.setDzengiType(reason);
        event.setBasisRelevant(false);
        event.setOutOfScope(true);
        event.setStatus(DzengiExtractedEventStatus.EXCLUDED);
        return event;
    }

    private static String stripTrailingDot(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return symbol;
        }
        return symbol.endsWith(".") ? symbol.substring(0, symbol.length() - 1) : symbol;
    }

    private static String mapLedgerCanonicalType(String type) {
        if (type == null) {
            return "UNKNOWN";
        }
        return switch (type) {
            case "deposit" -> "EXTERNAL_TRANSFER_IN";
            case "withdrawal" -> "EXTERNAL_TRANSFER_OUT";
            case "trade", "trade_commission", "exchange_commission", "swap" -> "FEE";
            default -> "UNKNOWN";
        };
    }

    private static boolean isLedgerBasisRelevant(String type) {
        return type != null && switch (type) {
            case "deposit", "withdrawal", "trade", "trade_commission", "exchange_commission", "swap" -> true;
            default -> false;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
