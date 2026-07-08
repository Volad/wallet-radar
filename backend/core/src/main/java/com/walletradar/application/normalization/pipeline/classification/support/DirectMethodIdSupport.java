package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.Map;

/**
 * Shared direct selector-to-type mapping used by the selector fallback stage.
 * Selectors that collide across protocol families belong to verified family classifiers,
 * not this global fallback.
 */
public final class DirectMethodIdSupport {

    private static final Map<String, NormalizedTransactionType> METHOD_ID_TYPES = Map.ofEntries(
            Map.entry("0x7ff36ab5", NormalizedTransactionType.SWAP),
            Map.entry("0x18cbafe5", NormalizedTransactionType.SWAP),
            Map.entry("0x38ed1739", NormalizedTransactionType.SWAP),
            Map.entry("0x414bf389", NormalizedTransactionType.SWAP),
            Map.entry("0xc04b8d59", NormalizedTransactionType.SWAP),
            Map.entry("0xdb3e2198", NormalizedTransactionType.SWAP),
            Map.entry("0x617ba037", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0xe8eda9df", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0xa415bcad", NormalizedTransactionType.BORROW),
            Map.entry("0x573ade81", NormalizedTransactionType.REPAY),
            Map.entry("0x852a12e3", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0xdb006a75", NormalizedTransactionType.LENDING_WITHDRAW),
            Map.entry("0xa5d4d0cc", NormalizedTransactionType.BRIDGE_OUT),
            Map.entry("0x9fbf10fc", NormalizedTransactionType.BRIDGE_OUT),
            Map.entry("0xec51b4c9", NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT),
            Map.entry("0x6eba5d0c", NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW),
            Map.entry("0xb88a802f", NormalizedTransactionType.REWARD_CLAIM),
            Map.entry("0x095ea7b3", NormalizedTransactionType.APPROVE)
    );

    private DirectMethodIdSupport() {
    }

    public static NormalizedTransactionType resolveType(String methodId) {
        if (methodId == null) {
            return null;
        }
        return METHOD_ID_TYPES.get(methodId);
    }
}
