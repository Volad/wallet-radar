package com.walletradar.domain.wallet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WalletRefTest {

    // ---- EVM ----

    @Test
    void evmLowercase() {
        WalletRef ref = WalletRef.parse("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045");
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.EVM);
        assertThat(ref.uid()).isEqualTo("0xd8da6bf26964af9d7eed9e03e53415d37aa96045");
        assertThat(ref.venueId()).isNull();
        assertThat(ref.subAccount()).isNull();
        assertThat(ref.umbrellaKey()).isEqualTo(ref.uid());
        assertThat(ref.canonicalRef()).isEqualTo(ref.uid());
    }

    @Test
    void evmUpperPrefix_normalisedToLower() {
        WalletRef ref = WalletRef.parse("0XABCDEF1234567890abcdef1234567890abcdef12");
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.EVM);
        assertThat(ref.uid()).startsWith("0x");
    }

    @Test
    void evm_roundTrip() {
        String address = "0xd8da6bf26964af9d7eed9e03e53415d37aa96045";
        assertThat(WalletRef.parse(address).canonicalRef()).isEqualTo(address);
    }

    // ---- TON ----

    @Test
    void tonFriendlyAddress() {
        String tonAddr = "UQDk2VTe5VFMM0tHHfAJGM9FtEnmBoTpaCMXWJqRbf3UCBV0";
        WalletRef ref = WalletRef.parse(tonAddr);
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.TON);
        assertThat(ref.venueId()).isNull();
        assertThat(ref.subAccount()).isNull();
        assertThat(ref.uid()).isEqualTo(ref.canonicalRef());
    }

    // ---- Solana ----

    @Test
    void solanaAddress() {
        String sol = "4Nd1m1nQ7iR6C1HqzW4NGJi9pX2gY3hK8LrP5bZ0vAU";
        WalletRef ref = WalletRef.parse(sol);
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.SOLANA);
        assertThat(ref.venueId()).isNull();
        assertThat(ref.uid()).isEqualTo(sol);
        assertThat(ref.canonicalRef()).isEqualTo(sol);
        assertThat(ref.umbrellaKey()).isEqualTo(sol);
    }

    // ---- CEX ----

    @ParameterizedTest
    @CsvSource({
            "BYBIT:123456,            bybit, 123456, '',    BYBIT:123456",
            "BYBIT:123456:FUND,       bybit, 123456, FUND,  BYBIT:123456:FUND",
            "BYBIT:123456:UTA,        bybit, 123456, UTA,   BYBIT:123456:UTA",
            "BYBIT:123456:EARN,       bybit, 123456, EARN,  BYBIT:123456:EARN",
            "BYBIT:123456:BOT,        bybit, 123456, BOT,   BYBIT:123456:BOT",
            "DZENGI:abc_def,          dzengi, abc_def, '',  DZENGI:abc_def",
    })
    void cexParsing(String input, String venueId, String uid, String subAccount, String canonical) {
        WalletRef ref = WalletRef.parse(input);
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.CEX);
        assertThat(ref.venueId()).isEqualTo(venueId);
        assertThat(ref.uid()).isEqualTo(uid);
        assertThat(ref.subAccount()).isEqualTo(subAccount.isEmpty() ? null : subAccount);
        assertThat(ref.canonicalRef()).isEqualTo(canonical.strip());
    }

    @Test
    void cex_umbrellaKey_stripsSubaccount() {
        WalletRef ref = WalletRef.parse("BYBIT:123456:FUND");
        assertThat(ref.umbrellaKey()).isEqualTo("BYBIT:123456");
    }

    @Test
    void walletRef_bot_collapsesToUmbrella() {
        // ADR-058 A7: the :BOT Trading-Bot compartment collapses to the BYBIT:<uid> umbrella,
        // exactly like :FUND/:UTA/:EARN — no new replay position key.
        WalletRef ref = WalletRef.parse("BYBIT:516601508:BOT");
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.CEX);
        assertThat(ref.subAccount()).isEqualTo("BOT");
        assertThat(ref.umbrellaKey()).isEqualTo("BYBIT:516601508");
    }

    @Test
    void cex_providerPrefix_isUpperCase() {
        WalletRef ref = WalletRef.parse("bybit:123456:FUND");
        assertThat(ref.providerPrefix()).isEqualTo("BYBIT");
        assertThat(ref.venueId()).isEqualTo("bybit");
        assertThat(ref.canonicalRef()).isEqualTo("BYBIT:123456:FUND");
    }

    // ---- null / blank ----

    @Test
    void nullInput_returnsSolanaEmpty() {
        WalletRef ref = WalletRef.parse(null);
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.SOLANA);
        assertThat(ref.uid()).isEmpty();
    }

    @Test
    void blankInput_returnsSolanaEmpty() {
        WalletRef ref = WalletRef.parse("   ");
        assertThat(ref.domain()).isEqualTo(WalletDomainKind.SOLANA);
    }

    // ---- equality ----

    @Test
    void equality_sameRef() {
        assertThat(WalletRef.parse("BYBIT:123456:FUND"))
                .isEqualTo(WalletRef.parse("BYBIT:123456:FUND"));
    }

    @Test
    void equality_differentSubAccount_notEqual() {
        assertThat(WalletRef.parse("BYBIT:123456:FUND"))
                .isNotEqualTo(WalletRef.parse("BYBIT:123456:UTA"));
    }

    // ---- OnChainAddressClassifier ----

    @ParameterizedTest
    @CsvSource({
            "0xd8da6bf26964af9d7eed9e03e53415d37aa96045, EVM",
            "0Xabcdef1234, EVM",
            "4Nd1m1nQ7iR6C1HqzW4NGJi9pX2gY3hK8LrP5bZ0vAU, SOLANA",
            "BYBIT:123456, CEX",
            "DZENGI:xxx, CEX",
    })
    void classifyDomain(String address, WalletDomainKind expected) {
        assertThat(OnChainAddressClassifier.classifyDomain(address)).isEqualTo(expected);
    }

    @Test
    void normalize_evm_lowercases() {
        assertThat(OnChainAddressClassifier.normalize("0XABCDEF")).isEqualTo("0xabcdef");
    }

    @Test
    void normalize_cex_uppercasesPrefix() {
        assertThat(OnChainAddressClassifier.normalize("bybit:123456:FUND")).isEqualTo("BYBIT:123456:FUND");
    }
}
