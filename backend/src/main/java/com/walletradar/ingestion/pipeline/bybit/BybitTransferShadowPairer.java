package com.walletradar.ingestion.pipeline.bybit;

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
 * Pairs Bybit exchange-side withdrawal shadow rows with their chain-aware sibling rows.
 */
@Component
@RequiredArgsConstructor
public class BybitTransferShadowPairer {

    private static final long WINDOW_SECONDS = 5L;
    private static final BigDecimal MAX_RELATIVE_DELTA = new BigDecimal("0.02");

    private final MongoOperations mongoOperations;

    public Optional<ExternalLedgerRaw> findChainAwareWithdrawalSibling(ExternalLedgerRaw row) {
        if (!isShadowWithdrawalRow(row)) {
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
                Criteria.where("canonicalType").is("EXTERNAL_TRANSFER_OUT"),
                Criteria.where("bybitType").regex("^Withdraw$", "i"),
                Criteria.where("assetSymbol").is(row.getAssetSymbol()),
                Criteria.where("timeUtc").gte(center.minusSeconds(WINDOW_SECONDS)).lte(center.plusSeconds(WINDOW_SECONDS)),
                Criteria.where("txHash").ne(null),
                Criteria.where("networkId").ne(null)
        ));
        query.with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")));
        List<ExternalLedgerRaw> candidates = mongoOperations.find(query, ExternalLedgerRaw.class);
        return candidates.stream()
                .filter(candidate -> candidate.getQuantityRaw() != null
                        && candidate.getQuantityRaw().signum() > 0
                        && quantityMatchesShadow(row.getQuantityRaw(), candidate.getQuantityRaw()))
                .min(Comparator
                        .comparingLong((ExternalLedgerRaw candidate) ->
                                Math.abs(candidate.getTimeUtc().getEpochSecond() - center.getEpochSecond()))
                        .thenComparing((ExternalLedgerRaw candidate) ->
                                row.getQuantityRaw().abs().subtract(candidate.getQuantityRaw().abs()).abs())
                        .thenComparing(ExternalLedgerRaw::getTimeUtc)
                        .thenComparing(ExternalLedgerRaw::getId));
    }

    private boolean isShadowWithdrawalRow(ExternalLedgerRaw row) {
        return row != null
                && "fund_asset_changes".equals(normalize(row.getSourceFileType()))
                && "external_transfer_out".equals(normalize(row.getCanonicalType()))
                && "withdraw".equals(normalize(row.getBybitType()))
                && "bybit".equals(normalize(row.getChain()))
                && row.getTxHash() == null
                && row.getNetworkId() == null
                && row.getQuantityRaw() != null
                && row.getQuantityRaw().signum() < 0;
    }

    private boolean quantityMatchesShadow(BigDecimal shadowQuantity, BigDecimal chainAwareQuantity) {
        BigDecimal shadowAbs = shadowQuantity.abs();
        BigDecimal chainAwareAbs = chainAwareQuantity.abs();
        if (chainAwareAbs.compareTo(shadowAbs) > 0) {
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
}
