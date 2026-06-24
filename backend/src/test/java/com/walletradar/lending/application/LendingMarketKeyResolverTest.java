package com.walletradar.lending.application;

import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.lending.persistence.LendingReceiptIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LendingMarketKeyResolverTest {

    private static final String WALLET = "0xabc";
    private static final String EULER_VAULT = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

    @Mock
    private LendingReceiptIdentityRepository repository;
    @Mock
    private ProtocolRegistryService protocolRegistryService;

    private LendingMarketKeyResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(repository.findByNetworkIdAndContractAddress(any(), any())).thenReturn(Optional.empty());
        resolver = new LendingMarketKeyResolver(
                new LendingReceiptIdentityService(repository, protocolRegistryService)
        );
    }

    @Test
    void eulerDepositAndWithdrawShareReceiptContractMarketKey() {
        String depositKey = resolver.marketAssetFromTransaction(eulerDeposit(), "Euler");
        String withdrawKey = resolver.marketAssetFromTransaction(eulerWithdraw(), "Euler");

        assertThat(depositKey).isEqualTo("evk-vault-39de0f00");
        assertThat(withdrawKey).isEqualTo(depositKey);
    }

    @Test
    void aaveAndCompoundRemainCollapsedAccountKeys() {
        assertThat(resolver.marketAssetFromTransaction(aaveDeposit(), "Aave")).isEqualTo("account-pool");
        assertThat(resolver.marketAssetFromTransaction(compoundDeposit(), "Compound")).isEqualTo("comet-base-market");
    }

    @Test
    void fluidLendingLoopOpenPrefersMatchedCounterpartyVaultOverLoopAccount() {
        NormalizedTransaction transaction = baseTransaction("0xloop", NormalizedTransactionType.LENDING_LOOP_OPEN);
        transaction.setProtocolName("Fluid");
        transaction.setMatchedCounterparty("0x3e11b9aeb9c7dbbda4dd41477223cc2f3f24b9d7");
        transaction.setFlows(List.of(
                flow("wstUSR", "0x2a52b289ba68bbd02676640aa9f605700c9e5699", "-100"),
                flow("USDC", USDC, "90")
        ));

        assertThat(resolver.marketAssetFromTransaction(transaction, "Fluid")).isEqualTo("vault-3e11b9ae");
    }

    @Test
    void balancePathUsesReceiptContractForEulerSupplyToken() {
        OnChainBalance balance = new OnChainBalance();
        balance.setAssetSymbol("eUSDC-2");
        balance.setAssetContract(EULER_VAULT);
        balance.setNetworkId(NetworkId.AVALANCHE);

        assertThat(resolver.marketAssetFromBalance("Euler", NetworkId.AVALANCHE, balance))
                .isEqualTo("evk-vault-39de0f00");
    }

    private static NormalizedTransaction eulerDeposit() {
        NormalizedTransaction transaction = baseTransaction("0xdep", NormalizedTransactionType.LENDING_DEPOSIT);
        NormalizedTransaction.Flow underlying = flow("USDC", USDC, "-100");
        NormalizedTransaction.Flow receipt = flow("eUSDC-2", EULER_VAULT, "100");
        transaction.setFlows(List.of(underlying, receipt));
        return transaction;
    }

    private static NormalizedTransaction eulerWithdraw() {
        NormalizedTransaction transaction = baseTransaction("0xwd", NormalizedTransactionType.LENDING_WITHDRAW);
        NormalizedTransaction.Flow receipt = flow("eUSDC-2", EULER_VAULT, "-100");
        NormalizedTransaction.Flow underlying = flow("USDC", USDC, "100");
        transaction.setFlows(List.of(receipt, underlying));
        return transaction;
    }

    private static NormalizedTransaction aaveDeposit() {
        NormalizedTransaction transaction = baseTransaction("0xaave", NormalizedTransactionType.LENDING_DEPOSIT);
        transaction.setProtocolName("Aave");
        transaction.setFlows(List.of(flow("aAvaUSDC", "0xusdc", "100")));
        return transaction;
    }

    private static NormalizedTransaction compoundDeposit() {
        NormalizedTransaction transaction = baseTransaction("0xcompound", NormalizedTransactionType.LENDING_DEPOSIT);
        transaction.setProtocolName("Compound");
        transaction.setFlows(List.of(flow("USDC", USDC, "-100")));
        return transaction;
    }

    private static NormalizedTransaction baseTransaction(String txHash, NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setTxHash(txHash);
        transaction.setNetworkId(NetworkId.AVALANCHE);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(type);
        transaction.setProtocolName("Euler");
        transaction.setBlockTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        return transaction;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String contract, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(new BigDecimal(quantity).signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }
}
