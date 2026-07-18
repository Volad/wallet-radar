package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.bson.Document;

import java.math.BigDecimal;

/**
 * B-ETH-01 / B-ETH-03: settlement sub-mode + realize-on-convert metadata stamped on the two legs of
 * a linked bridge pair at the OUTBOUND decision point (in the linking phase) and read back during
 * AVCO replay.
 *
 * <p>The metadata makes the two {@code BRIDGE_IN} sub-modes auditable (B-ETH-03) and lets the replay
 * legs decide, without re-deriving asset family, whether an asset-converting bridge must realize P&L
 * on the source and acquire the destination at its fair market value (B-ETH-01):</p>
 *
 * <ul>
 *   <li>{@link #SUB_MODE_ASSET_CONVERTING} — source family ≠ destination family. When
 *       {@code realizeOnConvert} is also set (non-peg source with a resolvable destination fair
 *       value) the replay DISPOSEs the source and ACQUIREs the destination at
 *       {@code destFairValueUsd}; otherwise the peg-neutral fast path is kept byte-identical.</li>
 *   <li>{@link #SUB_MODE_SAME_ASSET} — same-asset continuity carry (plain move-basis). Metadata is
 *       observability-only; no behavior change.</li>
 * </ul>
 *
 * <p>The destination fair value is persisted as a canonical plain string so it round-trips through
 * BSON without precision loss regardless of the numeric encoding chosen by the driver.</p>
 */
public final class BridgeSettlementMetadataSupport {

    /** Metadata sub-document key on {@link NormalizedTransaction#getMetadata()}. */
    public static final String METADATA_KEY = "bridgeSettlement";
    /** Sub-mode reason code key inside the {@link #METADATA_KEY} sub-document. */
    public static final String SUB_MODE_KEY = "subMode";
    /** Realize-on-convert flag key inside the {@link #METADATA_KEY} sub-document. */
    public static final String REALIZE_ON_CONVERT_KEY = "realizeOnConvert";
    /** Destination fair-market-value (USD, plain string) key inside the {@link #METADATA_KEY} sub-document. */
    public static final String DEST_FAIR_VALUE_USD_KEY = "destFairValueUsd";

    /** Asset-converting settlement sub-mode reason code (source family ≠ destination family). */
    public static final String SUB_MODE_ASSET_CONVERTING = "BRIDGE_SETTLEMENT_ASSET_CONVERTING";
    /** Same-asset continuity sub-mode reason code (plain move-basis carry). */
    public static final String SUB_MODE_SAME_ASSET = "BRIDGE_CONTINUITY_SAME_ASSET";

    private BridgeSettlementMetadataSupport() {
    }

    /**
     * Stamps the asset-converting settlement sub-mode, the realize-on-convert decision, and the
     * destination fair value (when realizing) on {@code transaction}. Returns {@code true} when the
     * stored metadata changed.
     */
    public static boolean stampAssetConvertingSettlement(
            NormalizedTransaction transaction,
            boolean realizeOnConvert,
            BigDecimal destFairValueUsd
    ) {
        if (transaction == null) {
            return false;
        }
        String destFairValue = realizeOnConvert && destFairValueUsd != null && destFairValueUsd.signum() > 0
                ? destFairValueUsd.stripTrailingZeros().toPlainString()
                : null;
        boolean effectiveRealize = realizeOnConvert && destFairValue != null;
        Document settlement = new Document(SUB_MODE_KEY, SUB_MODE_ASSET_CONVERTING)
                .append(REALIZE_ON_CONVERT_KEY, effectiveRealize);
        if (destFairValue != null) {
            settlement.append(DEST_FAIR_VALUE_USD_KEY, destFairValue);
        }
        return applySettlementMetadata(transaction, settlement);
    }

    /**
     * Stamps the same-asset continuity sub-mode (B-ETH-03 observability). No replay behavior change.
     * Returns {@code true} when the stored metadata changed.
     */
    public static boolean stampSameAssetContinuity(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        Document settlement = new Document(SUB_MODE_KEY, SUB_MODE_SAME_ASSET)
                .append(REALIZE_ON_CONVERT_KEY, Boolean.FALSE);
        return applySettlementMetadata(transaction, settlement);
    }

    /** True when the leg is stamped asset-converting (either realize or peg fast path). */
    public static boolean isAssetConvertingSettlement(NormalizedTransaction transaction) {
        return SUB_MODE_ASSET_CONVERTING.equals(subMode(transaction));
    }

    /**
     * True when the leg must realize P&L on convert: asset-converting, non-peg source, and a
     * resolvable positive destination fair value stamped at link time.
     */
    public static boolean isRealizeOnConvert(NormalizedTransaction transaction) {
        Document settlement = settlementDocument(transaction);
        if (settlement == null || !Boolean.TRUE.equals(settlement.getBoolean(REALIZE_ON_CONVERT_KEY, false))) {
            return false;
        }
        BigDecimal destFairValue = destFairValueUsd(transaction);
        return destFairValue != null && destFairValue.signum() > 0;
    }

    /** The stamped settlement sub-mode reason code, or {@code null} when absent. */
    public static String subMode(NormalizedTransaction transaction) {
        Document settlement = settlementDocument(transaction);
        return settlement == null ? null : settlement.getString(SUB_MODE_KEY);
    }

    /** The stamped destination fair market value (USD), or {@code null} when absent/unparseable. */
    public static BigDecimal destFairValueUsd(NormalizedTransaction transaction) {
        Document settlement = settlementDocument(transaction);
        if (settlement == null) {
            return null;
        }
        Object value = settlement.get(DEST_FAIR_VALUE_USD_KEY);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof org.bson.types.Decimal128 decimal128) {
                return decimal128.bigDecimalValue();
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean applySettlementMetadata(NormalizedTransaction transaction, Document settlement) {
        Document metadata = transaction.getMetadata();
        Document existing = metadata == null ? null : metadata.get(METADATA_KEY, Document.class);
        if (settlement.equals(existing)) {
            return false;
        }
        if (metadata == null) {
            metadata = new Document();
        }
        metadata.put(METADATA_KEY, settlement);
        transaction.setMetadata(metadata);
        return true;
    }

    private static Document settlementDocument(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getMetadata() == null) {
            return null;
        }
        return transaction.getMetadata().get(METADATA_KEY, Document.class);
    }
}
