package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
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
import java.util.regex.Pattern;

/**
 * Greedy ±5 second pairer for Bybit UTA trade legs.
 */
@Component
@RequiredArgsConstructor
public class BybitTradePairer {

    private static final long WINDOW_SECONDS = 5L;
    private static final long CONVERT_WINDOW_SECONDS = 2L;
    private static final long LIQUID_STAKING_WINDOW_SECONDS = 6L * 3600L;
    private static final List<String> CONVERT_TYPES = List.of("convert", "currency_buy", "currency_sell");
    private static final Pattern CONVERT_TYPE_PATTERN = Pattern.compile("^(convert|currency_buy|currency_sell)$", Pattern.CASE_INSENSITIVE);

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
        if (center == null || row.getUid() == null || !isConvertType(row.getBybitType())) {
            return List.of(row);
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").regex(CONVERT_TYPE_PATTERN),
                Criteria.where("timeUtc").gte(center.minusSeconds(CONVERT_WINDOW_SECONDS))
                        .lte(center.plusSeconds(CONVERT_WINDOW_SECONDS))
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        return mongoOperations.find(query, ExternalLedgerRaw.class);
    }

    public Optional<ExternalLedgerRaw> findLiquidStakingCounterLeg(ExternalLedgerRaw row) {
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return Optional.empty();
        }
        Query query = new Query(liquidStakingCriteria(row, center));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<ExternalLedgerRaw> candidates = mongoOperations.find(query, ExternalLedgerRaw.class);
        return candidates.stream()
                .filter(candidate -> candidate.getQuantityRaw() != null
                        && candidate.getQuantityRaw().signum() == -row.getQuantityRaw().signum())
                .filter(candidate -> sameLiquidStakingFamily(row, candidate))
                .min(Comparator
                        .comparingInt((ExternalLedgerRaw candidate) -> exactAbsQuantityMatch(row, candidate) ? 0 : 1)
                        .thenComparingDouble(candidate -> liquidStakingMagnitudePenalty(row, candidate))
                        .thenComparingLong((ExternalLedgerRaw candidate) ->
                                Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing(ExternalLedgerRaw::getTimeUtc)
                        .thenComparing(ExternalLedgerRaw::getId));
    }

    private Criteria liquidStakingCriteria(ExternalLedgerRaw row, Instant center) {
        List<Criteria> criteria = new java.util.ArrayList<>(List.of(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("sourceFileType").is(row.getSourceFileType()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("bybitType").is(row.getBybitType()),
                Criteria.where("assetSymbol").ne(row.getAssetSymbol()),
                Criteria.where("quantityRaw").exists(true),
                Criteria.where("timeUtc").gte(center.minusSeconds(LIQUID_STAKING_WINDOW_SECONDS))
                        .lte(center.plusSeconds(LIQUID_STAKING_WINDOW_SECONDS))
        ));
        if (requiresExactLiquidStakingDescription(row)) {
            criteria.add(Criteria.where("bybitDescription").is(row.getBybitDescription()));
        }
        return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
    }

    private boolean requiresExactLiquidStakingDescription(ExternalLedgerRaw row) {
        return row.getBybitDescription() != null
                && !row.getBybitDescription().isBlank()
                && !"eth 2.0".equals(normalize(row.getBybitType()));
    }

    private boolean sameLiquidStakingFamily(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(left.getAssetSymbol(), null);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(right.getAssetSymbol(), null);
        return leftFamily != null && leftFamily.equals(rightFamily);
    }

    private boolean exactAbsQuantityMatch(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        return left.getQuantityRaw() != null
                && right.getQuantityRaw() != null
                && left.getQuantityRaw().abs().compareTo(right.getQuantityRaw().abs()) == 0;
    }

    private double liquidStakingMagnitudePenalty(ExternalLedgerRaw left, ExternalLedgerRaw right) {
        if (left.getQuantityRaw() == null || right.getQuantityRaw() == null) {
            return Double.MAX_VALUE;
        }
        return left.getQuantityRaw().abs().subtract(right.getQuantityRaw().abs()).abs().doubleValue();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isConvertType(String bybitType) {
        return CONVERT_TYPES.contains(normalize(bybitType));
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
