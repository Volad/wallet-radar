package com.walletradar.costbasis.engine;

import com.walletradar.domain.EconomicEventType;

import java.math.BigDecimal;

/**
 * Classifies event types for AVCO formula per 03-accounting and 01-domain.
 * BUY = inflow that updates AVCO; SELL = outflow with realised P&amp;L; OUTFLOW = outflow without P&amp;L.
 */
public final class AvcoEventTypeHelper {

    private AvcoEventTypeHelper() {
    }

    /** Events that increase quantity and update AVCO (price × qty in weighted average). */
    public static boolean isBuyType(EconomicEventType type) {
        return type == EconomicEventType.SWAP_BUY
                || type == EconomicEventType.BORROW
                || type == EconomicEventType.STAKE_WITHDRAWAL
                || type == EconomicEventType.LEND_WITHDRAWAL
                || type == EconomicEventType.EXTERNAL_INBOUND
                || type == EconomicEventType.MANUAL_COMPENSATING
                || type == EconomicEventType.INTERNAL_TRANSFER; // dest: positive delta, use priceUsd as source AVCO
    }

    /** Events that decrease quantity and generate realised P&amp;L (INV-07). */
    public static boolean isSellType(EconomicEventType type) {
        return type == EconomicEventType.SWAP_SELL
                || type == EconomicEventType.LP_EXIT;
    }

    /** Outflow that decreases quantity but does not generate realised P&amp;L (AVCO unchanged). */
    public static boolean isOutflowNoPnl(EconomicEventType type) {
        return type == EconomicEventType.STAKE_DEPOSIT
                || type == EconomicEventType.LEND_DEPOSIT
                || type == EconomicEventType.REPAY
                || type == EconomicEventType.EXTERNAL_TRANSFER_OUT
                || (type == EconomicEventType.INTERNAL_TRANSFER); // source: negative delta
    }

    /** Positive quantity delta (inflow) for this event in AVCO. */
    public static boolean isInflow(EconomicEventType type, BigDecimal quantityDelta) {
        if (quantityDelta == null) return false;
        if (type == EconomicEventType.INTERNAL_TRANSFER) {
            return quantityDelta.compareTo(BigDecimal.ZERO) > 0;
        }
        if (type == EconomicEventType.MANUAL_COMPENSATING) {
            return quantityDelta.compareTo(BigDecimal.ZERO) > 0;
        }
        return isBuyType(type) && quantityDelta.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Negative quantity delta (outflow). */
    public static boolean isOutflow(EconomicEventType type, BigDecimal quantityDelta) {
        if (quantityDelta == null) return false;
        return quantityDelta.compareTo(BigDecimal.ZERO) < 0;
    }

    /** First event is SELL or transfer-out → hasIncompleteHistory (INV-09). */
    public static boolean isFirstEventIncomplete(EconomicEventType type, BigDecimal quantityDelta) {
        return isSellType(type) || (isOutflowNoPnl(type) && quantityDelta != null && quantityDelta.compareTo(BigDecimal.ZERO) < 0);
    }
}
