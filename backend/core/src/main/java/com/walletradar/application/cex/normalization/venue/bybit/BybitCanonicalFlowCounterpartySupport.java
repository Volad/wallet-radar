package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.linking.pipeline.clarification.CounterpartyType;
import com.walletradar.application.linking.pipeline.clarification.FlowCounterpartySupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.*;
import com.walletradar.session.application.AccountingUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

@Component
class BybitCanonicalFlowCounterpartySupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(BybitCanonicalFlowCounterpartySupport.class);
    private static final Set<String> STABLECOIN_SYMBOLS = Set.of("USDT","USDC","USDE","USDS","USDD","DAI","FDUSD","PYUSD","TUSD","USD1");
    private static final String BYBIT_PREFIX = "BYBIT:";
    private static final String FIAT_P2P_COUNTERPARTY = "FIAT:P2P";
    private static final String TX_HASH_MISSING_BYBIT_CORRIDOR = "TX_HASH_MISSING_BYBIT_CORRIDOR";
    private static final String BYBIT_HOT_WALLET_PREFIX = "BYBIT:HOT_WALLET:";
    private static final String BOT_TRANSFER_MARKER = "BOT_TRANSFER";
    private static final String BOT_TRANSFER_PENDING_COST = "BOT_TRANSFER_PENDING_COST";
    private final AccountingUniverseService accountingUniverseService;
    BybitCanonicalFlowCounterpartySupport(@Nullable AccountingUniverseService accountingUniverseService){this.accountingUniverseService=accountingUniverseService;}
void finalizeBybitFlows(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        if (transaction == null || row == null) {
            return;
        }
        applyBybitFlowCounterparty(transaction, row);
        FlowCounterpartySupport.applyTransactionCounterparty(transaction);
    }
void applyBybitFlowCounterparty(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        String uid = normalize(row.getUid());
        String walletRef = resolveWalletRef(row);
        String masterUid = extractUid(walletRef);
        if (masterUid.isBlank()) {
            masterUid = uid;
        }
        String accountRef = walletRef;
        NormalizedTransactionType type = transaction.getType();

        if (isFiatP2pRow(row)) {
            String fundRef = BYBIT_PREFIX + masterUid + ":FUND";
            String fiatCounterparty = resolveFiatP2pCounterparty(row);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(fundRef);
                flow.setCounterpartyAddress(fiatCounterparty);
                flow.setCounterpartyType(CounterpartyType.CEX);
                applyStableUsdPegIfEligible(flow);
            }
            transaction.setCounterpartyAddress(fiatCounterparty);
            transaction.setCounterpartyType(CounterpartyType.CEX);
            return;
        }

        if (type == NormalizedTransactionType.SWAP) {
            String matchedBook = BYBIT_PREFIX + masterUid + ":MATCHED_BOOK";
            String convertKey = resolveConvertCounterpartyKey(row, masterUid);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(accountRef);
                flow.setCounterpartyAddress(convertKey != null ? convertKey : matchedBook);
                flow.setCounterpartyType(CounterpartyType.CEX);
            }
            return;
        }

        if (type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY) {
            String loanCounterparty = BYBIT_PREFIX + "LOAN:" + loanOrderKey(row);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(ledgerAccountRef(masterUid, walletRef, "UTA"));
                flow.setCounterpartyAddress(loanCounterparty);
                flow.setCounterpartyType(CounterpartyType.PROTOCOL);
            }
            return;
        }

        if (type == NormalizedTransactionType.REWARD_CLAIM) {
            String rewardCounterparty = BYBIT_PREFIX + "REWARD:" + normalizeRewardBusiType(row);
            String earnRef = ledgerAccountRef(masterUid, walletRef, "EARN");
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(earnRef);
                flow.setCounterpartyAddress(rewardCounterparty);
                flow.setCounterpartyType(CounterpartyType.PROTOCOL);
            }
            return;
        }

        if (type == NormalizedTransactionType.INTERNAL_TRANSFER) {
            String counterparty = resolveInternalTransferCounterparty(row, walletRef, masterUid);
            if (counterparty == null || counterparty.isBlank() || counterparty.equalsIgnoreCase(walletRef)) {
                LOGGER.warn(
                        "BYBIT_NORM_BAD_CP rowId={} walletRef={} bybitType={} bybitDescription={} sourceFile={} resolvedCp={}",
                        row == null ? null : row.getId(),
                        walletRef,
                        row == null ? null : row.getBybitType(),
                        row == null ? null : row.getBybitDescription(),
                        row == null ? null : row.getSourceFile(),
                        counterparty
                );
                counterparty = fallbackInternalTransferCounterparty(walletRef, masterUid);
            }
            transaction.setCounterpartyAddress(counterparty);
            transaction.setCounterpartyType(CounterpartyType.CEX);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                flow.setAccountRef(accountRef);
                flow.setCounterpartyAddress(counterparty);
                flow.setCounterpartyType(CounterpartyType.CEX);
            }
            return;
        }

        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            applyBybitExternalTransferCounterparty(transaction, row, masterUid, walletRef, /*inbound=*/true);
            return;
        }

        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            applyBybitExternalTransferCounterparty(transaction, row, masterUid, walletRef, /*inbound=*/false);
            return;
        }

        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            flow.setAccountRef(accountRef);
            String topLevel = transaction.getCounterpartyAddress();
            if (topLevel != null && !topLevel.isBlank()) {
                flow.setCounterpartyAddress(topLevel);
            }
            if (transaction.getCounterpartyType() != null) {
                flow.setCounterpartyType(transaction.getCounterpartyType());
            }
        }
    }
