package com.walletradar.application.cex.acquisition.venue;

import com.walletradar.application.cex.port.VenueDescriptor;
import com.walletradar.application.normalization.store.NormalizedTransactionPostProcessor;
import com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.OnChainAddressClassifier;
import com.walletradar.domain.wallet.WalletDomainKind;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stamps the venue-neutral {@link ExternalCapitalBoundary} boundary-contract marker onto a
 * normalized transaction row at normalization time.
 *
 * <p><b>Ingestion-plane only.</b> Uses {@link VenueRegistry} to look up the venue's
 * {@link com.walletradar.application.cex.port.VenueExternalCapitalPolicy}.
 * Post-normalization consumers read the stamped {@code externalCapitalBoundary} field only.</p>
 *
 * <p>Boundary determination:
 * <ul>
 *   <li>Only CEX wallet addresses are considered; on-chain rows are left as {@code null}.</li>
 *   <li>Only capital-gate sub-accounts (e.g. Bybit {@code :FUND}) receive a marker.</li>
 *   <li>For {@code EXTERNAL_TRANSFER_IN}: eligible if at least one non-fee flow has an
 *       asset that passes {@link com.walletradar.application.cex.port.VenueExternalCapitalPolicy#isEligibleInflowAsset}.</li>
 *   <li>For {@code EXTERNAL_TRANSFER_OUT} and {@code FIAT_EXIT}: always eligible
 *       when on a capital-gate wallet.</li>
 * </ul>
 * </p>
 *
 * <p>The counterparty universe-membership check (non-universe counterparty) remains in the
 * portfolio conservation gate, which queries the stamped marker to decide which rows to include.</p>
 */
@Component
@RequiredArgsConstructor
public class CexBoundaryContractStamper implements NormalizedTransactionPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CexBoundaryContractStamper.class);

    private final VenueRegistry venueRegistry;

    /** {@inheritDoc} Stamps {@code externalCapitalBoundary} on {@code tx} if applicable. */
    @Override
    public void process(NormalizedTransaction tx) {
        stamp(tx);
    }

    /**
     * Stamps {@code externalCapitalBoundary} on {@code tx} if applicable. Idempotent.
     */
    public void stamp(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return;
        }
        WalletDomainKind domain = OnChainAddressClassifier.classifyDomain(tx.getWalletAddress());
        if (domain != WalletDomainKind.CEX) {
            return;
        }
        VenueDescriptor descriptor = venueRegistry.findByWalletAddress(tx.getWalletAddress()).orElse(null);
        if (descriptor == null) {
            log.warn("stamp: no descriptor for wallet={}", tx.getWalletAddress());
            return;
        }
        if (!descriptor.isCapitalGateWallet(tx.getWalletAddress())) {
            return;
        }
        NormalizedTransactionType type = tx.getType();
        if (type == null) {
            log.warn("stamp: null type for tx={}", tx.getId());
            return;
        }
        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            boolean hasEligibleFlow = tx.getFlows() != null && tx.getFlows().stream()
                    .filter(f -> f != null && f.getRole() != NormalizedLegRole.FEE)
                    .anyMatch(f -> descriptor.isEligibleInflowAsset(f.getAssetSymbol()));
            if (hasEligibleFlow) {
                tx.setExternalCapitalBoundary(ExternalCapitalBoundary.INFLOW);
                log.info("stamp: INFLOW set wallet={} id={} boundary={}", tx.getWalletAddress(), tx.getId(), tx.getExternalCapitalBoundary());
            } else {
                log.warn("stamp: EXTERNAL_TRANSFER_IN has no eligible inflow asset wallet={} flows={}",
                        tx.getWalletAddress(),
                        tx.getFlows() == null ? "null" : tx.getFlows().stream()
                                .filter(f -> f != null)
                                .map(f -> f.getRole() + ":" + f.getAssetSymbol())
                                .toList());
            }
        } else if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || type == NormalizedTransactionType.FIAT_EXIT) {
            tx.setExternalCapitalBoundary(ExternalCapitalBoundary.OUTFLOW);
            log.info("stamp: OUTFLOW set wallet={} id={} boundary={}", tx.getWalletAddress(), tx.getId(), tx.getExternalCapitalBoundary());
        }
    }
}
