package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Detects legacy zero-amount token transfer artifacts that should not create economic movement.
 */
public final class ZeroAmountTokenSupport {

    private ZeroAmountTokenSupport() {
    }

    public static boolean isZeroAmountOutboundOnly(OnChainRawTransactionView view) {
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return false;
        }

        boolean hasOutboundZeroTransfer = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            BigDecimal quantity = view.tokenTransferQuantity(transfer);

            if (walletAddress.equals(to) && !walletAddress.equals(from) && quantity != null && quantity.signum() > 0) {
                return false;
            }
            if (!walletAddress.equals(from) || walletAddress.equals(to)) {
                continue;
            }
            if (quantity == null || quantity.signum() != 0) {
                return false;
            }
            hasOutboundZeroTransfer = true;
        }

        if (!hasOutboundZeroTransfer) {
            return false;
        }
        if (hasPositiveDirectValue(view)) {
            return false;
        }

        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            if (walletAddress.equals(view.internalTransferFrom(transfer))
                    || walletAddress.equals(view.internalTransferTo(transfer))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPositiveDirectValue(OnChainRawTransactionView view) {
        BigInteger rawValue = view.rawValue();
        return rawValue != null && rawValue.signum() > 0
                && (view.walletAddress().equals(view.fromAddress()) || view.walletAddress().equals(view.toAddress()));
    }
}
