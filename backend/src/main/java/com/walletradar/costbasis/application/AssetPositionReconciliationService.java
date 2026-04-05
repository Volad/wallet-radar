package com.walletradar.costbasis.application;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetPosition;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.costbasis.domain.ReconciliationStatus;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches replayed asset positions with current-balance reconciliation fields.
 */
@Service
@RequiredArgsConstructor
public class AssetPositionReconciliationService {

    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.000000000001");

    private final AssetPositionRepository assetPositionRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;

    public int reconcile(Instant reconciledAt) {
        List<AssetPosition> positions = assetPositionRepository.findAll();
        if (positions.isEmpty()) {
            return 0;
        }

        Map<BalanceKey, AggregatedBalance> balancesByKey = aggregateBalances(onChainBalanceRepository.findAll());
        for (AssetPosition position : positions) {
            reconcilePosition(position, balancesByKey, reconciledAt);
        }
        assetPositionRepository.saveAll(positions);
        return positions.size();
    }

    private void reconcilePosition(
            AssetPosition position,
            Map<BalanceKey, AggregatedBalance> balancesByKey,
            Instant reconciledAt
    ) {
        if (position == null) {
            return;
        }
        if (isBybitWallet(position.getWalletAddress())) {
            position.setOnChainQuantity(null);
            position.setOnChainCapturedAt(null);
            position.setReconciliationStatus(ReconciliationStatus.NOT_APPLICABLE);
            position.setLastCalculatedAt(reconciledAt);
            return;
        }
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                position.getNetworkId(),
                position.getAssetSymbol(),
                position.getAssetContract()
        );
        if (identity == null || position.getWalletAddress() == null || position.getNetworkId() == null) {
            position.setOnChainQuantity(null);
            position.setOnChainCapturedAt(null);
            position.setReconciliationStatus(ReconciliationStatus.NOT_APPLICABLE);
            position.setLastCalculatedAt(reconciledAt);
            return;
        }

        AggregatedBalance aggregatedBalance = balancesByKey.get(new BalanceKey(
                position.getWalletAddress(),
                position.getNetworkId(),
                identity
        ));
        if (aggregatedBalance == null) {
            position.setOnChainQuantity(null);
            position.setOnChainCapturedAt(null);
            position.setReconciliationStatus(ReconciliationStatus.NOT_APPLICABLE);
            position.setLastCalculatedAt(reconciledAt);
            return;
        }

        BigDecimal onChainQuantity = aggregatedBalance.quantity();
        position.setOnChainQuantity(onChainQuantity);
        position.setOnChainCapturedAt(aggregatedBalance.capturedAt());
        position.setReconciliationStatus(quantitiesMatch(position.getQuantity(), onChainQuantity)
                ? ReconciliationStatus.MATCH
                : ReconciliationStatus.MISMATCH);
        position.setLastCalculatedAt(reconciledAt);
    }

    private Map<BalanceKey, AggregatedBalance> aggregateBalances(List<OnChainBalance> balances) {
        Map<BalanceKey, AggregatedBalance> balancesByKey = new LinkedHashMap<>();
        for (OnChainBalance balance : balances) {
            if (balance == null
                    || balance.getWalletAddress() == null
                    || balance.getNetworkId() == null
                    || balance.getQuantity() == null) {
                continue;
            }
            String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            if (identity == null) {
                continue;
            }
            BalanceKey key = new BalanceKey(balance.getWalletAddress(), balance.getNetworkId(), identity);
            AggregatedBalance existing = balancesByKey.get(key);
            Instant capturedAt = balance.getCapturedAt();
            if (existing == null || isNewer(capturedAt, existing.capturedAt())) {
                balancesByKey.put(key, new AggregatedBalance(balance.getQuantity(), capturedAt));
                continue;
            }
            if (sameInstant(capturedAt, existing.capturedAt())) {
                balancesByKey.put(key, new AggregatedBalance(
                        existing.quantity().add(balance.getQuantity()),
                        existing.capturedAt()
                ));
            }
        }
        return balancesByKey;
    }

    private boolean quantitiesMatch(BigDecimal derivedQuantity, BigDecimal onChainQuantity) {
        BigDecimal left = derivedQuantity == null ? BigDecimal.ZERO : derivedQuantity;
        BigDecimal right = onChainQuantity == null ? BigDecimal.ZERO : onChainQuantity;
        return left.subtract(right).abs().compareTo(QUANTITY_TOLERANCE) <= 0;
    }

    private boolean isNewer(Instant candidate, Instant current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return candidate.isAfter(current);
    }

    private boolean sameInstant(Instant left, Instant right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    private boolean isBybitWallet(String walletAddress) {
        return walletAddress != null && walletAddress.startsWith("BYBIT:");
    }

    private record BalanceKey(
            String walletAddress,
            NetworkId networkId,
            String accountingIdentity
    ) {
    }

    private record AggregatedBalance(
            BigDecimal quantity,
            Instant capturedAt
    ) {
    }
}
