package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.application.lending.spi.LiveLendingAssetAmount;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * WS-4 — live borrow-liability true-up for receipt-less networks (B3). The live reader's outstanding
 * debt is <strong>authoritative and supersedes</strong> the classification-derived
 * {@code borrow_liabilities.qtyOpen}: the value is <em>SET</em>, never stacked on top (else
 * {@code PortfolioConservationGate} would over-subtract the liability). Interest accrual (e.g.
 * 210 → 233 USDT) is a real expense and books <strong>no realized income</strong> — it only raises
 * the outstanding liability; stable debt marks at $1.
 *
 * <p>EVM debt is intentionally skipped: it already flows live through the variable/stable debt-token
 * balances, so it needs no external true-up. Runs in the background lending refresh (after AVCO
 * replay has rebuilt the liability book), and re-applies on every refresh so the live figure holds.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LendingLiabilityLiveTrueUpService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String STATUS_OPEN = "OPEN";
    private static final String LENDING_ORDER_MARKER = "lend";

    private final BorrowLiabilityRepository repository;

    public void trueUp(String universeId, LivePositionRequest request, LiveLendingPosition position) {
        if (universeId == null || universeId.isBlank() || position == null || position.debt().isEmpty()) {
            return;
        }
        NetworkId networkId = parseNetwork(request.networkId());
        // Receipt-less (non-EVM) only: EVM debt is already live via debt-token balances.
        if (networkId == null || NetworkAddressFormat.isEvm(networkId)) {
            return;
        }
        List<BorrowLiability> liabilities = repository.findByUniverseId(universeId);
        if (liabilities.isEmpty()) {
            return;
        }
        String wallet = request.walletAddress() == null ? "" : request.walletAddress().trim();
        Instant now = Instant.now();
        for (LiveLendingAssetAmount debtLeg : position.debt()) {
            if (debtLeg.quantity() == null || debtLeg.quantity().signum() < 0) {
                continue;
            }
            BorrowLiability liability = match(liabilities, debtLeg, wallet);
            if (liability == null) {
                continue;
            }
            applyOverride(liability, debtLeg, now);
            repository.save(liability);
            log.info("Live borrow-liability true-up universe={} orderId={} asset={} qtyOpen->{}",
                    universeId, liability.getOrderId(), liability.getAsset(), liability.getQtyOpen());
        }
    }

    /**
     * Flow-based match (never keyed on a transaction hash): the liability must belong to this
     * receipt-less lending wallet (accountRef / orderId carrying the wallet or a lending marker) and
     * reference the same debt asset. Avoids stacking onto an unrelated liability.
     */
    private BorrowLiability match(List<BorrowLiability> liabilities, LiveLendingAssetAmount debtLeg, String wallet) {
        for (BorrowLiability liability : liabilities) {
            if (!assetMatches(liability.getAsset(), debtLeg)) {
                continue;
            }
            String orderId = liability.getOrderId() == null ? "" : liability.getOrderId().toLowerCase(Locale.ROOT);
            boolean walletMatch = !wallet.isBlank()
                    && (wallet.equalsIgnoreCase(liability.getAccountRef()) || orderId.contains(wallet.toLowerCase(Locale.ROOT)));
            boolean lendingMarker = orderId.contains(LENDING_ORDER_MARKER);
            if (walletMatch || lendingMarker) {
                return liability;
            }
        }
        return null;
    }

    private void applyOverride(BorrowLiability liability, LiveLendingAssetAmount debtLeg, Instant now) {
        BigDecimal liveQty = debtLeg.quantity();
        BigDecimal priorBorrowed = liability.getQtyBorrowed() == null ? BigDecimal.ZERO : liability.getQtyBorrowed();
        // SET (override), not stack: outstanding debt becomes the authoritative live figure.
        liability.setQtyOpen(liveQty);
        liability.setQtyBorrowed(priorBorrowed.max(liveQty));
        BigDecimal unitPrice = unitPrice(debtLeg);
        if (unitPrice != null
                && (liability.getPortfolioAvcoAtOpen() == null || liability.getPortfolioAvcoAtOpen().signum() <= 0)) {
            liability.setPortfolioAvcoAtOpen(unitPrice);
        }
        liability.setStatus(STATUS_OPEN);
        liability.setClosedAt(null);
        liability.setLastTouchedAt(now);
    }

    private static BigDecimal unitPrice(LiveLendingAssetAmount debtLeg) {
        if (debtLeg.marketValueUsd() == null || debtLeg.quantity() == null || debtLeg.quantity().signum() <= 0) {
            return null;
        }
        return debtLeg.marketValueUsd().divide(debtLeg.quantity(), MC);
    }

    private static boolean assetMatches(String liabilityAsset, LiveLendingAssetAmount debtLeg) {
        if (liabilityAsset == null || liabilityAsset.isBlank()) {
            return false;
        }
        return liabilityAsset.equalsIgnoreCase(debtLeg.assetSymbol())
                || liabilityAsset.equals(debtLeg.assetContract());
    }

    private static NetworkId parseNetwork(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            return null;
        }
        try {
            return NetworkId.valueOf(networkId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
