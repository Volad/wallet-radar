package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
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
 * Pairs Bybit exchange-side transfer shadow rows with their chain-aware sibling rows.
 */
@Component
@RequiredArgsConstructor
public class BybitTransferShadowPairer {

    private static final long WINDOW_SECONDS = 5L;
    private static final BigDecimal MAX_RELATIVE_DELTA = new BigDecimal("0.20");

    private final MongoOperations mongoOperations;

    public Optional<ExternalLedgerRaw> findChainAwareTransferSibling(ExternalLedgerRaw row) {
        ShadowTransferDirection direction = shadowTransferDirection(row);
        if (direction == null) {
            return Optional.empty();
        }
        Instant center = row.getTimeUtc();
        if (center == null || row.getUid() == null || row.getAssetSymbol() == null || row.getQuantityRaw() == null) {
            return Optional.empty();
        }

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").ne(row.getId()),
                Criteria.where("uid").is(row.getUid()),
                Criteria.where("sourceFileType").is("withdraw_deposit"),
                Criteria.where("assetSymbol").is(row.getAssetSymbol()),
                Criteria.where("timeUtc").gte(center.minusSeconds(WINDOW_SECONDS)).lte(center.plusSeconds(WINDOW_SECONDS)),
                Criteria.where("txHash").ne(null),
                Criteria.where("networkId").ne(null)
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<ExternalLedgerRaw> candidates = mongoOperations.find(query, ExternalLedgerRaw.class);
        return candidates.stream()
                .filter(candidate -> candidateMatches(direction, row, candidate))
                .min(Comparator
                        .comparingLong((ExternalLedgerRaw candidate) ->
                                Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing((ExternalLedgerRaw candidate) ->
                                row.getQuantityRaw().abs().subtract(candidate.getQuantityRaw().abs()).abs())
                        .thenComparing(ExternalLedgerRaw::getTimeUtc)
                        .thenComparing(ExternalLedgerRaw::getId));
    }

    private boolean candidateMatches(
            ShadowTransferDirection direction,
            ExternalLedgerRaw shadow,
            ExternalLedgerRaw candidate
    ) {
        if (candidate == null || candidate.getQuantityRaw() == null || candidate.getQuantityRaw().signum() == 0) {
            return false;
        }
        if (direction == ShadowTransferDirection.OUTBOUND && candidate.getQuantityRaw().signum() <= 0) {
            return false;
        }
        if (direction == ShadowTransferDirection.INBOUND && candidate.getQuantityRaw().signum() <= 0) {
            return false;
        }
        if (normalizeShadowTransferDirection(candidate) != direction) {
            return false;
        }
        if (!normalize(shadow.getBybitType()).equals(normalize(candidate.getBybitType()))) {
            return false;
        }
        return quantityMatchesShadow(direction, shadow.getQuantityRaw(), candidate.getQuantityRaw());
    }

    private ShadowTransferDirection shadowTransferDirection(ExternalLedgerRaw row) {
        if (row == null
                || !"fund_asset_changes".equals(normalize(row.getSourceFileType()))
                || !"bybit".equals(normalize(row.getChain()))
                || row.getTxHash() != null
                || row.getNetworkId() != null
                || row.getQuantityRaw() == null
                || row.getQuantityRaw().signum() == 0) {
            return null;
        }
        ShadowTransferDirection direction = normalizeShadowTransferDirection(row);
        if (direction == ShadowTransferDirection.OUTBOUND && row.getQuantityRaw().signum() >= 0) {
            return null;
        }
        if (direction == ShadowTransferDirection.INBOUND && row.getQuantityRaw().signum() <= 0) {
            return null;
        }
        return direction;
    }

    private ShadowTransferDirection normalizeShadowTransferDirection(ExternalLedgerRaw row) {
        if (row == null) {
            return null;
        }
        String canonicalType = normalize(row.getCanonicalType());
        if ("external_transfer_out".equals(canonicalType)) {
            return ShadowTransferDirection.OUTBOUND;
        }
        if ("external_transfer_in".equals(canonicalType) || "external_inbound".equals(canonicalType)) {
            return ShadowTransferDirection.INBOUND;
        }
        return null;
    }

    private boolean quantityMatchesShadow(
            ShadowTransferDirection direction,
            BigDecimal shadowQuantity,
            BigDecimal chainAwareQuantity
    ) {
        BigDecimal shadowAbs = shadowQuantity.abs();
        BigDecimal chainAwareAbs = chainAwareQuantity.abs();
        if (direction == ShadowTransferDirection.OUTBOUND && chainAwareAbs.compareTo(shadowAbs) > 0) {
            return false;
        }
        BigDecimal delta = shadowAbs.subtract(chainAwareAbs).abs();
        if (delta.signum() == 0) {
            return true;
        }
        BigDecimal relativeDelta = delta.divide(shadowAbs, java.math.MathContext.DECIMAL128);
        return relativeDelta.compareTo(MAX_RELATIVE_DELTA) <= 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private enum ShadowTransferDirection {
        INBOUND,
        OUTBOUND
    }
}
