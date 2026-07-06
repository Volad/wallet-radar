package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes cost basis for non-stablecoin assets returned from Bybit trading bots.
 *
 * <p>Bot FUNDING_HISTORY events are reclassified to EXTERNAL_TRANSFER_IN/OUT by
 * {@link BybitCanonicalTransactionBuilder} and tagged with {@code BOT_TRANSFER}. Stablecoin
 * bot events are priced at $1 (peg). Non-stablecoin returns are left at PENDING_PRICE with
 * {@code BOT_TRANSFER_PENDING_COST}.</p>
 *
 * <p>This service groups all bot events into time-based sessions (14-day gap) and for sessions
 * with exactly one non-stablecoin asset, computes the cost basis from the net stablecoin
 * consumed. Mixed-asset sessions are left at PENDING_PRICE for the regular pricing pipeline
 * to apply FMV.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitBotTransferCostBasisService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final Duration SESSION_GAP = Duration.ofDays(14);
    private static final String BOT_TRANSFER_MARKER = "BOT_TRANSFER";
    private static final String BOT_TRANSFER_PENDING_COST = "BOT_TRANSFER_PENDING_COST";

    private static final Set<String> STABLECOIN_SYMBOLS = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "DAI", "FDUSD", "PYUSD", "TUSD", "USD1"
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int computeBotCostBasis() {
        List<NormalizedTransaction> allBotDocs = loadAllBotDocs();
        if (allBotDocs.isEmpty()) {
            return 0;
        }
        allBotDocs.sort(Comparator.comparing(
                NormalizedTransaction::getBlockTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        List<List<NormalizedTransaction>> sessions = splitIntoSessions(allBotDocs);

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int resolved = 0;

        for (List<NormalizedTransaction> session : sessions) {
            resolved += resolveSession(session, now, dirty);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
        }
        if (resolved > 0) {
            log.info("BYBIT_BOT_COST_BASIS sessions={} resolved={} total_bot_docs={}",
                    sessions.size(), resolved, allBotDocs.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadAllBotDocs() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("missingDataReasons").is(BOT_TRANSFER_MARKER)
        ));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private List<List<NormalizedTransaction>> splitIntoSessions(List<NormalizedTransaction> sorted) {
        List<List<NormalizedTransaction>> sessions = new ArrayList<>();
        List<NormalizedTransaction> current = new ArrayList<>();
        Instant lastTs = null;

        for (NormalizedTransaction tx : sorted) {
            Instant ts = tx.getBlockTimestamp();
            if (ts == null) {
                continue;
            }
            if (lastTs != null && Duration.between(lastTs, ts).abs().compareTo(SESSION_GAP) > 0) {
                if (!current.isEmpty()) {
                    sessions.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(tx);
            lastTs = ts;
        }
        if (!current.isEmpty()) {
            sessions.add(current);
        }
        return sessions;
    }

    private int resolveSession(List<NormalizedTransaction> session, Instant now, List<NormalizedTransaction> dirty) {
        Set<String> nonStableAssetsReturned = new LinkedHashSet<>();
        Map<String, BigDecimal> nonStableQtyReturned = new LinkedHashMap<>();
        BigDecimal stablecoinOut = BigDecimal.ZERO;
        BigDecimal stablecoinIn = BigDecimal.ZERO;

        for (NormalizedTransaction tx : session) {
            NormalizedTransaction.Flow flow = principalFlow(tx);
            if (flow == null || flow.getQuantityDelta() == null) {
                continue;
            }
            String asset = flow.getAssetSymbol();
            if (asset == null) {
                continue;
            }
            BigDecimal qty = flow.getQuantityDelta();

            if (isStablecoin(asset)) {
                if (qty.signum() < 0) {
                    stablecoinOut = stablecoinOut.add(qty.abs());
                } else if (qty.signum() > 0) {
                    stablecoinIn = stablecoinIn.add(qty);
                }
            } else {
                if (qty.signum() > 0) {
                    nonStableAssetsReturned.add(asset.toUpperCase(Locale.ROOT));
                    nonStableQtyReturned.merge(
                            asset.toUpperCase(Locale.ROOT),
                            qty,
                            BigDecimal::add
                    );
                }
            }
        }

        if (nonStableAssetsReturned.size() != 1) {
            return 0;
        }

        BigDecimal netConsumed = stablecoinOut.subtract(stablecoinIn);
        if (netConsumed.signum() <= 0) {
            return 0;
        }

        String targetAsset = nonStableAssetsReturned.iterator().next();
        BigDecimal totalReturned = nonStableQtyReturned.get(targetAsset);
        if (totalReturned == null || totalReturned.signum() <= 0) {
            return 0;
        }

        BigDecimal unitPrice = Decimal128Support.normalize(netConsumed.divide(totalReturned, MC));
        if (unitPrice == null || unitPrice.signum() <= 0) {
            return 0;
        }

        int resolved = 0;
        for (NormalizedTransaction tx : session) {
            if (!hasPendingBotCost(tx)) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(tx);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            String flowAsset = flow.getAssetSymbol();
            if (flowAsset == null || !flowAsset.toUpperCase(Locale.ROOT).equals(targetAsset)) {
                continue;
            }

            flow.setUnitPriceUsd(unitPrice);
            flow.setValueUsd(Decimal128Support.normalize(flow.getQuantityDelta().abs().multiply(unitPrice)));
            flow.setPriceSource(PriceSource.BOT_LEDGER);

            tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
            tx.setConfirmedAt(now);
            tx.getMissingDataReasons().remove(BOT_TRANSFER_PENDING_COST);
            tx.setUpdatedAt(now);

            dirty.add(tx);
            resolved++;
        }
        return resolved;
    }

    private static boolean hasPendingBotCost(NormalizedTransaction tx) {
        return tx != null
                && tx.getMissingDataReasons() != null
                && tx.getMissingDataReasons().contains(BOT_TRANSFER_PENDING_COST);
    }

    private static NormalizedTransaction.Flow principalFlow(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
                return flow;
            }
        }
        return null;
    }

    private static boolean isStablecoin(String symbol) {
        return symbol != null && STABLECOIN_SYMBOLS.contains(symbol.trim().toUpperCase(Locale.ROOT));
    }
}
