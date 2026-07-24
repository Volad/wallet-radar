package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.application.balance.OnChainBalanceProvider.ProviderBalance;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS-3 single-authority balance contribution: locked SOL collateral from the fresh live snapshot is
 * surfaced as a native-SOL {@code on_chain_balances} leg (Aave-parity) so the dashboard SOL quantity
 * picks up the ~5.42 SOL without a separate row — and only for Solana with a fresh snapshot.
 */
class JupiterLendLockedCollateralProviderTest {

    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    private final LendingLivePositionSnapshotService snapshotService = mock(LendingLivePositionSnapshotService.class);
    private final JupiterLendLockedCollateralProvider provider = new JupiterLendLockedCollateralProvider(snapshotService);

    private static LendingLivePositionSnapshot snapshotWithSol(String qty) {
        return new LendingLivePositionSnapshot()
                .setWalletAddress(WALLET)
                .setNetworkId("SOLANA")
                .setCollateral(List.of(new LendingLivePositionSnapshot.Leg()
                        .setAssetSymbol("SOL")
                        .setAssetContract("NATIVE:SOLANA")
                        .setDecimals(9)
                        .setQuantity(new BigDecimal(qty))));
    }

    @Test
    void surfacesLockedSolAsNativeBalance() {
        when(snapshotService.latestFreshForWallet(WALLET, "SOLANA"))
                .thenReturn(Optional.of(snapshotWithSol("5.422496102")));

        List<ProviderBalance> balances = provider.fetchLockedBalances(WALLET, NetworkId.SOLANA);

        assertThat(balances).hasSize(1);
        ProviderBalance sol = balances.get(0);
        assertThat(sol.assetSymbol()).isEqualTo("SOL");
        assertThat(sol.assetContract()).isEqualTo("NATIVE:SOLANA");
        assertThat(sol.nativeAsset()).isTrue();
        assertThat(sol.quantity()).isEqualByComparingTo("5.422496102");
    }

    @Test
    void emptyForNonSolanaNetwork() {
        assertThat(provider.fetchLockedBalances(WALLET, NetworkId.ARBITRUM)).isEmpty();
    }

    @Test
    void emptyWhenNoFreshSnapshot() {
        when(snapshotService.latestFreshForWallet(WALLET, "SOLANA")).thenReturn(Optional.empty());
        assertThat(provider.fetchLockedBalances(WALLET, NetworkId.SOLANA)).isEmpty();
    }
}
