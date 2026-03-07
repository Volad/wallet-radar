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
            "0xc04a8a10", // approveDelegation(address,uint256)
            "0x5a3b74b9", // setUserUseReserveAsCollateral(address,bool)
            "0xf3995c67", // selfPermit(address,uint256,uint256,uint8,bytes32,bytes32)
            "0xc2e3140a", // selfPermitIfNecessary(address,uint256,uint256,uint8,bytes32,bytes32)
            "0x30f28b7a", // permitTransferFrom(((address,uint256),uint256,uint256),(address,uint256),address,bytes)
            "0x137c29fe", // permitWitnessTransferFrom(((address,uint256),uint256,uint256),(address,uint256),address,bytes32,string,bytes)
            "0xcc53287f", // lockdown((address token, address spender)[] approvals)
            "0xfa6e671d", // setRelayerApproval(address sender, address relayer, bool approved)
            "0x0de54ba0", // setMinterApproval(address minter, bool approval)
            "0x110496e5"  // allow(address manager,bool isAllowed_)
    );

    private static final Set<String> APPROVAL_FUNCTION_HINTS = Set.of(
            "approve(",
            "setapprovalforall(",
            "increaseallowance(",
            "decreaseallowance(",
            "permit(",
            "approvedelegation(",
            "lockdown(",
            "setrelayerapproval(",
            "setminterapproval(",
            "allow("
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
        for (Document transfer : tx.explorerTokenTransfers()) {
            BigInteger value = tx.tokenTransferValue(transfer);
            if (value != null && value.signum() > 0) {
                return true;
            }
        }
        for (Document transfer : tx.explorerInternalTransfers()) {
            BigInteger value = tx.internalTransferValue(transfer);
            if (value != null && value.signum() > 0) {
                return true;
            }
        }
        for (Document log : tx.logs()) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.isEmpty() || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount != null && amount.signum() > 0) {
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
