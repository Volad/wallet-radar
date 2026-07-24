package com.walletradar.application.custody.application;

import com.walletradar.application.costbasis.support.WalletAddressReadScope;
import com.walletradar.application.linking.pipeline.clarification.ExternalCustodyDestinationRegistry;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Informational, read-only custody ledger (WS-5, ADR-072).
 *
 * <p>Tallies the raw on-chain in/out flows to each user-designated external custody destination so a
 * user can see how much capital is parked at an off-chain / custodial venue (e.g. Telegram Wallet
 * Earn). This is <b>strictly informational</b>: it is <b>not</b> part of the accounting universe and
 * is never added to portfolio totals, AVCO, or dashboard quantity. Rows are identified purely by the
 * venue-neutral {@code custodialOffChain} capability flag stamped at normalization time — no venue /
 * network branching. The venue may return a different asset than was deposited (USDT in → USDe out);
 * this ledger makes no attempt at cross-asset reconciliation — it just reports actual per-asset
 * flows. Any yield is realized naturally via external-in accounting on exit.</p>
 */
@Service
@RequiredArgsConstructor
public class CustodyLedgerQueryService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final UserSessionRepository userSessionRepository;
    private final MongoOperations mongoOperations;
    private final ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    public Optional<SessionCustodyLedgerView> findSessionCustodyLedger(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim()).map(this::toView);
    }

    private SessionCustodyLedgerView toView(UserSession session) {
        List<String> walletAddresses = walletAddresses(session);
        if (walletAddresses.isEmpty()) {
            return new SessionCustodyLedgerView(session.getId(), List.of());
        }
        List<NormalizedTransaction> custodyRows = loadCustodyRows(walletAddresses);

        Map<String, VenueAccumulator> byVenue = new LinkedHashMap<>();
        for (NormalizedTransaction tx : custodyRows) {
            if (tx == null || tx.getFlows() == null) {
                continue;
            }
            String venueAddress = tx.getCounterpartyAddress();
            String venueKey = venueAddress == null || venueAddress.isBlank()
                    ? "UNKNOWN_CUSTODY_VENUE"
                    : venueAddress.trim();
            VenueAccumulator venue = byVenue.computeIfAbsent(venueKey, ignored ->
                    new VenueAccumulator(venueAddress, resolveLabel(session.getId(), tx), resolveProvider(session.getId(), tx)));
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                BigDecimal qty = flow.getQuantityDelta();
                if (qty == null || qty.signum() == 0) {
                    continue;
                }
                String asset = flow.getAssetSymbol() == null || flow.getAssetSymbol().isBlank()
                        ? "UNKNOWN"
                        : flow.getAssetSymbol().trim();
                BigDecimal usd = flow.getValueUsd() == null ? BigDecimal.ZERO : flow.getValueUsd().abs();
                AssetAccumulator assetAcc = venue.assets.computeIfAbsent(asset, AssetAccumulator::new);
                if (qty.signum() < 0) {
                    // Capital leaving the wallet toward the venue → deposit.
                    assetAcc.depositedQty = assetAcc.depositedQty.add(qty.abs(), MC);
                    assetAcc.depositedUsd = assetAcc.depositedUsd.add(usd, MC);
                } else {
                    // Capital returning from the venue → withdrawal ("count on exit").
                    assetAcc.withdrawnQty = assetAcc.withdrawnQty.add(qty, MC);
                    assetAcc.withdrawnUsd = assetAcc.withdrawnUsd.add(usd, MC);
                }
            }
        }

        List<CustodyVenueView> venues = new ArrayList<>();
        for (VenueAccumulator venue : byVenue.values()) {
            List<CustodyAssetView> assets = new ArrayList<>();
            for (AssetAccumulator asset : venue.assets.values()) {
                assets.add(new CustodyAssetView(
                        asset.asset,
                        asset.depositedQty,
                        asset.withdrawnQty,
                        asset.depositedQty.subtract(asset.withdrawnQty, MC),
                        asset.depositedUsd,
                        asset.withdrawnUsd
                ));
            }
            venues.add(new CustodyVenueView(venue.venueAddress, venue.label, venue.provider, assets));
        }
        return new SessionCustodyLedgerView(session.getId(), venues);
    }

    private List<NormalizedTransaction> loadCustodyRows(List<String> walletAddresses) {
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                        Criteria.where("walletAddress").in(walletAddresses),
                        Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED),
                        Criteria.where("custodialOffChain").is(true)
                ))
                .with(Sort.by(
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("_id")
                ));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private String resolveLabel(String sessionId, NormalizedTransaction tx) {
        if (tx.getProtocolName() != null && !tx.getProtocolName().isBlank()) {
            return tx.getProtocolName().trim();
        }
        return externalCustodyDestinationRegistry
                .matchForSession(sessionId, tx.getCounterpartyAddress(), tx.getNetworkId())
                .map(ExternalCustodyDestinationRegistry.CustodyMatch::label)
                .orElse(tx.getCounterpartyAddress());
    }

    private String resolveProvider(String sessionId, NormalizedTransaction tx) {
        return externalCustodyDestinationRegistry
                .matchForSession(sessionId, tx.getCounterpartyAddress(), tx.getNetworkId())
                .map(ExternalCustodyDestinationRegistry.CustodyMatch::provider)
                .orElse(null);
    }

    private static List<String> walletAddresses(UserSession session) {
        if (session.getWallets() == null) {
            return List.of();
        }
        return session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .filter(Objects::nonNull)
                .map(WalletAddressReadScope::normalize)
                .filter(ref -> !ref.isBlank())
                .toList();
    }

    private static final class VenueAccumulator {
        private final String venueAddress;
        private final String label;
        private final String provider;
        private final Map<String, AssetAccumulator> assets = new LinkedHashMap<>();

        private VenueAccumulator(String venueAddress, String label, String provider) {
            this.venueAddress = venueAddress;
            this.label = label;
            this.provider = provider;
        }
    }

    private static final class AssetAccumulator {
        private final String asset;
        private BigDecimal depositedQty = BigDecimal.ZERO;
        private BigDecimal withdrawnQty = BigDecimal.ZERO;
        private BigDecimal depositedUsd = BigDecimal.ZERO;
        private BigDecimal withdrawnUsd = BigDecimal.ZERO;

        private AssetAccumulator(String asset) {
            this.asset = asset;
        }
    }

    public record SessionCustodyLedgerView(String sessionId, List<CustodyVenueView> venues) {
    }

    public record CustodyVenueView(
            String venueAddress,
            String label,
            String provider,
            List<CustodyAssetView> assets
    ) {
    }

    public record CustodyAssetView(
            String asset,
            BigDecimal depositedQty,
            BigDecimal withdrawnQty,
            BigDecimal netQty,
            BigDecimal depositedUsd,
            BigDecimal withdrawnUsd
    ) {
    }
}
