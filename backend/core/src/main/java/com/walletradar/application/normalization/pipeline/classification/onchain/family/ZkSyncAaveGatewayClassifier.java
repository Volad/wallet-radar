package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Narrow audited override for supported Aave gateway selectors that currently escape into
 * generic unwrap / LP fallback lanes.
 */
@Component
public class ZkSyncAaveGatewayClassifier implements OnChainFamilyClassifier {

    private static final String WITHDRAW_ETH_SELECTOR = "0x80500d20";
    private static final String SUPPLY_WITH_PERMIT_SELECTOR = "0x02c205f0";
    private static final String DEPOSIT_ETH_SELECTOR = "0x474cf53d";

    private static final String NATIVE_ETH_SYMBOL = "ETH";
    private static final String WRAPPED_ETH_SYMBOL = "WETH";
    private static final String ZKSYNC_AAVE_RECEIPT_SYMBOL = "AZKSWETH";
    private static final String BASE_AAVE_RECEIPT_SYMBOL = "AWETH";
    private static final Map<NetworkId, String> DEPOSIT_ETH_RECEIPT_SYMBOLS = Map.of(
            NetworkId.ZKSYNC, ZKSYNC_AAVE_RECEIPT_SYMBOL,
            NetworkId.BASE, BASE_AAVE_RECEIPT_SYMBOL
    );

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 140;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null || context.view().networkId() == null) {
            return Optional.empty();
        }
        Optional<ClassificationDecision> baseBorrowEth = classifyBorrowEth(context);
        if (baseBorrowEth.isPresent()) {
            return baseBorrowEth;
        }
        Optional<ClassificationDecision> repayWithATokens = classifyRepayWithATokens(context);
        if (repayWithATokens.isPresent()) {
            return repayWithATokens;
        }
        Optional<ClassificationDecision> selectorBased = switch (context.view().methodId()) {
            case WITHDRAW_ETH_SELECTOR -> classifyWithdrawEth(context);
            case SUPPLY_WITH_PERMIT_SELECTOR -> classifySupplyWithPermit(context);
            case DEPOSIT_ETH_SELECTOR -> classifyDepositEth(context);
            default -> Optional.empty();
        };
        if (selectorBased.isPresent()) {
            return selectorBased;
        }
        // R6b: ERC-20 Aave supply/withdraw on zkSync that bypasses the registered pool address
        // (e.g. via a router or smart-wallet contract) falls to heuristics (REWARD_CLAIM / LP_EXIT).
        // Intercept based on aToken symbol shape (prefix "aZks" for zkSync aTokens).
        return classifyZkSyncAaveErc20Lending(context);
    }

    private Optional<ClassificationDecision> classifyBorrowEth(OnChainClassificationContext context) {
        if (!functionStartsWith(context, "borroweth(")) {
            return Optional.empty();
        }
        if (!hasDebtMarker(context.movementLegs(), 1)) {
            return Optional.empty();
        }
        return Optional.of(build(context, NormalizedTransactionType.BORROW));
    }

    private Optional<ClassificationDecision> classifyRepayWithATokens(OnChainClassificationContext context) {
        if (!functionStartsWith(context, "repaywithatokens(")) {
            return Optional.empty();
        }
        if (!hasDebtMarker(context.movementLegs(), -1) || !hasOutboundAaveReceipt(context.movementLegs())) {
            return Optional.empty();
        }
        return Optional.of(build(context, NormalizedTransactionType.REPAY));
    }

    private Optional<ClassificationDecision> classifyWithdrawEth(OnChainClassificationContext context) {
        if (context.view().networkId() != NetworkId.ZKSYNC) {
            return Optional.empty();
        }
        if (!hasOutbound(context.movementLegs(), ZKSYNC_AAVE_RECEIPT_SYMBOL)
                || !hasInbound(context.movementLegs(), NATIVE_ETH_SYMBOL)) {
            return Optional.empty();
        }
        return Optional.of(build(context, NormalizedTransactionType.LENDING_WITHDRAW));
    }

    private Optional<ClassificationDecision> classifySupplyWithPermit(OnChainClassificationContext context) {
        if (context.view().networkId() != NetworkId.ZKSYNC) {
            return Optional.empty();
        }
        if (!hasOutbound(context.movementLegs(), WRAPPED_ETH_SYMBOL)
                || !hasInbound(context.movementLegs(), ZKSYNC_AAVE_RECEIPT_SYMBOL)) {
            return Optional.empty();
        }
        return Optional.of(build(context, NormalizedTransactionType.LENDING_DEPOSIT));
    }

    private Optional<ClassificationDecision> classifyDepositEth(OnChainClassificationContext context) {
        String receiptSymbol = DEPOSIT_ETH_RECEIPT_SYMBOLS.get(context.view().networkId());
        if (receiptSymbol == null) {
            return Optional.empty();
        }
        if (!hasOutbound(context.movementLegs(), NATIVE_ETH_SYMBOL)
                || !hasInbound(context.movementLegs(), receiptSymbol)) {
            return Optional.empty();
        }
        return Optional.of(build(context, NormalizedTransactionType.LENDING_DEPOSIT));
    }

    private ClassificationDecision build(
            OnChainClassificationContext context,
            NormalizedTransactionType type
    ) {
        return FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                ParityFlowSupport.flows(context.view(), context.movementLegs(), type),
                List.of(),
                "Aave",
                "V3"
        );
    }

    private boolean hasInbound(List<RawLeg> movementLegs, String symbol) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .anyMatch(leg -> leg.quantityDelta().signum() > 0 && matchesSymbol(leg, symbol));
    }

    private boolean hasOutbound(List<RawLeg> movementLegs, String symbol) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .anyMatch(leg -> leg.quantityDelta().signum() < 0 && matchesSymbol(leg, symbol));
    }

    private boolean matchesSymbol(RawLeg leg, String symbol) {
        if (leg == null || symbol == null) {
            return false;
        }
        String normalizedLegSymbol = leg.assetSymbol() == null ? "" : leg.assetSymbol().trim().toUpperCase(Locale.ROOT);
        return symbol.equals(normalizedLegSymbol);
    }

    private boolean functionStartsWith(OnChainClassificationContext context, String functionPrefix) {
        String functionName = context.view().functionName();
        if (functionName != null && functionName.startsWith(functionPrefix)) {
            return true;
        }
        for (Document transfer : context.view().explorerTokenTransfers()) {
            Object transferFunction = transfer == null ? null : transfer.get("functionName");
            if (transferFunction != null
                    && transferFunction.toString().trim().toLowerCase(Locale.ROOT).startsWith(functionPrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDebtMarker(List<RawLeg> movementLegs, int signum) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .filter(leg -> leg.quantityDelta().signum() == signum)
                .anyMatch(this::isAaveDebtMarker);
    }

    private boolean hasOutboundAaveReceipt(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .filter(leg -> leg.quantityDelta().signum() < 0)
                .anyMatch(this::isAaveReceipt);
    }

    private boolean isAaveDebtMarker(RawLeg leg) {
        String symbol = leg.assetSymbol() == null ? "" : leg.assetSymbol().trim().toUpperCase(Locale.ROOT);
        return symbol.startsWith("VARIABLEDEBT") || symbol.startsWith("STABLEDEBT");
    }

    private boolean isAaveReceipt(RawLeg leg) {
        String symbol = leg.assetSymbol() == null ? "" : leg.assetSymbol().trim().toUpperCase(Locale.ROOT);
        return symbol.startsWith("A") && !isAaveDebtMarker(leg);
    }

    /**
     * R6b: Detects Aave ERC-20 supply / withdraw on zkSync when the tx routes through an
     * unregistered contract (router, smart-wallet) and falls to heuristic classification.
     *
     * <p>Detection is based on the zkSync-specific aToken symbol prefix {@code aZks…} (uppercased:
     * {@code AZKS…}). This prefix is exclusive to Aave V3 on zkSync Era and unlikely to collide
     * with other DeFi protocols.
     *
     * <ul>
     *   <li>Inbound aToken + outbound non-aToken → {@code LENDING_DEPOSIT} (supply)</li>
     *   <li>Outbound aToken + inbound non-aToken → {@code LENDING_WITHDRAW} (withdraw)</li>
     * </ul>
     */
    private Optional<ClassificationDecision> classifyZkSyncAaveErc20Lending(OnChainClassificationContext context) {
        if (context.view().networkId() != NetworkId.ZKSYNC) {
            return Optional.empty();
        }
        List<RawLeg> legs = context.movementLegs();
        if (legs == null || legs.isEmpty()) {
            return Optional.empty();
        }

        boolean hasInboundZkSyncAToken = legs.stream()
                .anyMatch(leg -> leg != null && !leg.fee()
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0
                        && isZkSyncAToken(leg.assetSymbol()));
        boolean hasOutboundZkSyncAToken = legs.stream()
                .anyMatch(leg -> leg != null && !leg.fee()
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() < 0
                        && isZkSyncAToken(leg.assetSymbol()));
        boolean hasOutboundNonAToken = legs.stream()
                .anyMatch(leg -> leg != null && !leg.fee()
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() < 0
                        && !isZkSyncAToken(leg.assetSymbol()) && !isAaveDebtMarker(leg));
        boolean hasInboundNonAToken = legs.stream()
                .anyMatch(leg -> leg != null && !leg.fee()
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0
                        && !isZkSyncAToken(leg.assetSymbol()) && !isAaveDebtMarker(leg));

        // SUPPLY: outbound ERC-20 + inbound aToken (no aToken going out, no ERC-20 coming in)
        if (hasInboundZkSyncAToken && !hasOutboundZkSyncAToken
                && hasOutboundNonAToken && !hasInboundNonAToken) {
            return Optional.of(build(context, NormalizedTransactionType.LENDING_DEPOSIT));
        }
        // WITHDRAW: outbound aToken + inbound ERC-20 (no aToken coming in, no ERC-20 going out)
        if (hasOutboundZkSyncAToken && !hasInboundZkSyncAToken
                && hasInboundNonAToken && !hasOutboundNonAToken) {
            return Optional.of(build(context, NormalizedTransactionType.LENDING_WITHDRAW));
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} when the asset symbol is a zkSync Aave aToken (prefix {@code AZKS}).
     * This prefix is exclusive to Aave V3 on zkSync Era (e.g. {@code aZksWETH}, {@code aZksZK}).
     */
    private static boolean isZkSyncAToken(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        return assetSymbol.trim().toUpperCase(Locale.ROOT).startsWith("AZKS");
    }
}
