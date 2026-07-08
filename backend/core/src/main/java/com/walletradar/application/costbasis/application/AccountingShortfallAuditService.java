package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AccountingShortfallAudit;
import com.walletradar.application.costbasis.domain.AccountingShortfallAuditRepository;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DEBUG / DIAGNOSTIC (Cycle 15) — TEMPORARY.
 * Pure write-only diagnostic that persists replay shortfall events to
 * {@code accounting_shortfall_audit}. Not consumed by any production logic.
 * REMOVE after coverage acceptance.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountingShortfallAuditService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final AccountingShortfallAuditRepository repository;

    public AccountingShortfallAudit recordFromLedgerPoint(AssetLedgerPoint point, Instant createdAt) {
        if (point == null) {
            return null;
        }
        BigDecimal shortfallDelta = point.getQuantityShortfallDelta();
        if (shortfallDelta == null || shortfallDelta.signum() <= 0) {
            return null;
        }
        AccountingShortfallAudit audit = new AccountingShortfallAudit();
        audit.setId(point.getId() + ":shortfall");
        audit.setAccountingUniverseId(point.getAccountingUniverseId());
        audit.setNormalizedTransactionId(point.getNormalizedTransactionId());
        audit.setNormalizedType(point.getNormalizedType());
        audit.setWalletAddress(point.getWalletAddress());
        audit.setNetworkId(point.getNetworkId());
        audit.setAssetSymbol(point.getAssetSymbol());
        audit.setAssetIdentity(point.getAccountingAssetIdentity());
        audit.setCorrelationId(point.getCorrelationId());
        audit.setFlowIndex(point.getFlowIndex() == null ? 0 : point.getFlowIndex());
        audit.setQuantityShortfallDelta(shortfallDelta);
        audit.setQuantityBefore(point.getQuantityBefore());
        audit.setQuantityAfter(point.getQuantityAfter());
        BigDecimal bbBefore = basisBacked(point.getQuantityBefore(), point.getUncoveredQuantityAfter());
        BigDecimal bbAfter = point.getBasisBackedQuantityAfter();
        audit.setBasisBackedBefore(bbBefore);
        audit.setBasisBackedAfter(bbAfter);
        audit.setBlockTimestamp(point.getBlockTimestamp());
        audit.setCreatedAt(createdAt);
        log.warn(
                "ACCOUNTING_SHORTFALL_AUDIT tx={} type={} wallet={} asset={} shortfallDelta={} corr={}",
                point.getNormalizedTransactionId(),
                point.getNormalizedType(),
                point.getWalletAddress(),
                point.getAssetSymbol(),
                shortfallDelta,
                point.getCorrelationId()
        );
        return audit;
    }

    public void replaceUniverseAudits(String universeId, List<AccountingShortfallAudit> audits) {
        repository.deleteByAccountingUniverseId(universeId);
        if (audits != null && !audits.isEmpty()) {
            repository.saveAll(audits);
        }
    }

    public List<AccountingShortfallAudit> collectFromLedgerPoints(
            List<AssetLedgerPoint> points,
            Instant createdAt
    ) {
        List<AccountingShortfallAudit> audits = new ArrayList<>();
        if (points == null) {
            return audits;
        }
        for (AssetLedgerPoint point : points) {
            AccountingShortfallAudit audit = recordFromLedgerPoint(point, createdAt);
            if (audit != null) {
                audits.add(audit);
            }
        }
        return audits;
    }

    private static BigDecimal basisBacked(BigDecimal quantity, BigDecimal uncoveredAfter) {
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal u = uncoveredAfter == null ? BigDecimal.ZERO : uncoveredAfter;
        BigDecimal backed = q.subtract(u, MC);
        return backed.signum() < 0 ? BigDecimal.ZERO : backed;
    }
}
