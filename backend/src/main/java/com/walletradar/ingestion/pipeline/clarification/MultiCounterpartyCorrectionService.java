package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * WS-3b: idempotent correction for legacy {@code EXTERNAL_TRANSFER_OUT/IN} rows whose
 * {@code counterpartyAddress=MULTI} was caused by a FEE leg being counted as a principal
 * counterparty (root cause fixed by ADR-032 / WS-3a FEE exclusion).
 *
 * <p>Two phases run in order:</p>
 * <ol>
 *   <li><b>External-recipient de-MULTI</b> — resolves the single concrete counterparty from
 *       non-FEE flows and stamps it on the transaction. For non-own-wallet recipients the type
 *       stays {@code EXTERNAL_TRANSFER_OUT}; the concrete address replaces MULTI.
 *       Conservation-neutral: no basis change, no PnL change.</li>
 *   <li><b>Aggregator/DEX swap retype</b> — corrects {@code EXTERNAL_TRANSFER_OUT} rows whose
 *       concrete {@code counterpartyAddress} matches a known DEX aggregator and which have
 *       both inbound and outbound non-FEE flows. Retypes to {@code SWAP}.
 *       This phase runs <em>after</em> phase 1 so the concrete address is already stamped.</li>
 * </ol>
 *
 * <p>Idempotency: the de-MULTI query matches on {@code counterpartyAddress=MULTI} which will no
 * longer match after correction. The swap-retype query matches on a non-MULTI concrete address
 * with type=EXTERNAL_TRANSFER_OUT, so double-run = 0 mutations on both phases.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiCounterpartyCorrectionService {

    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-f0-9]{40}$");

    /**
     * Known DEX/aggregator counterparty addresses that indicate a swap mis-typed as
     * EXTERNAL_TRANSFER_OUT.  Only include addresses where both inbound and outbound flows are
     * expected (genuine swap shape). Curated — do NOT add bridge routers here.
     */
    private static final Set<String> KNOWN_SWAP_AGGREGATOR_ADDRESSES = Set.of(
            // Uniswap UniversalRouter — execute(bytes,bytes[],uint256)
            "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad",
            "0xef1c6e67703c7bd7107eed8303fbe6ec2554bf6b"
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    /**
     * Phase 1: stamp concrete counterparty on EXTERNAL_TRANSFER_OUT/IN rows with MULTI.
     * Own-wallet subset is handled by {@link OwnWalletBridgeMistypeCorrectionService}
     * ({@code reclassifyMultiCpOwnWalletTransfers}) which must run before this phase.
     */
    public int deMultiExternalTransfers(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                ),
                Criteria.where("counterpartyAddress").is(FlowCounterpartySupport.MULTI_COUNTERPARTY)
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (deMultiIfSingleConcreteCounterparty(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("MULTI_CP_EXTERNAL_DE_MULTI candidates={} updated={}", candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    /**
     * Phase 2: retype EXTERNAL_TRANSFER_OUT rows whose concrete counterparty is a known DEX
     * aggregator and which have both inbound and outbound non-FEE flows (swap shape).
     * Must run after {@link #deMultiExternalTransfers} so concrete address is already stamped.
     */
    public int retypeAggregatorSwapMistypes(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("counterpartyAddress").in(KNOWN_SWAP_AGGREGATOR_ADDRESSES)
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (retypeToSwapIfSwapShape(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("AGGREGATOR_SWAP_RETYPE candidates={} updated={}", candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    private boolean deMultiIfSingleConcreteCounterparty(NormalizedTransaction transaction, Instant now) {
        String resolvedCp = OwnWalletBridgeMistypeCorrectionService.resolveSingleConcreteCounterparty(transaction);
        if (!isHexAddress(resolvedCp)) {
            return false;
        }
        transaction.setCounterpartyAddress(resolvedCp);
        transaction.setUpdatedAt(now);
        return true;
    }

    private boolean retypeToSwapIfSwapShape(NormalizedTransaction transaction, Instant now) {
        if (transaction.getType() == NormalizedTransactionType.SWAP) {
            return false;
        }
        if (!hasInboundAndOutboundFlows(transaction)) {
            return false;
        }
        transaction.setType(NormalizedTransactionType.SWAP);
        transaction.setUpdatedAt(now);
        return true;
    }

    private boolean hasInboundAndOutboundFlows(NormalizedTransaction transaction) {
        if (transaction.getFlows() == null) {
            return false;
        }
        boolean hasIn = false;
        boolean hasOut = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            BigDecimal delta = flow.getQuantityDelta();
            if (delta == null || delta.signum() == 0) {
                continue;
            }
            if (delta.signum() > 0) {
                hasIn = true;
            } else {
                hasOut = true;
            }
        }
        return hasIn && hasOut;
    }

    private static boolean isHexAddress(String value) {
        return value != null
                && !value.isBlank()
                && HEX_ADDRESS.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
