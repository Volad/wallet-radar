package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeSettlementMetadataSupportTest {

    @Test
    void stampsAssetConvertingSubModeWithRealizeFlagAndDestFairValue() {
        NormalizedTransaction leg = new NormalizedTransaction();

        boolean changed = BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(
                leg, true, new BigDecimal("42000.5"));

        assertThat(changed).isTrue();
        assertThat(BridgeSettlementMetadataSupport.subMode(leg))
                .isEqualTo(BridgeSettlementMetadataSupport.SUB_MODE_ASSET_CONVERTING);
        assertThat(BridgeSettlementMetadataSupport.isAssetConvertingSettlement(leg)).isTrue();
        assertThat(BridgeSettlementMetadataSupport.isRealizeOnConvert(leg)).isTrue();
        assertThat(BridgeSettlementMetadataSupport.destFairValueUsd(leg)).isEqualByComparingTo("42000.5");
    }

    @Test
    void stampsAssetConvertingSubModeWithoutRealizeWhenPegNeutral() {
        NormalizedTransaction leg = new NormalizedTransaction();

        boolean changed = BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(leg, false, null);

        assertThat(changed).isTrue();
        assertThat(BridgeSettlementMetadataSupport.subMode(leg))
                .isEqualTo(BridgeSettlementMetadataSupport.SUB_MODE_ASSET_CONVERTING);
        assertThat(BridgeSettlementMetadataSupport.isAssetConvertingSettlement(leg)).isTrue();
        assertThat(BridgeSettlementMetadataSupport.isRealizeOnConvert(leg)).isFalse();
        assertThat(BridgeSettlementMetadataSupport.destFairValueUsd(leg)).isNull();
    }

    @Test
    void realizeFlagFalseWhenDestFairValueMissingEvenIfRequested() {
        NormalizedTransaction leg = new NormalizedTransaction();

        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(leg, true, null);

        assertThat(BridgeSettlementMetadataSupport.isRealizeOnConvert(leg)).isFalse();
        assertThat(BridgeSettlementMetadataSupport.destFairValueUsd(leg)).isNull();
    }

    @Test
    void stampsSameAssetContinuitySubMode() {
        NormalizedTransaction leg = new NormalizedTransaction();

        boolean changed = BridgeSettlementMetadataSupport.stampSameAssetContinuity(leg);

        assertThat(changed).isTrue();
        assertThat(BridgeSettlementMetadataSupport.subMode(leg))
                .isEqualTo(BridgeSettlementMetadataSupport.SUB_MODE_SAME_ASSET);
        assertThat(BridgeSettlementMetadataSupport.isAssetConvertingSettlement(leg)).isFalse();
        assertThat(BridgeSettlementMetadataSupport.isRealizeOnConvert(leg)).isFalse();
    }

    @Test
    void reStampingIdenticalMetadataIsNoOp() {
        NormalizedTransaction leg = new NormalizedTransaction();
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(leg, true, new BigDecimal("100"));

        boolean changedAgain = BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(
                leg, true, new BigDecimal("100"));

        assertThat(changedAgain).isFalse();
    }

    @Test
    void unstampedLegHasNoSettlementMetadata() {
        NormalizedTransaction leg = new NormalizedTransaction();

        assertThat(BridgeSettlementMetadataSupport.subMode(leg)).isNull();
        assertThat(BridgeSettlementMetadataSupport.isAssetConvertingSettlement(leg)).isFalse();
        assertThat(BridgeSettlementMetadataSupport.isRealizeOnConvert(leg)).isFalse();
        assertThat(BridgeSettlementMetadataSupport.destFairValueUsd(leg)).isNull();
    }
}
