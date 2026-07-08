package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.init;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies INIT Capital money-market transactions on Mantle Network.
 *
 * <p>INIT Capital's {@code PositionManager.execute(tuple _params)} (methodId {@code 0x247d4981})
 * bundles multiple DeFi actions in a single call, such as:
 * <ul>
 *   <li><b>Pattern A — deposit collateral + borrow</b>: collateral asset flows OUT to collateral
 *       pool AND borrowed asset flows IN from borrow pool → classified as {@code BORROW}</li>
 *   <li><b>Pattern B — pure additional borrow</b>: borrowed asset flows IN from borrow pool only
 *       (no collateral outflow) → classified as {@code BORROW}</li>
 *   <li><b>Pattern C — repay + withdraw collateral</b>: repayment flows OUT to PositionManager
 *       AND collateral returns IN from collateral pool → classified as {@code LENDING_WITHDRAW}</li>
 * </ul>
 *
 * <p>Detection (OR):
 * <ol>
 *   <li>The called {@code methodId} matches INIT Capital's execute selector ({@code 0x247d4981}), OR</li>
 *   <li>The primary registry match names the protocol "INIT Capital".</li>
 * </ol>
 */
@Component
public class InitCapitalSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String EXECUTE_METHOD_ID = "0x247d4981";
    private static final String PROTOCOL_NAME = "INIT Capital";
    private static final String PROTOCOL_KEY = "init_capital";

    /**
     * Known INIT Capital contract addresses on Mantle (lowercase).
     */
    private static final String POSITION_MANAGER = "0xf82cbcab75c1138a8f1f20179613e7c0c8337346";
    private static final Set<String> COLLATERAL_POOLS = Set.of(
            "0x6cc1039746803bc325ec6eb7262def3a672ae243"   // cmETH collateral pool
    );
    private static final Set<String> BORROW_POOLS = Set.of(
            "0x00a55649e597d463fd212fbe48a3b40f0e227d06"   // USDC borrow pool
    );
    private static final Set<String> ALL_KNOWN_POOLS;

    static {
        ALL_KNOWN_POOLS = new java.util.HashSet<>(COLLATERAL_POOLS);
        ALL_KNOWN_POOLS.addAll(BORROW_POOLS);
        ALL_KNOWN_POOLS.add(POSITION_MANAGER);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 145;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (!isInitCapitalTransaction(context)) {
            return List.of();
        }

        Optional<NormalizedTransactionType> pattern = detectPattern(context);
        if (pattern.isEmpty()) {
            return List.of();
        }

        NormalizedTransactionType type = pattern.get();
        String semanticType = type == NormalizedTransactionType.BORROW ? "borrow" : "repay_withdraw";
        return List.of(new ProtocolSemanticHint(
                PROTOCOL_KEY,
                semanticType,
                PROTOCOL_NAME,
                "V1",
                null,
                type,
                ConfidenceLevel.HIGH
        ));
    }

    private boolean isInitCapitalTransaction(ProtocolSemanticContext context) {
        if (EXECUTE_METHOD_ID.equals(context.view().methodId())) {
            return true;
        }
        Optional<ProtocolMatch> primary = context.protocolDiscovery().primaryMatch();
        return primary.isPresent() && PROTOCOL_NAME.equals(primary.get().protocolName());
    }

    /**
     * Identifies which INIT Capital action pattern the transaction represents based on
     * token transfer directions.
     */
    private Optional<NormalizedTransactionType> detectPattern(ProtocolSemanticContext context) {
        String wallet = context.view().walletAddress();
        if (wallet == null) {
            return Optional.empty();
        }

        boolean collateralOut = false;    // wallet → collateral pool
        boolean borrowIn = false;          // borrow pool → wallet
        boolean repayOut = false;          // wallet → PositionManager
        boolean collateralIn = false;      // collateral pool → wallet

        for (Document transfer : context.view().explorerTokenTransfers()) {
            String from = normalize(context.view().tokenTransferFrom(transfer));
            String to = normalize(context.view().tokenTransferTo(transfer));
            if (from == null || to == null) {
                continue;
            }

            // wallet → collateral pool (deposit collateral)
            if (wallet.equals(from) && COLLATERAL_POOLS.contains(to)) {
                collateralOut = true;
            }
            // borrow pool → wallet (borrow)
            if (BORROW_POOLS.contains(from) && wallet.equals(to)) {
                borrowIn = true;
            }
            // wallet → PositionManager (repayment)
            if (wallet.equals(from) && POSITION_MANAGER.equals(to)) {
                repayOut = true;
            }
            // collateral pool → wallet (collateral returned)
            if (COLLATERAL_POOLS.contains(from) && wallet.equals(to)) {
                collateralIn = true;
            }
        }

        // Pattern C: repay + withdraw collateral
        if (repayOut && collateralIn) {
            return Optional.of(NormalizedTransactionType.LENDING_WITHDRAW);
        }
        // Pattern A: deposit collateral + borrow
        if (collateralOut && borrowIn) {
            return Optional.of(NormalizedTransactionType.BORROW);
        }
        // Pattern B: pure borrow (no collateral deposit in this tx)
        if (borrowIn) {
            return Optional.of(NormalizedTransactionType.BORROW);
        }

        return Optional.empty();
    }

    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
