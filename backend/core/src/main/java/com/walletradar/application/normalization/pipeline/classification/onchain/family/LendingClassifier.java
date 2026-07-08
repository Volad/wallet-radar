package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.platform.networks.evm.rpc.EvkVaultShareRateResolver;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lending family classifier for clarified batch and Euler loop semantics that still resolve
 * through final fallback ordering.
 */
@Component
public class LendingClassifier implements OnChainFamilyClassifier {

    private static final Set<String> EULER_BATCH_ROUTERS = Set.of(
            "0xddcbe30a761edd2e19bba930a977475265f36fa1",
            "0x7bdbd0a7114aa42ca957f292145f6a931a345583"
    );
    private static final Set<String> EULER_KNOWN_VAULT_CONTRACTS = Set.of(
            // Plasma network vaults (wstUSR/USDT0 loop history).
            "0x4718484ac9dc07fbbc078561e8f8ef29e2a369cd",
            "0xac40d41ab11b0eb991a7d34d55dbdbb7849e92ef",
            // Avalanche network vaults (eUSDC-2, eUSDt-2 leveraged loops via EVC batch router).
            "0x39de0f00189306062d79edec6dca5bb6bfd108f9",
            "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4"
    );
    private static final String EULER_CALL_WITH_CONTEXT_TOPIC =
            "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec";
    private static final String EULER_BORROW_EVENT_TOPIC =
            "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750";
    private static final String EULER_BORROW_SELECTOR = "4b3fd148";
    private static final String EULER_BATCH_DECODER_REQUIRED =
            ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code();
    private static final String EULER_DEUSD_CONTRACT = "0xb57b25851fe2311cc3fe511c8f10e868932e0680";
    private static final String EULER_SHARE_PRICE_INFERENCE_REASON = "EULER_LOOP_TX_LOCAL_SHARE_PRICE";
    private static final String EULER_STABLE_PRICE_INFERENCE_REASON = "EULER_LOOP_STABLE_ANCHOR";
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /**
     * Maximum relative divergence between the source-share and replacement-share underlying USD value for
     * a pairing to be treated as a genuine value-for-value Euler rebalance. Set generously (2x) so only
     * gross mis-pairings (e.g. a $1,380 collateral relocation mistaken for a $5 share mint) are rejected;
     * a legitimate same-value collateral swap stays well within this band.
     */
    private static final BigDecimal REBALANCE_VALUE_EQUIVALENCE_MAX_DIVERGENCE = new BigDecimal("0.5");

    /** Underlying USD price of a USD-stablecoin EVK vault's underlying asset (peg). */
    private static final BigDecimal STABLE_UNDERLYING_USD = BigDecimal.ONE;

    private final EvkVaultShareRateResolver shareRateResolver;

    @Autowired
    public LendingClassifier(@Nullable EvkVaultShareRateResolver shareRateResolver) {
        this.shareRateResolver = shareRateResolver;
    }