void applyBybitExternalTransferCounterparty(
            NormalizedTransaction transaction,
            ExternalLedgerRaw row,
            String masterUid,
            String walletRef,
            boolean inbound
    ) {
        NetworkId networkId = row.getNetworkId();
        String rawAddress = inbound ? row.getSenderAddress() : row.getReceivedAddress();
        String chainAddress = NetworkAddressFormat.canonicalAddress(networkId, rawAddress);
        boolean addressMissing = chainAddress == null || chainAddress.isBlank();
        if (addressMissing) {
            chainAddress = BYBIT_HOT_WALLET_PREFIX + (networkId == null ? "UNKNOWN" : networkId.name());
        }
        String counterpartyType = classifyExternalTransferCounterpartyType(
                addressMissing ? null : chainAddress,
                networkId
        );
        String fundRef = ledgerAccountRef(masterUid, walletRef, "FUND");
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            flow.setAccountRef(fundRef);
            flow.setCounterpartyAddress(chainAddress);
            flow.setCounterpartyType(counterpartyType);
        }
        transaction.setCounterpartyAddress(chainAddress);
        transaction.setCounterpartyType(counterpartyType);
    }
String classifyExternalTransferCounterpartyType(String address, NetworkId networkId) {
        if (address != null && accountingUniverseService != null) {
            try {
                AccountingUniverseService.OwnMembership membership = accountingUniverseService.classify(address, networkId);
                if (membership.isMember()) {
                    AccountingUniverse.MemberType memberType = membership.memberType();
                    if (memberType == AccountingUniverse.MemberType.ON_CHAIN_WALLET) {
                        return CounterpartyType.PERSONAL_WALLET;
                    }
                    if (memberType == AccountingUniverse.MemberType.EXCHANGE_ACCOUNT) {
                        return CounterpartyType.CEX;
                    }
                }
            } catch (IllegalStateException ignored) {
                // Universe not bound on this thread; fall through to UNKNOWN_EOA.
            }
        }
        return CounterpartyType.UNKNOWN_EOA;
    }
boolean isBotTransfer(ExternalLedgerRaw row) {
        return row != null && "Bot".equalsIgnoreCase(row.getBybitType() == null ? null : row.getBybitType().trim());
    }
void reclassifyBotTransfer(NormalizedTransaction transaction, ExternalLedgerRaw row, Instant now) {
        transaction.setCorrelationId(null);
        transaction.setContinuityCandidate(false);
        transaction.setMatchedCounterparty(null);

        BigDecimal qty = row.getQuantityRaw();
        int sign = qty == null ? 0 : qty.signum();

        if (sign > 0) {
            transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow != null && flow.getRole() == NormalizedLegRole.TRANSFER) {
                    flow.setRole(NormalizedLegRole.BUY);
                }
            }
        } else if (sign < 0) {
            transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow != null && flow.getRole() == NormalizedLegRole.TRANSFER) {
                    flow.setRole(NormalizedLegRole.SELL);
                }
            }
        }

        if (!transaction.getMissingDataReasons().contains(BOT_TRANSFER_MARKER)) {
            transaction.getMissingDataReasons().add(BOT_TRANSFER_MARKER);
        }

        boolean nonStablecoinReturn = sign > 0
                && row.getAssetSymbol() != null
                && !isStablecoin(row.getAssetSymbol());
        if (nonStablecoinReturn) {
            if (!transaction.getMissingDataReasons().contains(BOT_TRANSFER_PENDING_COST)) {
                transaction.getMissingDataReasons().add(BOT_TRANSFER_PENDING_COST);
            }
        }
    }
