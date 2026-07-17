package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectMethodIdSupportTest {

    @Test
    @DisplayName("golden set: every selector resolves to the exact expected type")
    void goldenMappings() {
        assertThat(DirectMethodIdSupport.resolveType("0x7ff36ab5")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0x18cbafe5")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0x38ed1739")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0x414bf389")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0xc04b8d59")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0xdb3e2198")).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(DirectMethodIdSupport.resolveType("0x617ba037")).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(DirectMethodIdSupport.resolveType("0xe8eda9df")).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(DirectMethodIdSupport.resolveType("0xa415bcad")).isEqualTo(NormalizedTransactionType.BORROW);
        assertThat(DirectMethodIdSupport.resolveType("0x573ade81")).isEqualTo(NormalizedTransactionType.REPAY);
        assertThat(DirectMethodIdSupport.resolveType("0x852a12e3")).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        assertThat(DirectMethodIdSupport.resolveType("0xdb006a75")).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
        assertThat(DirectMethodIdSupport.resolveType("0xa5d4d0cc")).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(DirectMethodIdSupport.resolveType("0x9fbf10fc")).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(DirectMethodIdSupport.resolveType("0xec51b4c9")).isEqualTo(NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT);
        assertThat(DirectMethodIdSupport.resolveType("0x6eba5d0c")).isEqualTo(NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW);
        assertThat(DirectMethodIdSupport.resolveType("0xb88a802f")).isEqualTo(NormalizedTransactionType.REWARD_CLAIM);
        assertThat(DirectMethodIdSupport.resolveType("0x095ea7b3")).isEqualTo(NormalizedTransactionType.APPROVE);
    }

    @Test
    @DisplayName("null and unknown selectors resolve to null")
    void nullAndUnknown() {
        assertThat(DirectMethodIdSupport.resolveType(null)).isNull();
        assertThat(DirectMethodIdSupport.resolveType("0xdeadbeef")).isNull();
    }
}
