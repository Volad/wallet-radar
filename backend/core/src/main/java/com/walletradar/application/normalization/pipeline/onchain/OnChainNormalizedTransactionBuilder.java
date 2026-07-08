package com.walletradar.application.normalization.pipeline.onchain;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation;
import com.walletradar.application.costbasis.support.leverage.AaveDebtLoanCorrelationSupport;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds canonical on-chain normalized documents from classifier output.
 */
@Component
public class OnChainNormalizedTransactionBuilder {

    private static final String FLUID_LOG_OPERATE_TOPIC =
            "0x4d93b232a24e82b284ced7461bf4deacffe66759d5c24513e6f29e571ad78d15";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String FLUID_POSITION_NFT =
            "0x324c5dc1fc42c7a4d43d92df1eba58a54d13bf2d";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final Set<String> KNOWN_FLUID_VAULTS = Set.of(
            "0x3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7",
            "0xf2c8f54447cbd591c396b0dd7ac15faf552d0fa4",
            "0xb4f3bf2d96139563777c0231899ce06ee95cc946"
    );
    private static final Set<String> KNOWN_FLUID_MARKERS = Set.of(
            "3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7",
            "f2c8f54447cbd591c396b0dd7ac15faf552d0fa4",
            "b4f3bf2d96139563777c0231899ce06ee95cc946"
    );
    private static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);

    private final ClarificationPolicyService clarificationPolicyService;
    private final LeverageAcquisitionDetector leverageAcquisitionDetector;

    @Autowired
    public OnChainNormalizedTransactionBuilder(
            ClarificationPolicyService clarificationPolicyService,
            LeverageAcquisitionDetector leverageAcquisitionDetector
    ) {
        this.clarificationPolicyService = clarificationPolicyService;
        this.leverageAcquisitionDetector = leverageAcquisitionDetector;
    }

    public OnChainNormalizedTransactionBuilder() {
        this(new ClarificationPolicyService(), new LeverageAcquisitionDetector());
    }

    public NormalizedTransaction build(
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(canonicalId(rawTransaction));
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(0);
        normalized.setStatAttempts(0);
        normalized.setCreatedAt(now);
        normalized.setUpdatedAt(now);
        if (classificationResult.status() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(now);
        }
        return normalized;
    }

    public NormalizedTransaction rebuildAfterClarification(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(existing.getId());
        normalized.setCreatedAt(existing.getCreatedAt());
        normalized.setUpdatedAt(now);
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(safeCounter(existing.getPricingAttempts()));
        normalized.setStatAttempts(safeCounter(existing.getStatAttempts()));
        if (normalized.getCorrelationId() == null || normalized.getCorrelationId().isBlank()) {
            normalized.setCorrelationId(existing.getCorrelationId());
        }
        if (normalized.getContinuityCandidate() == null) {
            normalized.setContinuityCandidate(existing.getContinuityCandidate());
        }
        if (normalized.getMatchedCounterparty() == null || normalized.getMatchedCounterparty().isBlank()) {
            normalized.setMatchedCounterparty(existing.getMatchedCounterparty());
        }
        if (normalized.getCounterpartyAddress() == null || normalized.getCounterpartyAddress().isBlank()) {
            normalized.setCounterpartyAddress(existing.getCounterpartyAddress());
        }
        normalized.setClientId(existing.getClientId());
        if (normalized.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(existing.getConfirmedAt() != null ? existing.getConfirmedAt() : now);
        } else {
            normalized.setConfirmedAt(existing.getConfirmedAt());
        }
        return normalized;
    }

    public NormalizedTransaction rebuildAfterReclassification(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NormalizedTransaction normalized = new NormalizedTransaction();
        applyCanonicalFields(normalized, view, classificationResult);
        normalized.setId(existing.getId());
        normalized.setCreatedAt(existing.getCreatedAt());
        normalized.setUpdatedAt(now);
        normalized.setClarificationAttempts(clarificationAttemptBaseline(view));
        normalized.setFullReceiptClarificationAttempts(fullReceiptClarificationAttemptBaseline(view));
        normalized.setPricingAttempts(safeCounter(existing.getPricingAttempts()));
        normalized.setStatAttempts(safeCounter(existing.getStatAttempts()));
        if (normalized.getCorrelationId() == null || normalized.getCorrelationId().isBlank()) {
            normalized.setCorrelationId(existing.getCorrelationId());
        }
        if (normalized.getContinuityCandidate() == null) {
            normalized.setContinuityCandidate(existing.getContinuityCandidate());
        }
        if (normalized.getMatchedCounterparty() == null || normalized.getMatchedCounterparty().isBlank()) {
            normalized.setMatchedCounterparty(existing.getMatchedCounterparty());
        }
        if (normalized.getCounterpartyAddress() == null || normalized.getCounterpartyAddress().isBlank()) {
            normalized.setCounterpartyAddress(existing.getCounterpartyAddress());
        }
        normalized.setClientId(existing.getClientId());
        if (normalized.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            normalized.setConfirmedAt(existing.getConfirmedAt() != null ? existing.getConfirmedAt() : now);
        } else {
            normalized.setConfirmedAt(existing.getConfirmedAt());
        }
        return normalized;
    }

    public void enrichFluidEvidence(
            NormalizedTransaction normalized,
            RawTransaction rawTransaction
    ) {
        if (normalized == null || rawTransaction == null) {
            return;
        }
        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                normalized.getType(),
                normalized.getStatus(),
                normalized.getClassifiedBy(),
                normalized.getConfidence(),
                normalized.getFlows(),
                normalized.getMissingDataReasons(),
                normalized.getCorrelationId(),
                normalized.getContinuityCandidate(),
                normalized.getMatchedCounterparty(),
                normalized.getExcludedFromAccounting(),
                normalized.getAccountingExclusionReason(),
                normalized.getProtocolName(),
                normalized.getProtocolVersion()
        );
        applyFluidEvidence(normalized, OnChainRawTransactionView.wrap(rawTransaction), classificationResult);
    }

    public String canonicalId(RawTransaction rawTransaction) {
        return rawTransaction.getTxHash() + ":" + rawTransaction.getNetworkId() + ":" + rawTransaction.getWalletAddress();
    }

    private void applyCanonicalFields(
            NormalizedTransaction normalized,
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        normalized.setTxHash(view.txHash());
        normalized.setNetworkId(view.networkId());
        normalized.setWalletAddress(view.walletAddress());
        normalized.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.ON_CHAIN);
        normalized.setBlockTimestamp(view.blockTimestamp());
        normalized.setTransactionIndex(view.transactionIndex());
        normalized.setType(classificationResult.type());
        normalized.setEventSubtype(resolveEventSubtype(view, classificationResult));
        normalized.setStatus(classificationResult.status());
        normalized.setClassifiedBy(classificationResult.classifiedBy());
        normalized.setConfidence(classificationResult.confidence());
        normalized.setFlows(classificationResult.flows());
        List<String> missingDataReasons = classificationResult.missingDataReasons();
        if (classificationResult.status() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            missingDataReasons = clarificationPolicyService.mergeClassifierReasons(
                    view,
                    classificationResult.type(),
                    missingDataReasons
            );
        }
        normalized.setMissingDataReasons(missingDataReasons);
        normalized.setProtocolName(classificationResult.protocolName());
        normalized.setProtocolVersion(classificationResult.protocolVersion());
        normalized.setCorrelationId(classificationResult.correlationId());
        applySyntheticAaveLoanCorrelationId(normalized);
        normalized.setContinuityCandidate(Boolean.TRUE.equals(classificationResult.continuityCandidate()));
        normalized.setMatchedCounterparty(classificationResult.matchedCounterparty());
        normalized.setExcludedFromAccounting(Boolean.TRUE.equals(classificationResult.excludedFromAccounting()));
        normalized.setAccountingExclusionReason(classificationResult.accountingExclusionReason());
        applyFluidEvidence(normalized, view, classificationResult);
        applyMorphoEvidence(normalized, view, classificationResult);
        applyLeverageAcquisitionAnnotation(normalized, view);
    }

    /**
     * ADR-028: annotate an inferred leveraged buy (acquisition SWAP whose collateral is worth more
     * than the consideration because the gap is borrowed). The synthetic borrow is sized at replay;
     * here we persist the borrow evidence and the deterministic correlation key. When the shape has
     * borrow evidence but no usable correlation key (e.g. native-token collateral with no contract),
     * route to clarification rather than fabricate a liability we cannot key.
     */
    private void applyLeverageAcquisitionAnnotation(
            NormalizedTransaction normalized,
            OnChainRawTransactionView view
    ) {
        if (normalized.getType() != NormalizedTransactionType.SWAP) {
            return;
        }
        LeverageAcquisitionDetector.LeverageAnnotation annotation = leverageAcquisitionDetector.detect(
                view,
                normalized.getNetworkId(),
                normalized.getWalletAddress(),
                normalized.getFlows()
        );
        if (annotation == null) {
            return;
        }
        if (annotation.borrowEvidence()) {
            LeverageBorrowAnnotation.write(
                    normalized,
                    true,
                    annotation.evidenceKind() == null ? null : annotation.evidenceKind().name(),
                    annotation.loanCorrelationId(),
                    annotation.collateralContract(),
                    annotation.collateralSymbol()
            );
            return;
        }
        normalized.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        List<String> reasons = new ArrayList<>(normalized.getMissingDataReasons() == null
                ? List.of()
                : normalized.getMissingDataReasons());
        if (!reasons.contains(LeverageAcquisitionDetector.PENDING_REASON)) {
            reasons.add(LeverageAcquisitionDetector.PENDING_REASON);
        }
        normalized.setMissingDataReasons(reasons);
    }

    /**
     * F-3/F-4: stamp a deterministic synthetic loan id on on-chain Aave {@code BORROW}/{@code REPAY}
     * transactions that lack an exchange-issued correlation id, so the existing
     * {@code BorrowReplayHandler}/{@code RepayReplayHandler} can register the liability and book the
     * matched repay at ~$0. Never overrides an authoritative correlation id from another source.
     */
    private void applySyntheticAaveLoanCorrelationId(NormalizedTransaction normalized) {
        if (normalized.getCorrelationId() != null && !normalized.getCorrelationId().isBlank()) {
            return;
        }
        String syntheticLoanId = AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(normalized);
        if (syntheticLoanId != null) {
            normalized.setCorrelationId(syntheticLoanId);
        }
    }

    private void applyFluidEvidence(
            NormalizedTransaction normalized,
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        if (!"Fluid".equals(classificationResult.protocolName()) && !looksLikeFluidEvidence(view, classificationResult)) {
            return;
        }
        Document metadata = new Document();
        metadata.put("protocol", "Fluid");
        metadata.put("networkId", view.networkId() == null ? null : view.networkId().name());
        metadata.put("vaultAddress", classificationResult.matchedCounterparty());
        metadata.put("nftId", fluidNftId(view));
        metadata.put("marketKey", fluidMarketKey(view, classificationResult));
        metadata.put("wrapperKind", fluidWrapperKind(view));
        metadata.put("evidenceCompleteness", view.hasFullReceiptClarificationEvidence()
                ? "FULL_LOGS_PRESENT"
                : "WALLET_TRANSFERS_ONLY");
        metadata.put("positionKey", fluidPositionKey(view, metadata));
        metadata.put("decodedCollateralDeltaIntent", decodedCollateralIntent(classificationResult.type()));
        metadata.put("decodedDebtDeltaIntent", decodedDebtIntent(classificationResult.type()));
        metadata.put("lendingChildLegs", fluidChildLegs(view, classificationResult));
        normalized.setMetadata(metadata);

        Document evidence = new Document();
        evidence.put("source", view.hasFullReceiptClarificationEvidence() ? "PLASMA_RPC_RECEIPT" : "RAW_WALLET_EVIDENCE");
        evidence.put("txHash", view.txHash());
        evidence.put("blockNumber", firstLogValue(view, "blockNumber"));
        evidence.put("fluidLogOperate", fluidLogOperateEvidence(view));
        evidence.put("nftTransfers", fluidNftTransferEvidence(view));
        evidence.put("erc20TransferLogReferences", erc20TransferLogReferences(view));
        normalized.setClarificationEvidence(evidence);
    }

    private boolean looksLikeFluidEvidence(
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        String matched = normalizeAddress(classificationResult.matchedCounterparty());
        if (isKnownFluidVault(matched)) {
            return true;
        }
        String to = normalizeAddress(view.toAddress());
        if (isKnownFluidVault(to)) {
            return true;
        }
        String input = view.inputData();
        return input != null && KNOWN_FLUID_MARKERS.stream().anyMatch(input::contains);
    }

    private void applyMorphoEvidence(
            NormalizedTransaction normalized,
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        if (!"Morpho".equals(classificationResult.protocolName())) {
            return;
        }
        List<Document> childLegs = morphoChildLegs(view, classificationResult);
        if (childLegs.isEmpty()) {
            return;
        }
        Document metadata = normalized.getMetadata() == null ? new Document() : new Document(normalized.getMetadata());
        metadata.put("protocol", "Morpho");
        metadata.put("networkId", view.networkId() == null ? null : view.networkId().name());
        metadata.put("bundlerAddress", view.toAddress());
        metadata.put("adapterAddress", morphoAdapterAddress(view));
        metadata.put("lifecycleStrategyId", morphoStrategyId(view));
        metadata.put("evidenceCompleteness", view.hasFullReceiptClarificationEvidence()
                ? "FULL_LOGS_PRESENT"
                : "WALLET_TRANSFERS_ONLY");
        metadata.put("lendingChildLegs", childLegs);
        normalized.setMetadata(metadata);
    }

    private List<Document> morphoChildLegs(
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        if (classificationResult.type() == null) {
            return List.of();
        }
        String wallet = normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return List.of();
        }
        boolean hasNonStableWalletOutbound = view.explorerTokenTransfers().stream()
                .anyMatch(transfer -> wallet.equals(view.tokenTransferFrom(transfer))
                        && !morphoIsShareToken(view, transfer)
                        && !morphoIsStableLike(view.tokenTransferSymbol(transfer))
                        && positiveTransferQuantity(view, transfer));
        List<Document> legs = new ArrayList<>();
        int index = 0;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = view.tokenTransferSymbol(transfer);
            boolean fromWallet = wallet.equals(view.tokenTransferFrom(transfer));
            boolean toWallet = wallet.equals(view.tokenTransferTo(transfer));
            if (fromWallet
                    && !morphoIsShareToken(view, transfer)
                    && !(hasNonStableWalletOutbound && morphoIsStableLike(symbol))) {
                String legType = classificationResult.type() == NormalizedTransactionType.LENDING_LOOP_OPEN
                        ? "LENDING_LOOP_OPEN"
                        : "LENDING_DEPOSIT";
                legs.add(childLeg(
                        "morpho:collateral:" + index++,
                        legType,
                        "MORPHO_BUNDLER_COLLATERAL_IN",
                        legType.equals("LENDING_LOOP_OPEN") ? "Supply collateral" : "Deposit",
                        symbol,
                        quantity
                ));
                continue;
            }
            if (toWallet
                    && !morphoIsShareToken(view, transfer)
                    && !ZERO_ADDRESS.equals(view.tokenTransferFrom(transfer))) {
                if (classificationResult.type() == NormalizedTransactionType.LENDING_WITHDRAW
                        || classificationResult.type() == NormalizedTransactionType.VAULT_WITHDRAW) {
                    legs.add(childLeg(
                            "morpho:withdraw:" + index++,
                            "LENDING_WITHDRAW",
                            "MORPHO_BUNDLER_COLLATERAL_OUT",
                            "Withdraw",
                            symbol,
                            quantity
                    ));
                } else if (classificationResult.type() == NormalizedTransactionType.LENDING_LOOP_OPEN
                        && morphoIsStableLike(symbol)) {
                    legs.add(childLeg(
                            "morpho:borrow:" + index++,
                            "BORROW",
                            "MORPHO_BUNDLER_BORROW",
                            "Borrow",
                            symbol,
                            quantity
                    ));
                }
            }
        }
        return legs;
    }

    private boolean positiveTransferQuantity(OnChainRawTransactionView view, Document transfer) {
        BigDecimal quantity = view.tokenTransferQuantity(transfer);
        return quantity != null && quantity.signum() > 0;
    }

    private boolean morphoIsShareToken(OnChainRawTransactionView view, Document transfer) {
        String symbol = nullToUnknown(view.tokenTransferSymbol(transfer)).toLowerCase(Locale.ROOT);
        String name = nullToUnknown(view.tokenTransferName(transfer)).toLowerCase(Locale.ROOT);
        return symbol.startsWith("gt")
                || symbol.startsWith("mc")
                || symbol.startsWith("syrup")
                || name.contains("vault")
                || name.contains("syrup");
    }

    private boolean morphoIsStableLike(String symbol) {
        String normalized = nullToUnknown(symbol).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USDC", "USDT", "USDT0", "USD₮0", "DAI", "GHO", "USDE", "DEUSD", "EURC" -> true;
            default -> false;
        };
    }

    private String morphoAdapterAddress(OnChainRawTransactionView view) {
        String wallet = normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return null;
        }
        return view.explorerTokenTransfers().stream()
                .map(transfer -> wallet.equals(view.tokenTransferFrom(transfer))
                        ? view.tokenTransferTo(transfer)
                        : wallet.equals(view.tokenTransferTo(transfer))
                        ? view.tokenTransferFrom(transfer)
                        : null)
                .filter(Objects::nonNull)
                .filter(address -> !ZERO_ADDRESS.equals(address))
                .findFirst()
                .orElse(null);
    }

    private String morphoStrategyId(OnChainRawTransactionView view) {
        return String.join(":",
                "morpho",
                view.networkId() == null ? "unknown" : view.networkId().name().toLowerCase(Locale.ROOT),
                nullToUnknown(view.walletAddress()),
                nullToUnknown(morphoAdapterAddress(view))
        );
    }

    private boolean isKnownFluidVault(String address) {
        return address != null && KNOWN_FLUID_VAULTS.contains(address);
    }

    private String fluidMarketKey(OnChainRawTransactionView view, OnChainClassificationResult classificationResult) {
        String vault = classificationResult.matchedCounterparty();
        if (vault == null || vault.isBlank()) {
            return "Fluid:" + (view.networkId() == null ? "UNKNOWN" : view.networkId().name()) + ":VAULT-ACCOUNT";
        }
        return "Fluid:" + (view.networkId() == null ? "UNKNOWN" : view.networkId().name())
                + ":vault-" + vault.substring(2, Math.min(vault.length(), 10));
    }

    private String fluidPositionKey(OnChainRawTransactionView view, Document metadata) {
        return String.join(":",
                "fluid",
                view.networkId() == null ? "unknown" : view.networkId().name().toLowerCase(Locale.ROOT),
                nullToUnknown(view.walletAddress()),
                nullToUnknown(stringify(metadata.get("vaultAddress"))),
                nullToUnknown(stringify(metadata.get("nftId")))
        );
    }

    private String fluidWrapperKind(OnChainRawTransactionView view) {
        if ("0x57b7bf20".equals(view.methodId()) || startsWith(view.functionName(), "cast(")) {
            return "DSA_CAST";
        }
        if ("0xb88d4fde".equals(view.methodId()) || startsWith(view.functionName(), "safetransferfrom(")) {
            return "NFT_SAFE_TRANSFER";
        }
        return "DIRECT_VAULT";
    }

    private String decodedCollateralIntent(NormalizedTransactionType type) {
        if (type == NormalizedTransactionType.LENDING_LOOP_OPEN || type == NormalizedTransactionType.LENDING_DEPOSIT) {
            return "DEPOSIT";
        }
        if (type == NormalizedTransactionType.LENDING_LOOP_DECREASE
                || type == NormalizedTransactionType.LENDING_LOOP_CLOSE
                || type == NormalizedTransactionType.LENDING_WITHDRAW) {
            return "WITHDRAW";
        }
        return "NONE";
    }

    private String decodedDebtIntent(NormalizedTransactionType type) {
        if (type == NormalizedTransactionType.LENDING_LOOP_OPEN || type == NormalizedTransactionType.BORROW) {
            return "BORROW";
        }
        if (type == NormalizedTransactionType.LENDING_LOOP_DECREASE
                || type == NormalizedTransactionType.LENDING_LOOP_CLOSE
                || type == NormalizedTransactionType.REPAY) {
            return "REPAY";
        }
        return "NONE";
    }

    private List<Document> fluidChildLegs(
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        List<Document> legs = new ArrayList<>();
        addWalletVisibleFluidLegs(legs, classificationResult);
        for (Document event : fluidLogOperateEvidence(view)) {
            BigDecimal borrowAmount = decimal(event.get("borrowAmount"));
            if (borrowAmount == null || borrowAmount.signum() == 0) {
                continue;
            }
            String assetSymbol = stringify(event.get("symbol"));
            if (assetSymbol == null) {
                continue;
            }
            if (borrowAmount.signum() > 0) {
                legs.add(childLeg("borrow:" + event.get("logIndex"), "BORROW", "FLUID_LOG_OPERATE_BORROW",
                        "Borrow", assetSymbol, borrowAmount.abs()));
            } else {
                legs.add(childLeg("repay:" + event.get("logIndex"), "REPAY", "FLUID_LOG_OPERATE_REPAY",
                        "Repay", assetSymbol, borrowAmount.abs()));
            }
        }
        return legs;
    }

    private void addWalletVisibleFluidLegs(List<Document> legs, OnChainClassificationResult classificationResult) {
        if (classificationResult.flows() == null || classificationResult.type() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : classificationResult.flows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getAssetSymbol() == null) {
                continue;
            }
            if (flow.getRole() != null && flow.getRole().name().equals("FEE")) {
                continue;
            }
            int sign = flow.getQuantityDelta().signum();
            if (sign == 0) {
                continue;
            }
            switch (classificationResult.type()) {
                case LENDING_LOOP_OPEN -> {
                    if (sign < 0) {
                        legs.add(childLeg("supply", "LENDING_LOOP_OPEN", "FLUID_WALLET_VISIBLE_SUPPLY",
                                "Supply collateral", flow.getAssetSymbol(), flow.getQuantityDelta().abs()));
                    }
                }
                case LENDING_LOOP_DECREASE, LENDING_LOOP_CLOSE, LENDING_WITHDRAW -> {
                    if (sign > 0) {
                        legs.add(childLeg("withdraw", classificationResult.type().name(), "FLUID_WALLET_VISIBLE_WITHDRAW",
                                displayLabel(classificationResult.type()), flow.getAssetSymbol(), flow.getQuantityDelta().abs()));
                    }
                }
                case LENDING_DEPOSIT -> {
                    if (sign < 0) {
                        legs.add(childLeg("deposit", "LENDING_DEPOSIT", "FLUID_WALLET_VISIBLE_SUPPLY",
                                "Deposit", flow.getAssetSymbol(), flow.getQuantityDelta().abs()));
                    }
                }
                case BORROW -> {
                    if (sign > 0) {
                        legs.add(childLeg("borrow", "BORROW", "FLUID_WALLET_VISIBLE_BORROW",
                                "Borrow", flow.getAssetSymbol(), flow.getQuantityDelta().abs()));
                    }
                }
                case REPAY -> {
                    if (sign < 0) {
                        legs.add(childLeg("repay", "REPAY", "FLUID_WALLET_VISIBLE_REPAY",
                                "Repay", flow.getAssetSymbol(), flow.getQuantityDelta().abs()));
                    }
                }
                default -> {
                }
            }
        }
    }

    private Document childLeg(String id, String type, String eventSubtype, String displayType, String asset, BigDecimal quantity) {
        return new Document(Map.of(
                "id", id,
                "type", type,
                "eventSubtype", eventSubtype,
                "displayType", displayType,
                "assetSymbol", asset,
                "quantity", quantity
        ));
    }

    private List<Document> fluidLogOperateEvidence(OnChainRawTransactionView view) {
        List<Document> result = new ArrayList<>();
        for (Document log : view.persistedLogs()) {
            List<?> topics = list(log.get("topics"));
            if (topics.size() < 3 || !FLUID_LOG_OPERATE_TOPIC.equalsIgnoreCase(stringify(topics.get(0)))) {
                continue;
            }
            String token = addressFromTopic(stringify(topics.get(2)));
            String data = stringify(log.get("data"));
            List<String> words = dataWords(data);
            if (words.size() < 6) {
                continue;
            }
            BigInteger supplyRaw = signedWord(words.get(0));
            BigInteger borrowRaw = signedWord(words.get(1));
            int decimals = tokenDecimals(token);
            Document decoded = new Document();
            decoded.put("logIndex", log.get("logIndex"));
            decoded.put("user", addressFromTopic(stringify(topics.get(1))));
            decoded.put("token", token);
            decoded.put("symbol", tokenSymbol(token));
            decoded.put("supplyAmountRaw", supplyRaw.toString());
            decoded.put("supplyAmount", decimalAmount(supplyRaw, decimals));
            decoded.put("borrowAmountRaw", borrowRaw.toString());
            decoded.put("borrowAmount", decimalAmount(borrowRaw, decimals));
            decoded.put("withdrawTo", addressFromWord(words.get(2)));
            decoded.put("borrowTo", addressFromWord(words.get(3)));
            result.add(decoded);
        }
        return result;
    }

    private List<Document> fluidNftTransferEvidence(OnChainRawTransactionView view) {
        List<Document> result = new ArrayList<>();
        for (Document log : view.persistedLogs()) {
            String address = normalizeAddress(stringify(log.get("address")));
            List<?> topics = list(log.get("topics"));
            if (!FLUID_POSITION_NFT.equals(address)
                    || topics.size() < 4
                    || !ERC721_TRANSFER_TOPIC.equalsIgnoreCase(stringify(topics.get(0)))) {
                continue;
            }
            result.add(new Document(Map.of(
                    "logIndex", log.get("logIndex"),
                    "from", addressFromTopic(stringify(topics.get(1))),
                    "to", addressFromTopic(stringify(topics.get(2))),
                    "tokenId", unsignedWord(stringify(topics.get(3))).toString()
            )));
        }
        return result;
    }

    private String fluidNftId(OnChainRawTransactionView view) {
        return fluidNftTransferEvidence(view).stream()
                .map(document -> stringify(document.get("tokenId")))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<Document> erc20TransferLogReferences(OnChainRawTransactionView view) {
        List<Document> result = new ArrayList<>();
        for (Document log : view.persistedLogs()) {
            List<?> topics = list(log.get("topics"));
            if (topics.size() != 3 || !ERC721_TRANSFER_TOPIC.equalsIgnoreCase(stringify(topics.get(0)))) {
                continue;
            }
            String contract = normalizeAddress(stringify(log.get("address")));
            if (FLUID_POSITION_NFT.equals(contract)) {
                continue;
            }
            BigInteger rawAmount = unsignedWord(stringify(log.get("data")));
            int decimals = tokenDecimals(contract);
            result.add(new Document()
                    .append("logIndex", log.get("logIndex"))
                    .append("contract", contract)
                    .append("from", addressFromTopic(stringify(topics.get(1))))
                    .append("to", addressFromTopic(stringify(topics.get(2))))
                    .append("symbol", tokenSymbol(contract))
                    .append("amountRaw", rawAmount.toString())
                    .append("amount", decimalAmount(rawAmount, decimals)));
        }
        return result;
    }

    private Object firstLogValue(OnChainRawTransactionView view, String key) {
        return view.persistedLogs().stream()
                .map(log -> log.get(key))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String resolveEventSubtype(
            OnChainRawTransactionView view,
            OnChainClassificationResult classificationResult
    ) {
        if (classificationResult.type() != com.walletradar.domain.transaction.normalized.NormalizedTransactionType.REPAY
                || view == null) {
            return null;
        }
        if (functionStartsWith(view.functionName(), "repaywithatokens(")) {
            return "REPAY_WITH_ATOKENS";
        }
        return view.explorerTokenTransfers().stream()
                .map(transfer -> transfer == null ? null : transfer.get("functionName"))
                .map(value -> value == null ? null : value.toString())
                .anyMatch(functionName -> functionStartsWith(functionName, "repaywithatokens("))
                || hasRepayWithATokensFlowShape(classificationResult.flows())
                ? "REPAY_WITH_ATOKENS"
                : null;
    }

    private boolean functionStartsWith(String functionName, String prefix) {
        return functionName != null && functionName.trim().toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private boolean hasRepayWithATokensFlowShape(List<NormalizedTransaction.Flow> flows) {
        if (flows == null || flows.isEmpty()) {
            return false;
        }
        boolean burnsDebtMarker = false;
        boolean burnsReceiptToken = false;
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            String symbol = flow.getAssetSymbol() == null ? "" : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            if (symbol.startsWith("VARIABLEDEBT") || symbol.startsWith("STABLEDEBT")) {
                burnsDebtMarker = true;
            } else if (symbol.startsWith("A")) {
                burnsReceiptToken = true;
            }
        }
        return burnsDebtMarker && burnsReceiptToken;
    }

    private String displayLabel(NormalizedTransactionType type) {
        return switch (type) {
            case LENDING_LOOP_DECREASE -> "Loop decrease";
            case LENDING_LOOP_CLOSE -> "Loop close";
            case LENDING_WITHDRAW -> "Withdraw";
            default -> type.name();
        };
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String stringify(Object value) {
        return value == null ? null : value.toString();
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String addressFromTopic(String topic) {
        if (topic == null || topic.length() < 42) {
            return null;
        }
        return normalizeAddress("0x" + topic.substring(topic.length() - 40));
    }

    private String addressFromWord(String word) {
        if (word == null || word.length() != 64) {
            return null;
        }
        return normalizeAddress("0x" + word.substring(24));
    }

    private List<String> dataWords(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }
        String hex = data.startsWith("0x") ? data.substring(2) : data;
        List<String> words = new ArrayList<>();
        for (int index = 0; index + 64 <= hex.length(); index += 64) {
            words.add(hex.substring(index, index + 64));
        }
        return words;
    }

    private BigInteger signedWord(String word) {
        BigInteger value = unsignedWord(word);
        return value.testBit(255) ? value.subtract(TWO_256) : value;
    }

    private BigInteger unsignedWord(String word) {
        String hex = word == null ? "" : (word.startsWith("0x") ? word.substring(2) : word);
        if (hex.isBlank()) {
            return BigInteger.ZERO;
        }
        return new BigInteger(hex, 16);
    }

    private BigDecimal decimalAmount(BigInteger raw, int decimals) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw).movePointLeft(Math.max(0, decimals)).stripTrailingZeros();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int tokenDecimals(String contract) {
        String normalized = normalizeAddress(contract);
        if (normalized == null) {
            return 18;
        }
        return switch (normalized) {
            case "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb",
                 "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                 "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8" -> 6;
            case "0x2a52b289ba68bbd02676640aa9f605700c9e5699" -> 18;
            default -> 18;
        };
    }

    private String tokenSymbol(String contract) {
        String normalized = normalizeAddress(contract);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb" -> "USDT0";
            case "0x2a52b289ba68bbd02676640aa9f605700c9e5699" -> "wstUSR";
            case "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                 "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8" -> "USDC";
            default -> contract;
        };
    }

    private int clarificationAttemptBaseline(OnChainRawTransactionView view) {
        return view == null ? 0 : view.clarificationAttemptCount();
    }

    private int fullReceiptClarificationAttemptBaseline(OnChainRawTransactionView view) {
        return view == null ? 0 : view.fullReceiptClarificationAttemptCount();
    }

    private int safeCounter(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