boolean isFiatP2pRow(ExternalLedgerRaw row) {
        if (row == null) {
            return false;
        }
        String canonical = normalizeCanonicalLiteral(row.getCanonicalType());
        if ("EXTERNAL_IN_FIAT_P2P".equals(canonical) || "EXTERNAL_OUT_FIAT_P2P".equals(canonical)) {
            return true;
        }
        String bybitType = normalize(row.getBybitType());
        String description = normalize(row.getBybitDescription());
        return "fiat".equals(bybitType)
                || (description != null && description.contains("p2p purchase"));
    }
String resolveFiatP2pCounterparty(ExternalLedgerRaw row) {
        return FIAT_P2P_COUNTERPARTY;
    }
void confirmFiatP2pTransaction(NormalizedTransaction transaction, ExternalLedgerRaw row, Instant now) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            applyStableUsdPegIfEligible(flow);
        }
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setConfirmedAt(now);
    }
void applyStableUsdPegForExternalTransfers(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return;
        }
        if (transaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            applyStableUsdPegIfEligible(flow);
        }
    }
void applyStableUsdPegIfEligible(NormalizedTransaction.Flow flow) {
        if (flow == null || !isStablecoin(flow.getAssetSymbol())) {
            return;
        }
        BigDecimal quantity = flow.getQuantityDelta();
        if (quantity == null) {
            return;
        }
        flow.setUnitPriceUsd(BigDecimal.ONE);
        flow.setPriceSource(PriceSource.STABLECOIN);
        flow.setValueUsd(Decimal128Support.normalize(quantity.abs()));
    }
void recordMissingTxHashForBybitCorridor(NormalizedTransaction transaction, ExternalLedgerRaw row) {
        if (transaction == null || row == null) {
            return;
        }
        String bybitType = row.getBybitType();
        if (bybitType == null) {
            return;
        }
        String normalizedType = normalize(bybitType);
        if (!"deposit".equals(normalizedType) && !"withdraw".equals(normalizedType)) {
            return;
        }
        if (row.getTxHash() != null && !row.getTxHash().isBlank()) {
            return;
        }
        if (!transaction.getMissingDataReasons().contains(TX_HASH_MISSING_BYBIT_CORRIDOR)) {
            transaction.getMissingDataReasons().add(TX_HASH_MISSING_BYBIT_CORRIDOR);
        }
    }
String resolveConvertCounterpartyKey(ExternalLedgerRaw row, String masterUid) {
        if (row == null || !isConvertType(row.getBybitType())) {
            return null;
        }
        String orderKey = firstNonBlank(row.getTradeOrderId(), extractConvertOrderKey(row.getId()));
        if (orderKey == null || orderKey.isBlank()) {
            return null;
        }
        return BYBIT_PREFIX + masterUid + ":CONVERT:" + orderKey;
    }
boolean isConvertType(String bybitType) {
        if (bybitType == null) {
            return false;
        }
        String normalized = normalize(bybitType);
        return "convert".equals(normalized) || "currency_buy".equals(normalized) || "currency_sell".equals(normalized);
    }
String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
String extractConvertOrderKey(String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return null;
        }
        int lastColon = rowId.lastIndexOf(':');
        return lastColon >= 0 ? rowId.substring(lastColon + 1) : rowId;
    }
String loanOrderKey(ExternalLedgerRaw row) {
        if (row.getTradeOrderId() != null && !row.getTradeOrderId().isBlank()) {
            return row.getTradeOrderId().trim();
        }
        if (row.getId() != null && !row.getId().isBlank()) {
            return row.getId();
        }
        return normalize(row.getBybitDescription());
    }
String normalizeRewardBusiType(ExternalLedgerRaw row) {
        String busi = row.getBybitType();
        if (busi == null || busi.isBlank()) {
            busi = row.getBybitDescription();
        }
        return busi == null || busi.isBlank() ? "UNKNOWN" : busi.trim();
    }
String resolveWalletRef(ExternalLedgerRaw row) {
        if (row.getWalletRef() != null && !row.getWalletRef().isBlank()) {
            return row.getWalletRef().trim();
        }
        return BYBIT_PREFIX + normalize(row.getUid());
    }
String extractUid(String walletRef) {
        if (walletRef == null || !walletRef.regionMatches(true, 0, BYBIT_PREFIX, 0, BYBIT_PREFIX.length())) {
            return "";
        }
        String remainder = walletRef.substring(BYBIT_PREFIX.length()).trim();
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon).trim() : remainder.trim();
    }
String ledgerAccountRef(String masterUid, String walletRef, String defaultLedger) {
        if (walletRef != null && walletRef.contains(":")) {
            return walletRef;
        }
        return BYBIT_PREFIX + masterUid + ":" + defaultLedger;
    }
