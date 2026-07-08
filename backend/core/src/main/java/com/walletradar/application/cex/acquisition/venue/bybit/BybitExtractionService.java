package com.walletradar.application.cex.acquisition.venue.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds Bybit-specific extracted staging rows from immutable integration raw
 * events. Provider enrichment stays inside the external backfill lane.
 */
@Service
@RequiredArgsConstructor
public class BybitExtractionService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal QTY_MATCH_TOLERANCE = new BigDecimal("0.000000000001");
    /**
     * Cycle/5 FA-001 D2: FUNDING_HISTORY (FH) anchors and their chain-aware DEPOSIT_ONCHAIN /
     * WITHDRAWAL siblings are emitted by Bybit within a few hundred milliseconds of each other but
     * the timestamps occasionally differ by a small number of seconds (clock skew between FH and
     * the on-chain confirmation pipeline). 120s comfortably covers observed drift while staying
     * tight enough that no two distinct {@code (asset, qty)} corridor events collide.
     */
    private static final Duration FH_CHAIN_TIME_WINDOW = Duration.ofSeconds(120);
    private static final String BYBIT_PREFIX = "BYBIT:";
    /**
     * Cycle/5 N12: Bybit's `/v5/asset/transfer/query-inter-transfer-list` emits a synthetic row with this
     * `transferId` prefix whenever an external withdrawal originates from UNIFIED. The row carries
     * `fromAccountType=UNIFIED, toAccountType=FUND` and is logged ~1s **after** the paired `FUNDING_HISTORY`
     * withdraw event, which makes the replay engine see (a) FUND `-X` shortfall, then (b) FUND `+X` phantom
     * CARRY_IN. It is not a real internal transfer — it is the FUND-side accounting mirror of the withdrawal.
     * See `docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md` §8.
     */
    private static final String MAW_DEDUCT_TRANSFER_PREFIX = "maw_deduct_transfer_";
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
    private final MongoOperations mongoOperations;

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

    /**
     * Re-applies {@link #extract(IntegrationRawEvent)} to the immutable raw row and copies {@code basisRelevant}
     * onto {@code row} when it drifted. Needed after extraction-rule fixes (cycle/5 N1/N5): {@code reset-derived.sh}
     * only resets status and never re-runs extraction, so Mongo can still hold stale flags.
     */
    public boolean refreshBasisRelevantFromRaw(BybitExtractedEvent row) {
        if (row == null || row.getIntegrationRawEventId() == null || row.getIntegrationRawEventId().isBlank()) {
            return false;
        }
        IntegrationRawEvent raw = mongoOperations.findById(row.getIntegrationRawEventId(), IntegrationRawEvent.class);
        if (raw == null) {
            return false;
        }
        List<BybitExtractedEvent> fresh = extract(raw);
        Optional<BybitExtractedEvent> match = fresh.stream()
                .filter(candidate -> row.getId().equals(candidate.getId()))
                .findFirst();
        if (match.isEmpty()) {
            return false;
        }
        Boolean next = match.get().getBasisRelevant();
        if (java.util.Objects.equals(row.getBasisRelevant(), next)) {
            return false;
        }
        row.setBasisRelevant(next);
        return true;
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

        boolean basisRelevant = "EXECUTION_SPOT".equals(rawEvent.getStream());

        BybitExtractedEvent base = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":base");
        hydrateTradeLeg(base, pair.base(), symbol, side, pair.base(), baseQuantity, execPrice, feeCurrency, fee);
        applyBybitSubAccountWalletRef(base, BybitSubAccount.UTA);
        base.setTradeOrderId(firstNonBlank(text(payload, "orderId"), text(payload, "orderLinkId")));
        base.setBasisRelevant(basisRelevant);

        BybitExtractedEvent quote = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey() + ":quote");
        hydrateTradeLeg(quote, pair.quote(), symbol, side, pair.quote(), quoteQuantity, execPrice, feeCurrency, fee);
        applyBybitSubAccountWalletRef(quote, BybitSubAccount.UTA);
        quote.setTradeOrderId(base.getTradeOrderId());
        quote.setBasisRelevant(basisRelevant);

        return List.of(base, quote);
    }

    private List<BybitExtractedEvent> extractTransactionLog(IntegrationRawEvent rawEvent, JsonNode payload) {
        String bybitType = upper(text(payload, "type"));
        if (bybitType == null || bybitType.isBlank()) {
            bybitType = "--";
        }
        String canonicalType = classifyTransactionLogCanonical(bybitType);

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("uta_derivatives");
        event.setCanonicalType(canonicalType);
        event.setBybitType(bybitType);
        if (isTransactionLogConvertType(bybitType)) {
            event.setBybitDescription("Currency convert");
        }
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
        // TRADE duplicates EXECUTION_SPOT (G13). TRANSFER_IN mirrors INTERNAL_TRANSFER receiver (cycle/5 N5).
        // TRANSFER_OUT is sender-side authority on UTA and must stay basis-relevant (pairs with stream / FH).
        boolean txLogNonBasis = "TRADE".equals(bybitType) || "TRANSFER_IN".equals(bybitType);
        event.setBasisRelevant(txLogNonBasis ? false : isBasisRelevantCanonicalType(canonicalType));
        if ("LOANS_BORROW_FUNDS".equals(bybitType) || "LOANS_REPAY_FUNDS".equals(bybitType)) {
            event.setTradeOrderId(firstNonBlank(
                    text(payload, "orderId"),
                    text(payload, "orderLinkId"),
                    text(payload, "serialNo")
            ));
        }
        applyBybitSubAccountWalletRef(event, BybitSubAccount.UTA);
        return List.of(event);
    }

    private String classifyTransactionLogCanonical(String bybitType) {
        if (bybitType == null || bybitType.isBlank() || "--".equals(bybitType)) {
            return "FUNDING_FEE";
        }
        return switch (bybitType) {
            case "TRANSFER_IN", "TRANSFER_OUT" -> "INTERNAL_TRANSFER";
            case "BONUS", "AIRDROP", "REWARD" -> "REWARD_CLAIM";
            case "BONUS_RECOLLECT" -> "FEE";
            case "INTEREST" -> "REWARD_CLAIM";
            case "CURRENCY_BUY", "CURRENCY_SELL" -> "SWAP";
            case "TRADE" -> "UNKNOWN_CEX";
            case "FLEXIBLE_STAKING_SUBSCRIPTION",
                    "FLEXIBLE_STAKING_REDEMPTION",
                    "FIXED_STAKING_SUBSCRIPTION",
                    "FIXED_STAKING_REDEMPTION" -> "INTERNAL_TRANSFER";
            case "LOANS_PLEDGE_ASSET", "LOANS_ASSET_REDEMPTION" -> "INTERNAL_TRANSFER";
            case "LOANS_BORROW_FUNDS" -> "BORROW";
            case "LOANS_REPAY_FUNDS" -> "REPAY";
            case "LOANS_LIQUIDATION" -> "EXTERNAL_TRANSFER_OUT";
            case "LOANS_INTEREST" -> "FEE";
            case "SETTLEMENT", "DELIVERY", "LIQUIDATION" -> "FUNDING_FEE";
            default -> "UNKNOWN_CEX";
        };
    }

    private boolean isTransactionLogConvertType(String bybitType) {
        return "CURRENCY_BUY".equals(bybitType) || "CURRENCY_SELL".equals(bybitType);
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
        event.setBasisRelevant(!isWithdrawalCompanionTransfer(rawEvent));
        applyBybitSubAccountWalletRef(event, inferSubAccountForTransferLeg(fromAccountType, toAccountType, inbound));
        hydrateInternalTransferDepositFromOnChain(rawEvent, event);
        return event;
    }

    private BybitExtractedEvent extractUniversalTransfer(IntegrationRawEvent rawEvent, JsonNode payload) {
        String currentUid = uid(rawEvent.getAccountRef());
        String fromMemberId = text(payload, "fromMemberId", "fromMemberID");
        String toMemberId = text(payload, "toMemberId", "toMemberID");
        boolean inbound = currentUid != null && currentUid.equals(toMemberId) && !currentUid.equals(fromMemberId);
        String fromAccountType = text(payload, "fromAccountType");
        String toAccountType = text(payload, "toAccountType");

        BybitExtractedEvent event = baseEvent(rawEvent, payload, rawEvent.getProviderEventKey());
        event.setSourceFileType("fund_asset_changes");
        event.setCanonicalType("INTERNAL_TRANSFER");
        event.setBybitType(inbound ? "Transfer in" : "Transfer out");
        event.setBybitDescription(describeTransfer(fromAccountType, toAccountType));
        event.setAssetSymbol(upper(text(payload, "coin", "currency")));
        event.setQuantityRaw(applyDirection(decimal(payload, "amount", "qty"), inbound));
        event.setBasisRelevant(!isWithdrawalCompanionTransfer(rawEvent));
        applyBybitSubAccountWalletRef(event, inferSubAccountForTransferLeg(fromAccountType, toAccountType, inbound));
        hydrateInternalTransferDepositFromOnChain(rawEvent, event);
        return event;
    }

    /**
     * Cycle/5 N12: {@code maw_deduct_transfer_*} {@code INTERNAL_TRANSFER} / {@code UNIVERSAL_TRANSFER}
     * rows are synthetic withdrawal mirrors emitted by Bybit, not real internal transfers — see
     * {@link #MAW_DEDUCT_TRANSFER_PREFIX} javadoc and ADR-006 §8.
     */
    private boolean isWithdrawalCompanionTransfer(IntegrationRawEvent rawEvent) {
        if (rawEvent == null) {
            return false;
        }
        String key = rawEvent.getProviderEventKey();
        return key != null && key.startsWith(MAW_DEDUCT_TRANSFER_PREFIX);
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
        event.setTxHash(NetworkAddressFormat.canonicalTxHash(networkId, text(payload, "txID", "txId")));
        event.setSenderAddress(NetworkAddressFormat.canonicalAddress(networkId, text(payload, "fromAddress")));
        event.setReceivedAddress(NetworkAddressFormat.canonicalAddress(networkId, text(payload, "toAddress", "address")));
        event.setBybitStatus(text(payload, "status"));
        // Cycle/4 H2: FH Deposit is FUND accounting anchor; on-chain row is for hash continuity only.
        event.setBasisRelevant(false);
        event.setOutOfScope(networkId == null && chain != null && !"BYBIT".equalsIgnoreCase(chain));
        Optional<BybitExtractedEvent> txLogAnchor = findTxLogDepositAnchor(rawEvent, event);
        if (txLogAnchor.isPresent()
                && txLogAnchor.get().getWalletRef() != null
                && !txLogAnchor.get().getWalletRef().isBlank()) {
            event.setWalletRef(txLogAnchor.get().getWalletRef().trim());
        } else {
            applyBybitSubAccountWalletRef(event, BybitSubAccount.UTA);
        }
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
        event.setSenderAddress(text(payload, "fromAddress"));
        event.setReceivedAddress(text(payload, "toAddress", "address"));
        event.setBybitStatus(text(payload, "status"));
        event.setBasisRelevant(true);
        event.setOutOfScope(false);
        applyBybitSubAccountWalletRef(event, BybitSubAccount.FUND);
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
        event.setQuantityRaw(negative(abs(decimal(payload, "amount"))));
        event.setChain(chain);
        event.setNetworkId(networkId);
        event.setTxHash(NetworkAddressFormat.canonicalTxHash(networkId, text(payload, "txID", "txId")));
        event.setSenderAddress(NetworkAddressFormat.canonicalAddress(networkId, text(payload, "fromAddress")));
        event.setReceivedAddress(NetworkAddressFormat.canonicalAddress(networkId, text(payload, "toAddress", "address")));
        event.setFeePaid(negative(decimal(payload, "withdrawFee", "fee")));
        event.setBybitStatus(text(payload, "status"));
        // G12: authoritative withdrawal is FH/Withdraw; WITHDRAWAL stream is mirror-only.
        event.setBasisRelevant(false);
        event.setOutOfScope(networkId == null && chain != null && !"BYBIT".equalsIgnoreCase(chain));
        applyBybitSubAccountWalletRef(event, BybitSubAccount.FUND);
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
        applyBybitSubAccountWalletRef(sell, BybitSubAccount.FUND);
        String convertOrderId = firstNonBlank(
                text(payload, "exchangeTxId"),
                text(payload, "orderId"),
                text(payload, "convertId"),
                rawEvent.getProviderEventKey()
        );
        sell.setTradeOrderId(convertOrderId);

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
        applyBybitSubAccountWalletRef(buy, BybitSubAccount.FUND);
        buy.setTradeOrderId(convertOrderId);
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
        // Cycle/5 N4: walletRef is EARN — SUBSCRIBE credits EARN, REDEEM debits EARN (sign from EARN perspective).
        event.setQuantityRaw(orderValue == null ? null
                : ("REDEEM".equals(orderType) ? abs(orderValue).negate() : abs(orderValue)));
        event.setChain("BYBIT");
        event.setBasisRelevant(true);
        event.setBybitStatus(text(payload, "status"));
        applyBybitSubAccountWalletRef(event, BybitSubAccount.EARN);

        if ("STAKE".equals(orderType) || "SUBSCRIBE".equals(orderType)) {
            event.setCanonicalType("INTERNAL_TRANSFER");
            event.setBybitDescription("Flexible Savings Subscription");
            return event;
        }
        if ("REDEEM".equals(orderType)) {
            event.setCanonicalType("INTERNAL_TRANSFER");
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
        String fundingRaw = text(payload, "funding");
        event.setFunding(decimal(payload, "funding"));
        event.setCanonicalType(mapFundingHistoryCanonicalType(
                event.getBybitType(),
                event.getBybitDescription(),
                event.getAssetSymbol(),
                fundingRaw
        ));
        event.setTradeOrderId(firstNonBlank(text(payload, "tradeOrderId"), text(payload, "orderId")));
        if ("Deposit".equalsIgnoreCase(event.getBybitType()) || "Withdraw".equalsIgnoreCase(event.getBybitType())) {
            event.setTxHash(firstNonBlank(text(payload, "txID", "txId"), event.getTxHash()));
        }
        boolean basisRelevant = isBasisRelevantCanonicalType(event.getCanonicalType());
        // Cycle/5 N17 (REVERTS N1 suppression of FH/Deposit): FH/Deposit IS the FUND accounting anchor
        // for an external on-chain inflow. The chain-aware DEPOSIT_ONCHAIN row (sourceStream
        // DEPOSIT_ONCHAIN, sourceFileType=withdraw_deposit) is the basisRelevant=false continuity mirror
        // (set in extractChainDeposit). The INTERNAL_TRANSFER:deposit_* / UNIVERSAL_TRANSFER:deposit_*
        // auto-route rows are the FUND→UTA internal carry mirror and are normalized as INTERNAL_TRANSFER
        // (basis-relevant carry, not a second external acquisition). Without N17 the FH/Deposit row was
        // marked basisRelevant=false and the auto-route row was the only candidate to acquire basis;
        // the shadow pairer then excluded the auto-route via DEPOSIT_ONCHAIN linkage, leaking basis
        // for every external crypto deposit (USDT 13 307 silently destroyed; quantityShortfallAfter
        // accumulated 14 106 on FUND; realized PnL stuck at ≈ 0 while user lost > $10 k).
        String canonical = event.getCanonicalType();
        boolean fhLoanShadow = ("Crypto Loans".equalsIgnoreCase(event.getBybitType())
                || "Loans".equalsIgnoreCase(event.getBybitType()))
                && ("BORROW".equals(canonical) || "REPAY".equals(canonical) || "FEE".equals(canonical));
        if (fhLoanShadow) {
            basisRelevant = false;
        }
        // Cycle/5 N5: FH "Transfer in" duplicates INTERNAL_TRANSFER receiver; "Transfer out" is FUND sender leg.
        if ("INTERNAL_TRANSFER".equals(canonical) && "Transfer in".equalsIgnoreCase(event.getBybitType())) {
            basisRelevant = false;
        }
        if ("external_in_fiat_p2p".equals(canonical)) {
            suppressFiatP2pInternalTransferMirror(rawEvent, event);
        }
        event.setBasisRelevant(basisRelevant);
        applyBybitSubAccountWalletRef(event, inferFundingHistorySubAccount(event));
        String chain = upper(text(payload, "chain"));
        if (chain != null && !chain.isBlank()) {
            event.setChain(chain);
            NetworkId networkId = mapChain(chain);
            event.setNetworkId(networkId);
            if (event.getTxHash() != null && !event.getTxHash().isBlank()) {
                event.setTxHash(NetworkAddressFormat.canonicalTxHash(networkId, event.getTxHash()));
            }
        }
        if (!"Fiat".equalsIgnoreCase(event.getBybitType())) {
            hydrateFundingHistoryDepositFromOnChain(rawEvent, event);
            hydrateFundingHistoryWithdrawFromOnChain(rawEvent, event);
        }
        return event;
    }

    /**
     * Cycle/5 N18 (normalize-time re-hydration): the extraction-time pass above runs while
     * {@code FUNDING_HISTORY} segments are being processed, which is BEFORE {@code DEPOSIT_ONCHAIN}
     * and {@code WITHDRAWAL} segments in {@link com.walletradar.application.cex.acquisition.venue.bybit.BybitBackfillSegmentPlanner}.
     * On a fresh integration the chain-aware sibling is not yet in {@code bybit_extracted_events}, so
     * extraction-time hydration finds no candidate and the FH anchor stays with {@code chain="BYBIT"}.
     * The normalization stage runs AFTER all extraction has completed, so re-running hydration here
     * guarantees that every FH/Deposit and FH/Withdraw row carrying a matching DEPOSIT_ONCHAIN /
     * WITHDRAWAL sibling receives its {@code txHash} / {@code chain} / {@code networkId} before the
     * row is persisted and handed to {@link com.walletradar.application.linking.pipeline.clarification.BybitTransferContinuityRepairService}.
     *
     * <p>Returns {@code true} when hydration changed any field, so the normalization stage can persist
     * the row back to {@code bybit_extracted_events}.
     */
    public boolean hydrateFundingHistoryFromOnChainSibling(BybitExtractedEvent event) {
        if (event == null || mongoOperations == null) {
            return false;
        }
        if (!"FUNDING_HISTORY".equalsIgnoreCase(event.getSourceStream())) {
            return false;
        }
        if (event.getQuantityRaw() == null || event.getAssetSymbol() == null) {
            return false;
        }
        boolean addressesPresent = present(event.getSenderAddress()) && present(event.getReceivedAddress());
        boolean alreadyHydrated = present(event.getTxHash())
                && event.getNetworkId() != null
                && addressesPresent;
        if (alreadyHydrated) {
            return false;
        }
        Optional<BybitExtractedEvent> chainOpt;
        if ("Fiat".equalsIgnoreCase(event.getBybitType())) {
            return false;
        }
        if ("Deposit".equalsIgnoreCase(event.getBybitType())) {
            chainOpt = findMatchingChainDepositRow(
                    event.getIntegrationId(),
                    event.getAssetSymbol(),
                    event.getQuantityRaw().abs(),
                    event.getTimeUtc()
            );
        } else if ("Withdraw".equalsIgnoreCase(event.getBybitType())) {
            chainOpt = findMatchingChainWithdrawalRow(
                    event.getIntegrationId(),
                    event.getAssetSymbol(),
                    event.getQuantityRaw().abs(),
                    event.getTimeUtc()
            );
        } else {
            return false;
        }
        if (chainOpt.isEmpty()) {
            return false;
        }
        BybitExtractedEvent chain = chainOpt.get();
        return copyFromChainSibling(event, chain);
    }

    /**
     * FA-001 P0: copy on-chain identity ({@code txHash}, {@code networkId}, {@code chain}, sender /
     * receiver addresses) from a matching {@code DEPOSIT_ONCHAIN} / {@code WITHDRAWAL} sibling onto a
     * {@code FUNDING_HISTORY} anchor. Addresses are namespace-canonicalised via
     * {@link NetworkAddressFormat} so Solana/TON case-sensitivity is preserved. The hydration also
     * runs at extraction-time for the side that has both streams already persisted, and at
     * normalize-time for the typical case (FH segment is processed first per
     * {@code BybitBackfillSegmentPlanner}).
     */
    private boolean copyFromChainSibling(BybitExtractedEvent event, BybitExtractedEvent chain) {
        if (event == null || chain == null) {
            return false;
        }
        boolean changed = false;
        NetworkId targetNetwork = chain.getNetworkId() != null ? chain.getNetworkId() : event.getNetworkId();
        if (present(chain.getTxHash())) {
            String canonical = NetworkAddressFormat.canonicalTxHash(targetNetwork, chain.getTxHash());
            if (canonical != null && !canonical.equals(event.getTxHash())) {
                event.setTxHash(canonical);
                changed = true;
            }
        }
        if (chain.getChain() != null && !chain.getChain().equals(event.getChain())) {
            event.setChain(chain.getChain());
            changed = true;
        }
        if (chain.getNetworkId() != null && chain.getNetworkId() != event.getNetworkId()) {
            event.setNetworkId(chain.getNetworkId());
            changed = true;
        }
        if (present(chain.getSenderAddress())) {
            String canonical = NetworkAddressFormat.canonicalAddress(targetNetwork, chain.getSenderAddress());
            if (canonical != null && !canonical.equals(event.getSenderAddress())) {
                event.setSenderAddress(canonical);
                changed = true;
            }
        }
        if (present(chain.getReceivedAddress())) {
            String canonical = NetworkAddressFormat.canonicalAddress(targetNetwork, chain.getReceivedAddress());
            if (canonical != null && !canonical.equals(event.getReceivedAddress())) {
                event.setReceivedAddress(canonical);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Cycle/5 N18: copy on-chain {@code txHash} / {@code chain} / {@code networkId} from the matching
     * {@code DEPOSIT_ONCHAIN} sibling onto the {@code FUNDING_HISTORY/Deposit} anchor.
     *
     * <p>After N17 the FH/Deposit row is the canonical FUND accounting anchor (basisRelevant=true,
     * EXTERNAL_TRANSFER_IN with BUY role). Without on-chain metadata, the row has {@code chain="BYBIT"}
     * and no {@code txHash}, which means {@link com.walletradar.application.linking.pipeline.clarification.BybitTransferContinuityRepairService}
     * cannot link it to the on-chain {@code EXTERNAL_TRANSFER_OUT} leg from the user's own wallet
     * (Metamask/TWT/Uniswap) via {@code findAllByTxHashAndNetworkIdAndSource}. The continuity link is
     * what tells the replay engine "this is a cross-wallet transfer between the same accounting
     * universe, carry basis forward; do not crystallise realised PnL on either side". Without it,
     * every cross-wallet transfer creates a phantom step-up: the on-chain side disposes at market
     * (realised PnL = market − AVCO; positive when price rose since acquisition), and the Bybit side
     * acquires fresh basis at market. Smoking-gun example: 0.862 CMETH moved from Metamask to Bybit
     * generated +$1,571 phantom realised gain and +$3,745 fresh basis on Bybit.
     *
     * <p>Hydration uses the same (asset, |qty|, ±tolerance) match as
     * {@link #hydrateInternalTransferDepositFromOnChain(IntegrationRawEvent, BybitExtractedEvent)}.
     * The DEPOSIT_ONCHAIN row itself stays {@code basisRelevant=false} (excluded via
     * {@code BYBIT_BASIS_IRRELEVANT}), so the continuity matcher filters it out via
     * {@code excludedFromAccounting} and matches the FH/Deposit anchor uniquely.
     */
    private void hydrateFundingHistoryDepositFromOnChain(IntegrationRawEvent rawEvent, BybitExtractedEvent event) {
        if (rawEvent == null || event == null || mongoOperations == null) {
            return;
        }
        if (!"Deposit".equalsIgnoreCase(event.getBybitType())) {
            return;
        }
        if (event.getQuantityRaw() == null || event.getAssetSymbol() == null) {
            return;
        }
        if (present(event.getTxHash())
                && event.getNetworkId() != null
                && present(event.getSenderAddress())
                && present(event.getReceivedAddress())) {
            return;
        }
        findMatchingChainDepositRow(
                rawEvent.getIntegrationId(),
                event.getAssetSymbol(),
                event.getQuantityRaw().abs(),
                event.getTimeUtc()
        ).ifPresent(chain -> copyFromChainSibling(event, chain));
    }

    /**
     * Cycle/5 N18: symmetric hydration for the withdrawal side. {@code FUNDING_HISTORY/Withdraw} is the
     * canonical FUND-disposal anchor (basisRelevant=true, EXTERNAL_TRANSFER_OUT with SELL role); the
     * chain-aware {@code WITHDRAWAL} stream is the basisRelevant=false continuity mirror. Without
     * on-chain {@code txHash} / {@code networkId} the continuity matcher cannot pair the FH/Withdraw
     * anchor with the on-chain EXTERNAL_TRANSFER_IN at the user's destination wallet, so the same
     * phantom step-up appears on outflows.
     */
    private void hydrateFundingHistoryWithdrawFromOnChain(IntegrationRawEvent rawEvent, BybitExtractedEvent event) {
        if (rawEvent == null || event == null || mongoOperations == null) {
            return;
        }
        if (!"Withdraw".equalsIgnoreCase(event.getBybitType())) {
            return;
        }
        if (event.getQuantityRaw() == null || event.getAssetSymbol() == null) {
            return;
        }
        if (present(event.getTxHash())
                && event.getNetworkId() != null
                && present(event.getSenderAddress())
                && present(event.getReceivedAddress())) {
            return;
        }
        findMatchingChainWithdrawalRow(
                rawEvent.getIntegrationId(),
                event.getAssetSymbol(),
                event.getQuantityRaw().abs(),
                event.getTimeUtc()
        ).ifPresent(chain -> copyFromChainSibling(event, chain));
    }

    private Optional<BybitExtractedEvent> findMatchingChainWithdrawalRow(
            String integrationId,
            String assetSymbol,
            BigDecimal quantityAbs,
            Instant anchorTime
    ) {
        if (integrationId == null || assetSymbol == null || quantityAbs == null) {
            return Optional.empty();
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("integrationId").is(integrationId),
                Criteria.where("sourceStream").is(BybitIntegrationStream.WITHDRAWAL.name()),
                Criteria.where("assetSymbol").is(assetSymbol),
                Criteria.where("timeUtc").ne(null)
        ));
        List<BybitExtractedEvent> hits = mongoOperations.find(query, BybitExtractedEvent.class);
        return pickFundingHistorySibling(hits, quantityAbs, anchorTime, /*withdrawal=*/true);
    }

    /**
     * Cycle/5 N18: tolerate the rounding gap between Bybit's {@code FUNDING_HISTORY} stream (full
     * precision, e.g. {@code 0.862092260317885 CMETH}) and the {@code DEPOSIT_ONCHAIN} /
     * {@code WITHDRAWAL} stream (typically 8-decimal precision, e.g. {@code 0.86209226 CMETH}). The
     * 1e-12 exact-match tolerance used elsewhere is too strict for this pair: the FH/Deposit anchor
     * never matches its on-chain sibling and no {@code txHash} is hydrated, which silently kills the
     * cross-wallet continuity link (we saw this for the 0.862 CMETH transfer that kept producing the
     * +$1,571 phantom realised gain on the Metamask side). We accept a match when either:
     * (a) the absolute delta is within 1e-12 (legacy exact path), or
     * (b) the absolute delta is within 1e-7 (covers 8-decimal-precision rounding on the chain side),
     *     or (c) the relative delta is below 0.05 % (handles different scaling for sub-satoshi assets).
     */
    private boolean quantitiesMatchWithChainPrecision(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        BigDecimal delta = left.subtract(right).abs();
        if (delta.compareTo(QTY_MATCH_TOLERANCE) <= 0) {
            return true;
        }
        BigDecimal chainPrecisionTolerance = new BigDecimal("0.0000001");
        if (delta.compareTo(chainPrecisionTolerance) <= 0) {
            return true;
        }
        BigDecimal denominator = left.abs().max(right.abs());
        if (denominator.signum() <= 0) {
            return false;
        }
        BigDecimal relativeDelta = delta.divide(denominator, java.math.MathContext.DECIMAL64);
        return relativeDelta.compareTo(new BigDecimal("0.0005")) <= 0;
    }

    /**
     * Cycle/5 N10: {@code /v5/account/transaction-log} ("FUNDING_HISTORY") is the canonical FUND wallet
     * record (Bybit field `showBusiType=fundingAccountRecord*`). Therefore every FH row is on the FUND
     * sub-account by default. The historical exception — routing `Earn`-typed FH rows to EARN — caused
     * the FUND debit leg of FlexSavings / Launchpool / Auto-Earn / Fixed-Savings principal flows to be
     * misattributed to EARN, leaving FUND inflated by phantom LDO / MNT / LINK / ONDO / USDC / USDT / etc.
     *
     * Routing rule (raw-evidence-driven):
     * <ul>
     *   <li>FH/Earn `REWARD_CLAIM` (interest / yield / on-chain rewards / bonuses) → EARN. Interest
     *       auto-compounds into the user's Earn position; the FH log entry is a FUND-side notification,
     *       not a FUND balance change.</li>
     *   <li>FH/Earn `On-chain Earn subscription` / `On-chain Earn redemption` → EARN. Off-chain origin
     *       (user subscribed via on-chain liquid-staking, e.g. CMETH / BBSOL); no FUND counterpart in the
     *       stream pair.</li>
     *   <li>All other FH/Earn rows (manual FlexSavings Subscribe/Redeem, Auto-Earn, Launchpool, Fixed
     *       Savings Principal Redemption) → FUND. They are the FUND-side leg of a transfer; the EARN side
     *       is provided by `EARN_FLEXIBLE_SAVING` (manual sub/redeem) or stays single-sided when no mirror
     *       exists (Auto-Earn / Fixed Savings). Single-sided FUND moves preserve umbrella mass balance
     *       (asset left FUND for an Earn product that is not separately indexed) without inflating EARN.</li>
     *   <li>Non-Earn FH rows (Transfer, Convert, Deposit, Withdraw, Crypto Loans, Airdrop, etc.) → FUND
     *       (default; unchanged from cycle/3 routing).</li>
     * </ul>
     */
    private BybitSubAccount inferFundingHistorySubAccount(BybitExtractedEvent event) {
        if (event.getBybitType() == null || !"earn".equalsIgnoreCase(event.getBybitType().trim())) {
            return BybitSubAccount.FUND;
        }
        if ("REWARD_CLAIM".equals(event.getCanonicalType())) {
            return BybitSubAccount.EARN;
        }
        String desc = event.getBybitDescription();
        String normalizedDesc = desc == null ? "" : desc.toLowerCase();
        boolean isOnChainEarnPrincipal = normalizedDesc.contains("on-chain earn subscription")
                || normalizedDesc.contains("on-chain earn redemption");
        if (isOnChainEarnPrincipal) {
            // Cycle/5 N10 carve-out: a positive on-chain earn subscription credits the EARN
            // logical bucket (asset arrives from off-chain, never lands on FUND wallet balance).
            // A negative subscription / a redemption row is the FUND-side leg of an
            // on-chain stake-or-redeem (asset moves FUND ↔ Earn), so it must debit/credit
            // FUND to preserve mass balance with the corresponding "Mint" / "Redeem" event.
            BigDecimal qty = event.getQuantityRaw();
            boolean isInbound = qty != null && qty.signum() > 0;
            if (isInbound && normalizedDesc.contains("on-chain earn subscription")) {
                return BybitSubAccount.EARN;
            }
            return BybitSubAccount.FUND;
        }
        if (normalizedDesc.contains("on-chain earn")) {
            return BybitSubAccount.EARN;
        }
        return BybitSubAccount.FUND;
    }

    /**
     * Dimensions the wallet ref to the custody leg that actually receives/sends the balance movement.
     */
    private BybitSubAccount inferSubAccountForTransferLeg(String fromAccountType, String toAccountType, boolean inbound) {
        String leg = inbound ? toAccountType : fromAccountType;
        return bybitAccountTypeLabelToSubAccount(leg);
    }

    private BybitSubAccount bybitAccountTypeLabelToSubAccount(String accountType) {
        String u = upper(accountType);
        if (u == null || u.isBlank()) {
            return BybitSubAccount.FUND;
        }
        if (u.contains("EARN") || u.contains("SAVING") || u.contains("LAUNCH")) {
            return BybitSubAccount.EARN;
        }
        if (u.contains("UNIFIED") || u.contains("CONTRACT") || u.contains("SPOT") || u.contains("OPTION")) {
            return BybitSubAccount.UTA;
        }
        if (u.contains("FUND") || u.contains("INVEST")) {
            return BybitSubAccount.FUND;
        }
        return BybitSubAccount.FUND;
    }

    private void applyBybitSubAccountWalletRef(BybitExtractedEvent event, BybitSubAccount subAccount) {
        if (event == null || subAccount == null) {
            return;
        }
        String walletRef = event.getWalletRef();
        if (walletRef == null || walletRef.isBlank()) {
            return;
        }
        String normalized = walletRef.trim();
        if (!normalized.toUpperCase(Locale.ROOT).startsWith(BYBIT_PREFIX)) {
            return;
        }
        // Avoid rewriting if already dimensioned.
        if (normalized.split(":").length >= 3) {
            return;
        }
        event.setWalletRef(normalized + ":" + subAccount.name());
    }

    enum BybitSubAccount {
        UTA,
        FUND,
        EARN
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
        // Cycle/5 N9: cover stream-specific timestamp field names so timeUtc never silently falls back to importedAt
        // (which would push historical sub-account transfers / earn subscribes to "today" and break replay ordering).
        event.setTimeUtc(firstNonNull(
                instant(payload, "transactionTime"),
                instant(payload, "execTime"),
                instant(payload, "createTime"),
                instant(payload, "updatedTime"),
                instant(payload, "timestamp"),
                instant(payload, "createdAt"),
                instant(payload, "updatedAt"),
                instant(payload, "transferDate"),
                instant(payload, "transactionDate"),
                instant(payload, "blockTime"),
                rawEvent.getOccurredAt()
        ));
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
        if (normalizedBusiness.contains("eth 2.0")) {
            return "ETH 2.0";
        }
        if (normalizedBusiness.contains("crypto") && normalizedBusiness.contains("loan")) {
            return "Crypto Loans";
        }
        if ("rewards".equals(normalizedBusiness)) {
            return "Rewards";
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

    private String mapFundingHistoryCanonicalType(
            String bybitType,
            String description,
            String assetSymbol,
            String fundingRaw
    ) {
        String normalizedType = normalize(bybitType);
        String normalizedDescription = normalize(description);
        if ((bybitType == null || bybitType.isBlank()) && normalizedDescription.contains("spot fee refund")) {
            return "REWARD_CLAIM";
        }
        if ("earn".equals(normalizedType)) {
            if (normalizedDescription.contains("interest distribution")
                    || normalizedDescription.contains("yield")
                    || normalizedDescription.contains("rewards distribution")
                    || normalizedDescription.contains("airdrop bonus")) {
                return "REWARD_CLAIM";
            }
            if (normalizedDescription.contains("subscription")
                    || normalizedDescription.contains("redemption")
                    || normalizedDescription.contains("withdrawal")
                    || normalizedDescription.contains("auto-withdrawal")
                    || normalizedDescription.contains("on-chain earn subscription")
                    || normalizedDescription.contains("on-chain earn redemption")) {
                return "INTERNAL_TRANSFER";
            }
            if (fundingRaw == null || fundingRaw.isBlank()) {
                return "INTERNAL_TRANSFER";
            }
            return "UNKNOWN_CEX";
        }
        if ("convert".equals(normalizedType)) {
            return "SWAP";
        }
        if ("transfer in".equals(normalizedType) || "transfer out".equals(normalizedType) || "bot".equals(normalizedType)) {
            return "INTERNAL_TRANSFER";
        }
        if ("fiat".equals(normalizedType) || "p2p purchase".equals(normalizedType)) {
            return "external_in_fiat_p2p";
        }
        if ("p2p sale".equals(normalizedType)) {
            return "external_out_fiat_p2p";
        }
        if ("withdraw".equals(normalizedType)) {
            return "EXTERNAL_TRANSFER_OUT";
        }
        if ("deposit".equals(normalizedType)) {
            return "EXTERNAL_INBOUND";
        }
        if ("airdrop".equals(normalizedType)) {
            return "REWARD_CLAIM";
        }
        if ("rewards".equals(normalizedType)) {
            return "REWARD_CLAIM";
        }
        if ("eth 2.0".equals(normalizedType)) {
            if (normalizedDescription.contains("mint") || normalizedDescription.contains("stake")) {
                return "INTERNAL_TRANSFER";
            }
            return "UNKNOWN_CEX";
        }
        if ("crypto loans".equals(normalizedType)) {
            if (normalizedDescription.contains("borrow released")) {
                return "BORROW";
            }
            if (normalizedDescription.contains("borrow repayment")) {
                return "REPAY";
            }
            if (normalizedDescription.contains("increase collateral") || normalizedDescription.contains("decrease collateral")) {
                return "INTERNAL_TRANSFER";
            }
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

    private boolean isBasisRelevantCanonicalType(String canonicalType) {
        return switch (canonicalType) {
            case "REWARD_CLAIM", "VAULT_DEPOSIT", "VAULT_WITHDRAW", "EXTERNAL_INBOUND", "EXTERNAL_TRANSFER_OUT",
                    "external_in_fiat_p2p", "external_out_fiat_p2p",
                    "SWAP", "STAKING_DEPOSIT", "BORROW", "REPAY", "INTERNAL_TRANSFER", "FEE" -> true;
            default -> false;
        };
    }

    /**
     * ADR-011 D7: Fiat P2P credit is authoritative; a sibling INTERNAL_TRANSFER mirror in the same window is not.
     */
    private void suppressFiatP2pInternalTransferMirror(IntegrationRawEvent rawEvent, BybitExtractedEvent fiatEvent) {
        if (rawEvent == null || fiatEvent == null || mongoOperations == null
                || fiatEvent.getTimeUtc() == null
                || fiatEvent.getQuantityRaw() == null
                || fiatEvent.getAssetSymbol() == null) {
            return;
        }
        Instant center = fiatEvent.getTimeUtc();
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("integrationId").is(rawEvent.getIntegrationId()),
                Criteria.where("sourceStream").is(BybitIntegrationStream.FUNDING_HISTORY.name()),
                Criteria.where("canonicalType").is("INTERNAL_TRANSFER"),
                Criteria.where("uid").is(fiatEvent.getUid()),
                Criteria.where("assetSymbol").is(fiatEvent.getAssetSymbol()),
                Criteria.where("timeUtc").gte(center.minusSeconds(5)).lte(center.plusSeconds(5))
        ));
        List<BybitExtractedEvent> siblings = mongoOperations.find(query, BybitExtractedEvent.class);
        BigDecimal fiatQty = fiatEvent.getQuantityRaw().abs();
        for (BybitExtractedEvent sibling : siblings) {
            if (sibling.getQuantityRaw() == null) {
                continue;
            }
            if (quantitiesMatchWithChainPrecision(fiatQty, sibling.getQuantityRaw().abs())) {
                sibling.setBasisRelevant(false);
                mongoOperations.save(sibling);
            }
        }
    }

    /**
     * G2: FH rows that duplicate an EXECUTION_SPOT fill for the same trade order id are not trade legs.
     */
    public boolean fundingHistoryDuplicatesExecutionSpot(BybitExtractedEvent event) {
        if (event == null || event.getTradeOrderId() == null || event.getTradeOrderId().isBlank()) {
            return false;
        }
        if (!BybitIntegrationStream.FUNDING_HISTORY.name().equalsIgnoreCase(event.getSourceStream())) {
            return false;
        }
        if (mongoOperations == null || event.getIntegrationId() == null) {
            return false;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("integrationId").is(event.getIntegrationId()),
                Criteria.where("sourceStream").is(BybitIntegrationStream.EXECUTION_SPOT.name()),
                Criteria.where("tradeOrderId").is(event.getTradeOrderId())
        ));
        return mongoOperations.exists(query, BybitExtractedEvent.class);
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
            case "MANTLE", "MNT" -> NetworkId.MANTLE;
            case "SOL", "SOLANA" -> NetworkId.SOLANA;
            // FA-001 P1: TON corridor support — Bybit emits the chain as "TON".
            case "TON", "TONCOIN" -> NetworkId.TON;
            case "BSC", "BNB", "BEP20" -> NetworkId.BSC;
            case "AVAX", "AVALANCHE", "CCHAIN", "CAVAX" -> NetworkId.AVALANCHE;
            case "OP", "OPT", "OPTIMISM" -> NetworkId.OPTIMISM;
            case "POLYGON", "MATIC" -> NetworkId.POLYGON;
            case "LINEA" -> NetworkId.LINEA;
            case "ZKSYNC" -> NetworkId.ZKSYNC;
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
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

    /**
     * Cycle/5 N2: INTERNAL_TRANSFER auto-route rows keyed {@code deposit_*} inherit on-chain timestamp/hash
     * from the matching {@link BybitIntegrationStream#DEPOSIT_ONCHAIN} extract.
     */
    private void hydrateInternalTransferDepositFromOnChain(IntegrationRawEvent rawEvent, BybitExtractedEvent event) {
        if (rawEvent == null || event == null || mongoOperations == null) {
            return;
        }
        String key = rawEvent.getProviderEventKey();
        if (key == null || !key.startsWith("deposit_")) {
            return;
        }
        if (event.getQuantityRaw() == null || event.getAssetSymbol() == null) {
            return;
        }
        findMatchingChainDepositRow(
                rawEvent.getIntegrationId(),
                event.getAssetSymbol(),
                event.getQuantityRaw().abs(),
                event.getTimeUtc()
        ).ifPresent(chain -> {
            if (chain.getTimeUtc() != null) {
                event.setTimeUtc(chain.getTimeUtc());
            }
            copyFromChainSibling(event, chain);
        });
    }

    private Optional<BybitExtractedEvent> findMatchingChainDepositRow(
            String integrationId,
            String assetSymbol,
            BigDecimal quantityAbs,
            Instant anchorTime
    ) {
        if (integrationId == null || assetSymbol == null || quantityAbs == null) {
            return Optional.empty();
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("integrationId").is(integrationId),
                Criteria.where("sourceStream").is(BybitIntegrationStream.DEPOSIT_ONCHAIN.name()),
                Criteria.where("assetSymbol").is(assetSymbol),
                Criteria.where("timeUtc").ne(null)
        ));
        List<BybitExtractedEvent> hits = mongoOperations.find(query, BybitExtractedEvent.class);
        return pickFundingHistorySibling(hits, quantityAbs, anchorTime, /*withdrawal=*/false);
    }

    /**
     * FA-001 P0: pick the chain-aware sibling for a FUNDING_HISTORY anchor.
     *
     * <p>Strategy (in priority order):
     * <ol>
     *   <li>Time-first within {@link #FH_CHAIN_TIME_WINDOW}: every candidate whose {@code timeUtc} is
     *       within the window is scored by absolute time delta; if quantity matches within tolerance
     *       (deposit: exact ± tolerance; withdrawal: chain qty == FH qty − fee, within tolerance),
     *       we return the closest one.</li>
     *   <li>Fallback (no anchor time or no time-window candidate): exact-quantity match anywhere,
     *       picking the latest by timeUtc (legacy behaviour pre-P0 cycle/5).</li>
     * </ol>
     *
     * <p>This eliminates the previous failure mode where two distinct FH/Withdraw rows for the same
     * asset (e.g. 0.6 SOL vs 0.592 SOL with 0.008 SOL fee) collided against a single chain sibling
     * because the matcher compared {@code |fh|} against {@code |chain|} without ever consulting
     * {@code timeUtc} or the chain-side fee.</p>
     */
    private Optional<BybitExtractedEvent> pickFundingHistorySibling(
            List<BybitExtractedEvent> hits,
            BigDecimal fhQtyAbs,
            Instant anchorTime,
            boolean withdrawal
    ) {
        if (hits == null || hits.isEmpty()) {
            return Optional.empty();
        }
        if (anchorTime != null) {
            Optional<BybitExtractedEvent> windowed = hits.stream()
                    .filter(candidate -> candidate.getQuantityRaw() != null && candidate.getTimeUtc() != null)
                    .filter(candidate -> {
                        long deltaMs = Math.abs(Duration.between(candidate.getTimeUtc(), anchorTime).toMillis());
                        return deltaMs <= FH_CHAIN_TIME_WINDOW.toMillis();
                    })
                    .filter(candidate -> quantitiesMatchWithFeeTolerance(
                            fhQtyAbs,
                            candidate.getQuantityRaw().abs(),
                            withdrawal ? candidate.getFeePaid() : null
                    ))
                    .min(Comparator.comparingLong(
                            candidate -> Math.abs(Duration.between(candidate.getTimeUtc(), anchorTime).toMillis())
                    ));
            if (windowed.isPresent()) {
                return windowed;
            }
        }
        return hits.stream()
                .filter(candidate -> candidate.getQuantityRaw() != null
                        && quantitiesMatchWithChainPrecision(fhQtyAbs, candidate.getQuantityRaw().abs()))
                .max(Comparator.comparing(BybitExtractedEvent::getTimeUtc, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /**
     * Quantity consistency between a FUNDING_HISTORY anchor and its DEPOSIT_ONCHAIN / WITHDRAWAL
     * sibling. For deposits {@code |fh| ≈ |chain|}; for withdrawals {@code |fh| ≈ |chain| + |fee|}
     * because Bybit's FH/Withdraw row reports the gross debit (net + fee) whereas the WITHDRAWAL
     * stream reports the on-chain net plus a separate {@code withdrawFee}.
     */
    private boolean quantitiesMatchWithFeeTolerance(
            BigDecimal fhQtyAbs,
            BigDecimal chainQtyAbs,
            BigDecimal chainFeePaid
    ) {
        if (fhQtyAbs == null || chainQtyAbs == null) {
            return false;
        }
        if (quantitiesMatchWithChainPrecision(fhQtyAbs, chainQtyAbs)) {
            return true;
        }
        if (chainFeePaid == null) {
            return false;
        }
        BigDecimal grossed = chainQtyAbs.add(chainFeePaid.abs());
        return quantitiesMatchWithChainPrecision(fhQtyAbs, grossed);
    }

    private Optional<BybitExtractedEvent> findTxLogDepositAnchor(
            IntegrationRawEvent rawEvent,
            BybitExtractedEvent depositEvent
    ) {
        String integrationId = rawEvent.getIntegrationId();
        String uid = depositEvent.getUid();
        String asset = depositEvent.getAssetSymbol();
        BigDecimal qty = depositEvent.getQuantityRaw();
        Instant center = depositEvent.getTimeUtc();
        if (mongoOperations == null
                || integrationId == null
                || uid == null
                || asset == null
                || qty == null
                || center == null) {
            return Optional.empty();
        }
        Instant from = center.minusSeconds(60);
        Instant to = center.plusSeconds(60);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("integrationId").is(integrationId),
                Criteria.where("uid").is(uid),
                Criteria.where("sourceStream").is(BybitIntegrationStream.TRANSACTION_LOG.name()),
                Criteria.where("assetSymbol").is(asset),
                Criteria.where("timeUtc").gte(from).lte(to),
                new Criteria().orOperator(
                        Criteria.where("bybitType").is("TRANSFER_IN"),
                        Criteria.where("bybitType").is("DEPOSIT")
                )
        ));
        List<BybitExtractedEvent> hits = mongoOperations.find(query, BybitExtractedEvent.class);
        return hits.stream()
                .filter(candidate -> quantityMatchesDeposit(candidate.getQuantityRaw(), qty))
                .min(Comparator.comparingLong(a -> {
                    Instant t = a.getTimeUtc() != null ? a.getTimeUtc() : Instant.EPOCH;
                    return Math.abs(Duration.between(t, center).toMillis());
                }));
    }

    private static boolean quantityMatchesDeposit(BigDecimal candidateQty, BigDecimal depositQty) {
        if (candidateQty == null || depositQty == null) {
            return false;
        }
        if (candidateQty.subtract(depositQty).abs().compareTo(QTY_MATCH_TOLERANCE) <= 0) {
            return true;
        }
        return candidateQty.abs().subtract(depositQty.abs()).abs().compareTo(QTY_MATCH_TOLERANCE) <= 0;
    }

    private record SymbolPair(
            String base,
            String quote
    ) {
    }
}
