package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;

/**
 * Shared spoof-token (confusable-symbol) quarantine predicate (SF-1).
 *
 * <p>A principal flow asset whose ticker contains a non-allow-listed homoglyph
 * (Cyrillic {@code UЅDС}, Lisu {@code ꓴꓢꓓС}, zero-width-injected variants, …) and whose contract
 * is not a registry-known canonical asset is a spoof token impersonating a real stablecoin/native.
 * Such rows must be quarantined ({@code excludedFromAccounting=true}) so they never clutter the
 * ledger or mislead the user into copying a poisoned counterparty.</p>
 *
 * <p>Both the classification-time guard ({@code SpoofTokenClassifier}) and the idempotent
 * clarification sweep ({@code SpoofTokenDetector}) route through this single predicate so the two
 * layers always converge on the same verdict. The shape-based confusable-symbol guard
 * ({@link CanonicalAssetCatalog#isConfusableSymbol}) already allow-lists the legitimate {@code ₮}
 * (U+20AE) glyph used by real {@code USD₮0}, so real stablecoin transfers are never quarantined.</p>
 */
public final class SpoofTokenQuarantineSupport {

    /** Persisted accounting-exclusion reason and missing-data tag for quarantined spoof tokens. */
    public static final String REASON = ClassificationReasonCode.SPOOF_TOKEN_CONFUSABLE_SYMBOL.code();

    private SpoofTokenQuarantineSupport() {
    }

    /**
     * Returns {@code true} when an asset is a confusable-symbol spoof: its ticker trips the
     * homoglyph guard and its contract is not a registry-known canonical asset on the network.
     */
    public static boolean isConfusableSpoofAsset(NetworkId networkId, String assetContract, String assetSymbol) {
        if (!CanonicalAssetCatalog.isConfusableSymbol(assetSymbol)) {
            return false;
        }
        return !CanonicalAssetCatalog.isKnownCanonicalContract(networkId, assetContract);
    }
}
