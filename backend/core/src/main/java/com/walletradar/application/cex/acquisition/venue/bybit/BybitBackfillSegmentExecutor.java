package com.walletradar.application.cex.acquisition.venue.bybit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import com.walletradar.application.backfill.job.BackfillSegmentExecutor;
import com.walletradar.application.backfill.job.SessionBackfillCompletionPublisher;
import com.walletradar.integration.config.BybitIntegrationProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import com.walletradar.session.application.IntegrationSyncStatusService;
import com.walletradar.session.application.SessionSecretCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes shared integration backfill segments for Bybit. The control plane
 * stays in the common backfill runner; this class only knows how to process
 * one Bybit segment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitBackfillSegmentExecutor implements BackfillSegmentExecutor {

    private final BackfillSegmentRepository backfillSegmentRepository;
    private final UserSessionRepository userSessionRepository;
    private final IntegrationRawEventRepository integrationRawEventRepository;
    private final BybitExtractedEventRepository bybitExtractedEventRepository;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final IntegrationBackfillProperties integrationBackfillProperties;
    private final BybitIntegrationProperties bybitIntegrationProperties;
    private final BybitApiClient bybitApiClient;
    private final BybitExtractionService bybitExtractionService;
    private final SessionBackfillCompletionPublisher sessionBackfillCompletionPublisher;
    private final IntegrationSyncStatusService integrationSyncStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(BackfillSegment segment) {
        return segment != null
                && segment.getSourceKind() == BackfillSegment.SourceKind.INTEGRATION
                && UserSession.IntegrationProvider.BYBIT.name().equalsIgnoreCase(segment.getProvider());
    }

    @Override
    public void execute(BackfillSegment segment) {
        Instant now = Instant.now();
        segment.setStatus(BackfillSegment.SegmentStatus.RUNNING);
        segment.setStartedAt(now);
        segment.setUpdatedAt(now);
        backfillSegmentRepository.save(segment);

        try {
            UserSession session = userSessionRepository.findById(segment.getSessionId())
                    .orElseThrow(() -> new IllegalStateException("Session not found for integration segment"));
            UserSession.SessionIntegration integration = findIntegration(session, segment.getIntegrationId())
                    .orElseThrow(() -> new IllegalStateException("Integration not found for segment"));
            BybitIntegrationStream stream = BybitIntegrationStream.valueOf(segment.getStream());
            if (requiresSevenDayRepartition(stream, segment)) {
                repartitionOversizedSegment(segment, stream, session, integration);
                return;
            }
            EffectiveWindow effectiveWindow = resolveEffectiveWindow(
                    segment,
                    Instant.now(),
                    integrationBackfillProperties.getHistoryYears(),
                    bybitIntegrationProperties.getHistoryClampSafetyMinutes()
            );
            if (effectiveWindow == null) {
                log.info("Skipping Bybit segment outside supported history window: id={}, stream={}, from={}, to={}",
                        segment.getId(), segment.getStream(), segment.getFromTime(), segment.getToTime());
                completeSegment(segment, session, integration, 0L);
                return;
            }
            BybitCredentials credentials = readCredentials(integration);

            long processedCount = 0L;
            String cursor = null;
            do {
                BybitApiClient.BybitPage page = bybitApiClient.fetchStream(
                        stream,
                        credentials.apiKey(),
                        credentials.apiSecret(),
                        effectiveWindow.fromTime(),
                        effectiveWindow.toTime(),
                        cursor
                );
                for (JsonNode row : page.rows()) {
                    IntegrationRawEvent rawEvent = toRawEvent(segment, integration, row);
                    integrationRawEventRepository.save(rawEvent);
                    List<BybitExtractedEvent> extractedEvents = bybitExtractionService.extract(rawEvent);
                    if (!extractedEvents.isEmpty()) {
                        bybitExtractedEventRepository.saveAll(extractedEvents);
                    }
                    processedCount++;
                }
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());

            completeSegment(segment, session, integration, processedCount);
        } catch (Exception exception) {
            log.warn("Integration segment failed: id={}, provider={}, stream={}, error={}",
                    segment.getId(), segment.getProvider(), segment.getStream(), exception.getMessage(), exception);
            segment.setStatus(BackfillSegment.SegmentStatus.FAILED);
            segment.setErrorMessage(exception.getMessage());
            segment.setRetryCount((segment.getRetryCount() == null ? 0 : segment.getRetryCount()) + 1);
            segment.setUpdatedAt(Instant.now());
            backfillSegmentRepository.save(segment);
            userSessionRepository.findById(segment.getSessionId()).ifPresent(session -> findIntegration(session, segment.getIntegrationId())
                    .ifPresent(integration -> updateIntegrationSyncState(session, integration, exception.getMessage())));
        }
    }

    private boolean requiresSevenDayRepartition(BybitIntegrationStream stream, BackfillSegment segment) {
        if (segment.getFromTime() == null
                || segment.getToTime() == null
                || Duration.between(segment.getFromTime(), segment.getToTime()).compareTo(Duration.ofDays(7)) <= 0) {
            return false;
        }
        return stream == BybitIntegrationStream.EARN_FLEXIBLE_SAVING
                || stream == BybitIntegrationStream.FUNDING_HISTORY
                || stream == BybitIntegrationStream.TRANSACTION_LOG
                || stream == BybitIntegrationStream.EXECUTION_LINEAR
                || stream == BybitIntegrationStream.EXECUTION_INVERSE
                || stream == BybitIntegrationStream.EXECUTION_SPOT
                || stream == BybitIntegrationStream.EXECUTION_OPTION
                || stream == BybitIntegrationStream.INTERNAL_TRANSFER
                || stream == BybitIntegrationStream.UNIVERSAL_TRANSFER;
    }

    private void repartitionOversizedSegment(
            BackfillSegment segment,
            BybitIntegrationStream stream,
            UserSession session,
            UserSession.SessionIntegration integration
    ) {
        int nextSegmentIndex = backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(segment.getIntegrationId()).stream()
                .map(BackfillSegment::getSegmentIndex)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        List<BackfillSegment> replacementSegments = new ArrayList<>();
        Instant cursor = segment.getFromTime();
        while (cursor.isBefore(segment.getToTime())) {
            Instant segmentEnd = cursor.plus(7, ChronoUnit.DAYS);
            if (segmentEnd.isAfter(segment.getToTime())) {
                segmentEnd = segment.getToTime();
            }

            BackfillSegment replacement = new BackfillSegment();
            replacement.setId(segment.getIntegrationId() + ":" + stream.name() + ":" + nextSegmentIndex);
            replacement.setSessionId(segment.getSessionId());
            replacement.setSourceKind(segment.getSourceKind());
            replacement.setSegmentKind(BackfillSegment.SegmentKind.TIME_RANGE);
            replacement.setIntegrationId(segment.getIntegrationId());
            replacement.setProvider(segment.getProvider());
            replacement.setAccountRef(segment.getAccountRef());
            replacement.setStream(segment.getStream());
            replacement.setSegmentIndex(nextSegmentIndex);
            replacement.setFromTime(cursor);
            replacement.setToTime(segmentEnd);
            replacement.setStatus(BackfillSegment.SegmentStatus.PENDING);
            replacement.setProgressPct(0);
            replacement.setProcessedCount(0L);
            replacement.setRetryCount(0);
            replacement.setUpdatedAt(Instant.now());
            replacementSegments.add(replacement);

            nextSegmentIndex++;
            cursor = segmentEnd;
        }

        backfillSegmentRepository.saveAll(replacementSegments);
        backfillSegmentRepository.deleteById(segment.getId());
        log.info(
                "Repartitioned oversized Bybit segment: id={}, stream={}, replacements={}",
                segment.getId(),
                stream,
                replacementSegments.size()
        );
        updateIntegrationSyncState(session, integration, null);
    }

    static EffectiveWindow resolveEffectiveWindow(
            BackfillSegment segment,
            Instant anchor,
            int historyYears,
            int safetyMinutes
    ) {
        if (segment == null || segment.getFromTime() == null || segment.getToTime() == null) {
            return null;
        }
        Instant safeAnchor = (anchor == null ? Instant.now() : anchor).truncatedTo(ChronoUnit.SECONDS);
        Instant providerLowerBound = safeAnchor
                .minus(historyYears * 365L, ChronoUnit.DAYS)
                .plus(safetyMinutes, ChronoUnit.MINUTES);
        Instant effectiveFrom = segment.getFromTime();
        Instant effectiveTo = segment.getToTime();
        if (!effectiveTo.isAfter(providerLowerBound)) {
            return null;
        }
        if (effectiveFrom.isBefore(providerLowerBound)) {
            effectiveFrom = providerLowerBound;
        }
        if (!effectiveFrom.isBefore(effectiveTo)) {
            return null;
        }
        return new EffectiveWindow(effectiveFrom, effectiveTo);
    }

    private void completeSegment(
            BackfillSegment segment,
            UserSession session,
            UserSession.SessionIntegration integration,
            long processedCount
    ) {
        segment.setCursor(null);
        segment.setProcessedCount(processedCount);
        segment.setProgressPct(100);
        segment.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        segment.setCompletedAt(Instant.now());
        segment.setUpdatedAt(Instant.now());
        segment.setErrorMessage(null);
        backfillSegmentRepository.save(segment);
        updateIntegrationSyncState(session, integration, null);
        if (integration.getStatus() == UserSession.IntegrationStatus.READY) {
            sessionBackfillCompletionPublisher.maybePublishSessionCompletionBySessionId(session.getId());
        }
    }

    private Optional<UserSession.SessionIntegration> findIntegration(UserSession session, String integrationId) {
        if (session.getIntegrations() == null) {
            return Optional.empty();
        }
        return session.getIntegrations().stream()
                .filter(integration -> integrationId.equals(integration.getIntegrationId()))
                .findFirst();
    }

    private BybitCredentials readCredentials(UserSession.SessionIntegration integration) throws JsonProcessingException {
        String decrypted = sessionSecretCryptoService.decrypt(integration.getEncryptedCredentials());
        JsonNode node = objectMapper.readTree(decrypted);
        return new BybitCredentials(node.path("apiKey").asText(), node.path("apiSecret").asText());
    }

    private IntegrationRawEvent toRawEvent(
            BackfillSegment segment,
            UserSession.SessionIntegration integration,
            JsonNode row
    ) throws JsonProcessingException {
        String payloadJson = objectMapper.writeValueAsString(row);
        String ingestHash = sha256(payloadJson);
        String providerEventKey = providerEventKey(row, ingestHash);
        IntegrationRawEvent event = new IntegrationRawEvent();
        event.setId(segment.getIntegrationId() + ":" + segment.getStream() + ":" + providerEventKey);
        event.setSessionId(segment.getSessionId());
        event.setIntegrationId(segment.getIntegrationId());
        event.setProvider(segment.getProvider());
        event.setAccountRef(integration.getAccountRef());
        event.setStream(segment.getStream());
        event.setProviderEventKey(providerEventKey);
        event.setOccurredAt(extractOccurredAt(row));
        event.setFetchedAt(Instant.now());
        event.setSegmentId(segment.getId());
        event.setPayload(Document.parse(payloadJson));
        event.setIngestHash(ingestHash);
        return event;
    }

    private void updateIntegrationSyncState(UserSession session, UserSession.SessionIntegration integration, String lastError) {
        int total = (int) backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        int completed = (int) backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        int failed = (int) backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.FAILED
        );
        int progressPct = total == 0 ? 0 : (int) Math.round((double) completed * 100.0 / total);

        UserSession.IntegrationSyncState syncState = integration.getSyncState();
        if (syncState == null) {
            syncState = new UserSession.IntegrationSyncState();
            integration.setSyncState(syncState);
        }
        syncState.setTotalSegments(total);
        syncState.setCompletedSegments(completed);
        syncState.setFailedSegments(failed);
        syncState.setProgressPct(progressPct);
        integration.setLastError(lastError);
        Instant checkpoint = latestCompletedCheckpoint(integration.getIntegrationId());
        if (checkpoint != null) {
            integration.setLastSyncAt(checkpoint);
        }
        integration.setUpdatedAt(Instant.now());
        if (failed > 0 && completed < total) {
            integration.setStatus(UserSession.IntegrationStatus.ERROR);
        } else if (completed >= total && total > 0) {
            integration.setStatus(UserSession.IntegrationStatus.READY);
        } else {
            integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
        }
        session.setUpdatedAt(Instant.now());
        userSessionRepository.save(session);
        integrationSyncStatusService.update(integration, total, completed, failed, lastError);
    }

    private Instant latestCompletedCheckpoint(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return null;
        }
        return backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(integrationId).stream()
                .filter(segment -> segment.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                .map(BackfillSegment::getToTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private Instant extractOccurredAt(JsonNode row) {
        // Cycle/5 N9: include stream-specific timestamp fields so `occurredAt` is never silently null
        // for INTERNAL_TRANSFER / UNIVERSAL_TRANSFER / EARN_FLEXIBLE_SAVING rows (which use `timestamp` / `createdAt` / `updatedAt`).
        for (String field : List.of(
                "transactionTime",
                "execTime",
                "createTime",
                "updatedTime",
                "successAt",
                "completeTime",
                "time",
                "timestamp",
                "createdAt",
                "updatedAt",
                "transferDate",
                "transactionDate",
                "blockTime"
        )) {
            JsonNode candidate = row.path(field);
            if (candidate.isMissingNode() || candidate.isNull()) {
                continue;
            }
            String value = candidate.asText();
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                long epoch = Long.parseLong(value);
                return epoch < 100_000_000_000L
                        ? Instant.ofEpochSecond(epoch)
                        : Instant.ofEpochMilli(epoch);
            } catch (NumberFormatException ignored) {
                // try next field
            }
        }
        return null;
    }

    private String providerEventKey(JsonNode row, String fallback) {
        for (String field : List.of(
                "transLogId",
                "execId",
                "orderId",
                "orderLinkId",
                "tradeId",
                "transferId",
                "transferID",
                "txID",
                "withdrawID",
                "id"
        )) {
            String value = row.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // Transaction-log TRADE rows may omit stable ids; anchor on time + economic fingerprint.
        String txTime = row.path("transactionTime").asText(null);
        String symbol = row.path("symbol").asText(null);
        String type = row.path("type").asText(null);
        String change = row.path("change").asText(null);
        if (txTime != null && !txTime.isBlank() && symbol != null && type != null) {
            return "txlog:" + txTime + ":" + symbol + ":" + type + ":" + (change == null ? "" : change);
        }
        return fallback;
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash provider payload", exception);
        }
    }

    private record BybitCredentials(
            String apiKey,
            String apiSecret
    ) {
    }

    record EffectiveWindow(
            Instant fromTime,
            Instant toTime
    ) {
    }
}
