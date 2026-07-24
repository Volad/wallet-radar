package com.walletradar.platform.networks.solana.helius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.NetworkAdapter;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Solana {@link NetworkAdapter} backed by the Helius Enhanced Transactions REST API.
 *
 * <p>Fetches parsed DeFi-aware transaction history for a wallet address. The full Helius
 * parsed payload (type, source, events, tokenTransfers, nativeTransfers, accountData)
 * is stored in {@code RawTransaction.rawData} so the normalization stage can classify
 * by program ID without raw instruction decoding.</p>
 *
 * <p>Registered with {@link Order}(1) to take precedence over {@code SolanaNetworkAdapter}
 * when Helius is configured; falls back to base adapter when Helius is not configured.</p>
 *
 * <p>Block range params (fromBlock / toBlock) are reinterpreted as max transaction count,
 * consistent with the base {@code SolanaNetworkAdapter} signature-paging model.</p>
 */
@Component
@Order(1)
@Slf4j
public class HeliusSolanaNetworkAdapter implements NetworkAdapter {

    private static final int DEFAULT_PAGE_SIZE = 100;

    /** SPL Token program — owns classic token accounts (ATAs) that receive SPL transfers. */
    static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    /** SPL Token-2022 program — newer token accounts, resolved and merged alongside classic ATAs. */
    static final String TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

    /** Max signatures per getSignaturesForAddress page (Solana RPC limit 1–1000). */
    private static final int SIGNATURES_PAGE_LIMIT = 1000;
    /** Helius batch-parse cap (max 100 signatures per parseTransactions call). */
    private static final int PARSE_BATCH_SIZE = 100;
    /** Backfill horizon: never walk ATA history older than 2 years (matches the 2y window rule). */
    private static final long BACKFILL_HORIZON_SECONDS = Duration.ofDays(730).getSeconds();

    private final HeliusSolanaClient heliusClient;
    private final HeliusSolanaProperties properties;
    private final ObjectMapper objectMapper;
    private final SolanaRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final HeliusRequestThrottle throttle;

