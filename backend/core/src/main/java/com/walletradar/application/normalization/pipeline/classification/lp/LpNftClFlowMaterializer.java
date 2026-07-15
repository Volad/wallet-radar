package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.application.costbasis.support.LpReceiptSymbolSupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.LpExitFeeAmounts;
import com.walletradar.application.normalization.pipeline.classification.support.LpExitFeeDecomposer;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.NativeWrappedTokenSupport;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;

/**
 * Materializes NFT concentrated-liquidity receipt legs for Pancake/Uni/Velodrome/Aerodrome flows,
 * and (when full receipt evidence is present) splits inbound TRANSFER legs into
 * {@code principal} + {@code LP_FEE_INCOME} using decoded {@code DecreaseLiquidity} / {@code Collect}
 * event amounts (R1 — V3/Slipstream fee split).
 */
public final class LpNftClFlowMaterializer {

    private static final Logger log = LoggerFactory.getLogger(LpNftClFlowMaterializer.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    private LpNftClFlowMaterializer() {
    }

    public static List<NormalizedTransaction.Flow> enrich(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String correlationId,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        return enrich(view, movementLegs, type, correlationId, baseFlows, null);
    }

    /**
     * Enriches flows with optional pre-computed V4 fee fractions (from {@code LpV4ExitFeeDecomposer}).
     * When {@code externalFeeFractions} is non-null, V4 fee fractions take priority over the V3
     * auto-decode path for exits.
     *
     * @param externalFeeFractions per-contract fee fractions from V4 decomposer, or {@code null}
     */
    public static List<NormalizedTransaction.Flow> enrich(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String correlationId,
            List<NormalizedTransaction.Flow> baseFlows,
            Map<String, BigDecimal> externalFeeFractions
    ) {
        if (view == null || type == null || !supportsMaterialization(type)) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        if (correlationId == null) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        String receiptSymbol = receiptSymbol(correlationId);
        if (receiptSymbol == null) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        if (type == NormalizedTransactionType.LP_ENTRY) {
            return enrichEntry(view, movementLegs, type, receiptSymbol, baseFlows);
        }
        if (isPrincipalExitType(type)) {
            return enrichPrincipalExit(view, movementLegs, type, receiptSymbol, baseFlows, externalFeeFractions);
        }
        // BLOCKER-3: Balancer V3 gauge stake/unstake — canonicalize BPT → LP-RECEIPT.
        // Only applies when the receipt symbol is a registered Balancer V3 BPT pool (otherwise
        // we return baseFlows unchanged and preserve existing behaviour for other protocols).
        if (type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE) {
            if (!isBalancerV3BptReceipt(receiptSymbol)) {
                return baseFlows == null ? List.of() : baseFlows;
            }
            return enrichBptStakeUnstake(movementLegs, type, receiptSymbol, baseFlows);
        }
        return baseFlows == null ? List.of() : baseFlows;
    }

    public static boolean supportsMaterialization(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL
                // BLOCKER-3: BPT canonicalization for Balancer V3 gauge stake/unstake lifecycle.
                // LP_POSITION_STAKE sends the BPT to the gauge; LP_POSITION_UNSTAKE returns it.
                // Both require the raw BPT flow to be rewritten as LP-RECEIPT so the position
                // uses the same canonical key throughout its lifecycle (entry → stake → unstake → exit).
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE;
    }

    private static List<NormalizedTransaction.Flow> enrichEntry(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        boolean hasNftMintLog = LpPositionLifecycleSupport.hasPositionNftMintLog(view);
        // When an operator-supplied manualCorrelationOverride is present the full receipt is
        // permanently unavailable, so hasPositionNftMintLog is always false. Treat the override
        // as equivalent evidence — emit a synthetic qty=+1 NFT receipt leg so the position
        // tracks under the canonical LP-RECEIPT key throughout its lifecycle.
        boolean hasManualOverride = view != null && view.manualCorrelationOverride() != null;
        if (!hasNftMintLog && !hasManualOverride) {
            // A2 (minimal, R6a): ERC-20 BPT receipt for Balancer V3 pool-level positions.
            // The BPT token IS the receipt; its inbound quantity replaces the raw BPT transfer leg.
            if (isBalancerV3BptReceipt(receiptSymbol)) {
                return enrichBptEntry(movementLegs, receiptSymbol, baseFlows);
            }
            // R7 (A2 minimal): LFJ Liquidity Book ERC-1155 pair — emit a synthetic qty=+1 receipt
            // so entry/exit correlate into one basis pool. Full TransferBatch share sum is deferred
            // (stable AUSD/USDC, negligible materiality; see plan R7 Option-B carry).
            if (isLfjLbReceipt(receiptSymbol)) {
                List<NormalizedTransaction.Flow> flows = new ArrayList<>(baseFlows == null ? List.of() : baseFlows);
                if (!containsReceiptSymbol(flows, receiptSymbol)) {
                    NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
                    receipt.setAssetSymbol(receiptSymbol);
                    receipt.setQuantityDelta(BigDecimal.ONE);
                    receipt.setRole(NormalizedLegRole.TRANSFER);
                    flows.add(receipt);
                }
                return flows;
            }
            return baseFlows == null ? List.of() : baseFlows;
        }
        List<NormalizedTransaction.Flow> flows = new ArrayList<>(baseFlows == null ? List.of() : baseFlows);
        if (!containsReceiptSymbol(flows, receiptSymbol)) {
            NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
            receipt.setAssetSymbol(receiptSymbol);
            receipt.setQuantityDelta(BigDecimal.ONE);
            receipt.setRole(NormalizedLegRole.TRANSFER);
            flows.add(receipt);
        }
        return mergePrincipalLegsFromRaw(view, movementLegs, type, flows);
    }

    /**
     * A2 (minimal, R6a): Balancer V3 ERC-20 BPT entry materialization.
     *
     * <p>Replaces the raw BPT inbound transfer with an LP-RECEIPT leg carrying the actual BPT
     * quantity. Non-BPT outbound legs (the stables sent to the Vault) are preserved from
     * {@code baseFlows}. The BPT pool address is extracted from the receipt symbol's last segment.
     */
    private static List<NormalizedTransaction.Flow> enrichBptEntry(
            List<RawLeg> movementLegs,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        String bptAddr = extractBptPoolAddrFromReceipt(receiptSymbol);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        BigDecimal bptQty = BigDecimal.ZERO;

        if (baseFlows != null) {
            for (NormalizedTransaction.Flow flow : baseFlows) {
                if (flow == null) {
                    continue;
                }
                // Skip the raw BPT inbound leg — it will be replaced by the LP-RECEIPT leg below.
                if (bptAddr != null && bptAddr.equalsIgnoreCase(flow.getAssetContract())
                        && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0) {
                    bptQty = flow.getQuantityDelta();
                    continue;
                }
                flows.add(flow);
            }
        }

        // Fallback: scan raw movement legs if baseFlows didn't have the BPT leg.
        if (bptQty.signum() == 0 && bptAddr != null && movementLegs != null) {
            for (RawLeg leg : movementLegs) {
                if (leg == null || leg.fee()) {
                    continue;
                }
                if (bptAddr.equalsIgnoreCase(leg.assetContract())
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() > 0) {
                    bptQty = leg.quantityDelta();
                    break;
                }
            }
        }

        if (!containsReceiptSymbol(flows, receiptSymbol) && bptQty.signum() > 0) {
            NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
            receipt.setAssetSymbol(receiptSymbol);
            receipt.setQuantityDelta(bptQty);
            receipt.setRole(NormalizedLegRole.TRANSFER);
            flows.add(receipt);
        }
        return flows;
    }

    /**
     * BLOCKER-3: Balancer V3 gauge stake/unstake BPT canonicalization.
     *
     * <p>For {@code LP_POSITION_STAKE} the BPT (which is the LP receipt) is sent to the gauge:
     * the raw BPT outbound leg is replaced by an LP-RECEIPT outbound leg so the position tracks
     * under the canonical {@code LP-RECEIPT:NETWORK:BALANCERV3:poolAddress} key, consistent with
     * the LP_ENTRY inbound flow and the LP_EXIT outbound burn.</p>
     *
     * <p>For {@code LP_POSITION_UNSTAKE} the BPT is returned from the gauge: the raw BPT inbound
     * leg is replaced by an LP-RECEIPT inbound leg (symmetric with LP_POSITION_STAKE).</p>
     *
     * <p>Gauge token legs (any flow whose contract ≠ BPT pool address) are preserved unchanged.</p>
     */
    private static List<NormalizedTransaction.Flow> enrichBptStakeUnstake(
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        String bptAddr = extractBptPoolAddrFromReceipt(receiptSymbol);
        boolean isStake = type == NormalizedTransactionType.LP_POSITION_STAKE;
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        BigDecimal bptQty = BigDecimal.ZERO;
        // effectiveReceiptSymbol may be rewritten below (STAKE gauge-receipt fallback).
        String effectiveReceiptSymbol = receiptSymbol;

        if (baseFlows != null) {
            for (NormalizedTransaction.Flow flow : baseFlows) {
                if (flow == null) {
                    continue;
                }
                // Skip the raw BPT leg — replace it with LP-RECEIPT below.
                if (bptAddr != null && bptAddr.equalsIgnoreCase(flow.getAssetContract())
                        && flow.getQuantityDelta() != null
                        && (isStake
                                ? flow.getQuantityDelta().signum() < 0
                                : flow.getQuantityDelta().signum() > 0)) {
                    bptQty = flow.getQuantityDelta().abs();
                    continue;
                }
                flows.add(flow);
            }
        }

        // Fallback A: scan raw movement legs if baseFlows did not contain the BPT leg.
        if (bptQty.signum() == 0 && bptAddr != null && movementLegs != null) {
            for (RawLeg leg : movementLegs) {
                if (leg == null || leg.fee()) {
                    continue;
                }
                if (bptAddr.equalsIgnoreCase(leg.assetContract())
                        && leg.quantityDelta() != null
                        && (isStake
                                ? leg.quantityDelta().signum() < 0
                                : leg.quantityDelta().signum() > 0)) {
                    bptQty = leg.quantityDelta().abs();
                    break;
                }
            }
        }

        // Fallback B (STAKE only): the receiptSymbol may point to the gauge contract rather than
        // the BPT pool (e.g. LP-RECEIPT:AVALANCHE:BALANCERV3:0x<gauge>). In that case bptAddr is
        // the gauge address, which does not match the negative BPT flow. Scan movementLegs for any
        // negative non-gauge non-fee flow, treat that contract as the BPT address, and rewrite
        // receiptSymbol accordingly. Also handles the case where baseFlows is null (common for
        // LP_POSITION_STAKE when no pre-built flows are passed).
        if (isStake && bptQty.signum() == 0 && movementLegs != null) {
            String networkSegment = extractNetworkSegmentFromReceipt(receiptSymbol); // e.g. "AVALANCHE"
            for (RawLeg leg : movementLegs) {
                if (leg == null || leg.fee()) {
                    continue;
                }
                if (leg.assetContract() == null) {
                    continue;
                }
                if (leg.quantityDelta() == null || leg.quantityDelta().signum() >= 0) {
                    continue;
                }
                String candidateContract = leg.assetContract().toLowerCase(Locale.ROOT);
                // Skip the gauge contract itself.
                if (bptAddr != null && bptAddr.equalsIgnoreCase(candidateContract)) {
                    continue;
                }
                // This negative raw leg is the BPT being staked. Rebuild the LP-RECEIPT symbol.
                if (networkSegment != null) {
                    effectiveReceiptSymbol = "LP-RECEIPT:" + networkSegment + ":BALANCERV3:" + candidateContract;
                    bptQty = leg.quantityDelta().abs();
                    // Remove the raw BPT flow from the output list (may already be there if baseFlows was non-null).
                    final String finalCandidateContract = candidateContract;
                    flows.removeIf(f -> f != null && finalCandidateContract.equalsIgnoreCase(f.getAssetContract())
                            && f.getQuantityDelta() != null && f.getQuantityDelta().signum() < 0);
                    break;
                }
            }
        }

        if (bptQty.signum() > 0) {
            NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
            receipt.setAssetSymbol(effectiveReceiptSymbol);
            receipt.setQuantityDelta(isStake ? bptQty.negate() : bptQty);
            receipt.setRole(NormalizedLegRole.TRANSFER);
            flows.add(receipt);
        }
        return flows;
    }

    /** Extracts the network segment (e.g. {@code "AVALANCHE"}) from an LP-RECEIPT symbol. */
    private static String extractNetworkSegmentFromReceipt(String receiptSymbol) {
        if (receiptSymbol == null) {
            return null;
        }
        // Format: LP-RECEIPT:<NET>:PROTOCOL:<poolAddr>
        String[] parts = receiptSymbol.split(":", 4);
        if (parts.length < 2) {
            return null;
        }
        return parts[1]; // network segment
    }

    /**
     * BLOCKER-3: Removes the raw BPT outbound flow from a Balancer V3 LP_EXIT flow list so that
     * the canonical LP-RECEIPT burn is the sole representation of the BPT disposal.
     *
     * <p>The raw ERC-20 BPT transfer-to-zero is already semantically captured by the LP-RECEIPT
     * outbound leg. Keeping both creates a double-burn in the replay engine.</p>
     */
    private static void removeRawBptOutboundFlow(List<NormalizedTransaction.Flow> flows, String receiptSymbol) {
        String bptAddr = extractBptPoolAddrFromReceipt(receiptSymbol);
        if (bptAddr == null) {
            return;
        }
        flows.removeIf(flow -> flow != null
                && bptAddr.equalsIgnoreCase(flow.getAssetContract())
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0);
    }

    /**
     * A2 (minimal, R6a): Balancer V3 ERC-20 BPT exit materialization.
     *
     * <p>Replaces the raw BPT outbound transfer with an LP-RECEIPT outbound leg carrying the
     * actual (negated) BPT quantity. Called by {@link #enrichPrincipalExit} when the receipt
     * symbol indicates a Balancer V3 BPT pool.
     */
    private static NormalizedTransaction.Flow buildBptOutboundReceiptLeg(
            List<NormalizedTransaction.Flow> flows,
            List<RawLeg> movementLegs,
            String receiptSymbol
    ) {
        String bptAddr = extractBptPoolAddrFromReceipt(receiptSymbol);
        BigDecimal bptQty = BigDecimal.ZERO;

        // Try baseFlows first.
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null) {
                continue;
            }
            if (bptAddr != null && bptAddr.equalsIgnoreCase(flow.getAssetContract())
                    && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0) {
                bptQty = flow.getQuantityDelta().abs();
                break;
            }
        }
        // Fallback to raw movement legs.
        if (bptQty.signum() == 0 && bptAddr != null && movementLegs != null) {
            for (RawLeg leg : movementLegs) {
                if (leg == null || leg.fee()) {
                    continue;
                }
                if (bptAddr.equalsIgnoreCase(leg.assetContract())
                        && leg.quantityDelta() != null && leg.quantityDelta().signum() < 0) {
                    bptQty = leg.quantityDelta().abs();
                    break;
                }
            }
        }

