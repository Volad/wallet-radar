package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.BorrowLiability;
import com.walletradar.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.domain.common.PriceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks crypto-loan principal by {@code orderId} for zero-PnL roundtrips (ADR-012 §D2–D3).
 */
@Service
@RequiredArgsConstructor
public class BorrowLiabilityTracker {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal QTY_EPSILON = new BigDecimal("0.000001");
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_OPEN_FROM_REPAY = "OPEN_FROM_REPAY";

    private final BorrowLiabilityRepository repository;

    public record BorrowRecord(
            BigDecimal portfolioAvcoAtOpen,
            PriceSource portfolioAvcoSource
    ) {
    }

    public record RepayMatch(
            BigDecimal matchedQty,
            BigDecimal residualQty,
            BigDecimal liabilityAvcoUsd,
            boolean liabilityFound
    ) {
        public static RepayMatch zero() {
            return new RepayMatch(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
    }

    public Map<String, BorrowLiability> loadAllForUniverse(String universeId) {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        for (BorrowLiability liability : repository.findByUniverseId(universeId)) {
            if (liability.getCompositeId() != null) {
                book.put(liability.getCompositeId(), liability);
            }
        }
        return book;
    }

    public void replaceUniverseLiabilities(String universeId, Map<String, BorrowLiability> liabilities) {
        repository.deleteByUniverseId(universeId);
        if (liabilities != null && !liabilities.isEmpty()) {
            repository.saveAll(liabilities.values());
        }
    }

    public BorrowRecord recordBorrow(
            String universeId,
            String orderId,
            String accountRef,
            String asset,
            BigDecimal qty,
            BigDecimal currentPortfolioAvco,
            PriceSource avcoSource,
            Instant touchedAt,
            Map<String, BorrowLiability> book,
            Set<String> dirtyIds
    ) {
        if (qty == null || qty.signum() <= 0) {
            BigDecimal avco = nonNegativeAvco(currentPortfolioAvco);
            return new BorrowRecord(avco, avcoSource == null ? PriceSource.UNKNOWN : avcoSource);
        }
        String compositeId = BorrowLiability.compositeId(universeId, orderId);
        BorrowLiability liability = book.get(compositeId);
        BigDecimal avco = nonNegativeAvco(currentPortfolioAvco);
        PriceSource source = avcoSource == null ? PriceSource.UNKNOWN : avcoSource;
        if (liability == null) {
            liability = new BorrowLiability();
            liability.setCompositeId(compositeId);
            liability.setUniverseId(universeId);
            liability.setOrderId(orderId);
            liability.setAccountRef(accountRef);
            liability.setAsset(asset);
            liability.setQtyBorrowed(qty);
            liability.setQtyOpen(qty);
            liability.setPortfolioAvcoAtOpen(avco);
            liability.setPortfolioAvcoSource(source);
            liability.setOpenedAt(touchedAt);
            liability.setStatus(STATUS_OPEN);
            book.put(compositeId, liability);
        } else {
            BigDecimal priorOpen = zeroIfNull(liability.getQtyOpen());
            BigDecimal priorBorrowed = zeroIfNull(liability.getQtyBorrowed());
            BigDecimal newBorrowed = priorBorrowed.add(qty, MC);
            BigDecimal newOpen = priorOpen.add(qty, MC);
            BigDecimal priorAvco = zeroIfNull(liability.getPortfolioAvcoAtOpen());
            BigDecimal weightedAvco = priorOpen.signum() == 0
                    ? avco
                    : priorOpen.multiply(priorAvco, MC)
                            .add(qty.multiply(avco, MC), MC)
                            .divide(priorOpen.add(qty, MC), MC);
            liability.setQtyBorrowed(newBorrowed);
            liability.setQtyOpen(newOpen);
            liability.setPortfolioAvcoAtOpen(weightedAvco);
            liability.setPortfolioAvcoSource(source);
            liability.setStatus(STATUS_OPEN);
            liability.setClosedAt(null);
        }
        liability.setLastTouchedAt(touchedAt);
        if (accountRef != null && !accountRef.isBlank()) {
            liability.setAccountRef(accountRef);
        }
        if (asset != null && !asset.isBlank()) {
            liability.setAsset(asset);
        }
        dirtyIds.add(compositeId);
        return new BorrowRecord(liability.getPortfolioAvcoAtOpen(), liability.getPortfolioAvcoSource());
    }

    public RepayMatch recordRepay(
            String universeId,
            String orderId,
            String accountRef,
            String asset,
            BigDecimal qty,
            Instant touchedAt,
            Map<String, BorrowLiability> book,
            Set<String> dirtyIds
    ) {
        if (qty == null || qty.signum() <= 0) {
            return new RepayMatch(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        String compositeId = BorrowLiability.compositeId(universeId, orderId);
        BorrowLiability liability = book.get(compositeId);
        if (liability == null) {
            liability = new BorrowLiability();
            liability.setCompositeId(compositeId);
            liability.setUniverseId(universeId);
            liability.setOrderId(orderId);
            liability.setAccountRef(accountRef);
            liability.setAsset(asset);
            liability.setQtyBorrowed(qty);
            liability.setQtyOpen(BigDecimal.ZERO);
            liability.setPortfolioAvcoAtOpen(BigDecimal.ZERO);
            liability.setPortfolioAvcoSource(PriceSource.UNKNOWN);
            liability.setOpenedAt(touchedAt);
            liability.setStatus(STATUS_OPEN_FROM_REPAY);
            liability.setLastTouchedAt(touchedAt);
            book.put(compositeId, liability);
            dirtyIds.add(compositeId);
            return new RepayMatch(BigDecimal.ZERO, qty, BigDecimal.ZERO, false);
        }
        BigDecimal qtyOpen = zeroIfNull(liability.getQtyOpen());
        BigDecimal matchedQty = qty.min(qtyOpen);
        BigDecimal residualQty = qty.subtract(matchedQty, MC);
        BigDecimal liabilityAvco = zeroIfNull(liability.getPortfolioAvcoAtOpen());
        if (matchedQty.signum() > 0) {
            BigDecimal newOpen = qtyOpen.subtract(matchedQty, MC);
            liability.setQtyOpen(newOpen);
            if (newOpen.compareTo(QTY_EPSILON) <= 0) {
                liability.setQtyOpen(BigDecimal.ZERO);
                liability.setStatus(STATUS_CLOSED);
                liability.setClosedAt(touchedAt);
            } else {
                liability.setStatus(STATUS_PARTIAL);
                liability.setClosedAt(null);
            }
        }
        liability.setLastTouchedAt(touchedAt);
        dirtyIds.add(compositeId);
        return new RepayMatch(matchedQty, residualQty, liabilityAvco, true);
    }

    private static BigDecimal nonNegativeAvco(BigDecimal avco) {
        return avco == null || avco.signum() < 0 ? BigDecimal.ZERO : avco;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
