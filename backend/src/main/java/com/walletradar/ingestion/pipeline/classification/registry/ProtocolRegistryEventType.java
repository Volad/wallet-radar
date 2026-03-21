package com.walletradar.ingestion.pipeline.classification.registry;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

public enum ProtocolRegistryEventType {
    SWAP(NormalizedTransactionType.SWAP),
    LP_MINT(NormalizedTransactionType.LP_ENTRY),
    LP_BURN(NormalizedTransactionType.LP_EXIT),
    LENDING_DEPOSIT(NormalizedTransactionType.LENDING_DEPOSIT),
    LENDING_WITHDRAW(NormalizedTransactionType.LENDING_WITHDRAW),
    BORROW(NormalizedTransactionType.BORROW),
    REPAY(NormalizedTransactionType.REPAY),
    STAKING_DEPOSIT(NormalizedTransactionType.STAKING_DEPOSIT),
    STAKING_WITHDRAW(NormalizedTransactionType.STAKING_WITHDRAW),
    VAULT_DEPOSIT(NormalizedTransactionType.VAULT_DEPOSIT),
    VAULT_WITHDRAW(NormalizedTransactionType.VAULT_WITHDRAW),
    BRIDGE_OUT(NormalizedTransactionType.BRIDGE_OUT),
    BRIDGE_IN(NormalizedTransactionType.BRIDGE_IN),
    PROTOCOL_CUSTODY_DEPOSIT(NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT),
    PROTOCOL_CUSTODY_WITHDRAW(NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW),
    REWARD_CLAIM(NormalizedTransactionType.REWARD_CLAIM);

    private final NormalizedTransactionType normalizedType;

    ProtocolRegistryEventType(NormalizedTransactionType normalizedType) {
        this.normalizedType = normalizedType;
    }

    public NormalizedTransactionType toNormalizedType() {
        return normalizedType;
    }
}
