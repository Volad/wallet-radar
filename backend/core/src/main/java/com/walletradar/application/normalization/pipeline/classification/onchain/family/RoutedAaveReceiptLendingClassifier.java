package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.lending.application.LendingAssetSymbolSupport;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.session.application.TrackedWalletLookupService;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Network-agnostic Aave V3 supply/withdraw classifier for transactions that carry the Aave receipt
 * (aToken) shape but do <em>not</em> go directly to a registered Aave pool — e.g. an ERC-20 supply
 * or withdraw routed through a smart-wallet, relayer, paymaster, or aggregator router. Such
 * transactions bypass {@link AaveReceiptShapeClassifier} (which requires the Aave pool multicall to
 * {@code tx.to}) and {@link LendingRegistryClassifier} (which keys on the pool address), and would
 * otherwise fall through to the heuristic fallback and be mislabelled {@code LP_EXIT} (withdraw with
 * a tiny rebasing aToken dust leg) or {@code REWARD_CLAIM} (a lone inbound aToken supply).
 *
 * <p>Detection is by aToken symbol grammar ({@link LendingAssetSymbolSupport#isAaveSupplyReceiptSymbol})
 * plus <em>net</em> flow direction (tolerant of aToken rebasing/interest dust legs), and the
 * accounting family ({@link AccountingAssetFamilySupport#continuityIdentity}) shared between the
 * aToken receipt and its underlying — the same identity the AVCO replay uses to carry basis through
 * the aToken ↔ underlying lifecycle. No hardcoded contracts, wallets, networks, or transaction
 * hashes: the fix targets the class of bug, not a single market.</p>
 *
 * <p>Regression anchors (verify after renormalization): zkSync Aave ERC-20 withdraw
 * {@code 0x4d1a74bd0fe494fad72aa1af837b005adba1c00537561aa288df504ae0b30514} (was {@code LP_EXIT})
 * and the paired supply {@code 0x7e5aac2a7c9558a811f4f998b9cbc3f60e7980dfe40b9fc251470126856908be}
 * (was {@code REWARD_CLAIM}). Already-correct direct-to-pool Aave markets (BASE/MANTLE) route to
 * {@code tx.to}=registered pool and are deliberately skipped here so their behavior is unchanged.</p>
 */
@Component
public class RoutedAaveReceiptLendingClassifier implements OnChainFamilyClassifier {

    private static final String PROTOCOL_NAME = "Aave";
    private static final String PROTOCOL_VERSION = "V3";

    private final ProtocolRegistryService protocolRegistryService;
    private final TrackedWalletLookupService trackedWalletLookupService;

    public RoutedAaveReceiptLendingClassifier(
            ProtocolRegistryService protocolRegistryService,
            TrackedWalletLookupService trackedWalletLookupService
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.trackedWalletLookupService = trackedWalletLookupService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        // After AaveReceiptShapeClassifier (+130, pool multicall) and ZkSyncAaveGatewayClassifier
        // (+140, gateway selectors) so their audited, more specific paths keep priority; this
        // classifier only fires for the routed/relayed ERC-20 residue that those two do not cover.
        return Ordered.HIGHEST_PRECEDENCE + 145;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (context == null || context.view() == null || context.movementLegs() == null) {
            return Optional.empty();
        }
        List<RawLeg> legs = context.movementLegs();
        if (legs.isEmpty()) {
            return Optional.empty();
        }
        // Defer to the registry/shape path for direct-to-pool Aave supply/withdraw (BASE/MANTLE and
        // any other market whose pool address is registered) so their behavior is unchanged.
        if (isRegisteredAavePool(context.view())) {
            return Optional.empty();
        }
        // Borrow/repay carry variable/stable debt markers and belong to the gateway/registry path.
        if (hasDebtMarkerLeg(legs)) {
            return Optional.empty();
        }

        BigDecimal netReceipt = BigDecimal.ZERO;
        String receiptFamily = null;
        boolean hasNonReceiptEconomicLeg = false;
        for (RawLeg leg : legs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (LendingAssetSymbolSupport.isAaveSupplyReceiptSymbol(leg.assetSymbol())) {
                netReceipt = netReceipt.add(leg.quantityDelta());
                if (receiptFamily == null) {
                    receiptFamily = continuityIdentity(leg);
                }
            } else {
                hasNonReceiptEconomicLeg = true;
            }
        }
        if (receiptFamily == null || netReceipt.signum() == 0) {
            return Optional.empty();
        }

        // WITHDRAW: net aToken outbound + underlying (same family) inbound. Accrued interest surfaces
        // as an excess inbound quantity, which OnChainClassificationSupport.toFlows materializes as a
        // zero-cost BUY (income), matching the existing lending withdraw accounting convention.
        if (netReceipt.signum() < 0
                && hasNonReceiptFamilyLeg(legs, receiptFamily, /* inbound */ true)) {
            return decision(context, NormalizedTransactionType.LENDING_WITHDRAW);
        }

        // DEPOSIT (paired): net aToken inbound + underlying (same family) outbound.
        if (netReceipt.signum() > 0
                && hasNonReceiptFamilyLeg(legs, receiptFamily, /* inbound */ false)) {
            return decision(context, NormalizedTransactionType.LENDING_DEPOSIT);
        }

        // DEPOSIT (degenerate): a lone inbound aToken with no visible underlying outbound (the
        // supplied principal moved via a relayer/paymaster path). Classify as LENDING_DEPOSIT so the
        // received aToken acquires market basis in both lanes via the replay spot fallback, instead
        // of a zero-cost REWARD_CLAIM. Skip when the aToken was sent from another tracked wallet so
        // an inter-wallet aToken transfer keeps its continuity carry via EXTERNAL_TRANSFER.
        if (netReceipt.signum() > 0
                && !hasNonReceiptEconomicLeg
                && loneInboundReceiptFromUntrackedSource(context.view())) {
            return decision(context, NormalizedTransactionType.LENDING_DEPOSIT);
        }

        return Optional.empty();
    }

    private Optional<ClassificationDecision> decision(
            OnChainClassificationContext context,
            NormalizedTransactionType type
    ) {
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(context.view(), context.movementLegs(), type),
                List.of(),
                PROTOCOL_NAME,
                PROTOCOL_VERSION
        ));
    }

    private boolean isRegisteredAavePool(OnChainRawTransactionView view) {
        return protocolRegistryService.lookup(view.networkId(), view.toAddress())
                .filter(RoutedAaveReceiptLendingClassifier::isAavePool)
                .isPresent();
    }

    private static boolean isAavePool(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.LENDING
                && entry.role() == ProtocolRegistryRole.POOL
                && entry.protocolName() != null
                && entry.protocolName().trim().equalsIgnoreCase(PROTOCOL_NAME);
    }

    private static boolean hasDebtMarkerLeg(List<RawLeg> legs) {
        return legs.stream()
                .filter(leg -> leg != null && !leg.fee())
                .anyMatch(leg -> {
                    String symbol = leg.assetSymbol() == null
                            ? ""
                            : leg.assetSymbol().trim().toUpperCase(Locale.ROOT);
                    return symbol.startsWith("VARIABLEDEBT") || symbol.startsWith("STABLEDEBT");
                });
    }

    private static boolean hasNonReceiptFamilyLeg(List<RawLeg> legs, String receiptFamily, boolean inbound) {
        return legs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null)
                .filter(leg -> inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                .filter(leg -> !LendingAssetSymbolSupport.isAaveSupplyReceiptSymbol(leg.assetSymbol()))
                .anyMatch(leg -> receiptFamily.equals(continuityIdentity(leg)));
    }

    private boolean loneInboundReceiptFromUntrackedSource(OnChainRawTransactionView view) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        boolean sawInboundReceipt = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!LendingAssetSymbolSupport.isAaveSupplyReceiptSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            String to = OnChainRawTransactionView.normalizeAddress(view.tokenTransferTo(transfer));
            if (wallet == null || !wallet.equals(to)) {
                continue;
            }
            sawInboundReceipt = true;
            String from = OnChainRawTransactionView.normalizeAddress(view.tokenTransferFrom(transfer));
            if (from != null && !from.equals(wallet) && trackedWalletLookupService.contains(from)) {
                return false;
            }
        }
        return sawInboundReceipt;
    }

    private static String continuityIdentity(RawLeg leg) {
        return AccountingAssetFamilySupport.continuityIdentity(leg.assetSymbol(), leg.assetContract());
    }
}
