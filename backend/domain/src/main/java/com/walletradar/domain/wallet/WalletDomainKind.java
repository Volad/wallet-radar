package com.walletradar.domain.wallet;

/**
 * Broad classification of a wallet address: on-chain chain family or centralised exchange.
 * Pure domain enum — no Spring, no ports.
 */
public enum WalletDomainKind {
    EVM,
    SOLANA,
    TON,
    CEX
}
