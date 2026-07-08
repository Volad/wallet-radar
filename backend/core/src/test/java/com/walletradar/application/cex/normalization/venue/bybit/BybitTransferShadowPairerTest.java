package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitTransferShadowPairerTest {

    @Mock
    private MongoOperations mongoOperations;

    @Test
    void feeBearingWithdrawalShadowStillMatchesChainAwareSibling() {
        ExternalLedgerRaw shadow = shadowRow(
                "shadow-out",
                "Withdraw",
                "EXTERNAL_TRANSFER_OUT",
                new BigDecimal("-0.01082049")
        );
        ExternalLedgerRaw sibling = siblingRow(
                "withdraw-1",
                "Withdraw",
                "EXTERNAL_TRANSFER_OUT",
                NetworkId.ETHEREUM,
                "0x29522d49e2dd4145f3f60695670070fb2ae941c4ff011cf4ea86136ad6a3d752",
                new BigDecimal("0.00932049")
        );
        when(mongoOperations.find(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(List.of(sibling));

        BybitTransferShadowPairer pairer = new BybitTransferShadowPairer(mongoOperations);

        assertThat(pairer.findChainAwareTransferSibling(shadow))
                .contains(sibling);
    }

    @Test
    void depositShadowMatchesExactChainAwareSibling() {
        ExternalLedgerRaw shadow = shadowRow(
                "shadow-in",
                "Deposit",
                "EXTERNAL_INBOUND",
                new BigDecimal("0.699")
        );
        ExternalLedgerRaw sibling = siblingRow(
                "deposit-1",
                "Deposit",
                "EXTERNAL_INBOUND",
                NetworkId.ARBITRUM,
                "0x68fd61e039ea90e03b07145c67d7e68a118036eaa4c819fe12a5c178e39dfa52",
                new BigDecimal("0.699")
        );
        when(mongoOperations.find(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(List.of(sibling));

        BybitTransferShadowPairer pairer = new BybitTransferShadowPairer(mongoOperations);

        assertThat(pairer.findChainAwareTransferSibling(shadow))
                .contains(sibling);
    }

    private ExternalLedgerRaw shadowRow(
            String id,
            String bybitType,
            String canonicalType,
            BigDecimal quantity
    ) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setSourceFileType("fund_asset_changes");
        row.setBybitType(bybitType);
        row.setCanonicalType(canonicalType);
        row.setChain("BYBIT");
        row.setTimeUtc(Instant.parse("2026-02-19T08:14:22Z"));
        row.setAssetSymbol("ETH");
        row.setQuantityRaw(quantity);
        return row;
    }

    private ExternalLedgerRaw siblingRow(
            String id,
            String bybitType,
            String canonicalType,
            NetworkId networkId,
            String txHash,
            BigDecimal quantity
    ) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(id);
        row.setUid("uid-1");
        row.setSourceFileType("withdraw_deposit");
        row.setBybitType(bybitType);
        row.setCanonicalType(canonicalType);
        row.setChain(networkId.name());
        row.setNetworkId(networkId);
        row.setTxHash(txHash);
        row.setTimeUtc(Instant.parse("2026-02-19T08:14:23Z"));
        row.setAssetSymbol("ETH");
        row.setQuantityRaw(quantity);
        return row;
    }
}
