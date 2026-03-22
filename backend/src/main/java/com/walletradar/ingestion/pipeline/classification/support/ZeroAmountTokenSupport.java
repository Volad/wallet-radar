package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

/**
 * Detects legacy zero-amount token transfer artifacts that should not create economic movement.
 */
public final class ZeroAmountTokenSupport {

    private static final Set<String> KNOWN_NON_ECONOMIC_FINGERPRINTS = Set.of(
            "ARBITRUM|0x0cf79e0a|0x2ea84921448af2a15d4bc442fd7fb09dfdbbac6d",
            "ARBITRUM|0x0cf79e0a|0xf03bd4b57fdf6db5f8385b57c9c9fad906c13021",
            "ARBITRUM|0x0cf79e0a|0x40f431d9668cfd7d73654646175d2f66e1923e15",
            "ARBITRUM|0x0cf79e0a|0x9dc94b8cc0c884aa9fa7cb99b047c1b2b0108dde",
            "AVALANCHE|0x12514bba|0x2ea83386d546fe4c4dc4d06a0387e9230c7eac6d",
            "AVALANCHE|0x12514bba|0x1a871b0dc5cffa960c85bef30a499fc1a16e693f",
            "AVALANCHE|0x12514bba|0xf03be42c1d0f294d0583d0bb7f5f422a4d353021",
            "AVALANCHE|0xa9059cbb|0xf03b7d8fa0240e466100feee52869c8ecc203021",
            "AVALANCHE|0xa9059cbb|0x2ea823deb37b9c33737397a6d37d37d327650c6d",
            "AVALANCHE|0xa9059cbb|0x1a872b33479b10f57d308104004a4d5f57bf693f"
    );

    private ZeroAmountTokenSupport() {
    }

    public static boolean isKnownNonEconomicFamily(OnChainRawTransactionView view) {
        if (view == null || view.networkId() == null) {
            return false;
        }
        String methodId = view.methodId();
        String to = view.toAddress();
        if (methodId == null || methodId.isBlank() || to == null || to.isBlank()) {
            return false;
        }
        return KNOWN_NON_ECONOMIC_FINGERPRINTS.contains(view.networkId().name() + "|" + methodId + "|" + to);
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
