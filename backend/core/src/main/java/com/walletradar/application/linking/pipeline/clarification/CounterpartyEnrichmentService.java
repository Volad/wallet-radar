package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.domain.wallet.OnChainAddressClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Clarification-adjacent counterparty enrichment that fills row-local counterpartyAddress from
 * persisted raw evidence.
 *
 * <p>ADR-066: resolution is delegated to a per-{@link NetworkId}-family {@link CounterpartyResolver}
 * selected inside {@link #enrichInPlace}. EVM behaviour is unchanged — it runs through
 * {@link EvmCounterpartyResolver}, which holds the former inline logic verbatim.</p>
 */
@Service
@Slf4j
public class CounterpartyEnrichmentService {

    private final CounterpartyEnrichmentQueryService queryService;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final List<CounterpartyResolver> resolvers;

    @Autowired
    public CounterpartyEnrichmentService(
            CounterpartyEnrichmentQueryService queryService,
            RawTransactionRepository rawTransactionRepository,
            NormalizedTransactionRepository normalizedTransactionRepository,
            List<CounterpartyResolver> resolvers
    ) {
        this.queryService = queryService;
        this.rawTransactionRepository = rawTransactionRepository;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.resolvers = List.copyOf(resolvers);
    }

    /**
     * Test/legacy constructor: wires the EVM resolver from a {@link CounterpartyResolutionService}
     * so existing EVM unit tests keep constructing this service with the resolution service directly.
     */
    public CounterpartyEnrichmentService(
            CounterpartyEnrichmentQueryService queryService,
            CounterpartyResolutionService resolutionService,
            RawTransactionRepository rawTransactionRepository,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        this(
                queryService,
                rawTransactionRepository,
                normalizedTransactionRepository,
                List.of(new EvmCounterpartyResolver(resolutionService))
        );
    }

    public int processNextBatch(int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);
        int updated = 0;
        String afterId = null;
        Instant now = Instant.now();
        while (updated < boundedBatchSize) {
            List<NormalizedTransaction> batch = queryService.loadBatchAfterId(afterId, boundedBatchSize);
            if (batch.isEmpty()) {
                return updated;
            }
            for (NormalizedTransaction transaction : batch) {
                afterId = transaction.getId();
                Optional<RawTransaction> rawTransaction = loadRaw(transaction);
                if (enrich(transaction, rawTransaction.orElse(null), now)) {
                    updated++;
                    if (updated >= boundedBatchSize) {
                        return updated;
                    }
                }
            }
        }
        return updated;
    }

    public boolean enrich(NormalizedTransaction normalizedTransaction, @Nullable RawTransaction rawTransaction, Instant now) {
        if (!enrichInPlace(normalizedTransaction, rawTransaction, now)) {
            return false;
        }
        normalizedTransactionRepository.save(normalizedTransaction);
        log.debug(
                "Counterparty enriched normalizedTxId={} counterpartyAddress={}",
                normalizedTransaction.getId(),
                normalizedTransaction.getCounterpartyAddress()
        );
        return true;
    }

    public boolean enrichInPlace(
            NormalizedTransaction normalizedTransaction,
            @Nullable RawTransaction rawTransaction,
            Instant now
    ) {
        if (normalizedTransaction == null) {
            return false;
        }
        CounterpartyResolver resolver = selectResolver(normalizedTransaction.getNetworkId());
        if (resolver == null) {
            return false;
        }
        return resolver.enrichInPlace(normalizedTransaction, rawTransaction, now);
    }

    private CounterpartyResolver selectResolver(@Nullable NetworkId networkId) {
        for (CounterpartyResolver resolver : resolvers) {
            if (resolver.supports(networkId)) {
                return resolver;
            }
        }
        return null;
    }

    private Optional<RawTransaction> loadRaw(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null
                || normalizedTransaction.getTxHash() == null
                || normalizedTransaction.getNetworkId() == null
                || normalizedTransaction.getWalletAddress() == null) {
            return Optional.empty();
        }
        NetworkId network = normalizedTransaction.getNetworkId();
        // ADR-066 / RC-S2: Solana (base58) and TON (base64url) identifiers are case-sensitive and must
        // never be lowercased, or the raw doc key can never be matched. EVM keeps its 0x-lowercase form
        // (byte-for-byte unchanged). Wallet addresses reuse the codebase-wide family-aware normaliser
        // (see SourceSyncPlanner.normalizedAddress).
        boolean caseSensitiveFamily = network == NetworkId.SOLANA || network == NetworkId.TON;
        String txHash = caseSensitiveFamily
                ? normalizedTransaction.getTxHash().trim()
                : normalizedTransaction.getTxHash().trim().toLowerCase(Locale.ROOT);
        String networkId = network.name();
        String walletAddress = OnChainAddressClassifier.normalize(normalizedTransaction.getWalletAddress().trim());
        String rawId = txHash + ":" + networkId + ":" + walletAddress;

        Optional<RawTransaction> exact = rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                txHash,
                networkId,
                walletAddress
        );
        if (exact != null && exact.isPresent()) {
            return exact;
        }

        Optional<RawTransaction> byId = rawTransactionRepository.findById(rawId);
        if (byId != null && byId.isPresent()) {
            return byId;
        }

        return Optional.empty();
    }
}
