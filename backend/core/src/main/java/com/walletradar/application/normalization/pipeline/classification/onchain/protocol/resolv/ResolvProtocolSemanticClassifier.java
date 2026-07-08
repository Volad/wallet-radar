package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.resolv;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Resolv-owned unstake request / settlement semantic detection.
 */
@Component
public class ResolvProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    public static final String PROTOCOL_KEY = "resolv";
    public static final String PROTOCOL_NAME = "Resolv";
    public static final String SEMANTIC_STAKING_WITHDRAW_REQUEST = "staking_withdraw_request";
    public static final String SEMANTIC_STAKING_WITHDRAW = "staking_withdraw";

    private static final String RESOLV_STAKED_TOKEN_CONTRACT = "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23";
    private static final String RESOLV_TOKEN_CONTRACT = "0x259338656198ec7a76c729514d3cb45dfbf768a1";
    private static final String INITIATE_WITHDRAWAL_SELECTOR = "0x12edde5e";
    private static final String RESOLV_WITHDRAW_SELECTOR = "0xe1e13847";
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        String functionKey = functionKey(context.view().functionName());
        if (isBurnOnlyUnbondingRequest(context.view(), context.movementLegs(), functionKey)) {
            return List.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_STAKING_WITHDRAW_REQUEST,
                    PROTOCOL_NAME,
                    null,
                    resolveResolvCorrelationId(context.view(), context.movementLegs()),
                    NormalizedTransactionType.STAKING_WITHDRAW_REQUEST,
                    ConfidenceLevel.MEDIUM
            ));
        }
        if (isResolvWithdrawSettlement(context.view(), context.movementLegs(), functionKey)) {
            return List.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_STAKING_WITHDRAW,
                    PROTOCOL_NAME,
                    null,
                    resolveResolvCorrelationId(context.view(), context.movementLegs()),
                    NormalizedTransactionType.STAKING_WITHDRAW,
                    ConfidenceLevel.MEDIUM
            ));
        }
        return List.of();
    }

    private boolean isBurnOnlyUnbondingRequest(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (!INITIATE_WITHDRAWAL_SELECTOR.equals(view.methodId()) && !"initiatewithdrawal".equals(functionKey)) {
            return false;
        }
        return onlyOutbound(movementLegs)
                && hasBurnToZeroTransferFromWallet(view)
                && !hasInboundTransferToWallet(view);
    }

    private boolean isResolvWithdrawSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (!RESOLV_WITHDRAW_SELECTOR.equals(view.methodId()) && !"withdraw".equals(functionKey)) {
            return false;
        }
        if (!onlyInbound(movementLegs)) {
            return false;
        }
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0)
                .anyMatch(this::isResolvUnderlyingLeg);
    }

    private String resolveResolvCorrelationId(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view == null || view.walletAddress() == null) {
            return null;
        }
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0 && isResolvStakedLeg(leg)) {
                return "resolv-unstake:"
                        + view.walletAddress()
                        + ":"
                        + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            }
            if (leg.quantityDelta().signum() > 0 && isResolvUnderlyingLeg(leg)) {
                return "resolv-unstake:"
                        + view.walletAddress()
                        + ":"
                        + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            }
        }
        return null;
    }

    private boolean isResolvStakedLeg(RawLeg leg) {
        if (leg == null) {
            return false;
        }
        if (RESOLV_STAKED_TOKEN_CONTRACT.equalsIgnoreCase(leg.assetContract())) {
            return true;
        }
        return "stresolv".equalsIgnoreCase(leg.assetSymbol());
    }

    private boolean isResolvUnderlyingLeg(RawLeg leg) {
        if (leg == null) {
            return false;
        }
        if (RESOLV_TOKEN_CONTRACT.equalsIgnoreCase(leg.assetContract())) {
            return true;
        }
        return "resolv".equalsIgnoreCase(leg.assetSymbol());
    }

    private boolean hasBurnToZeroTransferFromWallet(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))
                    && isZeroAddress(view.tokenTransferTo(transfer))) {
                return true;
            }
        }
        return hasBurnedFungibleTransferFromWallet(view);
    }

    private boolean hasBurnedFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesPrimaryWallet(view, topicAddress(topicAt(log, 1)))
                    && isZeroAddress(topicAddress(topicAt(log, 2)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInboundTransferToWallet(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            if (matchesPrimaryWallet(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return hasAnyInboundFungibleTransferToWallet(view);
    }

    private boolean hasAnyInboundFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesPrimaryWallet(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean isErc20TransferLog(Document log) {
        List<String> topics = normalizedTopics(log);
        return topics.size() >= 3 && ERC20_TRANSFER_TOPIC.equals(topics.getFirst());
    }

    private List<String> normalizedTopics(Document log) {
        if (log == null) {
            return List.of();
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics) || topics.isEmpty()) {
            return List.of();
        }
        return topics.stream()
                .map(topic -> topic == null ? null : topic.toString().trim().toLowerCase(Locale.ROOT))
                .filter(topic -> topic != null && !topic.isBlank())
                .toList();
    }

    private String topicAt(Document log, int index) {
        List<String> topics = normalizedTopics(log);
        return index >= 0 && index < topics.size() ? topics.get(index) : null;
    }

    private String topicAddress(String topic) {
        if (topic == null || topic.length() < 40) {
            return null;
        }
        return "0x" + topic.substring(topic.length() - 40);
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String candidate) {
        if (view == null || view.walletAddress() == null || candidate == null) {
            return false;
        }
        return view.walletAddress().equalsIgnoreCase(candidate);
    }

    private boolean isZeroAddress(String address) {
        if (address == null) {
            return false;
        }
        return "0x0000000000000000000000000000000000000000".equalsIgnoreCase(address);
    }

    private String functionKey(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return "";
        }
        int parenIndex = functionName.indexOf('(');
        String candidate = parenIndex >= 0 ? functionName.substring(0, parenIndex) : functionName;
        return candidate.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean onlyOutbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() != 0)
                .allMatch(leg -> leg.quantityDelta().signum() < 0);
    }

    private boolean onlyInbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() != 0)
                .allMatch(leg -> leg.quantityDelta().signum() > 0);
    }
}
