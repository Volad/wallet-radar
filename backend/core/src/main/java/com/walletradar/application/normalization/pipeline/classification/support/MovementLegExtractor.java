package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.config.NativeSettlementRecoveryProperties;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the authoritative movement legs from the raw transaction view before family/protocol rules are applied.
 */
@Component
public class MovementLegExtractor {

    private static final Set<String> EULER_BATCH_ROUTERS = Set.of(
            "0xddcbe30a761edd2e19bba930a977475265f36fa1",
            "0x7bdbd0a7114aa42ca957f292145f6a931a345583"
    );
    private static final String ZKSYNC_SYSTEM_FEE_SINK = "0x0000000000000000000000000000000000008001";

    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;
    private final NativeSettlementRecoveryProperties nativeSettlementRecoveryProperties;

    /**
     * Convenience constructor used by tests and manual wiring; native-settlement recovery
     * (ADR-044 D2) defaults off.
     */
    public MovementLegExtractor(NativeAssetSymbolResolver nativeAssetSymbolResolver) {
        this(nativeAssetSymbolResolver, new NativeSettlementRecoveryProperties());
    }

    @Autowired
    public MovementLegExtractor(
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            NativeSettlementRecoveryProperties nativeSettlementRecoveryProperties
    ) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
        this.nativeSettlementRecoveryProperties = nativeSettlementRecoveryProperties;
    }

    public List<RawLeg> extract(OnChainRawTransactionView view) {
        List<RawLeg> legs = new ArrayList<>();
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return legs;
        }

        for (Document transfer : view.explorerTokenTransfers()) {
            if (isNativeAliasFeeLifecycleTransfer(view, transfer)) {
                continue;
            }
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String contract = view.tokenTransferContract(transfer);
            String symbol = view.tokenTransferSymbol(transfer);
            if (matchesWalletAccount(view, view.tokenTransferTo(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity));
            }
            // Only generate a DEBIT when the primary (main) wallet is the direct sender.
            // Euler-controlled sub-account outflows must not produce a DEBIT against the main
            // wallet's basis pool because:
            //   - sub-account → main wallet is an intra-user move (CREDIT above is the only leg)
            //   - sub-account → address(0) burns are loop-internal vault exits that the main
            //     wallet's pool did not fund directly (the leveraged portion lives on the sub-account)
            if (matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity.negate()));
            }
        }

        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
            if (matchesWalletAccount(view, view.internalTransferTo(transfer))) {
                legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
            }
            if (matchesWalletAccount(view, view.internalTransferFrom(transfer))) {
                legs.add(RawLeg.nativeAsset(nativeSymbol, quantity.negate()));
            }
        }

        if (!isDirectValueCoveredByInternalTransfer(view)
                && !isDirectValueCoveredByNativeAliasTransfer(view)) {
            BigInteger rawValue = view.rawValue();
            if (rawValue != null && rawValue.signum() > 0) {
                BigDecimal quantity = new BigDecimal(rawValue).movePointLeft(18);
                String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
                if (walletAddress.equals(view.toAddress())) {
                    legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
                }
                if (walletAddress.equals(view.fromAddress())) {
                    legs.add(RawLeg.nativeAsset(nativeSymbol, quantity.negate()));
                }
            }
        }

        legs = OneInchNativeSettlementSupport.enrichLegs(view, nativeAssetSymbolResolver, legs);
        legs = ParaSwapNativeSettlementSupport.enrichLegs(view, nativeAssetSymbolResolver, legs);

        if (walletAddress.equals(view.fromAddress())) {
            BigInteger gasFeeValue = gasFeeValue(view);
            if (gasFeeValue != null && gasFeeValue.signum() > 0) {
                BigDecimal gasQuantity = new BigDecimal(gasFeeValue).movePointLeft(18).negate();
                legs.add(RawLeg.fee(nativeAssetSymbolResolver.nativeSymbol(view.networkId()), gasQuantity));
            }
        }

        legs = WrappedNativeSupport.enrichLegs(view, nativeAssetSymbolResolver, legs);
        legs = LpNativeExitLegEnricher.enrichLegs(view, nativeAssetSymbolResolver, legs);
        // ADR-044 D2: router-agnostic native-settlement recovery runs last, after the existing
        // selector-specific enrichers, and only fires when the hasInboundNative guard is false.
        legs = NativeSettlementRecovery.enrichLegs(
                view, nativeAssetSymbolResolver, legs, nativeSettlementRecoveryProperties);
        return legs;
    }

    private boolean isDirectValueCoveredByInternalTransfer(OnChainRawTransactionView view) {
        BigInteger rawValue = view.rawValue();
        if (rawValue == null || rawValue.signum() == 0) {
            return false;
        }
        String from = view.fromAddress();
        String to = view.toAddress();
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null) {
                continue;
            }
            BigInteger transferValue = quantity.movePointRight(18).toBigInteger();
            if (rawValue.equals(transferValue)
                    && sameAddress(from, view.internalTransferFrom(transfer))
                    && sameAddress(to, view.internalTransferTo(transfer))) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectValueCoveredByNativeAliasTransfer(OnChainRawTransactionView view) {
        BigInteger rawValue = view.rawValue();
        if (rawValue == null || rawValue.signum() == 0) {
            return false;
        }
        String from = view.fromAddress();
        String to = view.toAddress();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!nativeAssetSymbolResolver.isNativeAliasContract(
                    view.networkId(),
                    view.tokenTransferContract(transfer)
            )) {
                continue;
            }
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null) {
                continue;
            }
            BigInteger transferValue = quantity.movePointRight(18).toBigInteger();
            if (rawValue.equals(transferValue)
                    && sameAddress(from, view.tokenTransferFrom(transfer))
                    && sameAddress(to, view.tokenTransferTo(transfer))) {
                return true;
            }
        }
        return false;
    }

    private boolean isNativeAliasFeeLifecycleTransfer(OnChainRawTransactionView view, Document transfer) {
        if (view.networkId() != NetworkId.ZKSYNC || !view.isFeePayer()) {
            return false;
        }
        if (!nativeAssetSymbolResolver.isNativeAliasContract(view.networkId(), view.tokenTransferContract(transfer))) {
            return false;
        }
        String from = view.tokenTransferFrom(transfer);
        String to = view.tokenTransferTo(transfer);
        if (!matchesWalletAccount(view, from) && !matchesWalletAccount(view, to)) {
            return false;
        }
        if (!sameAddress(ZKSYNC_SYSTEM_FEE_SINK, from) && !sameAddress(ZKSYNC_SYSTEM_FEE_SINK, to)) {
            return false;
        }
        BigDecimal quantity = view.tokenTransferQuantity(transfer);
        return quantity != null && quantity.signum() > 0;
    }

    private BigInteger gasFeeValue(OnChainRawTransactionView view) {
        BigInteger gasUsed = view.gasUsed();
        BigInteger gasPrice = view.gasPrice();
        if (gasUsed == null || gasPrice == null || gasUsed.signum() <= 0 || gasPrice.signum() <= 0) {
            return null;
        }
        return gasUsed.multiply(gasPrice);
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        return wallet != null && wallet.equals(normalizedAddress);
    }

    private boolean matchesWalletAccount(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        if (wallet == null || normalizedAddress == null) {
            return false;
        }
        if (wallet.equals(normalizedAddress)) {
            return true;
        }
        return isEulerControlledSubaccount(view, wallet, normalizedAddress);
    }

    private boolean isEulerControlledSubaccount(OnChainRawTransactionView view, String wallet, String candidate) {
        if (!"0xc16ae7a4".equals(view.methodId())) {
            return false;
        }
        String interacted = view.toAddress();
        if (interacted == null || !EULER_BATCH_ROUTERS.contains(interacted)) {
            return false;
        }
        return wallet.length() == 42
                && candidate.length() == 42
                && wallet.substring(0, 40).equals(candidate.substring(0, 40));
    }

    private boolean sameAddress(String left, String right) {
        String normalizedLeft = OnChainRawTransactionView.normalizeAddress(left);
        String normalizedRight = OnChainRawTransactionView.normalizeAddress(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }
}
