package com.walletradar.integration.bybit;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
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
 * Greedy pairer for extracted Bybit trade-like staging rows.
 */
@Component
@RequiredArgsConstructor
public class BybitExtractedTradePairer {

    private static final long WINDOW_SECONDS = 5L;
    private static final long CONVERT_WINDOW_SECONDS = 2L;
    private static final long LIQUID_STAKING_WINDOW_SECONDS = 6L * 3600L;

    private final MongoOperations mongoOperations;

    public Optional<BybitExtractedEvent> findOppositeLeg(BybitExtractedEvent row) {
        if (!"uta_derivatives".equals(normalize(row.getSourceFileType()))) {
            return Optional.empty();
        }
        String direction = normalize(row.getUtaDirection());
        if (!"buy".equals(direction) && !"sell".equals(direction)) {
            return Optional.empty();
        }

        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getUtaContract() == null || row.getAssetSymbol() == null || row.getAssetSymbol().isBlank()) {
            return Optional.empty();
        }

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("status").is(BybitExtractedEventStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("utaContract").is(row.getUtaContract()),
                Criteria.where("utaDirection").is(row.getUtaDirection()),
                Criteria.where("assetSymbol").ne(row.getAssetSymbol()),
                Criteria.where("quantityRaw").exists(true),
                Criteria.where("timeUtc").gte(center.minusSeconds(WINDOW_SECONDS)).lte(center.plusSeconds(WINDOW_SECONDS))
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<BybitExtractedEvent> candidates = mongoOperations.find(query, BybitExtractedEvent.class);
        Comparator<BybitExtractedEvent> comparator = Comparator
                .comparingDouble((BybitExtractedEvent candidate) -> tradeScore(row, candidate))
                .thenComparingLong((BybitExtractedEvent candidate) -> Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                .thenComparing(BybitExtractedEvent::getTimeUtc)
                .thenComparing(BybitExtractedEvent::getId);
        return candidates.stream().min(comparator);
    }

    public List<BybitExtractedEvent> loadConvertCluster(BybitExtractedEvent row) {
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null) {
            return List.of(row);
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(BybitExtractedEventStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").is(row.getBybitType()),
                Criteria.where("timeUtc").gte(center.minusSeconds(CONVERT_WINDOW_SECONDS)).lte(center.plusSeconds(CONVERT_WINDOW_SECONDS))
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        return mongoOperations.find(query, BybitExtractedEvent.class);
    }

    public Optional<BybitExtractedEvent> findLiquidStakingCounterLeg(BybitExtractedEvent row) {
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return Optional.empty();
        }
        Query query = new Query(liquidStakingCriteria(row, center));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<BybitExtractedEvent> candidates = mongoOperations.find(query, BybitExtractedEvent.class);
        return candidates.stream()
                .filter(candidate -> candidate.getQuantityRaw() != null && candidate.getQuantityRaw().signum() == -row.getQuantityRaw().signum())
                .filter(candidate -> sameLiquidStakingFamily(row, candidate))
                .min(Comparator
                        .comparingInt((BybitExtractedEvent candidate) -> exactAbsQuantityMatch(row, candidate) ? 0 : 1)
                        .thenComparingDouble(candidate -> liquidStakingMagnitudePenalty(row, candidate))
                        .thenComparingLong((BybitExtractedEvent candidate) -> Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing(BybitExtractedEvent::getTimeUtc)
                        .thenComparing(BybitExtractedEvent::getId));
    }

    private Criteria liquidStakingCriteria(BybitExtractedEvent row, Instant center) {
        List<Criteria> criteria = new java.util.ArrayList<>(List.of(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("status").is(BybitExtractedEventStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").is(row.getBybitType()),
                Criteria.where("assetSymbol").ne(row.getAssetSymbol()),
                Criteria.where("quantityRaw").exists(true),
                Criteria.where("timeUtc").gte(center.minusSeconds(LIQUID_STAKING_WINDOW_SECONDS))
                        .lte(center.plusSeconds(LIQUID_STAKING_WINDOW_SECONDS))
        ));
        if (row.getBybitDescription() != null && !row.getBybitDescription().isBlank()) {
            criteria.add(Criteria.where("bybitDescription").is(row.getBybitDescription()));
        }
        return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
    }

    private boolean sameLiquidStakingFamily(BybitExtractedEvent left, BybitExtractedEvent right) {
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right.getAssetSymbol(), null);
        return leftFamily != null && leftFamily.equals(rightFamily);
    }

    private boolean exactAbsQuantityMatch(BybitExtractedEvent left, BybitExtractedEvent right) {
        return left.getQuantityRaw() != null
                && right.getQuantityRaw() != null
                && left.getQuantityRaw().abs().compareTo(right.getQuantityRaw().abs()) == 0;
    }

    private double liquidStakingMagnitudePenalty(BybitExtractedEvent left, BybitExtractedEvent right) {
        if (left.getQuantityRaw() == null || right.getQuantityRaw() == null) {
            return Double.MAX_VALUE;
        }
        return left.getQuantityRaw().abs().subtract(right.getQuantityRaw().abs()).abs().doubleValue();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double tradeScore(BybitExtractedEvent row, BybitExtractedEvent candidate) {
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

    private BigDecimal expectedCounterAbs(BybitExtractedEvent row) {
        if (row.getQuantityRaw() == null || row.getFilledPrice() == null) {
            return null;
        }
        return row.getQuantityRaw().abs().multiply(row.getFilledPrice().abs());
    }
}
