package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Greedy ±5 second pairer for Bybit UTA trade legs.
 */
@Component
@RequiredArgsConstructor
public class BybitTradePairer {

    private static final long WINDOW_SECONDS = 5L;
    private static final long CONVERT_WINDOW_SECONDS = 2L;
    private static final long ETH2_WINDOW_SECONDS = 3600L;

    private final MongoOperations mongoOperations;

    public Optional<ExternalLedgerRaw> findOppositeLeg(ExternalLedgerRaw row) {
        if (!"uta_derivatives".equals(normalize(row.getSourceFileType()))) {
            return Optional.empty();
        }
        String direction = normalize(row.getUtaDirection());
        if (!"buy".equals(direction) && !"sell".equals(direction)) {
            return Optional.empty();
        }

        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getUtaContract() == null) {
            return Optional.empty();
        }

        if (row.getAssetSymbol() == null || row.getAssetSymbol().isBlank()) {
            return Optional.empty();
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("utaContract").is(row.getUtaContract()),
                Criteria.where("utaDirection").is(row.getUtaDirection()),
                Criteria.where("assetSymbol").ne(row.getAssetSymbol()),
                Criteria.where("quantityRaw").exists(true),
                Criteria.where("timeUtc").gte(center.minusSeconds(WINDOW_SECONDS)).lte(center.plusSeconds(WINDOW_SECONDS))
        ));
        query.with(Sort.by(
                Sort.Order.asc("timeUtc"),
                Sort.Order.asc("_id")
        ));
        List<ExternalLedgerRaw> candidates = mongoOperations.find(query, ExternalLedgerRaw.class);
        Comparator<ExternalLedgerRaw> comparator = Comparator
                .comparingDouble((ExternalLedgerRaw candidate) -> tradeScore(row, candidate))
                        .thenComparingLong((ExternalLedgerRaw candidate) ->
                                Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing(ExternalLedgerRaw::getTimeUtc)
                        .thenComparing(ExternalLedgerRaw::getId);
        return candidates.stream()
                .min(comparator);
    }

    public List<ExternalLedgerRaw> loadConvertCluster(ExternalLedgerRaw row) {
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null) {
            return List.of(row);
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").is(row.getBybitType()),
                Criteria.where("timeUtc").gte(center.minusSeconds(CONVERT_WINDOW_SECONDS))
                        .lte(center.plusSeconds(CONVERT_WINDOW_SECONDS))
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        return mongoOperations.find(query, ExternalLedgerRaw.class);
    }

    public Optional<ExternalLedgerRaw> findEthStakingCounterLeg(ExternalLedgerRaw row) {
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return Optional.empty();
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").is(row.getBybitType()),
                Criteria.where("assetSymbol").ne(row.getAssetSymbol()),
                Criteria.where("quantityRaw").exists(true),
                Criteria.where("timeUtc").gte(center.minusSeconds(ETH2_WINDOW_SECONDS))
                        .lte(center.plusSeconds(ETH2_WINDOW_SECONDS))
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<ExternalLedgerRaw> candidates = mongoOperations.find(query, ExternalLedgerRaw.class);
        return candidates.stream()
                .filter(candidate -> candidate.getQuantityRaw() != null
                        && candidate.getQuantityRaw().signum() == -row.getQuantityRaw().signum())
                .min(Comparator
                        .comparingLong((ExternalLedgerRaw candidate) ->
                                Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing(ExternalLedgerRaw::getTimeUtc)
                        .thenComparing(ExternalLedgerRaw::getId));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double tradeScore(ExternalLedgerRaw row, ExternalLedgerRaw candidate) {
        if (candidate.getQuantityRaw() == null || row.getQuantityRaw() == null) {
            return Double.MAX_VALUE;
        }
        if (candidate.getQuantityRaw().signum() == 0 || row.getQuantityRaw().signum() == 0) {
            return Double.MAX_VALUE;
        }
        if (candidate.getQuantityRaw().signum() == row.getQuantityRaw().signum()) {
            return Double.MAX_VALUE;
        }
        double timePenalty = Math.abs(candidate.getTimeUtc().getEpochSecond() - row.getTimeUtc().getEpochSecond());
        BigDecimal candidateFromRow = expectedCounterAbs(row);
        BigDecimal rowFromCandidate = expectedCounterAbs(candidate);
        double rowDelta = candidateFromRow == null
                ? Double.MAX_VALUE
                : candidate.getQuantityRaw().abs().subtract(candidateFromRow).abs().doubleValue();
        double candidateDelta = rowFromCandidate == null
                ? Double.MAX_VALUE
                : row.getQuantityRaw().abs().subtract(rowFromCandidate).abs().doubleValue();
        double magnitudePenalty = Math.min(rowDelta, candidateDelta);
        if (Double.isInfinite(magnitudePenalty) || Double.isNaN(magnitudePenalty) || magnitudePenalty == Double.MAX_VALUE) {
            magnitudePenalty = Math.abs(candidate.getQuantityRaw().abs().subtract(row.getQuantityRaw().abs()).doubleValue());
        }
        return magnitudePenalty + timePenalty;
    }

    private BigDecimal expectedCounterAbs(ExternalLedgerRaw row) {
        if (row.getQuantityRaw() == null || row.getFilledPrice() == null) {
            return null;
        }
        return row.getQuantityRaw().abs().multiply(row.getFilledPrice().abs());
    }
}
