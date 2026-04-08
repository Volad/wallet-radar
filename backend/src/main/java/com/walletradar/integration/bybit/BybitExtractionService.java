package com.walletradar.integration.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds Bybit-specific extracted staging rows from immutable integration raw
 * events. Provider enrichment stays inside the external backfill lane.
 */
@Service
@RequiredArgsConstructor
public class BybitExtractionService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Set<String> STABLE_QUOTES = Set.of(
            "USDT",
            "USDC",
            "USDE",
            "BUSD",
            "FDUSD",
            "DAI",
            "USD1",
            "PYUSD",
            "TUSD"
    );

    private final ObjectMapper objectMapper;

    public List<BybitExtractedEvent> extract(IntegrationRawEvent rawEvent) {
        if (rawEvent == null || rawEvent.getPayload() == null || rawEvent.getStream() == null) {
            return List.of();
        }
        JsonNode payload = objectMapper.convertValue(rawEvent.getPayload(), JsonNode.class);
        BybitIntegrationStream stream = BybitIntegrationStream.valueOf(rawEvent.getStream());
        return switch (stream) {
            case TRANSACTION_LOG -> extractTransactionLog(rawEvent, payload);
            case EXECUTION_LINEAR, EXECUTION_INVERSE, EXECUTION_SPOT, EXECUTION_OPTION -> extractExecution(rawEvent, payload);
            case FUNDING_HISTORY -> List.of(extractFundingHistory(rawEvent, payload));
            case INTERNAL_TRANSFER -> List.of(extractInternalTransfer(rawEvent, payload));
            case UNIVERSAL_TRANSFER -> List.of(extractUniversalTransfer(rawEvent, payload));
            case DEPOSIT_ONCHAIN -> List.of(extractChainDeposit(rawEvent, payload));
            case DEPOSIT_INTERNAL -> List.of(extractInternalDeposit(rawEvent, payload));
            case WITHDRAWAL -> List.of(extractWithdrawal(rawEvent, payload));
            case CONVERT_HISTORY -> extractConvert(rawEvent, payload);
            case EARN_FLEXIBLE_SAVING -> List.of(extractFlexibleSaving(rawEvent, payload));
        };
    }

    private List<BybitExtractedEvent> extractExecution(IntegrationRawEvent rawEvent, JsonNode payload) {
        String symbol = text(payload, "symbol");
        String side = upper(text(payload, "side"));
        BigDecimal execQty = decimal(payload, "execQty");
        BigDecimal execValue = decimal(payload, "execValue");
        BigDecimal execPrice = decimal(payload, "execPrice");
        if (symbol == null || side == null || execQty == null || execValue == null || execPrice == null) {
            return List.of(unsupported(rawEvent, payload, "uta_derivatives", "TRADE", "EXECUTION_HISTORY_FIELDS_MISSING"));
        }
        SymbolPair pair = splitSymbol(symbol);
        if (pair == null) {
            return List.of(unsupported(rawEvent, payload, "uta_derivatives", "TRADE", "EXECUTION_SYMBOL_UNSUPPORTED"));
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return List.of(unsupported(rawEvent, payload, "uta_derivatives", "TRADE", "EXECUTION_SIDE_UNSUPPORTED"));
        }

        BigDecimal fee = decimal(payload, "execFee");
        String feeCurrency = upper(text(payload, "feeCurrency"));
        BigDecimal baseQuantity = "BUY".equals(side) ? execQty : execQty.negate();
        BigDecimal quoteQuantity = "BUY".equals(side) ? execValue.negate() : execValue;

        BybitExtractedEvent base = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":base");
        hydrateTradeLeg(base, pair.base(), symbol, side, pair.base(), baseQuantity, execPrice, feeCurrency, fee);

        BybitExtractedEvent quote = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":quote");
        hydrateTradeLeg(quote, pair.quote(), symbol, side, pair.quote(), quoteQuantity, execPrice, feeCurrency, fee);

        return List.of(base, quote);
    }

    private List<BybitExtractedEvent> extractTransactionLog(IntegrationRawEvent rawEvent, JsonNode payload) {
        String bybitType = upper(text(payload, "type"));
        if (bybitType == null || bybitType.isBlank()) {
            bybitType = "--";
        }
        String canonicalType = switch (bybitType) {
            case "TRANSFER_IN", "TRANSFER_OUT" -> "INTERNAL_TRANSFER";
            case "BONUS", "AIRDROP" -> "REWARD_CLAIM";
            case "BONUS_RECOLLECT" -> "FEE";
            case "INTEREST" -> "REWARD_CLAIM";
            case "--", "SETTLEMENT", "DELIVERY", "LIQUIDATION" -> "FUNDING_FEE";
            case "TRADE" -> "UNKNOWN_CEX";
            default -> "UNKNOWN_CEX";
        };

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("uta_derivatives");
        event.setCanonicalType(canonicalType);
        event.setBybitType(bybitType);
        event.setAssetSymbol(upper(text(payload, "currency", "coin")));
        event.setQuantityRaw(firstNonNull(decimal(payload, "change"), decimal(payload, "cashFlow")));
        event.setFilledPrice(decimal(payload, "tradePrice"));
        event.setFeePaid(negative(decimal(payload, "fee")));
        event.setCashFlow(decimal(payload, "cashFlow"));
        event.setChange(decimal(payload, "change"));
        event.setFunding(decimal(payload, "funding"));
        event.setWalletBalance(firstNonNull(decimal(payload, "cashBalance"), decimal(payload, "walletBalance")));
        event.setUtaContract(text(payload, "symbol"));
        event.setUtaDirection(upper(text(payload, "side")));
        event.setBasisRelevant(isBasisRelevantCanonicalType(canonicalType));
        return List.of(event);
    }

    private BybitExtractedEvent extractInternalTransfer(IntegrationRawEvent rawEvent, JsonNode payload) {
        String fromAccountType = text(payload, "fromAccountType");
        String toAccountType = text(payload, "toAccountType");
        boolean inbound = inferAccountTypeDirection(fromAccountType, toAccountType);

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setCanonicalType("INTERNAL_TRANSFER");
        event.setBybitType(inbound ? "Transfer in" : "Transfer out");
        event.setBybitDescription(describeTransfer(fromAccountType, toAccountType));
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(applyDirection(decimal(payload, "amount", "qty"), inbound));
        event.setBasisRelevant(false);
        return event;
    }

    private BybitExtractedEvent extractUniversalTransfer(IntegrationRawEvent rawEvent, JsonNode payload) {
        String currentUid = uid(rawEvent.getAccountRef());
        String fromMemberId = text(payload, "fromMemberId", "fromMemberID");
        String toMemberId = text(payload, "toMemberId", "toMemberID");
        boolean inbound = currentUid != null && currentUid.equals(toMemberId) && !currentUid.equals(fromMemberId);

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setCanonicalType("INTERNAL_TRANSFER");
        event.setBybitType(inbound ? "Transfer in" : "Transfer out");
        event.setBybitDescription(describeTransfer(text(payload, "fromAccountType"), text(payload, "toAccountType")));
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(applyDirection(decimal(payload, "amount", "qty"), inbound));
        event.setBasisRelevant(false);
        return event;
    }

    private BybitExtractedEvent extractChainDeposit(IntegrationRawEvent rawEvent, JsonNode payload) {
        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        String chain = upper(text(payload, "chain"));
        NetworkId networkId = mapChain(chain);
        event.setSourceFileType("withdraw_deposit");
        event.setCanonicalType("EXTERNAL_INBOUND");
        event.setBybitType("Deposit");
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(abs(decimal(payload, "amount")));
        event.setChain(chain);
        event.setNetworkId(networkId);
        event.setTxHash(text(payload, "txID", "txId"));
        event.setReceivedAddress(text(payload, "toAddress", "address"));
        event.setBybitStatus(text(payload, "status"));
        event.setBasisRelevant(true);
        event.setOutOfScope(networkId == null && chain != null && !"BYBIT".equalsIgnoreCase(chain));
        return event;
    }

    private BybitExtractedEvent extractInternalDeposit(IntegrationRawEvent rawEvent, JsonNode payload) {
        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setCanonicalType("EXTERNAL_INBOUND");
        event.setBybitType("Deposit");
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(abs(decimal(payload, "amount")));
        event.setChain("BYBIT");
        event.setReceivedAddress(text(payload, "toAddress", "address"));
        event.setBybitStatus(text(payload, "status"));
        event.setBasisRelevant(true);
        event.setOutOfScope(false);
        return event;
    }

    private BybitExtractedEvent extractWithdrawal(IntegrationRawEvent rawEvent, JsonNode payload) {
        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        String chain = upper(text(payload, "chain"));
        NetworkId networkId = mapChain(chain);
        event.setSourceFileType("withdraw_deposit");
        event.setCanonicalType("EXTERNAL_TRANSFER_OUT");
        event.setBybitType("Withdraw");
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(abs(decimal(payload, "amount")));
        event.setChain(chain);
        event.setNetworkId(networkId);
        event.setTxHash(text(payload, "txID", "txId"));
        event.setReceivedAddress(text(payload, "toAddress", "address"));
        event.setFeePaid(negative(decimal(payload, "withdrawFee", "fee")));
        event.setBybitStatus(text(payload, "status"));
        event.setBasisRelevant(true);
        event.setOutOfScope(networkId == null && chain != null && !"BYBIT".equalsIgnoreCase(chain));
        return event;
    }

    private List<BybitExtractedEvent> extractConvert(IntegrationRawEvent rawEvent, JsonNode payload) {
        String fromCoin = upper(text(payload, "fromCoin"));
        String toCoin = upper(text(payload, "toCoin"));
        BigDecimal fromAmount = decimal(payload, "fromAmount");
        BigDecimal toAmount = decimal(payload, "toAmount");
        if (fromCoin == null || toCoin == null || fromAmount == null || toAmount == null) {
            return List.of(unsupported(rawEvent, payload, "fund_asset_changes", "Convert", "CONVERT_FIELDS_MISSING"));
        }

        BybitExtractedEvent sell = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":sell");
        sell.setSourceFileType("fund_asset_changes");
        sell.setCanonicalType("SWAP");
        sell.setBybitType("Convert");
        sell.setBybitDescription("Convert");
        sell.setAssetSymbol(fromCoin);
        sell.setQuantityRaw(abs(fromAmount).negate());
        sell.setBasisRelevant(true);
        sell.setChain("BYBIT");
        sell.setBybitStatus(text(payload, "exchangeStatus", "status"));

        BybitExtractedEvent buy = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":buy");
        buy.setSourceFileType("fund_asset_changes");
        buy.setCanonicalType("SWAP");
        buy.setBybitType("Convert");
        buy.setBybitDescription("Convert");
        buy.setAssetSymbol(toCoin);
        buy.setQuantityRaw(abs(toAmount));
        buy.setBasisRelevant(true);
        buy.setChain("BYBIT");
        buy.setBybitStatus(text(payload, "exchangeStatus", "status"));
        return List.of(sell, buy);
    }

    private BybitExtractedEvent extractFlexibleSaving(IntegrationRawEvent rawEvent, JsonNode payload) {
        String orderType = upper(text(payload, "orderType"));
        BigDecimal orderValue = firstNonNull(decimal(payload, "orderValue"), decimal(payload, "amount"));
        String coin = upper(text(payload, "coin", "asset"));

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setBybitType("Earn");
        event.setAssetSymbol(coin);
        event.setQuantityRaw(orderValue == null ? null : ("REDEEM".equals(orderType) ? abs(orderValue) : abs(orderValue).negate()));
        event.setChain("BYBIT");
        event.setBasisRelevant(true);
        event.setBybitStatus(text(payload, "status"));

        if ("STAKE".equals(orderType) || "SUBSCRIBE".equals(orderType)) {
            event.setCanonicalType("VAULT_DEPOSIT");
            event.setBybitDescription("Flexible Savings Subscription");
            return event;
        }
        if ("REDEEM".equals(orderType)) {
            event.setCanonicalType("VAULT_WITHDRAW");
            event.setBybitDescription("Flexible Savings Principal Redemption");
            return event;
        }

        event.setCanonicalType("UNKNOWN_CEX");
        event.setBybitDescription("Flexible Savings Unsupported Order");
        return event;
    }

    private BybitExtractedEvent extractFundingHistory(IntegrationRawEvent rawEvent, JsonNode payload) {
        String businessType = text(payload, "showBusiTypeEn");
        String description = text(payload, "descriptionEn");
        String direction = upper(text(payload, "ioDirection"));

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setAssetSymbol(upper(text(payload, "currency", "coin")));
        event.setQuantityRaw(applyFundingDirection(decimal(payload, "txnAmt"), direction));
        event.setWalletBalance(decimal(payload, "afterAmt"));
        event.setCashFlow(event.getQuantityRaw());
        event.setChange(event.getQuantityRaw());
        event.setBybitType(normalizeFundingHistoryType(businessType, description, direction));
        event.setBybitDescription(normalizeFundingHistoryDescription(description, direction));
        event.setCanonicalType(mapFundingHistoryCanonicalType(
                event.getBybitType(),
                event.getBybitDescription(),
                event.getAssetSymbol()
        ));
        event.setBasisRelevant(isBasisRelevantCanonicalType(event.getCanonicalType()));
        return event;
    }

    private void hydrateTradeLeg(
            BybitExtractedEvent event,
            String assetSymbol,
            String symbol,
            String side,
            String feeCurrencyCandidate,
            BigDecimal quantityRaw,
            BigDecimal execPrice,
            String feeCurrency,
            BigDecimal fee
    ) {
        event.setSourceFileType("uta_derivatives");
        event.setCanonicalType("SWAP");
        event.setBybitType("TRADE");
        event.setAssetSymbol(assetSymbol);
        event.setQuantityRaw(quantityRaw);
        event.setUtaContract(symbol);
        event.setUtaDirection(side);
        event.setFilledPrice(execPrice);
        if (fee != null && feeCurrency != null && feeCurrency.equalsIgnoreCase(feeCurrencyCandidate)) {
            event.setFeePaid(negative(abs(fee)));
            event.setChange(quantityRaw == null ? null : quantityRaw.add(event.getFeePaid(), MC));
        } else {
            event.setChange(quantityRaw);
        }
        event.setCashFlow(quantityRaw);
        event.setBasisRelevant(true);
    }

    private BybitExtractedEvent unsupported(
            IntegrationRawEvent rawEvent,
            JsonNode payload,
            String sourceFileType,
            String bybitType,
            String description
    ) {
        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType(sourceFileType);
        event.setCanonicalType("UNKNOWN_CEX");
        event.setBybitType(bybitType);
        event.setBybitDescription(description);
        event.setAssetSymbol(upper(text(payload, "currency", "coin")));
        event.setQuantityRaw(firstNonNull(decimal(payload, "change"), decimal(payload, "cashFlow"), decimal(payload, "amount")));
        event.setBasisRelevant(true);
        return event;
    }

    private BybitExtractedEvent baseEvent(IntegrationRawEvent rawEvent, JsonNode payload, String suffix) {
        BybitExtractedEvent event = new BybitExtractedEvent();
        event.setId(rawEvent.getIntegrationId() + ":" + rawEvent.getStream() + ":" + suffix);
        event.setIntegrationRawEventId(rawEvent.getId());
        event.setProviderEventKey(rawEvent.getProviderEventKey());
        event.setSourceStream(rawEvent.getStream());
        event.setSource("BYBIT_API");
        event.setSourceFile(rawEvent.getStream());
        event.setUid(uid(rawEvent.getAccountRef()));
        event.setSessionId(rawEvent.getSessionId());
        event.setIntegrationId(rawEvent.getIntegrationId());
        event.setTimeUtc(firstNonNull(instant(payload, "transactionTime"), instant(payload, "execTime"), instant(payload, "createTime"), instant(payload, "updatedTime"), rawEvent.getOccurredAt()));
        event.setWalletRef(rawEvent.getAccountRef());
        event.setStatus(BybitExtractedEventStatus.RAW);
        event.setImportedAt(rawEvent.getFetchedAt());
        event.setBybitStatus(text(payload, "status"));
        event.setChain("BYBIT");
        return event;
    }

    private BigDecimal applyFundingDirection(BigDecimal amount, String ioDirection) {
        if (amount == null) {
            return null;
        }
        if ("O".equals(ioDirection)) {
            return amount.abs().negate();
        }
        if ("I".equals(ioDirection)) {
            return amount.abs();
        }
        return amount;
    }

    private String normalizeFundingHistoryType(String businessType, String description, String ioDirection) {
        String normalizedBusiness = normalize(businessType);
        String normalizedDescription = normalize(description);
        if (normalizedDescription.contains("trading bot")) {
            return "Bot";
        }
        if (normalizedDescription.contains("airdrop")) {
            return "Airdrop";
        }
        if (normalizedDescription.contains("p2p purchase")) {
            return "Fiat";
        }
        if (normalizedDescription.contains("eth 2.0")) {
            return "ETH 2.0";
        }
        return switch (normalizedBusiness) {
            case "earn" -> "Earn";
            case "convert" -> "Convert";
            case "withdraw" -> "Withdraw";
            case "deposit" -> "Deposit";
            case "loans" -> "Loans";
            case "airdrop" -> "Airdrop";
            case "fiat" -> "Fiat";
            case "transfer in", "transfer_in", "transferin" -> "Transfer in";
            case "transfer out", "transfer_out", "transferout" -> "Transfer out";
            case "transfer" -> "O".equals(ioDirection) ? "Transfer out" : "Transfer in";
            case "bot" -> "Bot";
            default -> null;
        };
    }

    private String normalizeFundingHistoryDescription(String description, String ioDirection) {
        String normalized = normalize(description);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.contains("flexible") && normalized.contains("interest")) {
            return "Flexible Savings Interest Distribution";
        }
        if (normalized.contains("flexible") && normalized.contains("subscription")) {
            return "Flexible Savings Subscription";
        }
        if (normalized.contains("flexible") && (normalized.contains("principal redemption") || normalized.contains("redemption"))) {
            return "Flexible Savings Principal Redemption";
        }
        if (normalized.contains("launchpool") && normalized.contains("yield")) {
            return "Launchpool Yield";
        }
        if (normalized.contains("launchpool") && normalized.contains("subscription")) {
            return "Launchpool Subscription";
        }
        if (normalized.contains("launchpool") && normalized.contains("auto") && (normalized.contains("withdraw") || normalized.contains("redeem"))) {
            return "Launchpool Auto-Withdrawal";
        }
        if (normalized.contains("launchpool") && normalized.contains("manual") && (normalized.contains("withdraw") || normalized.contains("redeem"))) {
            return "Launchpool Manual Withdrawal";
        }
        if (normalized.contains("on-chain") && normalized.contains("earn") && normalized.contains("subscription")) {
            return "On-chain Earn subscription";
        }
        if (normalized.contains("fixed") && normalized.contains("interest")) {
            return "Fixed Savings Interest Distribution";
        }
        if (normalized.contains("fixed") && (normalized.contains("principal redemption") || normalized.contains("redemption"))) {
            return "Fixed Savings Principal Redemption";
        }
        if (normalized.contains("trading bot")) {
            return "I".equals(ioDirection) ? "Transfer from Trading Bot" : "Transfer to Trading Bot";
        }
        if (normalized.contains("unified")) {
            return "I".equals(ioDirection) ? "Transfer from Unified Trading Account" : "Transfer to Unified Trading Account";
        }
        if (normalized.contains("subaccount") || normalized.contains("sub-account")) {
            return "I".equals(ioDirection) ? "Transfer from Subaccount" : "Transfer to Subaccount";
        }
        if (normalized.contains("convert")) {
            return "Convert";
        }
        if (normalized.contains("withdraw")) {
            return "Withdrawal";
        }
        if (normalized.contains("deposit")) {
            return "Deposit";
        }
        if (normalized.contains("airdrop")) {
            return "Airdrop Bonus";
        }
        if (normalized.contains("p2p purchase")) {
            return "P2P Purchase";
        }
        if (normalized.contains("repay principal")) {
            return "Repay Principal";
        }
        if (normalized.contains("repay interest")) {
            return "Repay Interest";
        }
        if (normalized.contains("pledge")) {
            return "Pledge Assets";
        }
        if (normalized.contains("borrow funds")) {
            return "Borrow Funds";
        }
        if (normalized.contains("asset redemption")) {
            return "Asset Redemption";
        }
        if (normalized.contains("stake")) {
            return "Stake";
        }
        if (normalized.contains("mint")) {
            return "Mint";
        }
        return description;
    }

    private String mapFundingHistoryCanonicalType(String bybitType, String description, String assetSymbol) {
        String normalizedType = normalize(bybitType);
        String normalizedDescription = normalize(description);
        if ("earn".equals(normalizedType)) {
            if (normalizedDescription.contains("on-chain earn subscription") && isAuditedEthFamilySymbol(assetSymbol)) {
                return "STAKING_DEPOSIT";
            }
            if (normalizedDescription.contains("interest distribution") || normalizedDescription.contains("yield")) {
                return "REWARD_CLAIM";
            }
            if (normalizedDescription.contains("subscription")) {
                return "VAULT_DEPOSIT";
            }
            if (normalizedDescription.contains("withdrawal") || normalizedDescription.contains("redemption")) {
                return "VAULT_WITHDRAW";
            }
            return "UNKNOWN_CEX";
        }
        if ("convert".equals(normalizedType)) {
            return "SWAP";
        }
        if ("transfer in".equals(normalizedType) || "transfer out".equals(normalizedType) || "bot".equals(normalizedType)) {
            return "INTERNAL_TRANSFER";
        }
        if ("withdraw".equals(normalizedType)) {
            return "EXTERNAL_TRANSFER_OUT";
        }
        if ("deposit".equals(normalizedType) || "fiat".equals(normalizedType)) {
            return "EXTERNAL_INBOUND";
        }
        if ("airdrop".equals(normalizedType)) {
            return "REWARD_CLAIM";
        }
        if ("eth 2.0".equals(normalizedType)) {
            return "STAKING_DEPOSIT";
        }
        if ("loans".equals(normalizedType)) {
            if (normalizedDescription.contains("repay interest")) {
                return "FEE";
            }
            if (normalizedDescription.contains("repay principal") || normalizedDescription.contains("asset redemption")) {
                return "REPAY";
            }
            if (normalizedDescription.contains("pledge") || normalizedDescription.contains("borrow funds")) {
                return "BORROW";
            }
        }
        return "UNKNOWN_CEX";
    }

    private boolean isAuditedEthFamilySymbol(String assetSymbol) {
        return "FAMILY:ETH".equals(AccountingAssetFamilySupport.continuityIdentity(assetSymbol, null));
    }

    private boolean isBasisRelevantCanonicalType(String canonicalType) {
        return switch (canonicalType) {
            case "REWARD_CLAIM", "VAULT_DEPOSIT", "VAULT_WITHDRAW", "EXTERNAL_INBOUND", "EXTERNAL_TRANSFER_OUT",
                    "SWAP", "STAKING_DEPOSIT", "BORROW", "REPAY" -> true;
            default -> false;
        };
    }

    private boolean inferAccountTypeDirection(String fromAccountType, String toAccountType) {
        String normalizedFrom = upper(fromAccountType);
        String normalizedTo = upper(toAccountType);
        if (normalizedTo != null && (normalizedTo.contains("UNIFIED") || normalizedTo.contains("FUND"))) {
            return true;
        }
        if (normalizedFrom != null && (normalizedFrom.contains("UNIFIED") || normalizedFrom.contains("FUND"))) {
            return false;
        }
        return false;
    }

    private String describeTransfer(String fromAccountType, String toAccountType) {
        if (fromAccountType == null && toAccountType == null) {
            return null;
        }
        if (fromAccountType == null) {
            return "Transfer to " + toAccountType;
        }
        if (toAccountType == null) {
            return "Transfer from " + fromAccountType;
        }
        return "Transfer from " + fromAccountType + " to " + toAccountType;
    }

    private String uid(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return null;
        }
        if (accountRef.startsWith("BYBIT:")) {
            return accountRef.substring("BYBIT:".length());
        }
        return accountRef;
    }

    private NetworkId mapChain(String chain) {
        if (chain == null || chain.isBlank()) {
            return null;
        }
        return switch (chain.trim().toUpperCase(Locale.ROOT)) {
            case "ETH", "ERC20", "ETHEREUM" -> NetworkId.ETHEREUM;
            case "ARBI", "ARBITRUM" -> NetworkId.ARBITRUM;
            case "BASE" -> NetworkId.BASE;
            case "MANTLE" -> NetworkId.MANTLE;
            case "SOL", "SOLANA" -> NetworkId.SOLANA;
            case "BSC", "BNB", "BEP20" -> NetworkId.BSC;
            case "AVAX", "AVALANCHE", "CCHAIN" -> NetworkId.AVALANCHE;
            default -> null;
        };
    }

    private SymbolPair splitSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        for (String suffix : STABLE_QUOTES) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                return new SymbolPair(normalized.substring(0, normalized.length() - suffix.length()), suffix);
            }
        }
        return null;
    }

    private Instant instant(JsonNode node, String fieldName) {
        String value = text(node, fieldName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long epoch = Long.parseLong(value);
            return epoch < 100_000_000_000L ? Instant.ofEpochSecond(epoch) : Instant.ofEpochMilli(epoch);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(value);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String text(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode field = node.path(fieldName);
            if (!field.isMissingNode() && !field.isNull()) {
                String value = field.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal decimal(JsonNode node, String... fieldNames) {
        String value = text(node, fieldNames);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal negative(BigDecimal value) {
        return value == null ? null : value.abs().negate();
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    private BigDecimal applyDirection(BigDecimal value, boolean inbound) {
        if (value == null) {
            return null;
        }
        return inbound ? value.abs() : value.abs().negate();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record SymbolPair(
            String base,
            String quote
    ) {
    }
}