    /** Convenience constructor for tests / manual wiring without on-chain EVK rate resolution. */
    public LendingClassifier() {
        this(null);
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_ECONOMIC_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 90;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ClassificationDecision> semanticDecision = classifyFromSemantics(context);
        if (semanticDecision.isPresent()) {
            return semanticDecision;
        }

        if (!isClarifiedBatchCandidate(context.view())) {
            return Optional.empty();
        }

        Optional<ClassificationDecision> eulerLoopDecision = classifyEulerLoopPath(context.view(), context.movementLegs());
        if (eulerLoopDecision.isPresent()) {
            return eulerLoopDecision;
        }

        if (isEulerBatchClarificationRequired(context.view(), context.movementLegs())) {
            return Optional.of(pendingReceiptClarification(
                    context.view(),
                    context.movementLegs(),
                    List.of(EULER_BATCH_DECODER_REQUIRED)
            ));
        }

        if (!context.view().hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }

        boolean shareInbound = hasShareLikeMovement(context.movementLegs(), true)
                || hasMintedFungibleTransferToWallet(context.view());
        boolean shareOutbound = hasShareLikeMovement(context.movementLegs(), false)
                || hasBurnedFungibleTransferFromWallet(context.view());
        boolean principalInbound = hasNonShareMovement(context.movementLegs(), true)
                || hasAnyInboundFungibleTransferToWallet(context.view());
        boolean principalOutbound = hasNonShareMovement(context.movementLegs(), false)
                || hasAnyOutboundFungibleTransferFromWallet(context.view());

        if (shareInbound && principalOutbound) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.LENDING_DEPOSIT,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.LENDING_DEPOSIT,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(
                            context.movementLegs(),
                            NormalizedTransactionType.LENDING_DEPOSIT
                    ),
                    List.of()
            ));
        }
        if (shareOutbound && principalInbound) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.LENDING_WITHDRAW,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.LENDING_WITHDRAW,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(
                            context.movementLegs(),
                            NormalizedTransactionType.LENDING_WITHDRAW
                    ),
                    List.of()
            ));
        }

        return Optional.empty();
    }

    private boolean isEulerBatchClarificationRequired(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        return !view.hasFullReceiptClarificationEvidence()
                && hasEulerLikeBatchEvidence(view, movementLegs);
    }

    private boolean hasEulerLikeBatchEvidence(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (isEulerBatchRouter(view.toAddress())) {
            return true;
        }
        if (movementLegs != null && movementLegs.stream().anyMatch(this::isEulerLikeMovement)) {
            return true;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (isEulerLikeTransfer(view, transfer)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEulerLikeMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && isEulerLikeAssetSymbol(leg.assetSymbol());
    }

    private boolean isEulerLikeTransfer(OnChainRawTransactionView view, Document transfer) {
        return isEulerLikeAssetSymbol(view.tokenTransferSymbol(transfer))
                || isEulerLikeAssetName(view.tokenTransferName(transfer));
    }

    private boolean isEulerLikeAssetSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("E")
                && (normalized.contains("-") || normalized.contains("DEBT"));
    }

    private boolean isEulerLikeAssetName(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            return false;
        }
        String normalized = assetName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("euler") || normalized.contains("evk vault");
    }

    private Optional<ClassificationDecision> classifyFromSemantics(OnChainClassificationContext context) {
        Optional<ProtocolSemanticHint> loopOpen = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_LOOP_OPEN);
        if (loopOpen.isPresent()) {
            ProtocolSemanticHint value = loopOpen.orElseThrow();
            List<NormalizedTransaction.Flow> flows = "Morpho".equalsIgnoreCase(value.protocolName())
                    ? transferFlows(context.movementLegs())
                    : buildEulerLoopOpenFlows(context.view(), context.movementLegs());
            if (!flows.isEmpty()) {
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.LENDING_LOOP_OPEN,
                        OnChainClassificationSupport.initialStatus(
                                context.view(),
                                NormalizedTransactionType.LENDING_LOOP_OPEN,
                                ConfidenceLevel.LOW
                        ),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        flows,
                        List.of(),
                        value.protocolName(),
                        value.protocolVersion()
                ));
            }
        }

        Optional<ProtocolSemanticHint> rebalance = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
        if (rebalance.isPresent()) {
            Optional<EulerLoopRebalancePattern> rebalancePattern =
                    detectEulerLoopRebalancePattern(context.movementLegs(), context.view());
            if (rebalancePattern.isPresent()) {
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                        OnChainClassificationSupport.initialStatus(
                                context.view(),
                                NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                                ConfidenceLevel.LOW
                        ),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        buildEulerLoopRebalanceFlows(rebalancePattern.orElseThrow(), context.movementLegs()),
                        List.of(),
                        rebalance.orElseThrow().protocolName(),
                        rebalance.orElseThrow().protocolVersion()
                ));
            }
        }

        Optional<ProtocolSemanticHint> decrease = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_LOOP_DECREASE);
        if (decrease.isPresent()) {
            Optional<ClassificationDecision> unwindDecision = buildEulerUnwindDecision(
                    context,
                    decrease.orElseThrow(),
                    NormalizedTransactionType.LENDING_LOOP_DECREASE
            );
            if (unwindDecision.isPresent()) {
                return unwindDecision;
            }
        }

        Optional<ProtocolSemanticHint> close = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_LOOP_CLOSE);
        if (close.isPresent()) {
            Optional<ClassificationDecision> unwindDecision = buildEulerUnwindDecision(
                    context,
                    close.orElseThrow(),
                    NormalizedTransactionType.LENDING_LOOP_CLOSE
            );
            if (unwindDecision.isPresent()) {
                return unwindDecision;
            }
        }

        Optional<ProtocolSemanticHint> deposit = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_DEPOSIT);
        if (deposit.isPresent()) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.LENDING_DEPOSIT,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.LENDING_DEPOSIT,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(
                            context.movementLegs(),
                            NormalizedTransactionType.LENDING_DEPOSIT
                    ),
                    List.of(),
                    deposit.orElseThrow().protocolName(),
                    deposit.orElseThrow().protocolVersion()
            ));
        }

        Optional<ProtocolSemanticHint> withdraw = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_WITHDRAW);
        if (withdraw.isPresent()) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.LENDING_WITHDRAW,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.LENDING_WITHDRAW,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(
                            context.movementLegs(),
                            NormalizedTransactionType.LENDING_WITHDRAW
                    ),
                    List.of(),
                    withdraw.orElseThrow().protocolName(),
                    withdraw.orElseThrow().protocolVersion()
            ));
        }

        return Optional.empty();
    }

    private Optional<ClassificationDecision> buildEulerUnwindDecision(
            OnChainClassificationContext context,
            ProtocolSemanticHint semanticHint,
            NormalizedTransactionType type
    ) {
        Optional<Document> shareOutboundTransfer = findEulerLoopShareOutboundTransfer(context.view());
        Optional<Document> returnedStableTransfer = findEulerLoopReturnedStableTransfer(context.view());
        if (shareOutboundTransfer.isEmpty() || returnedStableTransfer.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal stableUnitPrice = resolveEulerStableLikeUnitPrice(returnedStableTransfer.orElseThrow());
        BigDecimal shareQuantity = context.view().tokenTransferQuantity(shareOutboundTransfer.orElseThrow());
        BigDecimal returnedQuantity = context.view().tokenTransferQuantity(returnedStableTransfer.orElseThrow());
        if (stableUnitPrice == null || shareQuantity == null || returnedQuantity == null
                || shareQuantity.signum() <= 0 || returnedQuantity.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal shareUnitPrice = returnedQuantity.multiply(stableUnitPrice)
                .divide(shareQuantity, MathContext.DECIMAL128);
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.LOW),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                buildEulerLoopUnwindFlows(
                        context.view(),
                        context.movementLegs(),
                        shareOutboundTransfer.orElseThrow(),
                        returnedStableTransfer.orElseThrow(),
                        stableUnitPrice,
                        shareUnitPrice
                ),
                List.of(),
                semanticHint.protocolName(),
                semanticHint.protocolVersion()
        ));
    }

    private Optional<ClassificationDecision> classifyEulerReceiptOnlyLoopOpen(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return Optional.empty();
        }
        boolean stableReceiptInbound = false;
        boolean collateralReceiptInbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null
                    || leg.fee()
                    || leg.quantityDelta() == null
                    || leg.quantityDelta().signum() <= 0
                    || !isShareLikeSymbol(leg.assetSymbol())
                    || isDebtLikeSymbol(leg.assetSymbol())) {
                continue;
            }
            if (isEulerStableLikeShareSymbol(leg.assetSymbol())) {
                stableReceiptInbound = true;
            } else {
                collateralReceiptInbound = true;
            }
        }
        if (!stableReceiptInbound || !collateralReceiptInbound) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.LENDING_LOOP_OPEN,
                OnChainClassificationSupport.initialStatus(
                        view,
                        NormalizedTransactionType.LENDING_LOOP_OPEN,
                        ConfidenceLevel.LOW
                ),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                transferFlows(movementLegs),
                List.of(),
                "Euler",
                null
        ));
    }

    private Optional<ClassificationDecision> classifyEulerBatchDeposit(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return Optional.empty();
        }
        boolean outboundPrincipalToEulerShareContract = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = view.tokenTransferSymbol(transfer);
            if (isShareLikeSymbol(symbol) || isDebtLikeSymbol(symbol)) {
                continue;
            }
            if (matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))
                    && isEulerLikeAddress(view.tokenTransferTo(transfer))) {
                outboundPrincipalToEulerShareContract = true;
                break;
            }
        }
        if (!outboundPrincipalToEulerShareContract) {
            return Optional.empty();
        }
        boolean hasOutboundPrincipal = movementLegs.stream()
                .anyMatch(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() < 0
                        && !isShareLikeSymbol(leg.assetSymbol())
                        && !isDebtLikeSymbol(leg.assetSymbol()));
        if (!hasOutboundPrincipal) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.LENDING_DEPOSIT,
                OnChainClassificationSupport.initialStatus(
                        view,
                        NormalizedTransactionType.LENDING_DEPOSIT,
                        ConfidenceLevel.LOW
                ),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                transferFlows(movementLegs),
                List.of(),
                "Euler",
                null
        ));
    }

    private Optional<ClassificationDecision> classifyEulerLoopPath(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!isEulerBatchRouter(view.toAddress())) {
            return Optional.empty();
        }
        Optional<ClassificationDecision> receiptOnlyOpen = classifyEulerReceiptOnlyLoopOpen(view, movementLegs);
        if (receiptOnlyOpen.isPresent()) {
            return receiptOnlyOpen;
        }
        Optional<ClassificationDecision> batchDeposit = classifyEulerBatchDeposit(view, movementLegs);
        if (batchDeposit.isPresent()) {
            return batchDeposit;
        }
        if (isEulerBorrowBackedCollateralOpen(view, movementLegs)) {
            if (!view.hasFullReceiptClarificationEvidence() || !hasEulerClarifiedCollateralOpenLifecycle(view)) {
                return Optional.of(blockingReview(view, movementLegs, List.of(EULER_BATCH_DECODER_REQUIRED)));
            }
            List<NormalizedTransaction.Flow> flows = buildEulerLoopOpenFlows(view, movementLegs);
            if (flows.isEmpty()) {
                return Optional.of(blockingReview(view, movementLegs, List.of(EULER_BATCH_DECODER_REQUIRED)));
            }
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    view,
                    NormalizedTransactionType.LENDING_LOOP_OPEN,
                    OnChainClassificationSupport.initialStatus(
                            view,
                            NormalizedTransactionType.LENDING_LOOP_OPEN,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    flows,
                    List.of(),
                    "Euler",
                    null
            ));
        }

        Optional<EulerLoopRebalancePattern> rebalancePattern = detectEulerLoopRebalancePattern(movementLegs, view);
        if (rebalancePattern.isPresent()) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    view,
                    NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                    OnChainClassificationSupport.initialStatus(
                            view,
                            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    buildEulerLoopRebalanceFlows(rebalancePattern.orElseThrow(), movementLegs),
                    List.of(),
                    "Euler",
                    null
            ));
        }

        Optional<Document> shareOutboundTransfer = findEulerLoopShareOutboundTransfer(view);
        if (shareOutboundTransfer.isEmpty()) {
            return Optional.empty();
        }

        Optional<Document> returnedStableTransfer = findEulerLoopReturnedStableTransfer(view);
        if (returnedStableTransfer.isEmpty()) {
            return Optional.of(blockingReview(view, movementLegs, List.of(EULER_BATCH_DECODER_REQUIRED)));
        }

        BigDecimal stableUnitPrice = resolveEulerStableLikeUnitPrice(returnedStableTransfer.orElseThrow());
        if (stableUnitPrice == null) {
            return Optional.of(blockingReview(view, movementLegs, List.of(EULER_BATCH_DECODER_REQUIRED)));
        }

        Document shareTransfer = shareOutboundTransfer.orElseThrow();
        BigDecimal shareQuantity = view.tokenTransferQuantity(shareTransfer);
        BigDecimal returnedQuantity = view.tokenTransferQuantity(returnedStableTransfer.orElseThrow());
        if (shareQuantity == null || returnedQuantity == null
                || shareQuantity.signum() <= 0
                || returnedQuantity.signum() <= 0) {
            return Optional.of(blockingReview(view, movementLegs, List.of(EULER_BATCH_DECODER_REQUIRED)));
        }

        BigDecimal shareUnitPrice = returnedQuantity.multiply(stableUnitPrice)
                .divide(shareQuantity, MathContext.DECIMAL128);
        List<NormalizedTransaction.Flow> flows = buildEulerLoopUnwindFlows(
                view,
                movementLegs,
                shareTransfer,
                returnedStableTransfer.orElseThrow(),
                stableUnitPrice,
                shareUnitPrice
        );
        NormalizedTransactionType type = isZeroAddress(view.tokenTransferTo(shareTransfer))
                ? NormalizedTransactionType.LENDING_LOOP_CLOSE
                : NormalizedTransactionType.LENDING_LOOP_DECREASE;
        return Optional.of(FamilyDecisionSupport.buildWithView(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, ConfidenceLevel.LOW),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                flows,
                List.of(),
                "Euler",
                null
        ));
    }

    private List<NormalizedTransaction.Flow> transferFlows(List<RawLeg> movementLegs) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            flows.add(leg.fee() ? buildFlow(
                    NormalizedLegRole.FEE,
                    leg.assetContract(),
                    leg.assetSymbol(),
                    leg.quantityDelta()
            ) : buildTransferFlow(leg));
        }
        return flows;
    }

    private ClassificationDecision blockingReview(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            List<String> missingDataReasons
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                missingDataReasons
        );
    }

    private ClassificationDecision pendingReceiptClarification(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            List<String> missingDataReasons
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                missingDataReasons,
                "Euler",
                null
        );
    }

    private List<NormalizedTransaction.Flow> buildEulerLoopOpenFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        RawLeg shareLeg = null;
        RawLeg debtLeg = null;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() <= 0) {
                continue;
            }
            if (isDebtLikeSymbol(leg.assetSymbol())) {
                debtLeg = leg;
                continue;
            }
            if (isShareLikeSymbol(leg.assetSymbol())) {
                shareLeg = leg;
            }
        }
        if (shareLeg == null) {
            return List.of();
        }

        Optional<Document> anchorTransfer = findEulerLoopOpenAnchorTransfer(view, shareLeg.assetContract());
        if (anchorTransfer.isEmpty()) {
            return List.of();
        }

        BigDecimal anchorQuantity = view.tokenTransferQuantity(anchorTransfer.orElseThrow());
        BigDecimal shareQuantity = shareLeg.quantityDelta().abs();
        BigDecimal anchorUnitPrice = resolveEulerStableLikeUnitPrice(anchorTransfer.orElseThrow());
        if (anchorQuantity == null || shareQuantity.signum() <= 0 || anchorUnitPrice == null) {
            return List.of();
        }

        BigDecimal shareUnitPrice = resolveEvkShareUnitPriceUsd(view, shareLeg.assetContract(), shareLeg.assetSymbol())
                .orElseGet(() -> anchorQuantity.multiply(anchorUnitPrice)
                        .divide(shareQuantity, MathContext.DECIMAL128));

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        if (debtLeg != null) {
            flows.add(buildTransferFlow(debtLeg));
        }
        NormalizedTransaction.Flow shareFlow = buildFlow(
                NormalizedLegRole.BUY,
                shareLeg.assetContract(),
                shareLeg.assetSymbol(),
                shareLeg.quantityDelta()
        );
        applyResolvedPrice(
                shareFlow,
                shareUnitPrice,
                PriceSource.SWAP_DERIVED,
                EULER_SHARE_PRICE_INFERENCE_REASON
        );
        flows.add(shareFlow);
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private List<NormalizedTransaction.Flow> buildEulerLoopRebalanceFlows(
            EulerLoopRebalancePattern pattern,
            List<RawLeg> movementLegs
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(buildTransferFlow(pattern.sourceShare()));
        if (pattern.sourceRefund() != null) {
            flows.add(buildTransferFlow(pattern.sourceRefund()));
        }
        flows.add(buildTransferFlow(pattern.replacementShare()));
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private List<NormalizedTransaction.Flow> buildEulerLoopUnwindFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Document shareTransfer,
            Document returnedStableTransfer,
            BigDecimal stableUnitPrice,
            BigDecimal shareUnitPrice
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        String shareContract = view.tokenTransferContract(shareTransfer);
        String shareSymbol = view.tokenTransferSymbol(shareTransfer);
        BigDecimal resolvedShareUnitPrice = resolveEvkShareUnitPriceUsd(view, shareContract, shareSymbol)
                .orElse(shareUnitPrice);
        NormalizedTransaction.Flow shareFlow = buildFlow(
                NormalizedLegRole.SELL,
                shareContract,
                shareSymbol,
                view.tokenTransferQuantity(shareTransfer).negate()
        );
        applyResolvedPrice(
                shareFlow,
                resolvedShareUnitPrice,
                PriceSource.SWAP_DERIVED,
                EULER_SHARE_PRICE_INFERENCE_REASON
        );
        flows.add(shareFlow);

        NormalizedTransaction.Flow returnedFlow = buildFlow(
                NormalizedLegRole.BUY,
                view.tokenTransferContract(returnedStableTransfer),
                view.tokenTransferSymbol(returnedStableTransfer),
                view.tokenTransferQuantity(returnedStableTransfer)
        );
        applyResolvedPrice(
                returnedFlow,
                stableUnitPrice,
                PriceSource.STABLECOIN,
                EULER_STABLE_PRICE_INFERENCE_REASON
        );
        flows.add(returnedFlow);
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private Optional<Document> findEulerLoopOpenAnchorTransfer(
            OnChainRawTransactionView view,
            String shareContract
    ) {
        Document best = null;
        BigDecimal bestQuantity = BigDecimal.ZERO;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (shareContract == null || !safeEquals(shareContract, view.tokenTransferTo(transfer))) {
                continue;
            }
            if (isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))
                    || !isEulerStableLikeTransfer(view, transfer)) {
                continue;
            }
            if (quantity.compareTo(bestQuantity) > 0) {
                best = transfer;
                bestQuantity = quantity;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Document> findEulerLoopShareOutboundTransfer(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))) {
                continue;
            }
            String to = view.tokenTransferTo(transfer);
            if (isZeroAddress(to) || isEulerControlledSubaccount(view, view.walletAddress(), to)) {
                return Optional.of(transfer);
            }
        }
        return Optional.empty();
    }

    private Optional<Document> findEulerLoopReturnedStableTransfer(OnChainRawTransactionView view) {
        Document best = null;
        BigDecimal bestQuantity = BigDecimal.ZERO;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferTo(transfer))
                    || !isEulerStableLikeTransfer(view, transfer)
                    || isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (quantity.compareTo(bestQuantity) > 0) {
                best = transfer;
                bestQuantity = quantity;
            }
        }
        return Optional.ofNullable(best);
    }

    private BigDecimal resolveEulerStableLikeUnitPrice(Document transfer) {
        return transfer != null ? BigDecimal.ONE : null;
    }

    private boolean isEulerStableLikeTransfer(OnChainRawTransactionView view, Document transfer) {
        if (transfer == null) {
            return false;
        }
        String contract = view.tokenTransferContract(transfer);
        if (contract != null && EULER_DEUSD_CONTRACT.equalsIgnoreCase(contract)) {
            return true;
        }
        String symbol = view.tokenTransferSymbol(transfer);
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return "USDC".equals(normalized)
                || "USDT".equals(normalized)
                || "USDT0".equals(normalized)
                || "USD₮0".equals(normalized)
                || "DEUSD".equals(normalized);
    }

    private boolean isEulerStableLikeShareSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("USDC")
                || normalized.contains("USDT")
                || normalized.contains("USD₮0")
                || normalized.contains("DEUSD");
    }

    private boolean isEulerLikeAddress(String address) {
        String normalized = normalizeContract(address);
        return normalized != null && EULER_KNOWN_VAULT_CONTRACTS.contains(normalized);
    }

    private Optional<EulerLoopRebalancePattern> detectEulerLoopRebalancePattern(
            List<RawLeg> movementLegs,
            OnChainRawTransactionView view
    ) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerDebtLikeMovement)) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerNonShareEconomicMovement)) {
            return Optional.empty();
        }

        Map<String, RawLeg> outboundShares = aggregateEulerShareLegs(movementLegs, false);
        Map<String, RawLeg> inboundShares = aggregateEulerShareLegs(movementLegs, true);
        if (outboundShares.size() != 1 || inboundShares.isEmpty()) {
            return Optional.empty();
        }

        RawLeg sourceShare = outboundShares.values().iterator().next();
        String sourceAssetKey = rawLegAssetKey(sourceShare);
        Map<String, RawLeg> replacementCandidates = new LinkedHashMap<>(inboundShares);
        RawLeg sameAssetInbound = replacementCandidates.get(sourceAssetKey);
        RawLeg sourceRefund = null;
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) == 0
                && replacementCandidates.size() > 1) {
            replacementCandidates.remove(sourceAssetKey);
        }
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) != 0) {
            sourceRefund = sameAssetInbound;
            replacementCandidates.remove(sourceAssetKey);
        }
        if (replacementCandidates.size() != 1) {
            return Optional.empty();
        }

        RawLeg replacementShare = replacementCandidates.values().iterator().next();
        if (sameAsset(sourceShare, replacementShare)) {
            return Optional.empty();
        }

        // Reject grossly value-divergent pairings (EVK shares are NOT 1:1 with their underlying): an
        // internal collateral relocation worth ~$1,380 must not be paired with a ~$5 share mint, which
        // would carry the full $1,380 basis onto the tiny lot (~$216/share). Value both legs at their true
        // convertToAssets underlying; when the rate cannot be resolved, keep the pattern (fail-safe).
        if (!isValueEquivalentRebalance(view, sourceShare, replacementShare)) {
            return Optional.empty();
        }

        return Optional.of(new EulerLoopRebalancePattern(sourceShare, replacementShare, sourceRefund));
    }

    /**
     * True unless the two share legs can be proven to diverge grossly in underlying USD value. Only applies
     * to USD-stablecoin EVK shares (valued at $1/underlying via {@code convertToAssets}); for any other
     * symbol, or when the on-chain rate cannot be resolved, returns true so the caller keeps the existing
     * pattern (fail-safe — never fabricate a rejection from a missing rate).
     */
    private boolean isValueEquivalentRebalance(
            OnChainRawTransactionView view,
            RawLeg sourceShare,
            RawLeg replacementShare
    ) {
        if (!isEulerStableLikeShareSymbol(sourceShare.assetSymbol())
                || !isEulerStableLikeShareSymbol(replacementShare.assetSymbol())) {
            return true;
        }
        Optional<BigDecimal> sourceValue = resolveEvkShareUsdValue(view, sourceShare);
        Optional<BigDecimal> replacementValue = resolveEvkShareUsdValue(view, replacementShare);
        if (sourceValue.isEmpty() || replacementValue.isEmpty()) {
            return true;
        }
        BigDecimal source = sourceValue.orElseThrow();
        BigDecimal replacement = replacementValue.orElseThrow();
        if (source.signum() <= 0 || replacement.signum() <= 0) {
            return true;
        }
        BigDecimal max = source.max(replacement);
        BigDecimal divergence = source.subtract(replacement).abs().divide(max, MathContext.DECIMAL128);
        return divergence.compareTo(REBALANCE_VALUE_EQUIVALENCE_MAX_DIVERGENCE) <= 0;
    }

    /** USD value of a stablecoin EVK share leg via convertToAssets ($1/underlying), or empty if unresolved. */
    private Optional<BigDecimal> resolveEvkShareUsdValue(OnChainRawTransactionView view, RawLeg shareLeg) {
        if (shareLeg == null || shareLeg.quantityDelta() == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> perShare = resolveEvkUnderlyingUnitsPerShare(view, shareLeg.assetContract());
        return perShare.map(rate -> shareLeg.quantityDelta().abs().multiply(rate).multiply(STABLE_UNDERLYING_USD));
    }

    /**
     * USD unit price of one whole stablecoin EVK share via {@code convertToAssets} at the tx block, or empty
     * when the share is not a USD-stablecoin EVK share or the rate cannot be resolved (fail-safe).
     */
    private Optional<BigDecimal> resolveEvkShareUnitPriceUsd(
            OnChainRawTransactionView view,
            String shareContract,
            String shareSymbol
    ) {
        if (!isEulerStableLikeShareSymbol(shareSymbol)) {
            return Optional.empty();
        }
        return resolveEvkUnderlyingUnitsPerShare(view, shareContract)
                .map(rate -> rate.multiply(STABLE_UNDERLYING_USD));
    }

    private Optional<BigDecimal> resolveEvkUnderlyingUnitsPerShare(
            OnChainRawTransactionView view,
            String shareContract
    ) {
        if (shareRateResolver == null || view == null || shareContract == null || shareContract.isBlank()) {
            return Optional.empty();
        }
        Long block = view.blockNumber();
        NetworkId network = view.networkId();
        if (block == null || network == null) {
            return Optional.empty();
        }
        return shareRateResolver.resolveUnderlyingUnitsPerShare(network, shareContract, block);
    }

    private Map<String, RawLeg> aggregateEulerShareLegs(
            List<RawLeg> movementLegs,
            boolean inbound
    ) {
        Map<String, RawLeg> aggregated = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null
                    || leg.fee()
                    || leg.quantityDelta() == null
                    || leg.quantityDelta().signum() == 0
                    || isDebtLikeSymbol(leg.assetSymbol())
                    || !isShareLikeSymbol(leg.assetSymbol())
                    || (inbound ? leg.quantityDelta().signum() <= 0 : leg.quantityDelta().signum() >= 0)) {
                continue;
            }
            String assetKey = rawLegAssetKey(leg);
            RawLeg current = aggregated.get(assetKey);
            if (current == null) {
                aggregated.put(assetKey, leg);
                continue;
            }
            aggregated.put(assetKey, new RawLeg(
                    current.assetContract(),
                    current.assetSymbol(),
                    current.quantityDelta().add(leg.quantityDelta()),
                    false
            ));
        }
        return aggregated;
    }

    private boolean isEulerDebtLikeMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && isDebtLikeSymbol(leg.assetSymbol());
    }

    private boolean isEulerNonShareEconomicMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && !isDebtLikeSymbol(leg.assetSymbol())
                && !isShareLikeSymbol(leg.assetSymbol());
    }

    private boolean sameAsset(RawLeg left, RawLeg right) {
        if (left == null || right == null) {
            return false;
        }
        String leftContract = normalizeContract(left.assetContract());
        String rightContract = normalizeContract(right.assetContract());
        if (leftContract != null || rightContract != null) {
            return leftContract != null && leftContract.equals(rightContract);
        }
        return normalizeSymbol(left.assetSymbol()).equals(normalizeSymbol(right.assetSymbol()));
    }

    private String rawLegAssetKey(RawLeg leg) {
        String contract = normalizeContract(leg.assetContract());
        return contract != null ? contract : "SYMBOL:" + normalizeSymbol(leg.assetSymbol());
    }

    private String normalizeContract(String contract) {
        return contract == null || contract.isBlank() ? null : contract.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String address) {
        return safeEquals(
                OnChainRawTransactionView.normalizeAddress(view.walletAddress()),
                OnChainRawTransactionView.normalizeAddress(address)
        );
    }

    private void appendFeeFlows(
            List<NormalizedTransaction.Flow> flows,
            List<RawLeg> movementLegs
    ) {
        for (RawLeg leg : movementLegs) {
            if (leg == null || !leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            flows.add(buildFlow(
                    NormalizedLegRole.FEE,
                    leg.assetContract(),
                    leg.assetSymbol(),
                    leg.quantityDelta()
            ));
        }
    }

    private NormalizedTransaction.Flow buildTransferFlow(RawLeg leg) {
        return buildFlow(
                NormalizedLegRole.TRANSFER,
                leg.assetContract(),
                leg.assetSymbol(),
                leg.quantityDelta()
        );
    }

    private NormalizedTransaction.Flow buildFlow(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(assetContract);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }

    private void applyResolvedPrice(
            NormalizedTransaction.Flow flow,
            BigDecimal unitPriceUsd,
            PriceSource priceSource,
            String inferenceReason
    ) {
        if (flow == null || unitPriceUsd == null || priceSource == null) {
            return;
        }
        BigDecimal persistedUnitPriceUsd = Decimal128Support.normalize(unitPriceUsd);
        BigDecimal persistedValueUsd = flow.getQuantityDelta() == null
                ? null
                : Decimal128Support.normalize(flow.getQuantityDelta().abs().multiply(persistedUnitPriceUsd));
        flow.setUnitPriceUsd(persistedUnitPriceUsd);
        flow.setValueUsd(persistedValueUsd);
        flow.setPriceSource(priceSource);
        flow.setIsInferred(true);
        flow.setInferenceReason(inferenceReason);
        flow.setConfidence(ConfidenceLevel.MEDIUM);
    }

    private boolean hasShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbolWithContract(leg.assetSymbol(), leg.assetContract()));
    }

    private boolean hasNonShareMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && !isShareLikeSymbolWithContract(leg.assetSymbol(), leg.assetContract()));
    }

    /**
     * Returns true if the symbol looks like a lending protocol vault-share receipt.
     *
     * <p>When a contract address is available, prefer
     * {@link #isShareLikeSymbolWithContract(String, String)}: for the {@code e} prefix it
     * additionally verifies the contract is a known Euler vault, preventing false positives on
     * other {@code e}-prefixed tokens (e.g. Euler debt receipts like {@code eUSDt-2-DEBT} or
     * arbitrary tokens that happen to start with {@code e}).
     */
    private boolean isShareLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("syrup")) {
            return false;
        }
        return normalized.startsWith("a")
                || normalized.startsWith("c")
                || normalized.startsWith("s")
                || normalized.startsWith("e")
                || normalized.startsWith("gt");
    }

    /**
     * Contract-aware share-like check. For the {@code e} prefix the contract must be a
     * non-null, known Euler vault address; any other {@code e}-prefixed contract (unknown
     * or a debt position) is NOT treated as a vault share.
     *
     * <p>For all other prefixes, behaviour is identical to {@link #isShareLikeSymbol(String)}.
     */
    private boolean isShareLikeSymbolWithContract(String assetSymbol, String contract) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("syrup")) {
            return false;
        }
        if (normalized.startsWith("e")) {
            return isEulerLikeAddress(contract);
        }
        return normalized.startsWith("a")
                || normalized.startsWith("c")
                || normalized.startsWith("s")
                || normalized.startsWith("gt");
    }

    private boolean isDebtLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        return assetSymbol.trim().toLowerCase(Locale.ROOT).contains("debt");
    }

    private boolean hasDebtLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasNonDebtShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbolWithContract(leg.assetSymbol(), leg.assetContract())
                        && !isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasMintedFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 2)))
                    && isZeroAddress(topicAddress(topicAt(log, 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBurnedFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 1)))
                    && isZeroAddress(topicAddress(topicAt(log, 2)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyInboundFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyOutboundFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, from) && !isZeroAddress(to)) {
                return true;
            }
        }
        return false;
    }

    private boolean isErc20TransferLog(Document log) {
        if (log == null) {
            return false;
        }
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
        List<String> normalized = new ArrayList<>(topics.size());
        for (Object topic : topics) {
            String value = topic == null ? null : topic.toString();
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String topicAt(Document log, int index) {
        List<String> topics = normalizedTopics(log);
        if (index < 0 || index >= topics.size()) {
            return null;
        }
        return topics.get(index);
    }

    private String logData(Document log) {
        if (log == null) {
            return null;
        }
        Object data = log.get("data");
        return data == null ? null : data.toString();
    }

    private String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private boolean isZeroAddress(String address) {
        return "0x0000000000000000000000000000000000000000".equals(address);
    }

    private boolean matchesWalletAccount(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        if (wallet == null || normalizedAddress == null) {
            return false;
        }
        if (wallet.equals(normalizedAddress)) {
            return true;
        }
        return isEulerControlledSubaccount(view, wallet, normalizedAddress);
    }

    private boolean isEulerControlledSubaccount(OnChainRawTransactionView view, String wallet, String candidate) {
        if (!"0xc16ae7a4".equals(view.methodId())) {
            return false;
        }
        if (!isEulerBatchRouter(view.toAddress())) {
            return false;
        }
        return wallet.length() == 42
                && candidate.length() == 42
                && wallet.substring(0, 40).equals(candidate.substring(0, 40));
    }

    private boolean isEulerBatchRouter(String address) {
        return address != null && EULER_BATCH_ROUTERS.contains(address.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isEulerBorrowBackedCollateralOpen(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!isEulerBatchRouter(view.toAddress())) {
            return false;
        }
        if (!hasEulerBorrowCallContext(view) || !hasEulerBorrowEvent(view)) {
            return false;
        }
        return hasDebtLikeMovement(movementLegs, true)
                && hasNonDebtShareLikeMovement(movementLegs, true);
    }

    private boolean hasEulerBorrowCallContext(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (topics.isEmpty() || !EULER_CALL_WITH_CONTEXT_TOPIC.equals(topics.getFirst())) {
                continue;
            }
            String data = logData(log);
            if (data != null && data.toLowerCase(Locale.ROOT).contains(EULER_BORROW_SELECTOR)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerBorrowEvent(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (!topics.isEmpty() && EULER_BORROW_EVENT_TOPIC.equals(topics.getFirst())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerClarifiedCollateralOpenLifecycle(OnChainRawTransactionView view) {
        boolean debtMintToWallet = false;
        boolean shareMintToWallet = false;
        boolean protocolHop = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = view.tokenTransferSymbol(transfer);
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            if (matchesWalletAccount(view, to) && isZeroAddress(from)) {
                if (isDebtLikeSymbol(symbol)) {
                    debtMintToWallet = true;
                    continue;
                }
                if (isShareLikeSymbol(symbol)) {
                    shareMintToWallet = true;
                    continue;
                }
            }
            if (!matchesWalletAccount(view, from)
                    && !matchesWalletAccount(view, to)
                    && !isZeroAddress(from)
                    && !isZeroAddress(to)
                    && !isDebtLikeSymbol(symbol)
                    && !isShareLikeSymbol(symbol)) {
                protocolHop = true;
            }
        }
        return debtMintToWallet && shareMintToWallet && protocolHop;
    }

    private boolean isClarifiedBatchCandidate(OnChainRawTransactionView view) {
        return "0xc16ae7a4".equals(view.methodId())
                || (view.functionName() != null && view.functionName().contains("batch"));
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private record EulerLoopRebalancePattern(
            RawLeg sourceShare,
            RawLeg replacementShare,
            RawLeg sourceRefund
    ) {
    }
}