        if (bptQty.signum() == 0) {
            // No BPT outbound found; fall back to qty=1 (NFT convention) to avoid missing receipt leg.
            bptQty = BigDecimal.ONE;
        }
        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setAssetSymbol(receiptSymbol);
        receipt.setQuantityDelta(bptQty.negate());
        receipt.setRole(NormalizedLegRole.TRANSFER);
        return receipt;
    }

    private static boolean isBalancerV3BptReceipt(String receiptSymbol) {
        if (receiptSymbol == null) {
            return false;
        }
        String upper = receiptSymbol.toUpperCase(Locale.ROOT);
        return upper.startsWith("LP-RECEIPT:") && upper.contains(":BALANCERV3:");
    }

    /**
     * R7: Detects a synthetic LFJ Liquidity Book receipt symbol of the form
     * {@code LP-RECEIPT:<NET>:LFJ:<pairAddress>}.
     */
    private static boolean isLfjLbReceipt(String receiptSymbol) {
        if (receiptSymbol == null) {
            return false;
        }
        String upper = receiptSymbol.toUpperCase(Locale.ROOT);
        return upper.startsWith("LP-RECEIPT:") && upper.contains(":LFJ:");
    }

    /**
     * Extracts the BPT pool address from a receipt symbol of the form
     * {@code LP-RECEIPT:<NET>:BALANCERV3:<poolAddress>}.
     */
    private static String extractBptPoolAddrFromReceipt(String receiptSymbol) {
        if (receiptSymbol == null) {
            return null;
        }
        // Format: LP-RECEIPT:<NET>:BALANCERV3:<poolAddr> — exactly 4 colon-separated segments.
        String[] parts = receiptSymbol.split(":", 4);
        if (parts.length < 4) {
            return null;
        }
        return parts[3]; // poolAddress (lowercased by LpReceiptSymbolSupport.canonicalizeSegments)
    }

    private static List<NormalizedTransaction.Flow> enrichPrincipalExit(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        return enrichPrincipalExit(view, movementLegs, type, receiptSymbol, baseFlows, null);
    }

    private static List<NormalizedTransaction.Flow> enrichPrincipalExit(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows,
            Map<String, BigDecimal> externalFeeFractions
    ) {
        List<NormalizedTransaction.Flow> flows = mergePrincipalLegsFromRaw(
                view,
                movementLegs,
                type,
                new ArrayList<>(baseFlows == null ? List.of() : baseFlows)
        );
        if (externalFeeFractions != null && !externalFeeFractions.isEmpty()) {
            // V4 path: use pre-computed fee fractions from LpV4ExitFeeDecomposer
            flows = splitFeeFlows(flows, externalFeeFractions);
        } else {
            // V3/Slipstream path: auto-decode from DecreaseLiquidity/Collect events
            flows = applyFeeSplitIfAvailable(view, flows);
        }
        if (!containsReceiptSymbol(flows, receiptSymbol)) {
            if (isBalancerV3BptReceipt(receiptSymbol)) {
                // A2 / BLOCKER-3: replace the raw BPT outbound flow with the canonical LP-RECEIPT
                // outbound leg so LP_EXIT does not double-burn both "Aave GHO/USDT/USDC" and
                // "LP-RECEIPT:AVALANCHE:BALANCERV3:0xfcec3c8d...".
                NormalizedTransaction.Flow receipt = buildBptOutboundReceiptLeg(flows, movementLegs, receiptSymbol);
                removeRawBptOutboundFlow(flows, receiptSymbol);
                flows.add(receipt);
            } else {
                flows.add(buildNftOutboundReceiptLeg(receiptSymbol));
            }
        }
        return flows;
    }

    private static NormalizedTransaction.Flow buildNftOutboundReceiptLeg(String receiptSymbol) {
        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setAssetSymbol(receiptSymbol);
        receipt.setQuantityDelta(BigDecimal.ONE.negate());
        receipt.setRole(NormalizedLegRole.TRANSFER);
        return receipt;
    }

    /**
     * R1: splits inbound TRANSFER legs into principal + {@code LP_FEE_INCOME} when
     * {@code DecreaseLiquidity} and {@code Collect} event evidence is present in persisted logs.
     * Idempotent: if logs are absent or no fee exists, returns the list unchanged.
     */
    private static List<NormalizedTransaction.Flow> applyFeeSplitIfAvailable(
            OnChainRawTransactionView view,
            List<NormalizedTransaction.Flow> flows
    ) {
        Optional<LpExitFeeAmounts> feeAmounts = LpExitFeeDecomposer.decode(view);
        if (feeAmounts.isEmpty() || !feeAmounts.get().hasFee()) {
            return flows;
        }
        Map<String, BigDecimal> feeFractions = LpExitFeeDecomposer.feeFractionsForContracts(
                feeAmounts.get(), view);
        if (feeFractions.isEmpty()) {
            return flows;
        }
        return splitFeeFlows(flows, feeFractions, view.networkId());
    }

    /**
     * Replaces each inbound TRANSFER leg whose contract appears in {@code feeFractions} with two
     * legs: a {@code TRANSFER} principal leg and an {@code LP_FEE_INCOME} fee leg. Legs whose
     * contract is absent from the map, or where the fee fraction is zero, are left unchanged.
     *
     * <p><strong>C2 — Native ETH support:</strong> for null-assetContract (native ETH) TRANSFER
     * legs, the fee fraction is looked up by the canonical WETH contract address for {@code networkId}
     * (see {@link NativeWrappedTokenSupport}). This handles Uniswap V3/Slipstream exits that return
     * native ETH via {@code unwrapWETH9} instead of WETH directly.</p>
     *
     * @param flows        flows to process
     * @param feeFractions map of normalized contract address → fee fraction [0, 1]
     * @param networkId    network for WETH address resolution; may be {@code null} (disables native ETH split)
     */
    private static List<NormalizedTransaction.Flow> splitFeeFlows(
            List<NormalizedTransaction.Flow> flows,
            Map<String, BigDecimal> feeFractions,
            com.walletradar.domain.common.NetworkId networkId
    ) {
        List<NormalizedTransaction.Flow> result = new ArrayList<>(flows.size() + feeFractions.size());
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null) {
                result.add(null);
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0) {
                result.add(flow);
                continue;
            }

            String key;
            if (flow.getAssetContract() == null) {
                // C2: native ETH — look up by canonical WETH address for this network.
                String canonicalWeth = NativeWrappedTokenSupport.canonicalWeth(networkId);
                key = canonicalWeth; // may be null if network not in scope
            } else {
                key = flow.getAssetContract().trim().toLowerCase(Locale.ROOT);
            }

            BigDecimal feeFraction = key != null ? feeFractions.get(key) : null;
            if (feeFraction == null || feeFraction.signum() <= 0) {
                result.add(flow);
                continue;
            }

            BigDecimal totalQty = flow.getQuantityDelta();
            BigDecimal feeQty = totalQty.multiply(feeFraction, MC).setScale(18, java.math.RoundingMode.HALF_DOWN);
            BigDecimal principalQty = totalQty.subtract(feeQty);

            log.info("LP exit fee split: asset={} totalQty={} principalQty={} feeQty={} feeFraction={}",
                    flow.getAssetSymbol(), totalQty, principalQty, feeQty, feeFraction);

            if (principalQty.signum() > 0) {
                result.add(copyFlowWithQty(flow, principalQty));
            }
            if (feeQty.signum() > 0) {
                NormalizedTransaction.Flow feeLeg = copyFlowWithQty(flow, feeQty);
                feeLeg.setRole(NormalizedLegRole.LP_FEE_INCOME);
                result.add(feeLeg);
            }
        }
        return result;
    }

    /**
     * V4 path: external pre-computed fee fractions (no native ETH support on this path — deferred).
     */
    private static List<NormalizedTransaction.Flow> splitFeeFlows(
            List<NormalizedTransaction.Flow> flows,
            Map<String, BigDecimal> feeFractions
    ) {
        return splitFeeFlows(flows, feeFractions, null);
    }

    private static NormalizedTransaction.Flow copyFlowWithQty(NormalizedTransaction.Flow src, BigDecimal qty) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(src.getRole());
        copy.setAssetContract(src.getAssetContract());
        copy.setAssetSymbol(src.getAssetSymbol());
        copy.setQuantityDelta(qty);
        copy.setLogIndex(src.getLogIndex());
        copy.setCounterpartyAddress(src.getCounterpartyAddress());
        copy.setCounterpartyType(src.getCounterpartyType());
        return copy;
    }

    private static List<NormalizedTransaction.Flow> mergePrincipalLegsFromRaw(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows
    ) {
        List<NormalizedTransaction.Flow> rawPrincipalFlows =
                OnChainClassificationSupport.toFlows(view, movementLegs, type);
        Map<String, NormalizedTransaction.Flow> merged = new LinkedHashMap<>();
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null) {
                continue;
            }
            merged.put(flowKey(flow), flow);
        }
        for (NormalizedTransaction.Flow flow : rawPrincipalFlows) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getAssetSymbol() != null && flow.getAssetSymbol().startsWith("LP-RECEIPT:")) {
                continue;
            }
            merged.putIfAbsent(flowKey(flow), flow);
        }
        return new ArrayList<>(merged.values());
    }

    private static boolean containsReceiptSymbol(List<NormalizedTransaction.Flow> flows, String receiptSymbol) {
        if (flows == null || receiptSymbol == null) {
            return false;
        }
        return flows.stream()
                .anyMatch(flow -> flow != null && receiptSymbol.equalsIgnoreCase(flow.getAssetSymbol()));
    }

    private static String receiptSymbol(String correlationId) {
        return LpReceiptSymbolSupport.fromLpPositionCorrelation(correlationId);
    }

    private static boolean isPrincipalExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static String flowKey(NormalizedTransaction.Flow flow) {
        String contract = flow.getAssetContract() == null
                ? ""
                : flow.getAssetContract().trim().toLowerCase(Locale.ROOT);
        String symbol = flow.getAssetSymbol() == null
                ? ""
                : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        String role = flow.getRole() == null ? "" : flow.getRole().name();
        String quantity = flow.getQuantityDelta() == null
                ? "0"
                : flow.getQuantityDelta().stripTrailingZeros().toPlainString();
        return contract + "|" + symbol + "|" + role + "|" + quantity;
    }
}
