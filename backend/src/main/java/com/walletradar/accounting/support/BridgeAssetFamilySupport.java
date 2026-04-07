package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

/**
 * Conservative bridge-family identity mapping used by continuity logic.
 */
public final class BridgeAssetFamilySupport {

    private BridgeAssetFamilySupport() {
    }

    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        return AccountingAssetFamilySupport.continuityIdentity(flow);
    }
}
