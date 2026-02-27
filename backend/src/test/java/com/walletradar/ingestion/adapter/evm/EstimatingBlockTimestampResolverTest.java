package com.walletradar.ingestion.adapter.evm;

import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstimatingBlockTimestampResolverTest {

    private static final NetworkId NETWORK = NetworkId.ETHEREUM;
    private static final double ETH_AVG_BLOCK_TIME = 12.0;

    @Mock
    private BlockTimestampResolver exactResolver;

    private EstimatingBlockTimestampResolver estimator;

    @BeforeEach
    void setUp() {
        estimator = new EstimatingBlockTimestampResolver();
    }

    @Test
    @DisplayName("calibrate with two anchor points computes correct avg block time")
    void calibrate_twoAnchors_computesAvgBlockTime() {
        long fromBlock = 18_000_000L;
        long toBlock = 19_000_000L;
        Instant fromTs = Instant.parse("2023-09-01T00:00:00Z");
        Instant toTs = fromTs.plusSeconds((long) ((toBlock - fromBlock) * ETH_AVG_BLOCK_TIME));

        when(exactResolver.getBlockTimestamp(NETWORK, fromBlock)).thenReturn(fromTs);
        when(exactResolver.getBlockTimestamp(NETWORK, toBlock)).thenReturn(toTs);

        estimator.calibrate(NETWORK, fromBlock, toBlock, exactResolver, ETH_AVG_BLOCK_TIME);

        assertThat(estimator.isCalibrated(NETWORK)).isTrue();
    }

    @Test
    @DisplayName("estimate returns exact anchor timestamp for fromBlock")
    void estimate_fromBlock_returnsExactTimestamp() {
        long fromBlock = 18_000_000L;
        long toBlock = 19_000_000L;
        Instant fromTs = Instant.parse("2023-09-01T00:00:00Z");
        Instant toTs = fromTs.plusSeconds((long) ((toBlock - fromBlock) * ETH_AVG_BLOCK_TIME));

        when(exactResolver.getBlockTimestamp(NETWORK, fromBlock)).thenReturn(fromTs);
        when(exactResolver.getBlockTimestamp(NETWORK, toBlock)).thenReturn(toTs);

        estimator.calibrate(NETWORK, fromBlock, toBlock, exactResolver, ETH_AVG_BLOCK_TIME);

        Instant estimated = estimator.estimate(NETWORK, fromBlock);
        assertThat(estimated).isEqualTo(fromTs);
    }

    @Test
    @DisplayName("estimate returns close-to-exact timestamp for toBlock")
    void estimate_toBlock_returnsCloseTimestamp() {
        long fromBlock = 18_000_000L;
        long toBlock = 19_000_000L;
        Instant fromTs = Instant.parse("2023-09-01T00:00:00Z");
        Instant toTs = fromTs.plusSeconds((long) ((toBlock - fromBlock) * ETH_AVG_BLOCK_TIME));

        when(exactResolver.getBlockTimestamp(NETWORK, fromBlock)).thenReturn(fromTs);
        when(exactResolver.getBlockTimestamp(NETWORK, toBlock)).thenReturn(toTs);

        estimator.calibrate(NETWORK, fromBlock, toBlock, exactResolver, ETH_AVG_BLOCK_TIME);

        Instant estimated = estimator.estimate(NETWORK, toBlock);
        assertThat(Duration.between(toTs, estimated).abs().getSeconds()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("interpolation accuracy: mid-range block within 1 minute for million-block span")
    void estimate_midRange_withinOneMinute() {
        long fromBlock = 18_000_000L;
        long toBlock = 19_000_000L;
        long midBlock = 18_500_000L;
        Instant fromTs = Instant.parse("2023-09-01T00:00:00Z");
        Instant toTs = fromTs.plusSeconds((long) ((toBlock - fromBlock) * ETH_AVG_BLOCK_TIME));
        Instant expectedMid = fromTs.plusSeconds((long) ((midBlock - fromBlock) * ETH_AVG_BLOCK_TIME));

        when(exactResolver.getBlockTimestamp(NETWORK, fromBlock)).thenReturn(fromTs);
        when(exactResolver.getBlockTimestamp(NETWORK, toBlock)).thenReturn(toTs);

        estimator.calibrate(NETWORK, fromBlock, toBlock, exactResolver, ETH_AVG_BLOCK_TIME);

        Instant estimated = estimator.estimate(NETWORK, midBlock);
        assertThat(Duration.between(expectedMid, estimated).abs().getSeconds()).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("fromBlock == toBlock uses fallback avg block time and returns exact ts for that block")
    void calibrate_sameBlock_usesFallback() {
        long block = 18_000_000L;
        Instant ts = Instant.parse("2023-09-01T00:00:00Z");

        when(exactResolver.getBlockTimestamp(NETWORK, block)).thenReturn(ts);

        estimator.calibrate(NETWORK, block, block, exactResolver, ETH_AVG_BLOCK_TIME);

        Instant estimated = estimator.estimate(NETWORK, block);
        assertThat(estimated).isEqualTo(ts);
    }

    @Test
    @DisplayName("fromBlock == toBlock: nearby block uses fallback avg block time")
    void calibrate_sameBlock_nearbyBlockUsesFallback() {
        long block = 18_000_000L;
        Instant ts = Instant.parse("2023-09-01T00:00:00Z");

        when(exactResolver.getBlockTimestamp(NETWORK, block)).thenReturn(ts);

        estimator.calibrate(NETWORK, block, block, exactResolver, ETH_AVG_BLOCK_TIME);

        long nearbyBlock = block + 100;
        Instant estimated = estimator.estimate(NETWORK, nearbyBlock);
        Instant expected = ts.plusSeconds((long) (100 * ETH_AVG_BLOCK_TIME));
        assertThat(estimated).isEqualTo(expected);
    }

    @Test
    @DisplayName("estimate throws if not calibrated for network")
    void estimate_notCalibrated_throws() {
        assertThatThrownBy(() -> estimator.estimate(NETWORK, 18_000_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No calibration data");
    }

    @Test
    @DisplayName("L2 network (Arbitrum) with fast block times interpolates correctly")
    void estimate_arbitrum_fastBlocks() {
        NetworkId arbitrum = NetworkId.ARBITRUM;
        double arbAvgBlockTime = 0.25;
        long fromBlock = 100_000_000L;
        long toBlock = 200_000_000L;
        Instant fromTs = Instant.parse("2024-01-01T00:00:00Z");
        Instant toTs = fromTs.plusSeconds((long) ((toBlock - fromBlock) * arbAvgBlockTime));

        when(exactResolver.getBlockTimestamp(arbitrum, fromBlock)).thenReturn(fromTs);
        when(exactResolver.getBlockTimestamp(arbitrum, toBlock)).thenReturn(toTs);

        estimator.calibrate(arbitrum, fromBlock, toBlock, exactResolver, arbAvgBlockTime);

        long testBlock = 150_000_000L;
        Instant estimated = estimator.estimate(arbitrum, testBlock);
        Instant expected = fromTs.plusSeconds((long) ((testBlock - fromBlock) * arbAvgBlockTime));
        assertThat(Duration.between(expected, estimated).abs().getSeconds()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("multiple networks can be calibrated independently")
    void calibrate_multipleNetworks_independent() {
        long ethFrom = 18_000_000L;
        long ethTo = 19_000_000L;
        Instant ethFromTs = Instant.parse("2023-09-01T00:00:00Z");
        Instant ethToTs = ethFromTs.plusSeconds((long) ((ethTo - ethFrom) * ETH_AVG_BLOCK_TIME));

        long arbFrom = 100_000_000L;
        long arbTo = 200_000_000L;
        double arbAvg = 0.25;
        Instant arbFromTs = Instant.parse("2024-01-01T00:00:00Z");
        Instant arbToTs = arbFromTs.plusSeconds((long) ((arbTo - arbFrom) * arbAvg));

        when(exactResolver.getBlockTimestamp(NETWORK, ethFrom)).thenReturn(ethFromTs);
        when(exactResolver.getBlockTimestamp(NETWORK, ethTo)).thenReturn(ethToTs);
        when(exactResolver.getBlockTimestamp(NetworkId.ARBITRUM, arbFrom)).thenReturn(arbFromTs);
        when(exactResolver.getBlockTimestamp(NetworkId.ARBITRUM, arbTo)).thenReturn(arbToTs);

        estimator.calibrate(NETWORK, ethFrom, ethTo, exactResolver, ETH_AVG_BLOCK_TIME);
        estimator.calibrate(NetworkId.ARBITRUM, arbFrom, arbTo, exactResolver, arbAvg);

        assertThat(estimator.isCalibrated(NETWORK)).isTrue();
        assertThat(estimator.isCalibrated(NetworkId.ARBITRUM)).isTrue();

        Instant ethEstimate = estimator.estimate(NETWORK, ethFrom);
        assertThat(ethEstimate).isEqualTo(ethFromTs);

        Instant arbEstimate = estimator.estimate(NetworkId.ARBITRUM, arbFrom);
        assertThat(arbEstimate).isEqualTo(arbFromTs);
    }
}
