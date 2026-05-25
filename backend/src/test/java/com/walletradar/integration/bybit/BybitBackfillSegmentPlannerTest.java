package com.walletradar.integration.bybit;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.integration.config.BybitIntegrationProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class BybitBackfillSegmentPlannerTest {

    @Test
    void producesTimeRangeSegmentsForSharedBackfillPlanner() {
        BybitIntegrationProperties bybitProperties = new BybitIntegrationProperties();
        bybitProperties.setTransactionLogWindowDays(365);
        bybitProperties.setExecutionWindowDays(365);
        bybitProperties.setFundingHistoryWindowDays(365);
        bybitProperties.setTransferWindowDays(365);
        bybitProperties.setDepositWithdrawalWindowDays(365);
        bybitProperties.setConvertWindowDays(365);
        bybitProperties.setEarnWindowDays(365);

        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(1);

        BybitBackfillSegmentPlanner service = new BybitBackfillSegmentPlanner(
                bybitProperties,
                backfillProperties
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);

        List<BackfillSegment> segments = service.planInitialBackfill(
                "session-1",
                integration,
                java.time.Instant.parse("2026-04-07T12:00:00Z")
        );

        assertThat(segments).hasSizeGreaterThanOrEqualTo(12);
        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.getSessionId()).isEqualTo("session-1");
            assertThat(segment.getSourceKind()).isEqualTo(BackfillSegment.SourceKind.INTEGRATION);
            assertThat(segment.getSegmentKind()).isEqualTo(BackfillSegment.SegmentKind.TIME_RANGE);
            assertThat(segment.getIntegrationId()).isEqualTo("BYBIT-33625378");
            assertThat(segment.getProvider()).isEqualTo("BYBIT");
            assertThat(segment.getAccountRef()).isEqualTo("BYBIT:33625378");
            assertThat(segment.getStatus()).isEqualTo(BackfillSegment.SegmentStatus.PENDING);
        });
        assertThat(segments.stream().map(BackfillSegment::getStream).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(
                        BybitIntegrationStream.TRANSACTION_LOG.name(),
                        BybitIntegrationStream.EXECUTION_LINEAR.name(),
                        BybitIntegrationStream.EXECUTION_INVERSE.name(),
                        BybitIntegrationStream.EXECUTION_SPOT.name(),
                        BybitIntegrationStream.EXECUTION_OPTION.name(),
                        BybitIntegrationStream.FUNDING_HISTORY.name(),
                        BybitIntegrationStream.EARN_FLEXIBLE_SAVING.name(),
                        BybitIntegrationStream.INTERNAL_TRANSFER.name(),
                        BybitIntegrationStream.UNIVERSAL_TRANSFER.name(),
                        BybitIntegrationStream.DEPOSIT_INTERNAL.name(),
                        BybitIntegrationStream.CONVERT_HISTORY.name(),
                        BybitIntegrationStream.DEPOSIT_ONCHAIN.name(),
                        BybitIntegrationStream.WITHDRAWAL.name()
                ));
    }

    @Test
    void clampsFundingHistorySegmentsToBybitSevenDayLimit() {
        BybitIntegrationProperties bybitProperties = new BybitIntegrationProperties();
        bybitProperties.setTransactionLogWindowDays(365);
        bybitProperties.setExecutionWindowDays(365);
        bybitProperties.setFundingHistoryWindowDays(30);
        bybitProperties.setTransferWindowDays(365);
        bybitProperties.setDepositWithdrawalWindowDays(365);
        bybitProperties.setConvertWindowDays(365);
        bybitProperties.setEarnWindowDays(30);

        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(1);

        BybitBackfillSegmentPlanner service = new BybitBackfillSegmentPlanner(
                bybitProperties,
                backfillProperties
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);

        List<BackfillSegment> segments = service.planInitialBackfill(
                "session-1",
                integration,
                java.time.Instant.parse("2026-04-07T12:00:00Z")
        );

        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.FUNDING_HISTORY.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.EARN_FLEXIBLE_SAVING.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
    }

    @Test
    void producesOnlyDeltaSegmentsForIncrementalRefresh() {
        BybitIntegrationProperties bybitProperties = new BybitIntegrationProperties();
        bybitProperties.setTransactionLogWindowDays(365);
        bybitProperties.setExecutionWindowDays(365);
        bybitProperties.setFundingHistoryWindowDays(30);
        bybitProperties.setTransferWindowDays(365);
        bybitProperties.setDepositWithdrawalWindowDays(365);
        bybitProperties.setConvertWindowDays(365);
        bybitProperties.setEarnWindowDays(30);

        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(1);

        BybitBackfillSegmentPlanner service = new BybitBackfillSegmentPlanner(
                bybitProperties,
                backfillProperties
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);

        java.time.Instant from = java.time.Instant.parse("2026-04-10T11:00:00Z");
        java.time.Instant to = java.time.Instant.parse("2026-04-10T12:00:00Z");
        List<BackfillSegment> segments = service.planIncrementalBackfill(
                "session-1",
                integration,
                from,
                to,
                java.time.Instant.parse("2026-04-10T12:00:00Z")
        );

        assertThat(segments).hasSize(13);
        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.getFromTime()).isEqualTo(from);
            assertThat(segment.getToTime()).isEqualTo(to);
        });
    }

    @Test
    void clampsTransactionLogAndExecutionSegmentsToSevenDayLimit() {
        BybitIntegrationProperties bybitProperties = new BybitIntegrationProperties();
        bybitProperties.setTransactionLogWindowDays(365);
        bybitProperties.setExecutionWindowDays(365);
        bybitProperties.setFundingHistoryWindowDays(365);
        bybitProperties.setTransferWindowDays(365);
        bybitProperties.setDepositWithdrawalWindowDays(365);
        bybitProperties.setConvertWindowDays(365);
        bybitProperties.setEarnWindowDays(365);

        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(1);

        BybitBackfillSegmentPlanner service = new BybitBackfillSegmentPlanner(
                bybitProperties,
                backfillProperties
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);

        List<BackfillSegment> segments = service.planInitialBackfill(
                "session-1",
                integration,
                java.time.Instant.parse("2026-04-07T12:00:00Z")
        );

        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.TRANSACTION_LOG.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.EXECUTION_SPOT.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
    }

    @Test
    void clampsInternalAndUniversalTransferSegmentsToBybitSevenDayLimit() {
        BybitIntegrationProperties bybitProperties = new BybitIntegrationProperties();
        bybitProperties.setTransactionLogWindowDays(365);
        bybitProperties.setExecutionWindowDays(365);
        bybitProperties.setFundingHistoryWindowDays(365);
        bybitProperties.setTransferWindowDays(30);
        bybitProperties.setDepositWithdrawalWindowDays(365);
        bybitProperties.setConvertWindowDays(365);
        bybitProperties.setEarnWindowDays(365);

        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(1);

        BybitBackfillSegmentPlanner service = new BybitBackfillSegmentPlanner(
                bybitProperties,
                backfillProperties
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);

        List<BackfillSegment> segments = service.planInitialBackfill(
                "session-1",
                integration,
                java.time.Instant.parse("2026-04-07T12:00:00Z")
        );

        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.INTERNAL_TRANSFER.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
        assertThat(segments).filteredOn(segment -> BybitIntegrationStream.UNIVERSAL_TRANSFER.name().equals(segment.getStream()))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(java.time.Duration.between(segment.getFromTime(), segment.getToTime()))
                        .isLessThanOrEqualTo(java.time.Duration.ofDays(7)));
    }
}
