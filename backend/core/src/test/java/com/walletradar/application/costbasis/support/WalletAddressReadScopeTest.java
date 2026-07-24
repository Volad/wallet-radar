package com.walletradar.application.costbasis.support;

import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2 — family-aware read-scope address normalization: EVM/CEX lowercased, Solana base58
 * case-preserved, TON collapsed to its preferred member ref. Blindly lowercasing corrupted base58
 * Solana/TON addresses, which is what hid Solana LP positions from the discovery {@code $in} query.
 */
class WalletAddressReadScopeTest {

    private static final String EVM = "0xAbC0000000000000000000000000000000000123";
    private static final String SOLANA = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String TON = "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms";

    @Test
    void evmIsLowercased() {
        assertThat(WalletAddressReadScope.normalize(EVM)).isEqualTo(EVM.toLowerCase());
    }

    @Test
    void solanaBase58CaseIsPreserved() {
        assertThat(WalletAddressReadScope.normalize(SOLANA)).isEqualTo(SOLANA);
        assertThat(WalletAddressReadScope.normalize(" " + SOLANA + " ")).isEqualTo(SOLANA);
    }

    @Test
    void tonCollapsesToPreferredMemberRef() {
        assertThat(WalletAddressReadScope.normalize(TON))
                .isEqualTo(TonAddressCanonicalizer.preferredMemberRef(TON))
                .isNotBlank();
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertThat(WalletAddressReadScope.normalize(null)).isEmpty();
        assertThat(WalletAddressReadScope.normalize("   ")).isEmpty();
    }
}
