package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.application.balance.OnChainBalanceProvider.ProviderBalance;
import com.walletradar.application.costbasis.application.balance.ProtocolLockedBalanceProvider;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Contributes Jupiter Lend (Solana) locked SOL collateral into {@code on_chain_balances} (WS-3
 * single-authority). Reads the freshest persisted {@link LendingLivePositionSnapshot} for the wallet
 * — never performs network I/O on the balance-refresh path (snapshot-first) — and surfaces the
 * collateral legs so the dashboard SOL quantity and move-basis pick up the locked ~5.42 SOL exactly
 * like an Aave aToken (Aave-parity). Native SOL collateral is booked using the descriptor
 * {@code native-identity} sentinel (resolved via {@link NetworkNativeAssets#nativeIdentity}) so it
 * merges with the wallet's free SOL rather than appearing as a separate row.
 */
@Component
@RequiredArgsConstructor
public class JupiterLendLockedCollateralProvider implements ProtocolLockedBalanceProvider {

    private final LendingLivePositionSnapshotService snapshotService;

    @Override
    public List<ProviderBalance> fetchLockedBalances(String walletAddress, NetworkId networkId) {
        if (networkId != NetworkId.SOLANA || walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        LendingLivePositionSnapshot snapshot = snapshotService
                .latestFreshForWallet(walletAddress.trim(), networkId.name())
                .orElse(null);
        if (snapshot == null || snapshot.getCollateral() == null || snapshot.getCollateral().isEmpty()) {
            return List.of();
        }
        List<ProviderBalance> balances = new ArrayList<>();
        for (LendingLivePositionSnapshot.Leg leg : snapshot.getCollateral()) {
            BigDecimal quantity = leg.getQuantity();
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            boolean nativeAsset = leg.getAssetContract() != null
                    && leg.getAssetContract().equals(NetworkNativeAssets.nativeIdentity(NetworkId.SOLANA));
            balances.add(new ProviderBalance(
                    leg.getAssetSymbol(),
                    leg.getAssetContract(),
                    leg.getDecimals(),
                    quantity,
                    nativeAsset
            ));
        }
        return List.copyOf(balances);
    }
}
