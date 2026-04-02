package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the authoritative movement legs from the raw transaction view before family/protocol rules are applied.
 */
@Component
public class MovementLegExtractor {

    private static final String EULER_BATCH_ROUTER = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public MovementLegExtractor(NativeAssetSymbolResolver nativeAssetSymbolResolver) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    public List<RawLeg> extract(OnChainRawTransactionView view) {
        List<RawLeg> legs = new ArrayList<>();
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return legs;
        }

        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String contract = view.tokenTransferContract(transfer);
            String symbol = view.tokenTransferSymbol(transfer);
            if (matchesWalletAccount(view, view.tokenTransferTo(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity));
            }
            if (matchesWalletAccount(view, view.tokenTransferFrom(transfer))) {
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

        if (!isDirectValueCoveredByInternalTransfer(view)) {
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

        if (walletAddress.equals(view.fromAddress())) {
            BigInteger gasUsed = view.gasUsed();
            BigInteger gasPrice = view.gasPrice();
            if (gasUsed != null && gasPrice != null && gasUsed.signum() > 0 && gasPrice.signum() > 0) {
                BigDecimal gasQuantity = new BigDecimal(gasUsed.multiply(gasPrice)).movePointLeft(18).negate();
                legs.add(RawLeg.fee(nativeAssetSymbolResolver.nativeSymbol(view.networkId()), gasQuantity));
            }
        }

        return WrappedNativeSupport.enrichLegs(view, nativeAssetSymbolResolver, legs);
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
                    && safeEquals(from, view.internalTransferFrom(transfer))
                    && safeEquals(to, view.internalTransferTo(transfer))) {
                return true;
            }
        }
        return false;
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
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return false;
        }
        return wallet.length() == 42
                && candidate.length() == 42
                && wallet.substring(0, 40).equals(candidate.substring(0, 40));
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}
