package com.walletradar.canonical.correlation;

import com.walletradar.application.cex.normalization.venue.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitInternalTransferPairer;
import com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapser;
import com.walletradar.application.linking.pipeline.clarification.BybitOnChainEarnOrphanRepairService;
import com.walletradar.application.linking.pipeline.clarification.CorridorCorrelationKeyFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures ingestion re-exports remain aligned with the canonical correlation contract.
 */
class CorrelationContractEquivalenceTest {

    @Test
    void ingestionReexportsMatchCanonicalPrefixes() {
        assertThat(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX);
        assertThat(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_CROSS_UID_V1_PREFIX);
        assertThat(BybitInternalTransferPairer.REKEYED_CORRELATION_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_REKEYED_V1_PREFIX);
        assertThat(BybitInternalTransferPairer.BUNDLE_CORRELATION_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_IT_BUNDLE_V1_PREFIX);
        assertThat(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX);
        assertThat(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_CORR_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_EARN_ONCHAIN_V1_PREFIX);
        assertThat(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_FUND_CORR_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX);
        assertThat(CorridorCorrelationKeyFactory.CORRIDOR_PREFIX)
                .isEqualTo(CorrelationContract.BYBIT_CORRIDOR_PREFIX);
    }
}