    public HeliusSolanaNetworkAdapter(HeliusSolanaClient heliusClient,
                                      HeliusSolanaProperties properties,
                                      ObjectMapper objectMapper,
                                      SolanaRpcClient rpcClient,
                                      @Qualifier("solanaRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
                                      @Qualifier("solanaDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
                                      HeliusRequestThrottle throttle) {
        this.heliusClient = heliusClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.throttle = throttle;
    }

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.SOLANA && properties.isConfigured();
    }

    @Override
    public int getMaxBlockBatchSize() {
        return DEFAULT_PAGE_SIZE;
    }

    @Override
    public boolean supportsBlockCheckpointing() {
        // Solana uses signature-based pagination, not slot ranges
        return false;
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        if (networkId != NetworkId.SOLANA) {
            return List.of();
        }
        long maxTxs = (toBlock > 0 && fromBlock >= 0) ? (toBlock - fromBlock + 1) : Long.MAX_VALUE;
        List<RawTransaction> all = new ArrayList<>();
        // Signatures already captured, so ATA-derived history is deduped against owner history.
        Set<String> seenSignatures = new LinkedHashSet<>();

        fetchOwnerHistory(walletAddress, maxTxs, all, seenSignatures);
        fetchTokenAccountInboundHistory(walletAddress, maxTxs, all, seenSignatures);

        return all;
    }

    /**
     * Pages the owner's parsed history via Helius {@code /addresses/{owner}/transactions}
     * (owner {@code getSignaturesForAddress}). This misses SPL transfers where the owner is only
     * the recipient (its ATA, not the owner pubkey, is in the account keys); see
     * {@link #fetchTokenAccountInboundHistory}.
     *
     * <p><b>No-complete-on-partial-fetch:</b> a mid-stream {@link RpcException} is rethrown so the
     * backfill segment is marked FAILED and retried, rather than returning partial results (which
     * would let the checkpoint advance past un-fetched history and create permanent gaps). A natural
     * end (empty or short page) returns cleanly.</p>
     */
    private void fetchOwnerHistory(String walletAddress, long maxTxs,
                                   List<RawTransaction> all, Set<String> seenSignatures) {
        String before = null;
        int pageSize = DEFAULT_PAGE_SIZE;
        while (all.size() < maxTxs) {
            List<JsonNode> page;
            try {
                page = heliusClient.getTransactionHistory(walletAddress, before, pageSize);
            } catch (RpcException e) {
                log.warn("Helius owner history fetch failed for {} after {} txs; failing segment for retry: {}",
                        walletAddress, all.size(), e.getMessage());
                throw e;
            }
            if (page.isEmpty()) {
                break;
            }
            for (JsonNode parsed : page) {
                if (all.size() >= maxTxs) {
                    break;
                }
                addRaw(parsed, walletAddress, all, seenSignatures);
            }
            if (page.size() < pageSize) {
                break;
            }
            // Last signature on the page becomes the "before" cursor for the next page
            JsonNode lastTx = page.get(page.size() - 1);
            before = lastTx.path("signature").asText(null);
            if (before == null || before.isBlank()) {
                break;
            }
        }
    }

    /**
     * Captures inbound SPL transfers addressed to the wallet's token accounts (ATAs), which owner
     * history never returns. Resolves the wallet's SPL / Token-2022 token accounts, pages each ATA's
     * signatures back to the 2-year horizon, dedups against already-seen signatures, then re-enriches
     * the new signatures through the same Helius parse endpoint so the {@code rawData} shape matches
     * owner history.
     *
     * <p><b>Guard:</b> if token-account resolution fails, log a warning and fall back to
     * owner-history-only (do not fail the whole fetch). Per-ATA signature paging and batch parsing,
     * however, propagate {@link RpcException} (no-complete-on-partial-fetch).</p>
     */
    private void fetchTokenAccountInboundHistory(String walletAddress, long maxTxs,
                                                 List<RawTransaction> all, Set<String> seenSignatures) {
        if (all.size() >= maxTxs) {
            return;
        }
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(NetworkId.SOLANA.name(), defaultRotator);
        List<String> tokenAccounts;
        try {
            tokenAccounts = resolveTokenAccountPubkeys(rotator, walletAddress);
        } catch (RpcException e) {
            log.warn("getTokenAccountsByOwner failed for {}; falling back to owner-history only: {}",
                    walletAddress, e.getMessage());
            return;
        }
        if (tokenAccounts.isEmpty()) {
            return;
        }

        long horizon = Instant.now().getEpochSecond() - BACKFILL_HORIZON_SECONDS;
        Set<String> newSignatures = new LinkedHashSet<>();
        for (String ata : tokenAccounts) {
            collectAtaSignatures(rotator, ata, horizon, newSignatures, seenSignatures);
        }
        if (newSignatures.isEmpty()) {
            return;
        }

        List<String> batch = new ArrayList<>(PARSE_BATCH_SIZE);
        for (String signature : newSignatures) {
            batch.add(signature);
            if (batch.size() >= PARSE_BATCH_SIZE) {
                enrichAndCollect(batch, walletAddress, maxTxs, all, seenSignatures);
                batch.clear();
                if (all.size() >= maxTxs) {
                    return;
                }
            }
        }
        if (!batch.isEmpty()) {
            enrichAndCollect(batch, walletAddress, maxTxs, all, seenSignatures);
        }
    }

    /**
     * Resolves the wallet's classic SPL and Token-2022 token account pubkeys via
     * {@code getTokenAccountsByOwner}. Any RPC failure propagates as {@link RpcException} to the
     * caller's fallback guard.
     */
    private List<String> resolveTokenAccountPubkeys(RpcEndpointRotator rotator, String owner) {
        LinkedHashSet<String> pubkeys = new LinkedHashSet<>();
        for (String programId : List.of(TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID)) {
            JsonNode result = callWithRetry(rotator, "getTokenAccountsByOwner",
                    List.of(owner, Map.of("programId", programId), Map.of("encoding", "jsonParsed")));
            JsonNode value = result.path("value");
            if (value.isArray()) {
                for (JsonNode account : value) {
                    String pubkey = account.path("pubkey").asText(null);
                    if (pubkey != null && !pubkey.isBlank()) {
                        pubkeys.add(pubkey);
                    }
                }
            }
        }
        return new ArrayList<>(pubkeys);
    }

    /**
     * Pages one token account's signatures (newest first) via {@code getSignaturesForAddress},
     * following the {@code before} cursor until the account is exhausted or a signature older than
     * the 2-year horizon is reached. Adds not-yet-seen, non-errored signatures to {@code target}.
     */
    private void collectAtaSignatures(RpcEndpointRotator rotator, String ata, long horizon,
                                      Set<String> target, Set<String> seenSignatures) {
        String before = null;
        while (true) {
            Map<String, Object> config = before == null
                    ? Map.of("limit", SIGNATURES_PAGE_LIMIT)
                    : Map.of("limit", SIGNATURES_PAGE_LIMIT, "before", before);
            JsonNode result = callWithRetry(rotator, "getSignaturesForAddress", List.of(ata, config));
            if (!result.isArray() || result.isEmpty()) {
                return;
            }
            boolean horizonReached = false;
            for (JsonNode sigInfo : result) {
                long blockTime = sigInfo.path("blockTime").asLong(0L);
                if (blockTime > 0 && blockTime < horizon) {
                    horizonReached = true;
                    break;
                }
                String signature = sigInfo.path("signature").asText(null);
                if (signature == null || signature.isBlank()) {
                    continue;
                }
                boolean failed = !sigInfo.path("err").isNull() && !sigInfo.path("err").isMissingNode();
                if (!failed && !seenSignatures.contains(signature)) {
                    target.add(signature);
                }
            }
            if (horizonReached || result.size() < SIGNATURES_PAGE_LIMIT) {
                return;
            }
            JsonNode last = result.get(result.size() - 1);
            before = last.path("signature").asText(null);
            if (before == null || before.isBlank()) {
                return;
            }
        }
    }

    /**
     * Re-enriches a batch of ATA signatures through the Helius parse endpoint and appends the
     * resulting raw transactions (deduped by signature). A {@link RpcException} propagates.
     */
    private void enrichAndCollect(List<String> signatures, String walletAddress, long maxTxs,
                                  List<RawTransaction> all, Set<String> seenSignatures) {
        List<JsonNode> parsed = heliusClient.parseTransactions(signatures);
        for (JsonNode node : parsed) {
            if (all.size() >= maxTxs) {
                return;
            }
            addRaw(node, walletAddress, all, seenSignatures);
        }
    }

    /**
     * Builds a raw transaction from a parsed node and appends it if its signature has not already
     * been collected. Keeps {@code seenSignatures} authoritative for cross-source dedup.
     */
    private void addRaw(JsonNode parsed, String walletAddress,
                        List<RawTransaction> all, Set<String> seenSignatures) {
        RawTransaction raw = buildRaw(parsed, walletAddress);
        if (raw == null) {
            return;
        }
        if (seenSignatures.add(raw.getTxHash())) {
            all.add(raw);
        }
    }

    /**
     * Single JSON-RPC call with endpoint rotation and retry, mirroring
     * {@code SolanaNetworkAdapter}. Returns the {@code result} node; throws {@link RpcException} on a
     * JSON-RPC error or after exhausting all attempts.
     */
    private JsonNode callWithRetry(RpcEndpointRotator rotator, String method, Object params) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during Solana RPC retry", e);
                }
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                // Share the Helius rate budget with the Enhanced-API client: these RPC calls
                // (getTokenAccountsByOwner / getSignaturesForAddress) hit the Helius RPC URL too.
                throttle.acquire();
                String json = rpcClient.call(endpoint, method, params).block();
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode()) {
                    throw new RpcException(method + " error: " + error);
                }
                return root.path("result");
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("Solana RPC " + method + " failed after " + rotator.getMaxAttempts() + " attempts",
                lastException);
    }

    private RawTransaction buildRaw(JsonNode parsed, String walletAddress) {
        String signature = parsed.path("signature").asText(null);
        if (signature == null || signature.isBlank()) {
            return null;
        }
        // Skip failed transactions (Helius includes them; skip errors)
        if (!parsed.path("transactionError").isNull() && !parsed.path("transactionError").isMissingNode()) {
            return null;
        }
        long slot = parsed.path("slot").asLong(0L);
        long blockTime = parsed.path("timestamp").asLong(0L);

        RawTransaction tx = new RawTransaction();
        tx.setId(signature + ":" + NetworkId.SOLANA.name() + ":" + walletAddress);
        tx.setTxHash(signature);
        tx.setNetworkId(NetworkId.SOLANA.name());
        tx.setWalletAddress(walletAddress);
        tx.setSyncMethod(RawSyncMethod.RPC);
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount(0);
        tx.setCreatedAt(Instant.now());
        tx.setSlot(slot);

        // Store the full Helius parsed payload — normalization reads type/source/events/tokenTransfers etc.
        Document rawData = new Document();
        rawData.put("signature", signature);
        rawData.put("slot", slot);
        rawData.put("blockTime", blockTime);
        rawData.put("heliusParsed", jsonNodeToDocument(parsed));
        rawData.put("source", "HELIUS_ENHANCED");
        tx.setRawData(rawData);

        return tx;
    }

    private Object jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(jsonNodeToDocument(child)));
            return list;
        }
        if (node.isObject()) {
            Document doc = new Document();
            node.fields().forEachRemaining(f -> doc.put(f.getKey(), jsonNodeToDocument(f.getValue())));
            return doc;
        }
        return null;
    }
}
