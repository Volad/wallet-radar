package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.application.lending.application.LendingActiveMarketDiscoveryService.ActiveMarket;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the two active-market discovery sources: the EVM receipt/debt-token scan over
 * {@code on_chain_balances} still emits Aave markets, and the receipt-less source (Solana Jupiter Lend)
 * is merged in and deduped by the composite market key.
 */
class LendingActiveMarketDiscoveryServiceTest {

    private static final String SESSION = "session-1";
    private static final String A_TOKEN = "0xaave-aweth";

    private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);
    private final OnChainBalanceRepository onChainBalanceRepository = mock(OnChainBalanceRepository.class);
    private final LendingReceiptIdentityService receiptIdentityService = mock(LendingReceiptIdentityService.class);
    private final LendingReceiptLessActiveMarketSource receiptLessSource =
            mock(LendingReceiptLessActiveMarketSource.class);

    private final LendingActiveMarketDiscoveryService service = new LendingActiveMarketDiscoveryService(
            userSessionRepository, onChainBalanceRepository, receiptIdentityService, receiptLessSource);

    @Test
    void discoversEvmReceiptMarketAndMergesReceiptLessMarkets() {
        givenSessionWithAaveSupplyBalance();
        when(receiptLessSource.discover(anyList())).thenReturn(List.of(
                jupiterMarket("SUPPLY", "SOL", "NATIVE:SOLANA"),
                jupiterMarket("BORROW", "USDC", "EPjF")));

        List<ActiveMarket> markets = service.discover();

        assertThat(markets).hasSize(3);
        assertThat(markets).anySatisfy(m -> {
            assertThat(m.protocol()).isEqualTo("Aave");
            assertThat(m.networkId()).isEqualTo("BASE");
            assertThat(m.side()).isEqualTo("SUPPLY");
            assertThat(m.underlyingSymbol()).isEqualTo("WETH");
            assertThat(m.marketKey()).isEqualTo("Aave:BASE:ACCOUNT-POOL");
        });
        assertThat(markets).filteredOn(m -> "Jupiter Lend".equals(m.protocol())).hasSize(2);
    }

    @Test
    void doesNotDuplicateWhenReceiptLessSourceReturnsSameKeyAsEvmScan() {
        givenSessionWithAaveSupplyBalance();
        ActiveMarket duplicateOfEvm = new ActiveMarket(
                SESSION, "Aave", "BASE", "0xwallet", "Aave:BASE:ACCOUNT-POOL",
                "SUPPLY", "aBasWETH", "WETH", A_TOKEN);
        when(receiptLessSource.discover(anyList())).thenReturn(List.of(duplicateOfEvm));

        List<ActiveMarket> markets = service.discover();

        assertThat(markets).hasSize(1);
    }

    @Test
    void noSessionsReturnsEmpty() {
        when(userSessionRepository.findAll()).thenReturn(List.of());
        assertThat(service.discover()).isEmpty();
    }

    private void givenSessionWithAaveSupplyBalance() {
        UserSession session = new UserSession();
        session.setId(SESSION);
        when(userSessionRepository.findAll()).thenReturn(List.of(session));

        OnChainBalance balance = new OnChainBalance();
        balance.setSessionId(SESSION);
        balance.setWalletAddress("0xwallet");
        balance.setNetworkId(NetworkId.BASE);
        balance.setAssetSymbol("aBasWETH");
        balance.setAssetContract(A_TOKEN);
        balance.setQuantity(new BigDecimal("1.5"));
        when(onChainBalanceRepository.findBySessionIdIn(anyList())).thenReturn(List.of(balance));

        when(receiptIdentityService.isLendingPositionSymbol(eq(NetworkId.BASE), eq(A_TOKEN), any()))
                .thenReturn(true);
        when(receiptIdentityService.protocolHint(eq(NetworkId.BASE), eq(A_TOKEN), any()))
                .thenReturn(Optional.of("Aave"));
        when(receiptIdentityService.underlyingSymbol(eq(NetworkId.BASE), eq(A_TOKEN), any()))
                .thenReturn("WETH");
    }

    private static ActiveMarket jupiterMarket(String side, String underlying, String contract) {
        return new ActiveMarket(
                SESSION, "Jupiter Lend", "SOLANA", "9Grpx", "Jupiter Lend:SOLANA:ACCOUNT-POOL",
                side, underlying, underlying, contract);
    }
}
