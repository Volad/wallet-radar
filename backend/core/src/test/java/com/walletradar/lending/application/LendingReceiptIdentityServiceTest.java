package com.walletradar.lending.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.lending.persistence.LendingReceiptIdentityDocument;
import com.walletradar.lending.persistence.LendingReceiptIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LendingReceiptIdentityServiceTest {

    private static final String WALLET = "0xabc";

    @Mock
    private LendingReceiptIdentityRepository repository;
    @Mock
    private ProtocolRegistryService protocolRegistryService;

    private LendingReceiptIdentityService service;

    @BeforeEach
    void setUp() {
        service = new LendingReceiptIdentityService(repository, protocolRegistryService);
    }

    @Test
    void grammarFallbackResolvesEulerIndexedShare() {
        Optional<LendingReceiptIdentity> identity = service.resolveGrammar("eWBTC-1");

        assertThat(identity).isPresent();
        assertThat(identity.orElseThrow().protocol()).isEqualTo("Euler");
        assertThat(identity.orElseThrow().underlyingSymbol()).isEqualTo("WBTC");
        assertThat(identity.orElseThrow().side()).isEqualTo("SUPPLY");
    }

    @Test
    void grammarFallbackResolvesAaveWethReceiptToMarketUnderlying() {
        Optional<LendingReceiptIdentity> identity = service.resolveGrammar("AWETH");

        assertThat(identity).isPresent();
        assertThat(identity.orElseThrow().protocol()).isEqualTo("Aave");
        assertThat(identity.orElseThrow().underlyingSymbol()).isEqualTo("WETH");
        assertThat(identity.orElseThrow().side()).isEqualTo("SUPPLY");
    }

    @Test
    void derivedIndexWinsOverGrammar() {
        LendingReceiptIdentityDocument stored = new LendingReceiptIdentityDocument();
        stored.setProtocol("Euler");
        stored.setUnderlyingSymbol("WBTC");
        stored.setSide("SUPPLY");
        stored.setSource("DERIVED_TX_PAIR");
        when(repository.findByNetworkIdAndContractAddress("BASE", "0x0000000000000000000000000000000000000001"))
                .thenReturn(Optional.of(stored));

        Optional<LendingReceiptIdentity> identity = service.resolve(
                NetworkId.BASE,
                "0x0000000000000000000000000000000000000001",
                "eWBTC-1"
        );

        assertThat(identity).isPresent();
        assertThat(identity.orElseThrow().underlyingSymbol()).isEqualTo("WBTC");
        assertThat(identity.orElseThrow().source()).isEqualTo("DERIVED_TX_PAIR");
    }

    @Test
    void indexesReceiptContractFromDepositPair() {
        when(repository.findByNetworkIdAndContractAddress(any(), any())).thenReturn(Optional.empty());

        service.indexTransaction(eulerDeposit());

        ArgumentCaptor<LendingReceiptIdentityDocument> captor = ArgumentCaptor.forClass(LendingReceiptIdentityDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getProtocol()).isEqualTo("Euler");
        assertThat(captor.getValue().getUnderlyingSymbol()).isEqualTo("USDC");
        assertThat(captor.getValue().getAssetSymbol()).isEqualToIgnoringCase("eUSDC-2");
    }

    @Test
    void indexesReceiptWithGrammarUnderlyingEvenWhenPairedFlowIsNativeEth() {
        when(repository.findByNetworkIdAndContractAddress(any(), any())).thenReturn(Optional.empty());

        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setTxHash("0xaweth");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        transaction.setProtocolName("Aave");
        transaction.setBlockTimestamp(Instant.parse("2026-01-01T00:00:00Z"));

        NormalizedTransaction.Flow underlying = new NormalizedTransaction.Flow();
        underlying.setRole(NormalizedLegRole.SELL);
        underlying.setAssetSymbol("ETH");
        underlying.setAssetContract("0xeth");
        underlying.setQuantityDelta(new BigDecimal("-1"));

        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setRole(NormalizedLegRole.BUY);
        receipt.setAssetSymbol("AWETH");
        receipt.setAssetContract("0x0000000000000000000000000000000000000003");
        receipt.setQuantityDelta(new BigDecimal("1"));

        transaction.setFlows(java.util.List.of(underlying, receipt));

        service.indexTransaction(transaction);

        ArgumentCaptor<LendingReceiptIdentityDocument> captor = ArgumentCaptor.forClass(LendingReceiptIdentityDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUnderlyingSymbol()).isEqualTo("WETH");
    }

    @Test
    void doesNotMisclassifyEurcAsEulerReceipt() {
        assertThat(service.resolveGrammar("EURC")).isEmpty();
        assertThat(service.isLendingPositionSymbol(NetworkId.BASE, null, "EURC")).isFalse();
        verify(repository, never()).save(any());
    }

    private static NormalizedTransaction eulerDeposit() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setTxHash("0xdep");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        transaction.setProtocolName("Euler");
        transaction.setBlockTimestamp(Instant.parse("2026-01-01T00:00:00Z"));

        NormalizedTransaction.Flow underlying = new NormalizedTransaction.Flow();
        underlying.setRole(NormalizedLegRole.SELL);
        underlying.setAssetSymbol("USDC");
        underlying.setAssetContract("0xusdc");
        underlying.setQuantityDelta(new BigDecimal("-100"));

        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setRole(NormalizedLegRole.BUY);
        receipt.setAssetSymbol("eUSDC-2");
        receipt.setAssetContract("0x0000000000000000000000000000000000000002");
        receipt.setQuantityDelta(new BigDecimal("100"));

        transaction.setFlows(java.util.List.of(underlying, receipt));
        return transaction;
    }
}
