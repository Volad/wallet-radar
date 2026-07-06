package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.config.NativeSettlementRecoveryProperties;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ADR-044 D2 — router-agnostic, evidence-first recovery of a native inbound {@link RawLeg} when the
 * explorer never surfaced the native settlement (no indexed internal transfer, no native-alias
 * transfer, no {@code msg.value} credit) and the transaction is not one of the few routers covered
 * by a selector-specific enricher ({@link OneInchNativeSettlementSupport},
 * {@link ParaSwapNativeSettlementSupport}, {@link LpNativeExitLegEnricher}).
 *
 * <p>This support runs <b>last</b> in {@link MovementLegExtractor#extract} and fires only when the
 * load-bearing {@code hasInboundNative} double-count guard is false (Invariant c). It sources the
 * recovered quantity from the canonical, exact, router-agnostic proof: the WETH
 * {@code Withdrawal(src, wad)} receipt log (evidence #3), gated by:
 * <ul>
 *   <li>{@code src != wallet} — an intermediary unwrapped WETH, not the wallet selling its own
 *       WETH;</li>
 *   <li>no wallet-originated wrapped-native outbound of the same {@code wad} in the same tx —
 *       positively excludes the sell side;</li>
 *   <li>a missing-inbound shape — no native inbound leg and no indexed internal transfer to the
 *       wallet.</li>
 * </ul>
 *
 * <p>It emits only a physical native inbound quantity leg; the existing SWAP pricing (ADR-021/022)
 * and LP-exit per-asset carry (ADR-022) attach basis downstream. No basis is computed here.
 * Calldata exact-out decoders (evidence #4) remain owned by the ParaSwap/1inch supports which run
 * earlier in {@code extract()}; when they fire, {@code hasInboundNative} makes this recovery a
 * no-op. Unlike {@link LpNativeExitLegEnricher}, this generalization does not require an embedded
 * {@code decreaseLiquidity}/{@code burn} selector, so it covers 0x, LiFi, Odos, Kyber, Universal
 * Router and generic multicall LP exits uniformly.
 */
public final class NativeSettlementRecovery {

    static final String WETH_WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    private static final int NATIVE_DECIMALS = 18;

    private NativeSettlementRecovery() {
    }

    /**
     * Returns an enriched leg list including a synthesized native inbound leg recovered from WETH
     * {@code Withdrawal} evidence, or the original list unchanged when the guards do not match or
     * recovery is not enabled for the chain (ADR-044 D5).
     */
    public static List<RawLeg> enrichLegs(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> legs,
            NativeSettlementRecoveryProperties properties
    ) {
        if (view == null || nativeAssetSymbolResolver == null || legs == null || properties == null) {
            return legs;
        }
        if (!properties.isEnabledForChain(view.networkId())) {
            return legs;
        }

        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        String wrappedContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());
        if (nativeSymbol == null || wrappedContract == null) {
            return legs;
        }

        // Invariant (c): never add a second native inbound when one already exists.
        if (hasInboundNative(legs, nativeSymbol)) {
            return legs;
        }
        // Evidence #1: an indexed internal transfer to the wallet already materialized the credit.
        if (hasIndexedInternalTransferToWallet(view)) {
            return legs;
        }

        BigDecimal recovered = recoverFromWithdrawalLogs(view, wrappedContract, legs);
        if (recovered == null || recovered.signum() <= 0) {
            return legs;
        }

        List<RawLeg> enriched = new ArrayList<>(legs);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, recovered));
        return enriched;
    }

    private static BigDecimal recoverFromWithdrawalLogs(
            OnChainRawTransactionView view,
            String wrappedContract,
            List<RawLeg> legs
    ) {
        String userWallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        if (userWallet == null) {
            return null;
        }
        // Sell-side exclusion: the wallet's own wrapped-native outbound quantities. A Withdrawal of
        // exactly one of these wads is the router unwrapping the wallet's sold WETH, not a payout.
        List<BigDecimal> walletWrappedOutboundWads = walletWrappedOutboundWads(legs, wrappedContract);

        BigDecimal total = BigDecimal.ZERO;
        boolean matched = false;
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
            if (src == null || src.equals(userWallet)) {
                continue;
            }
            BigDecimal wad = parseUnsignedQuantity(stringValue(log.get("data")), NATIVE_DECIMALS);
            if (wad == null || wad.signum() <= 0) {
                continue;
            }
            if (consumeMatchingOutboundWad(walletWrappedOutboundWads, wad)) {
                continue;
            }
            total = total.add(wad);
            matched = true;
        }
        return matched ? total : null;
    }

    private static List<BigDecimal> walletWrappedOutboundWads(List<RawLeg> legs, String wrappedContract) {
        List<BigDecimal> wads = new ArrayList<>();
        for (RawLeg leg : legs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() >= 0) {
                continue;
            }
            if (wrappedContract.equalsIgnoreCase(normalizeContract(leg.assetContract()))) {
                wads.add(leg.quantityDelta().abs());
            }
        }
        return wads;
    }

    private static boolean consumeMatchingOutboundWad(List<BigDecimal> walletWrappedOutboundWads, BigDecimal wad) {
        for (int index = 0; index < walletWrappedOutboundWads.size(); index++) {
            if (walletWrappedOutboundWads.get(index).compareTo(wad) == 0) {
                walletWrappedOutboundWads.remove(index);
                return true;
            }
        }
        return false;
    }

    private static boolean hasIndexedInternalTransferToWallet(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (matchesWallet(view, view.internalTransferTo(transfer))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesWallet(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String candidate = OnChainRawTransactionView.normalizeAddress(address);
        return wallet != null && wallet.equals(candidate);
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

    private static String normalizeContract(String assetContract) {
        return assetContract == null ? null : assetContract.trim().toLowerCase(Locale.ROOT);
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

    private static BigDecimal parseUnsignedQuantity(String value, int decimals) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            BigInteger raw = text.startsWith("0x") || text.startsWith("0X")
                    ? new BigInteger(text.substring(2), 16)
                    : new BigInteger(text);
            return new BigDecimal(raw).movePointLeft(Math.max(0, decimals));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
