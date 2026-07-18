package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;

/**
 * Shared spoof-token quarantine predicates (SF-1).
 *
 * <p><b>SF-1(a/b) — confusable-symbol:</b> A principal flow asset whose ticker contains a
 * non-allow-listed homoglyph (Cyrillic {@code UЅDС}, Lisu {@code ꓴꓢꓓС}, zero-width-injected
 * variants, …) and whose contract is not a registry-known canonical asset is a spoof token
 * impersonating a real stablecoin/native. Such rows must be quarantined
 * ({@code excludedFromAccounting=true}) so they never clutter the ledger or mislead the user
 * into copying a poisoned counterparty.</p>
 *
 * <p><b>SF-1(c) — native-symbol impersonation:</b> An ERC-20 token whose {@code assetSymbol} is
 * identical to a network's native asset symbol (e.g. an ERC-20 named {@code "ETH"} on Arbitrum)
 * and whose {@code assetContract} is not the canonical wrapped-native contract is a poisoning
 * token. If processed as real native ETH it creates phantom disposals against the live native-asset
 * ledger position, inflating shortfalls. These are quarantined with reason
 * {@code SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION}.</p>
 *
 * <p>Both the classification-time guard ({@code SpoofTokenClassifier}) and the idempotent
 * clarification sweep ({@code SpoofTokenDetector}) route through these predicates so the two
 * layers always converge on the same verdict.</p>
 */
public final class SpoofTokenQuarantineSupport {

    /** Persisted reason for SF-1(a/b) confusable-homoglyph spoof tokens. */
    public static final String REASON = ClassificationReasonCode.SPOOF_TOKEN_CONFUSABLE_SYMBOL.code();

    /** Persisted reason for SF-1(c) native-symbol impersonation spoof tokens. */
    public static final String NATIVE_IMPERSONATION_REASON =
            ClassificationReasonCode.SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION.code();

    private SpoofTokenQuarantineSupport() {
    }

    /**
     * SF-1(a/b): Returns {@code true} when an asset is a confusable-symbol spoof: its ticker
     * trips the homoglyph guard and its contract is not a registry-known canonical asset on
     * the network.
     */
    public static boolean isConfusableSpoofAsset(NetworkId networkId, String assetContract, String assetSymbol) {
        if (!CanonicalAssetCatalog.isConfusableSymbol(assetSymbol)) {
            return false;
        }
        return !CanonicalAssetCatalog.isKnownCanonicalContract(networkId, assetContract);
    }

    /**
     * SF-1(c): Returns {@code true} when an ERC-20 token impersonates the network's native asset
     * by using the exact same symbol (e.g. an ERC-20 named {@code "ETH"} on Arbitrum).
     *
     * <p>Genuine native transfers have {@code assetContract == null}. Genuine wrapped-native tokens
     * (WETH, WAVAX, …) have a distinct {@code assetSymbol} ("WETH"). Any ERC-20 that claims the
     * raw native symbol with a non-null, non-wrapped-native contract is an impersonator.</p>
     *
     * @param nativeSymbol        the canonical native symbol for the network (e.g. {@code "ETH"})
     * @param wrappedNativeContract the canonical wrapped-native contract address for the network
     *                            (e.g. Arbitrum WETH {@code 0x82af49447d8a07e3bd95bd0d56f35241523fbab1});
     *                            {@code null} if the network has no wrapped native
     * @param assetContract       the ERC-20 contract address from the raw leg (may be {@code null})
     * @param assetSymbol         the token symbol from the raw leg
     */
    public static boolean isNativeSymbolSpoof(
            String nativeSymbol, String wrappedNativeContract,
            String assetContract, String assetSymbol) {
        if (assetContract == null || assetContract.isBlank()) {
            return false;
        }
        if (nativeSymbol == null || !nativeSymbol.equalsIgnoreCase(
                assetSymbol == null ? null : assetSymbol.trim())) {
            return false;
        }
        if (wrappedNativeContract != null
                && wrappedNativeContract.equalsIgnoreCase(assetContract.trim())) {
            return false;
        }
        return true;
    }
}
