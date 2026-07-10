package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.session.application.SessionSecretCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches authoritative Dzengi balances live and persists/caches them per integration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DzengiLiveBalanceService {

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final DzengiApiClient dzengiApiClient;
    private final UserSessionRepository userSessionRepository;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final ObjectMapper objectMapper;
    private final MongoOperations mongoOperations;

    private final Map<String, CachedSnapshot> memoryCache = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, BigDecimal> getUmbrellaBalances(String integrationId) {
        DzengiApiClient.LiveDzengiBalances snapshot = getOrFetch(integrationId).orElse(null);
        return snapshot == null ? Map.of() : Collections.unmodifiableMap(snapshot.umbrella());
    }

    public enum LiveSnapshotAvailability {
        UNKNOWN,
        KNOWN_EMPTY,
        KNOWN_NON_EMPTY
    }

    public record LiveSnapshotView(
            LiveSnapshotAvailability availability,
            Map<String, BigDecimal> umbrella,
            Instant fetchedAt
    ) {
    }

    public Optional<LiveSnapshotView> getSnapshotView(String integrationId) {
        return getOrFetch(integrationId).map(snapshot -> {
            if (isEmptyUmbrellaTombstone(integrationId, snapshot)) {
                return new LiveSnapshotView(LiveSnapshotAvailability.KNOWN_EMPTY, Map.of(), snapshot.fetchedAt());
            }
            if (snapshot.umbrella() == null || snapshot.umbrella().isEmpty()) {
                return new LiveSnapshotView(LiveSnapshotAvailability.UNKNOWN, Map.of(), snapshot.fetchedAt());
            }
            return new LiveSnapshotView(
                    LiveSnapshotAvailability.KNOWN_NON_EMPTY,
                    Collections.unmodifiableMap(snapshot.umbrella()),
                    snapshot.fetchedAt()
            );
        });
    }

    public Optional<DzengiApiClient.LiveDzengiBalances> getOrFetch(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        CachedSnapshot cached = memoryCache.get(integrationId);
        Instant now = Instant.now();
        if (cached != null && cached.fetchedAt.isAfter(now.minus(SNAPSHOT_TTL))) {
            return Optional.of(cached.snapshot);
        }
        return loadPersisted(integrationId);
    }

    public Optional<DzengiApiClient.LiveDzengiBalances> refresh(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        return refreshFromApi(integrationId, Instant.now());
    }

    private Optional<DzengiApiClient.LiveDzengiBalances> refreshFromApi(String integrationId, Instant now) {
        Optional<DzengiCredentials> credentials = resolveCredentials(integrationId);
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        try {
            DzengiApiClient.LiveDzengiBalances snapshot = dzengiApiClient.fetchLiveBalances(
                    credentials.get().apiKey(),
                    credentials.get().apiSecret()
            );
            memoryCache.put(integrationId, new CachedSnapshot(snapshot, now));
            persistSnapshot(integrationId, snapshot);
            return Optional.of(snapshot);
        } catch (RuntimeException ex) {
            log.warn(
                    "Dzengi live snapshot refresh failed for integration {}: {}",
                    integrationId,
                    ex.getMessage()
            );
            return Optional.empty();
        }
    }

    private boolean isEmptyUmbrellaTombstone(String integrationId, DzengiApiClient.LiveDzengiBalances snapshot) {
        if (snapshot.umbrella() != null && !snapshot.umbrella().isEmpty()) {
            return false;
        }
        return mongoOperations.exists(
                Query.query(Criteria.where("integrationId").is(integrationId)
                        .and("assetSymbol").is(DzengiLiveBalance.EMPTY_UMBRELLA_SYMBOL)),
                DzengiLiveBalance.class
        );
    }

    private Optional<DzengiApiClient.LiveDzengiBalances> loadPersisted(String integrationId) {
        List<DzengiLiveBalance> persisted = mongoOperations.find(
                Query.query(Criteria.where("integrationId").is(integrationId)),
                DzengiLiveBalance.class
        );
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BigDecimal> umbrella = new LinkedHashMap<>();
        Instant fetchedAt = Instant.EPOCH;
        boolean emptyTombstone = false;
        for (DzengiLiveBalance row : persisted) {
            String symbol = row.getAssetSymbol();
            if (DzengiLiveBalance.EMPTY_UMBRELLA_SYMBOL.equals(symbol)) {
                emptyTombstone = true;
                if (row.getFetchedAt() != null && row.getFetchedAt().isAfter(fetchedAt)) {
                    fetchedAt = row.getFetchedAt();
                }
                continue;
            }
            if (row.getUmbrellaQty() != null && row.getUmbrellaQty().signum() > 0) {
                umbrella.merge(symbol, row.getUmbrellaQty(), BigDecimal::add);
            }
            if (row.getFetchedAt() != null && row.getFetchedAt().isAfter(fetchedAt)) {
                fetchedAt = row.getFetchedAt();
            }
        }
        if (emptyTombstone && umbrella.isEmpty()) {
            return Optional.of(new DzengiApiClient.LiveDzengiBalances(umbrella, fetchedAt));
        }
        if (umbrella.isEmpty() && !emptyTombstone) {
            return Optional.empty();
        }
        return Optional.of(new DzengiApiClient.LiveDzengiBalances(umbrella, fetchedAt));
    }

    private void persistSnapshot(String integrationId, DzengiApiClient.LiveDzengiBalances snapshot) {
        mongoOperations.remove(
                Query.query(Criteria.where("integrationId").is(integrationId)),
                DzengiLiveBalance.class
        );
        if (snapshot.umbrella().isEmpty()) {
            DzengiLiveBalance tombstone = new DzengiLiveBalance();
            tombstone.setId(DzengiLiveBalance.key(integrationId, DzengiLiveBalance.EMPTY_UMBRELLA_SYMBOL));
            tombstone.setIntegrationId(integrationId);
            tombstone.setAssetSymbol(DzengiLiveBalance.EMPTY_UMBRELLA_SYMBOL);
            tombstone.setUmbrellaQty(BigDecimal.ZERO);
            tombstone.setFetchedAt(snapshot.fetchedAt());
            mongoOperations.save(tombstone);
            return;
        }
        for (String symbol : snapshot.umbrella().keySet()) {
            DzengiLiveBalance row = new DzengiLiveBalance();
            row.setId(DzengiLiveBalance.key(integrationId, symbol));
            row.setIntegrationId(integrationId);
            row.setAssetSymbol(symbol);
            row.setUmbrellaQty(snapshot.umbrella().get(symbol));
            row.setFetchedAt(snapshot.fetchedAt());
            mongoOperations.save(row);
        }
    }

    private Optional<DzengiCredentials> resolveCredentials(String integrationId) {
        for (UserSession session : userSessionRepository.findAll()) {
            if (session.getIntegrations() == null) {
                continue;
            }
            for (UserSession.SessionIntegration integration : session.getIntegrations()) {
                if (integration == null
                        || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                        || !integrationId.equals(integration.getIntegrationId())
                        || integration.getEncryptedCredentials() == null) {
                    continue;
                }
                try {
                    String decrypted = sessionSecretCryptoService.decrypt(integration.getEncryptedCredentials());
                    JsonNode node = objectMapper.readTree(decrypted);
                    String apiKey = node.path("apiKey").asText();
                    String apiSecret = node.path("apiSecret").asText();
                    if (apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank()) {
                        return Optional.of(new DzengiCredentials(apiKey, apiSecret));
                    }
                } catch (JsonProcessingException ex) {
                    log.warn("Failed to decode Dzengi credentials for integration {}: {}", integrationId, ex.getMessage());
                } catch (RuntimeException ex) {
                    log.warn("Failed to decrypt Dzengi credentials for integration {}: {}", integrationId, ex.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    public static String umbrellaWalletRef(String walletRef) {
        if (walletRef == null) {
            return null;
        }
        String normalized = walletRef.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("dzengi:")) {
            return null;
        }
        String[] parts = normalized.split(":", -1);
        if (parts.length < 2) {
            return null;
        }
        return parts[0] + ":" + parts[1];
    }

    private record DzengiCredentials(String apiKey, String apiSecret) {
    }

    private record CachedSnapshot(DzengiApiClient.LiveDzengiBalances snapshot, Instant fetchedAt) {
    }
}
