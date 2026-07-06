package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-044 D3 — classification-time native-settlement clarification trigger.
 *
 * <p>A {@code SWAP} / {@code LP_EXIT*} / {@code LP_FEE_CLAIM} / {@code UNWRAP} transaction that
 * classifies off its visible token leg (e.g. USDC out of an LP exit) never fetches a full receipt,
 * so the WETH {@code Withdrawal} evidence that {@link NativeSettlementRecovery} needs is never
 * persisted. This support detects the narrow "native output but missing native inbound" shape and
 * signals that the transaction must be routed to the <b>existing</b> full-receipt clarification path
 * (via {@code NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED}), after which recovery is deterministic.
 *
 * <p>It fires only when:
 * <ul>
 *   <li>the type is a native-output-capable family;</li>
 *   <li>no full-receipt clarification evidence exists yet (idempotent — never re-triggers);</li>
 *   <li>there is no inbound native leg and no indexed internal transfer to the wallet;</li>
 *   <li>calldata exhibits a native-output signal (embedded/top-level {@code WETH.withdraw} selector
 *       or a native-sentinel destination), or a WETH {@code Withdrawal(src, wad)} log with
 *       {@code src != wallet} is already present.</li>
 * </ul>
 * This reuses the existing clarification mechanism and adds no new RPC subsystem.
 */
public final class NativeSettlementClarificationTrigger {

    private static final Set<NormalizedTransactionType> TARGET_TYPES = EnumSet.of(
            NormalizedTransactionType.SWAP,
            NormalizedTransactionType.LP_EXIT,
            NormalizedTransactionType.LP_EXIT_PARTIAL,
            NormalizedTransactionType.LP_EXIT_FINAL,
            NormalizedTransactionType.LP_FEE_CLAIM,
            NormalizedTransactionType.UNWRAP
    );

    private static final String WRAPPED_NATIVE_WITHDRAW_SELECTOR = "0x2e1a7d4d";
    private static final String NATIVE_SENTINEL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String WETH_WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    private NativeSettlementClarificationTrigger() {
    }

    public static boolean requiresReceiptClarification(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        if (view == null || movementLegs == null || type == null || nativeAssetSymbolResolver == null) {
            return false;
        }
        if (!TARGET_TYPES.contains(type)) {
            return false;
        }
        if (view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        String wrappedContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());
        if (nativeSymbol == null || wrappedContract == null) {
            return false;
        }
        if (hasInboundNative(movementLegs, nativeSymbol)) {
            return false;
        }
        if (hasIndexedInternalTransferToWallet(view)) {
            return false;
        }
        return hasNativeOutputSignal(view, wrappedContract);
    }

    private static boolean hasNativeOutputSignal(OnChainRawTransactionView view, String wrappedContract) {
        String inputData = view.inputData();
        if (WRAPPED_NATIVE_WITHDRAW_SELECTOR.equals(view.methodId())
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, WRAPPED_NATIVE_WITHDRAW_SELECTOR)) {
            return true;
        }
        if (inputData != null && inputData.contains(NATIVE_SENTINEL.substring(2))) {
            return true;
        }
        return hasWrappedNativeWithdrawalLog(view, wrappedContract);
    }

    private static boolean hasWrappedNativeWithdrawalLog(OnChainRawTransactionView view, String wrappedContract) {
        String userWallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        for (Document log : view.persistedLogs()) {
            if (log == null) {
                continue;
            }
            List<?> topics = log.getList("topics", Object.class, List.of());
            if (topics.size() < 2) {
                continue;
            }
            if (!WETH_WITHDRAWAL_TOPIC.equals(normalizeTopic(stringValue(topics.getFirst())))) {
                continue;
            }
            if (!wrappedContract.equals(normalizeAddress(stringValue(log.get("address"))))) {
                continue;
            }
            String src = normalizeIndexedAddress(stringValue(topics.get(1)));
            if (src != null && !src.equals(userWallet)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIndexedInternalTransferToWallet(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (wallet == null) {
            return false;
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (wallet.equals(OnChainRawTransactionView.normalizeAddress(view.internalTransferTo(transfer)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInboundNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> leg != null
                        && !leg.fee()
                        && leg.assetContract() == null
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private static String normalizeAddress(String value) {
        return OnChainRawTransactionView.normalizeAddress(value);
    }

    private static String normalizeIndexedAddress(String topicValue) {
        String normalized = normalizeTopic(topicValue);
        if (normalized == null || normalized.length() != 66) {
            return null;
        }
        return normalizeAddress("0x" + normalized.substring(normalized.length() - 40));
    }

    private static String normalizeTopic(String value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
