package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.backfill.job.BackfillSegmentExecutor;
import com.walletradar.application.backfill.job.SessionBackfillCompletionPublisher;
import com.walletradar.application.cex.config.DzengiIntegrationProperties;
import com.walletradar.application.cex.config.IntegrationBackfillProperties;
import com.walletradar.application.session.application.IntegrationSyncStatusService;
import com.walletradar.application.session.application.SessionSecretCryptoService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEventRepository;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes shared integration backfill segments for Dzengi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DzengiBackfillSegmentExecutor implements BackfillSegmentExecutor {

    private final BackfillSegmentRepository backfillSegmentRepository;
    private final UserSessionRepository userSessionRepository;
    private final IntegrationRawEventRepository integrationRawEventRepository;
    private final DzengiExtractedEventRepository dzengiExtractedEventRepository;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final IntegrationBackfillProperties integrationBackfillProperties;
    private final DzengiIntegrationProperties dzengiIntegrationProperties;
    private final DzengiApiClient dzengiApiClient;
    private final DzengiExtractionService dzengiExtractionService;
    private final DzengiSymbolMetadataCache symbolMetadataCache;
    private final SessionBackfillCompletionPublisher sessionBackfillCompletionPublisher;
    private final IntegrationSyncStatusService integrationSyncStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(BackfillSegment segment) {
        return segment != null
                && segment.getSourceKind() == BackfillSegment.SourceKind.INTEGRATION
                && UserSession.IntegrationProvider.DZENGI.name().equalsIgnoreCase(segment.getProvider());
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
            DzengiCredentials credentials = readCredentials(integration);
            String stream = segment.getStream();
            long processedCount = 0L;

            if (DzengiIntegrationStream.EXCHANGE_INFO.name().equals(stream)) {
                symbolMetadataCache.refreshAll();
                DzengiApiClient.DzengiPage page = dzengiApiClient.fetchStream(
                        DzengiIntegrationStream.EXCHANGE_INFO, null, credentials.apiKey(), credentials.apiSecret(),
                        segment.getFromTime(), segment.getToTime());
                for (JsonNode row : page.asList()) {
                    IntegrationRawEvent rawEvent = toRawEvent(segment, integration, row, "symbol:" + row.path("symbol").asText());
                    integrationRawEventRepository.save(rawEvent);
                    processedCount++;
                }
            } else if (stream != null && stream.startsWith("MY_TRADES_V2:")) {
                String symbol = stream.substring("MY_TRADES_V2:".length());
                DzengiApiClient.DzengiPage page = dzengiApiClient.fetchStream(
                        DzengiIntegrationStream.MY_TRADES_V2, symbol, credentials.apiKey(), credentials.apiSecret(),
                        segment.getFromTime(), segment.getToTime());
                for (JsonNode row : page.asList()) {
                    IntegrationRawEvent rawEvent = toRawEvent(segment, integration, row, text(row, "id"));
                    integrationRawEventRepository.save(rawEvent);
                    List<DzengiExtractedEvent> extracted = dzengiExtractionService.extract(rawEvent);
                    if (!extracted.isEmpty()) {
                        dzengiExtractedEventRepository.saveAll(extracted);
                    }
                    processedCount++;
                }
            } else if (stream != null && stream.startsWith("MY_TRADES:")) {
                String symbol = stream.substring("MY_TRADES:".length());
                DzengiApiClient.DzengiPage page = dzengiApiClient.fetchStream(
                        DzengiIntegrationStream.MY_TRADES, symbol, credentials.apiKey(), credentials.apiSecret(),
                        segment.getFromTime(), segment.getToTime());
                for (JsonNode row : page.asList()) {
                    IntegrationRawEvent rawEvent = toRawEvent(segment, integration, row, text(row, "id"));
                    integrationRawEventRepository.save(rawEvent);
                    List<DzengiExtractedEvent> extracted = dzengiExtractionService.extract(rawEvent);
                    if (!extracted.isEmpty()) {
                        dzengiExtractedEventRepository.saveAll(extracted);
                    }
                    processedCount++;
                }
            } else {
                DzengiIntegrationStream integrationStream = DzengiIntegrationStream.valueOf(stream);
                DzengiApiClient.DzengiPage page = dzengiApiClient.fetchStream(
                        integrationStream, null, credentials.apiKey(), credentials.apiSecret(),
                        segment.getFromTime(), segment.getToTime());
                for (JsonNode row : page.asList()) {
                    IntegrationRawEvent rawEvent = toRawEvent(segment, integration, row, providerEventKey(row, integrationStream));
                    integrationRawEventRepository.save(rawEvent);
                    List<DzengiExtractedEvent> extracted = dzengiExtractionService.extract(rawEvent);
                    if (!extracted.isEmpty()) {
                        dzengiExtractedEventRepository.saveAll(extracted);
                    }
                    processedCount++;
                }
            }

            completeSegment(segment, session, integration, processedCount);
        } catch (Exception exception) {
            log.warn("Dzengi integration segment failed: id={}, stream={}, error={}",
                    segment.getId(), segment.getStream(), exception.getMessage());
            segment.setStatus(BackfillSegment.SegmentStatus.FAILED);
            segment.setErrorMessage(exception.getMessage());
            segment.setRetryCount((segment.getRetryCount() == null ? 0 : segment.getRetryCount()) + 1);
            segment.setUpdatedAt(Instant.now());
            backfillSegmentRepository.save(segment);
            userSessionRepository.findById(segment.getSessionId()).ifPresent(session ->
                    findIntegration(session, segment.getIntegrationId())
                            .ifPresent(integration -> updateIntegrationSyncState(session, integration, exception.getMessage())));
        }
    }

    private static String providerEventKey(JsonNode row, DzengiIntegrationStream stream) {
        if (stream == DzengiIntegrationStream.TRADING_POSITIONS_HISTORY) {
            return firstNonBlank(text(row, "positionId"), text(row, "id"));
        }
        return text(row, "id");
    }

    private void completeSegment(
            BackfillSegment segment,
            UserSession session,
            UserSession.SessionIntegration integration,
            long processedCount
    ) {
        segment.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        segment.setProcessedCount(processedCount);
        segment.setProgressPct(100);
        segment.setCompletedAt(Instant.now());
        segment.setUpdatedAt(Instant.now());
        segment.setErrorMessage(null);
        backfillSegmentRepository.save(segment);
        updateIntegrationSyncState(session, integration, null);
        sessionBackfillCompletionPublisher.maybePublishSessionCompletionBySessionId(session.getId());
    }

    private void updateIntegrationSyncState(
            UserSession session,
            UserSession.SessionIntegration integration,
            String lastError
    ) {
        long total = backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        long completed = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        long failed = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.FAILED
        );
        int progressPct = total == 0 ? 0 : (int) Math.round((double) completed * 100.0 / total);
        UserSession.IntegrationSyncState syncState = integration.getSyncState();
        if (syncState == null) {
            syncState = new UserSession.IntegrationSyncState();
            integration.setSyncState(syncState);
        }
        syncState.setTotalSegments((int) total);
        syncState.setCompletedSegments((int) completed);
        syncState.setFailedSegments((int) failed);
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
        integrationSyncStatusService.update(integration, (int) total, (int) completed, (int) failed, lastError);
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

    private IntegrationRawEvent toRawEvent(
            BackfillSegment segment,
            UserSession.SessionIntegration integration,
            JsonNode row,
            String eventKey
    ) throws JsonProcessingException {
        IntegrationRawEvent rawEvent = new IntegrationRawEvent();
        String providerEventKey = eventKey == null || eventKey.isBlank() ? hashRow(row) : eventKey;
        rawEvent.setId(segment.getIntegrationId() + ":" + segment.getStream() + ":" + providerEventKey);
        rawEvent.setSessionId(segment.getSessionId());
        rawEvent.setIntegrationId(segment.getIntegrationId());
        rawEvent.setProvider(UserSession.IntegrationProvider.DZENGI.name());
        rawEvent.setAccountRef(integration.getAccountRef());
        rawEvent.setStream(segment.getStream());
        rawEvent.setProviderEventKey(providerEventKey);
        rawEvent.setOccurredAt(Instant.ofEpochMilli(row.path("timestamp").asLong(row.path("time").asLong(0L))));
        rawEvent.setFetchedAt(Instant.now());
        rawEvent.setSegmentId(segment.getId());
        rawEvent.setPayload(Document.parse(objectMapper.writeValueAsString(row)));
        rawEvent.setIngestHash(hashRow(row));
        return rawEvent;
    }

    private DzengiCredentials readCredentials(UserSession.SessionIntegration integration) throws JsonProcessingException {
        String json = sessionSecretCryptoService.decrypt(integration.getEncryptedCredentials());
        JsonNode node = objectMapper.readTree(json);
        return new DzengiCredentials(node.path("apiKey").asText(), node.path("apiSecret").asText());
    }

    private Optional<UserSession.SessionIntegration> findIntegration(UserSession session, String integrationId) {
        if (session.getIntegrations() == null) {
            return Optional.empty();
        }
        return session.getIntegrations().stream()
                .filter(i -> integrationId.equals(i.getIntegrationId()))
                .findFirst();
    }

    private static String hashRow(JsonNode row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(row.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return String.valueOf(row.hashCode());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private record DzengiCredentials(String apiKey, String apiSecret) {
    }
}