String resolveInternalTransferCounterparty(ExternalLedgerRaw row, String walletRef, String masterUid) {
        if (row == null) {
            return null;
        }
        String subCorrelation = BybitCanonicalCorrelationSupport.bybitSubAccountTransferCorrelationId(row);
        if (subCorrelation != null) {
            String sibling = BybitCanonicalCorrelationSupport.otherSubAccount(walletRef);
            if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                return sibling;
            }
        }
        String sourceFile = normalize(row.getSourceFile());
        String description = normalize(row.getBybitDescription());
        String bybitType = normalize(row.getBybitType());
        if (sourceFile.contains("earn_flexible_saving")) {
            return BYBIT_PREFIX + masterUid + ":FUND";
        }
        if (sourceFile.contains("funding_history")) {
            if (description.contains("flexible savings") || description.contains("savings") || "earn".equalsIgnoreCase(row.getBybitType())) {
                return BYBIT_PREFIX + masterUid + ":EARN";
            }
            return BYBIT_PREFIX + masterUid + ":UTA";
        }
        if (sourceFile.contains("transaction_log")) {
            if (bybitType.contains("flexible_staking") || bybitType.contains("staking")) {
                return BYBIT_PREFIX + masterUid + ":EARN";
            }
            if (bybitType.contains("transfer")) {
                return BYBIT_PREFIX + masterUid + ":FUND";
            }
        }
        // Cycle/6 A1+: INTERNAL_TRANSFER (master ledger) rows describe the other side via
        // `bybitDescription` ("Transfer from <SRC> to <DST>"). Map the role names (FUND/UNIFIED
        // /UTA/EARN/FUNDING) onto sibling accounts. The own side is identified by walletRef so the
        // counterparty is whichever side of the description does NOT match the walletRef suffix.
        if (sourceFile.contains("internal_transfer")) {
            String walletAccount = walletAccountSuffix(walletRef);
            String descriptionOther = parseInternalTransferDescriptionOther(description, walletAccount);
            if (descriptionOther != null) {
                return BYBIT_PREFIX + masterUid + ":" + descriptionOther;
            }
            if (bybitType.contains("transfer in") && walletAccount != null) {
                String sibling = BybitCanonicalCorrelationSupport.otherSubAccount(walletRef);
                if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                    return sibling;
                }
            }
            if (bybitType.contains("transfer out") && walletAccount != null) {
                String sibling = BybitCanonicalCorrelationSupport.otherSubAccount(walletRef);
                if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
                    return sibling;
                }
            }
        }
        return null;
    }
static String walletAccountSuffix(String walletRef) {
        if (walletRef == null) {
            return null;
        }
        int colon = walletRef.lastIndexOf(':');
        if (colon < 0 || colon == walletRef.length() - 1) {
            return null;
        }
        return walletRef.substring(colon + 1).toUpperCase(Locale.ROOT);
    }
static String parseInternalTransferDescriptionOther(String description, String walletAccount) {
        if (description == null || description.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("transfer\\s+from\\s+(\\w+)\\s+to\\s+(\\w+)").matcher(description);
        if (!matcher.find()) {
            return null;
        }
        String src = normalizeAccountToken(matcher.group(1));
        String dst = normalizeAccountToken(matcher.group(2));
        if (src == null || dst == null) {
            return null;
        }
        if (walletAccount != null && walletAccount.equals(src)) {
            return dst;
        }
        if (walletAccount != null && walletAccount.equals(dst)) {
            return src;
        }
        return null;
    }
static String normalizeAccountToken(String token) {
        if (token == null) {
            return null;
        }
        String upper = token.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "FUND", "FUNDING" -> "FUND";
            case "UNIFIED", "UTA" -> "UTA";
            case "EARN", "SAVINGS" -> "EARN";
            default -> null;
        };
    }
String fallbackInternalTransferCounterparty(String walletRef, String masterUid) {
        String sibling = walletRef == null ? null : BybitCanonicalCorrelationSupport.otherSubAccount(walletRef);
        if (sibling != null && !sibling.equalsIgnoreCase(walletRef)) {
            return sibling;
        }
        if (walletRef != null && walletRef.toUpperCase(Locale.ROOT).endsWith(":FUND")) {
            return BYBIT_PREFIX + masterUid + ":UTA";
        }
        return BYBIT_PREFIX + masterUid + ":FUND";
    }
    private static String normalizeCanonicalLiteral(String canonicalType) {
        if (canonicalType == null || canonicalType.isBlank()) return null;
        String normalized = canonicalType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EXTERNAL_INBOUND" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            case "EXTERNAL_IN_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_IN.name();
            case "EXTERNAL_OUT_FIAT_P2P" -> NormalizedTransactionType.EXTERNAL_TRANSFER_OUT.name();
            default -> normalized;
        };
    }
    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private boolean isStablecoin(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) return false;
        return STABLECOIN_SYMBOLS.contains(assetSymbol.trim().toUpperCase(Locale.ROOT));
    }
}
