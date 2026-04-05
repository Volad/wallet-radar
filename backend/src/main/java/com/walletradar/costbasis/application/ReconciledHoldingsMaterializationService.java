package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.costbasis.domain.ReconciledHolding;
import com.walletradar.costbasis.domain.ReconciledHoldingRepository;
import com.walletradar.costbasis.domain.ReconciliationStatus;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a bounded holdings read model from replay state plus current on-chain balance evidence.
 */
@Service
@RequiredArgsConstructor
public class ReconciledHoldingsMaterializationService {

    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.000000000001");

    private final AssetPositionRepository assetPositionRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final ReconciledHoldingRepository reconciledHoldingRepository;

    public int materialize(Instant materializedAt) {
        Map<HoldingKey, AssetPosition> positionsByKey = positionsByKey(assetPositionRepository.findAll());
        List<ReconciledHolding> holdings = new ArrayList<>();
        for (OnChainBalance balance : onChainBalanceRepository.findAll()) {
            ReconciledHolding holding = toHolding(balance, positionsByKey, materializedAt);
            if (holding != null) {
                holdings.add(holding);
            }
        }
        reconciledHoldingRepository.deleteAll();
        if (!holdings.isEmpty()) {
            reconciledHoldingRepository.saveAll(holdings);
        }
        return holdings.size();
    }

    private Map<HoldingKey, AssetPosition> positionsByKey(List<AssetPosition> positions) {
        Map<HoldingKey, AssetPosition> positionsByKey = new LinkedHashMap<>();
        for (AssetPosition position : positions) {
            if (position == null
                    || position.getWalletAddress() == null
                    || position.getNetworkId() == null
                    || isBybitWallet(position.getWalletAddress())) {
                continue;
            }
            String accountingIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    position.getNetworkId(),
                    position.getAssetSymbol(),
                    position.getAssetContract()
            );
            if (accountingIdentity == null) {
                continue;
            }
            positionsByKey.put(new HoldingKey(
                    position.getWalletAddress(),
                    position.getNetworkId(),
                    accountingIdentity
            ), position);
        }
        return positionsByKey;
    }

    private ReconciledHolding toHolding(
            OnChainBalance balance,
            Map<HoldingKey, AssetPosition> positionsByKey,
            Instant materializedAt
    ) {
        if (balance == null
                || balance.getWalletAddress() == null
                || balance.getNetworkId() == null
                || balance.getQuantity() == null
                || isBybitWallet(balance.getWalletAddress())) {
            return null;
        }
        String accountingIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                balance.getNetworkId(),
                balance.getAssetSymbol(),
                balance.getAssetContract()
        );
        if (accountingIdentity == null) {
            return null;
        }

        AssetPosition position = positionsByKey.get(new HoldingKey(
                balance.getWalletAddress(),
                balance.getNetworkId(),
                accountingIdentity
        ));
        ReconciledHolding holding = new ReconciledHolding();
        holding.setId(balance.getWalletAddress() + ":" + balance.getNetworkId().name() + ":" + accountingIdentity);
        holding.setWalletAddress(balance.getWalletAddress());
        holding.setNetworkId(balance.getNetworkId());
        holding.setAssetSymbol(position != null ? position.getAssetSymbol() : balance.getAssetSymbol());
        holding.setAssetContract(position != null ? position.getAssetContract() : balance.getAssetContract());
        holding.setCurrentQuantity(balance.getQuantity());
        holding.setCurrentHolding(balance.getQuantity().signum() > 0);
        holding.setOnChainCapturedAt(balance.getCapturedAt());
        holding.setMaterializedAt(materializedAt);
        if (position != null) {
            BigDecimal derivedQuantity = zeroIfNull(position.getQuantity());
            BigDecimal quantityShortfall = zeroIfNull(position.getQuantityShortfall());
            BigDecimal basisBackedDerivedQuantity = nonNegative(derivedQuantity.subtract(quantityShortfall));
            BigDecimal currentCoveredQuantity = balance.getQuantity().min(basisBackedDerivedQuantity);
            BigDecimal currentUncoveredQuantity = nonNegative(balance.getQuantity().subtract(currentCoveredQuantity));
            holding.setDerivedQuantity(position.getQuantity());
            holding.setBasisBackedDerivedQuantity(basisBackedDerivedQuantity);
            holding.setCurrentCoveredQuantity(currentCoveredQuantity);
            holding.setCurrentUncoveredQuantity(currentUncoveredQuantity);
            holding.setCurrentCostBasisProvable(currentUncoveredQuantity.signum() == 0);
            holding.setPerWalletAvco(position.getPerWalletAvco());
            holding.setTotalCostBasisUsd(position.getTotalCostBasisUsd());
            holding.setTotalGasPaidUsd(position.getTotalGasPaidUsd());
            holding.setTotalRealisedPnlUsd(position.getTotalRealisedPnlUsd());
            holding.setQuantityShortfall(position.getQuantityShortfall());
            holding.setHasIncompleteHistory(position.getHasIncompleteHistory());
            holding.setHasUnresolvedFlags(position.getHasUnresolvedFlags());
            holding.setUnresolvedFlagCount(position.getUnresolvedFlagCount());
            holding.setLastEventTimestamp(position.getLastEventTimestamp());
            holding.setReconciliationStatus(resolveStatus(position, balance));
        } else {
            holding.setBasisBackedDerivedQuantity(BigDecimal.ZERO);
            holding.setCurrentCoveredQuantity(BigDecimal.ZERO);
            holding.setCurrentUncoveredQuantity(balance.getQuantity());
            holding.setCurrentCostBasisProvable(balance.getQuantity().signum() == 0);
            holding.setReconciliationStatus(ReconciliationStatus.NOT_APPLICABLE);
        }
        return holding;
    }

    private ReconciliationStatus resolveStatus(AssetPosition position, OnChainBalance balance) {
        if (position == null || balance == null) {
            return ReconciliationStatus.NOT_APPLICABLE;
        }
        if (position.getReconciliationStatus() != null) {
            return position.getReconciliationStatus();
        }
        BigDecimal derivedQuantity = position.getQuantity() == null ? BigDecimal.ZERO : position.getQuantity();
        BigDecimal onChainQuantity = balance.getQuantity() == null ? BigDecimal.ZERO : balance.getQuantity();
        return derivedQuantity.subtract(onChainQuantity).abs().compareTo(QUANTITY_TOLERANCE) <= 0
                ? ReconciliationStatus.MATCH
                : ReconciliationStatus.MISMATCH;
    }

    private boolean isBybitWallet(String walletAddress) {
        return walletAddress != null && walletAddress.startsWith("BYBIT:");
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private record HoldingKey(
            String walletAddress,
            NetworkId networkId,
            String accountingIdentity
    ) {
    }
}
