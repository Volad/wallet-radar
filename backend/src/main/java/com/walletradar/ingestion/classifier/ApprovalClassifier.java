package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies non-economic permission updates (approve / permit / setApprovalForAll)
 * so they do not fall into NEEDS_REVIEW.
 */
@Component
@Order(60)
public class ApprovalClassifier implements TxClassifier {

    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private static final Set<String> KNOWN_APPROVAL_SELECTORS = Set.of(
            "0x095ea7b3", // approve(address,uint256)
            "0xa22cb465", // setApprovalForAll(address,bool)
            "0x39509351", // increaseAllowance(address,uint256)
            "0xa457c2d7", // decreaseAllowance(address,uint256)
            "0xc04a8a10"  // observed BASE permission call (Aave-style approval/delegation)
    );

    private static final Set<String> APPROVAL_FUNCTION_HINTS = Set.of(
            "approve(",
            "setapprovalforall(",
            "increaseallowance(",
            "decreaseallowance(",
            "permit(",
            "approvedelegation("
    );

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || !tx.hasRawData() || walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        if (isFailedTx(tx)) {
            return List.of();
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String sender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || sender == null || !wallet.equals(sender)) {
            return List.of();
        }

        BigInteger value = tx.readRawOrExplorerUnsigned("value");
        if (value != null && value.signum() > 0) {
            return List.of();
        }
        if (!isApprovalSignature(tx)) {
            return List.of();
        }
        if (hasEconomicTransferEffects(tx)) {
            return List.of();
        }

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.APPROVAL);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(tx.readRawOrExplorerAddress("to"));
        event.setAssetSymbol("");
        event.setQuantityDelta(BigDecimal.ZERO);
        event.setCounterpartyAddress(tx.decodeCalldataAddressArg(0));
        return List.of(event);
    }

    private static boolean hasEconomicTransferEffects(RawTransactionNormalizationView tx) {
        if (!tx.explorerTokenTransfers().isEmpty() || !tx.explorerInternalTransfers().isEmpty()) {
            return true;
        }
        for (Document log : tx.logs()) {
            List<String> topics = tx.getLogTopics(log);
            if (topics != null && !topics.isEmpty() && TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isApprovalSignature(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null && KNOWN_APPROVAL_SELECTORS.contains(selector)) {
            return true;
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        for (String hint : APPROVAL_FUNCTION_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFailedTx(RawTransactionNormalizationView tx) {
        String isError = tx.readRawOrExplorerLower("isError");
        if ("1".equals(isError)) {
            return true;
        }
        String receiptStatus = tx.readRawOrExplorerLower("txreceipt_status");
        if ("0".equals(receiptStatus) || "0x0".equals(receiptStatus)) {
            return true;
        }
        String status = tx.readRawOrExplorerLower("status");
        return "0x0".equals(status);
    }
}
