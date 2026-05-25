package com.walletradar.integration.bybit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.session.application.SessionSecretCryptoService;
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
 * Cycle/5 N15: fetches authoritative Bybit balances live and persists/caches them per
 * {@code integrationId}. Used by the dashboard query layer to clamp Bybit umbrella inventories so
 * phantom positions left behind by API-gap defects (e.g. Earn-product withdrawals that none of the
 * ingested streams expose for legacy accounts) cannot inflate the umbrella above the user's real
 * Bybit holdings.
 *
 * <p>Cache strategy: in-memory {@link CachedSnapshot} keyed by {@code integrationId} with a configurable
 * TTL ({@link #SNAPSHOT_TTL}). When the dashboard requests an umbrella balance map, the service either
 * returns the cached snapshot (if fresh) or re-fetches from the Bybit API and re-persists to Mongo.</p>
 *
 * <p>If credentials are unavailable, the API call fails, or the API key has been revoked, the service
 * gracefully returns the last-persisted snapshot ({@code bybit_live_balances} collection) so the
 * dashboard never blocks on a live network round-trip. When no persisted snapshot exists either, an
 * empty map is returned and clamping is effectively disabled for that integration.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BybitLiveBalanceService {

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final BybitApiClient bybitApiClient;
    private final UserSessionRepository userSessionRepository;
    private final SessionSecretCryptoService sessionSecretCryptoService;
    private final ObjectMapper objectMapper;
    private final MongoOperations mongoOperations;

    private final Map<String, CachedSnapshot> memoryCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns the umbrella balance per symbol (sum across UTA + FUND + EARN) for the supplied
     * integration, refreshing from Bybit if the cached snapshot is stale.
     *
     * @return immutable map: {@code SYMBOL → BigDecimal}; empty when no snapshot can be produced.
     */
    public Map<String, BigDecimal> getUmbrellaBalances(String integrationId) {
        BybitApiClient.LiveBybitBalances snapshot = getOrFetch(integrationId).orElse(null);
        return snapshot == null ? Map.of() : Collections.unmodifiableMap(snapshot.umbrella());
    }

    /**
     * Returns the underlying snapshot with per-sub-account detail, refreshing if stale. Empty when no
     * snapshot can be produced (no integration, no credentials, persistent API failures).
     */
    public Optional<BybitApiClient.LiveBybitBalances> getOrFetch(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        CachedSnapshot cached = memoryCache.get(integrationId);
        Instant now = Instant.now();
        if (cached != null && cached.fetchedAt.isAfter(now.minus(SNAPSHOT_TTL))) {
            return Optional.of(cached.snapshot);
        }
        Optional<BybitApiClient.LiveBybitBalances> refreshed = refreshFromApi(integrationId, now);
        if (refreshed.isPresent()) {
            return refreshed;
        }
        return loadPersisted(integrationId);
    }

    /**
     * Forces a live refresh, bypassing the in-memory TTL. Returns the refreshed snapshot or empty
     * when no integration / credentials are available.
     */
    public Optional<BybitApiClient.LiveBybitBalances> refresh(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        return refreshFromApi(integrationId, Instant.now());
    }

    private Optional<BybitApiClient.LiveBybitBalances> refreshFromApi(String integrationId, Instant now) {
        Optional<BybitCredentials> credentials = resolveCredentials(integrationId);
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        try {
            BybitApiClient.LiveBybitBalances snapshot = bybitApiClient.fetchLiveBalances(
                    credentials.get().apiKey(),
                    credentials.get().apiSecret()
            );
            memoryCache.put(integrationId, new CachedSnapshot(snapshot, now));
            persistSnapshot(integrationId, snapshot);
            return Optional.of(snapshot);
        } catch (RuntimeException ex) {
            log.warn(
                    "Bybit live snapshot refresh failed for integration {}: {}",
                    integrationId,
                    ex.getMessage()
            );
            return Optional.empty();
        }
    }

    private Optional<BybitApiClient.LiveBybitBalances> loadPersisted(String integrationId) {
        List<BybitLiveBalance> persisted = mongoOperations.find(
                Query.query(Criteria.where("integrationId").is(integrationId)),
                BybitLiveBalance.class
        );
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BigDecimal> uta = new LinkedHashMap<>();
        Map<String, BigDecimal> fund = new LinkedHashMap<>();
        Map<String, BigDecimal> earn = new LinkedHashMap<>();
        Map<String, BigDecimal> umbrella = new LinkedHashMap<>();
        Instant fetchedAt = Instant.EPOCH;
        for (BybitLiveBalance row : persisted) {
            String symbol = row.getAssetSymbol();
            if (row.getUtaQty() != null && row.getUtaQty().signum() > 0) {
                uta.merge(symbol, row.getUtaQty(), BigDecimal::add);
            }
            if (row.getFundQty() != null && row.getFundQty().signum() > 0) {
                fund.merge(symbol, row.getFundQty(), BigDecimal::add);
            }
            if (row.getEarnQty() != null && row.getEarnQty().signum() > 0) {
                earn.merge(symbol, row.getEarnQty(), BigDecimal::add);
            }
            if (row.getUmbrellaQty() != null && row.getUmbrellaQty().signum() > 0) {
                umbrella.merge(symbol, row.getUmbrellaQty(), BigDecimal::add);
            }
            if (row.getFetchedAt() != null && row.getFetchedAt().isAfter(fetchedAt)) {
                fetchedAt = row.getFetchedAt();
            }
        }
        return Optional.of(new BybitApiClient.LiveBybitBalances(uta, fund, earn, umbrella, fetchedAt));
    }

    private void persistSnapshot(String integrationId, BybitApiClient.LiveBybitBalances snapshot) {
        mongoOperations.remove(
                Query.query(Criteria.where("integrationId").is(integrationId)),
                BybitLiveBalance.class
        );
        for (String symbol : snapshot.umbrella().keySet()) {
            BybitLiveBalance row = new BybitLiveBalance();
            row.setId(BybitLiveBalance.key(integrationId, symbol));
            row.setIntegrationId(integrationId);
            row.setAssetSymbol(symbol);
            row.setUtaQty(snapshot.uta().get(symbol));
            row.setFundQty(snapshot.fund().get(symbol));
            row.setEarnQty(snapshot.earn().get(symbol));
            row.setUmbrellaQty(snapshot.umbrella().get(symbol));
            row.setFetchedAt(snapshot.fetchedAt());
            mongoOperations.save(row);
        }
    }

    private Optional<BybitCredentials> resolveCredentials(String integrationId) {
        // The same integration may live in multiple sessions; pick any session that has it READY.
        List<UserSession> sessions = userSessionRepository.findAll();
        for (UserSession session : sessions) {
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
                        return Optional.of(new BybitCredentials(apiKey, apiSecret));
                    }
                } catch (JsonProcessingException ex) {
                    log.warn("Failed to decode Bybit credentials for integration {}: {}", integrationId, ex.getMessage());
                } catch (RuntimeException ex) {
                    log.warn("Failed to decrypt Bybit credentials for integration {}: {}", integrationId, ex.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private record BybitCredentials(String apiKey, String apiSecret) {}

    private record CachedSnapshot(BybitApiClient.LiveBybitBalances snapshot, Instant fetchedAt) {}

    /**
     * Normalises a Bybit ledger walletRef ({@code BYBIT:<UID>}, {@code BYBIT:<UID>:FUND}, etc.) to its
     * umbrella identifier ({@code bybit:<UID>}) so dashboard clamping can be applied uniformly.
     */
    public static String umbrellaWalletRef(String walletRef) {
        if (walletRef == null) {
            return null;
        }
        String normalized = walletRef.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("bybit:")) {
            return null;
        }
        String[] parts = normalized.split(":", -1);
        if (parts.length < 2) {
            return null;
        }
        return parts[0] + ":" + parts[1];
    }
}
