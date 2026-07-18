package com.walletradar.application.portfolio.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.application.costbasis.breakeven.BreakEvenAttributionService;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator;
import com.walletradar.application.costbasis.support.CexUmbrellaSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.OutOfScopeFamilySupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.application.pricing.latest.CurrentPriceReadService;
import com.walletradar.application.pricing.latest.ResolvedPrice;
import com.walletradar.application.portfolio.application.port.SessionDashboardReadPort;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Session-scoped dashboard snapshot based on current balances and latest replay state.
 */
@Service
@RequiredArgsConstructor
public class SessionDashboardQueryService implements SessionDashboardReadPort {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String ISSUE_YIELD_ACCRUAL = "yield_accrual";
    private static final String ISSUE_COVERAGE_GAP = "coverage_gap";
    private static final String ISSUE_HISTORY_FLAGS = "history_flags";
    private static final String ISSUE_MISSING_REPLAY_POINT = "missing_replay_point";
    private static final String PRICE_ISSUE_MISSING = "missing_price";
    private static final String PRICE_ISSUE_STALE = "stale_price";
    private static final String PRICE_ISSUE_HISTORICAL_FALLBACK = "historical_price_fallback";
    private static final String PRICE_ISSUE_UNSUPPORTED_PROTOCOL_VALUATION = "unsupported_protocol_valuation";
    private static final String VALUATION_AAVE_INDEX_ACCRUING = "AAVE_INDEX_ACCRUING";
    private static final String VALUATION_GMX_MARKET_TOKEN_SNAPSHOT = "GMX_MARKET_TOKEN_SNAPSHOT";
    private static final String PRICE_SOURCE_PROTOCOL_SNAPSHOT = "PROTOCOL_SNAPSHOT";
    private static final long CURRENT_QUOTE_STALE_AFTER_SECONDS = 90 * 60;
    private static final BigDecimal AVCO_CAP_COVERAGE_THRESHOLD = new BigDecimal("0.05");
    private static final BigDecimal MINIMUM_POSITION_VALUE_USD = new BigDecimal("0.01");
    private static final BigDecimal AVCO_CAP_PRICE_MULTIPLIER = BigDecimal.TEN;

    private final UserSessionRepository userSessionRepository;
    private final MongoOperations mongoOperations;
    private final AccountingUniverseService accountingUniverseService;
    private final CexLiveBalancePort cexLiveBalancePort;
    private final CurrentPriceReadService currentPriceReadService;
    private final PortfolioConservationGate portfolioConservationGate;
    private final BreakEvenCalculator breakEvenCalculator;
    private final Cache<String, SessionDashboardView> sessionDashboardCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(45, TimeUnit.SECONDS)
            .build();

    public Optional<SessionDashboardView> findSessionDashboard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedSessionId = sessionId.trim();
        SessionDashboardView cached = sessionDashboardCache.getIfPresent(normalizedSessionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SessionDashboardView> loaded = userSessionRepository.findById(normalizedSessionId).map(this::toView);
        loaded.ifPresent(view -> sessionDashboardCache.put(normalizedSessionId, view));
        return loaded;
    }

    private SessionDashboardView toView(UserSession session) {
        Instant responseTime = Instant.now();
        AllowedScope allowedScope = AllowedScope.from(session.getWallets());
        AccountingUniverseService.AccountingUniverseScope universeScope = accountingUniverseService.resolveScope(session);
        List<String> walletAddresses = allowedScope.walletAddresses();

        List<AssetLedgerPoint> scopedLedgerPoints = loadAssetLedgerPoints(universeScope.accountingUniverseId());
        List<OnChainBalance> scopedBalances = loadOnChainBalances(session.getId(), walletAddresses).stream()
                .filter(balance -> allowedScope.includes(balance.getWalletAddress(), balance.getNetworkId()))
                .toList();

        LinkedHashSet<String> enabledCexVenueRefs = new LinkedHashSet<>(CexUmbrellaSupport.enabledCexAccountRefs(session));

        Map<BucketKey, AssetLedgerPoint> latestPointByBucket = latestLedgerPointByBucket(scopedLedgerPoints);
        Map<FamilyRowKey, BigDecimal> realisedPnlByFamily = pnlDeltaByFamily(
                scopedLedgerPoints,
                enabledCexVenueRefs,
                AssetLedgerPoint::getRealisedPnlDeltaUsd
        );
        Map<FamilyRowKey, BigDecimal> netRealisedPnlByFamily = pnlDeltaByFamily(
                scopedLedgerPoints,
                enabledCexVenueRefs,
                AssetLedgerPoint::getNetRealisedPnlDeltaUsd
        );
        BigDecimal totalRealisedPnlUsd = scopedLedgerPoints.stream()
                .map(AssetLedgerPoint::getRealisedPnlDeltaUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        // RC-8 (ADR-014): the conservation identity feeds realized PnL on IN-SCOPE families only.
        // Out-of-scope families (SOL / TON / HYPEREVM — lifecycle ends at the CEX, excluded from
        // adjustedMTM) must be excluded symmetrically from reportedPnL, otherwise their realized
        // leaks into conservationDelta. The displayed totalRealisedPnlUsd is unchanged.
        BigDecimal inScopeRealisedPnlUsd = scopedLedgerPoints.stream()
                .filter(point -> point.getRealisedPnlDeltaUsd() != null)
                .filter(point -> !OutOfScopeFamilySupport.isOutOfScopeFamily(
                        point.getAccountingFamilyIdentity(), point.getAssetSymbol()))
                .map(AssetLedgerPoint::getRealisedPnlDeltaUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));

        Map<BucketKey, OnChainBalance> latestBalances = latestBalanceByBucket(scopedBalances);
        Map<FamilyRowKey, TokenPositionAccumulator> rows = new LinkedHashMap<>();
        for (OnChainBalance balance : latestBalances.values()) {
            BigDecimal currentQuantity = zeroIfNull(balance.getQuantity());
            if (currentQuantity.signum() <= 0) {
                continue;
            }

            String accountingAssetIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            BucketKey bucketKey = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    accountingAssetIdentity
            );
            AssetLedgerPoint latestPoint = latestPointByBucket.get(bucketKey);
            String familyIdentity = resolvedFamilyIdentity(
                    latestPoint,
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            String familyDisplaySymbol = latestPoint == null || blank(latestPoint.getFamilyDisplaySymbol())
                    ? AssetLedgerSupport.familyDisplaySymbol(familyIdentity, balance.getAssetSymbol())
                    : latestPoint.getFamilyDisplaySymbol();
            String rowSymbol = blank(familyDisplaySymbol) ? normalizeSymbol(balance.getAssetSymbol()) : familyDisplaySymbol;

            if (AccountingAssetFamilySupport.isLpReceiptSymbol(balance.getAssetSymbol())) {
                continue;
            }
            FamilyRowKey rowKey = new FamilyRowKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    familyIdentity
            );
            TokenPositionAccumulator accumulator = rows.computeIfAbsent(
                    rowKey,
                    ignored -> new TokenPositionAccumulator(
                            familyIdentity,
                            rowSymbol,
                            displayName(rowSymbol),
                            balance.getNetworkId(),
                            normalizeAddress(balance.getWalletAddress())
                    )
            );
            accumulator.addBalance(currentQuantity, latestPoint, balance.getAssetSymbol());
        }

        if (!enabledCexVenueRefs.isEmpty()) {
            for (Map.Entry<BucketKey, AssetLedgerPoint> ledgerEntry : latestPointByBucket.entrySet()) {
                BucketKey bucketKey = ledgerEntry.getKey();
                if (!CexUmbrellaSupport.cexLedgerMatchesEnabledVenue(bucketKey.walletAddress(), enabledCexVenueRefs)) {
                    continue;
                }
                AssetLedgerPoint latestPoint = ledgerEntry.getValue();
                // CEX umbrella uses raw quantityAfter (no shortfall subtraction) and is reconciled
                // against live balances downstream in clampCexUmbrellaToLive.
                BigDecimal currentQuantity = CexUmbrellaSupport.cexRawQuantityAfter(latestPoint);
                if (currentQuantity.signum() <= 0) {
                    continue;
                }
                if (AccountingAssetFamilySupport.isLpReceiptSymbol(latestPoint.getAssetSymbol())) {
                    continue;
                }
                if (isBareCexUmbrellaWallet(bucketKey.walletAddress())
                        && hasCexVenueSubLedgerForFamily(
                        latestPointByBucket,
                        bucketKey.walletAddress(),
                        resolvedFamilyIdentity(
                                latestPoint,
                                bucketKey.networkId(),
                                latestPoint.getAssetSymbol(),
                                latestPoint.getAssetContract()
                        ),
                        enabledCexVenueRefs
                )) {
                    continue;
                }
                String familyIdentity = resolvedFamilyIdentity(
                        latestPoint,
                        bucketKey.networkId(),
                        latestPoint.getAssetSymbol(),
                        latestPoint.getAssetContract()
                );
                String familyDisplaySymbol = latestPoint == null || blank(latestPoint.getFamilyDisplaySymbol())
                        ? AssetLedgerSupport.familyDisplaySymbol(familyIdentity, latestPoint.getAssetSymbol())
                        : latestPoint.getFamilyDisplaySymbol();
                String rowSymbol = blank(familyDisplaySymbol)
                        ? normalizeSymbol(latestPoint.getAssetSymbol())
                        : familyDisplaySymbol;
                String aggregatedWallet = CexUmbrellaSupport.ledgerWalletKeyForAggregation(bucketKey.walletAddress(), enabledCexVenueRefs);
                FamilyRowKey rowKey = new FamilyRowKey(
                        aggregatedWallet,
                        bucketKey.networkId(),
                        familyIdentity
                );
                TokenPositionAccumulator accumulator = rows.computeIfAbsent(
                        rowKey,
                        ignored -> new TokenPositionAccumulator(
                                familyIdentity,
                                rowSymbol,
                                displayName(rowSymbol),
                                bucketKey.networkId(),
                                aggregatedWallet
                        )
                );
                accumulator.addBalance(currentQuantity, latestPoint, latestPoint.getAssetSymbol());
            }
        }

        // Reconcile each CEX umbrella accumulator's quantity against the authoritative live balance.
        // Phantom positions (ledger > live) are clamped down; ledger shortfalls (ledger < live) are
        // inflated up. AVCO metrics are scaled proportionally on clamp-down and unchanged on inflate.
        clampCexUmbrellaToLive(rows, session, enabledCexVenueRefs);
        addMissingLiveCexRows(rows, session);

        Map<String, DashboardPriceSnapshot> latestPricesBySymbol = loadLatestPrices(
                rows.values().stream()
                .flatMap(accumulator -> accumulator.priceLookupCandidates().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new)),
                responseTime
        );

        List<TokenPositionView> baseTokenPositions = rows.entrySet().stream()
                .map(entry -> entry.getValue().toView(
                        resolvePrice(latestPricesBySymbol, entry.getValue().priceLookupCandidates()),
                        zeroIfNull(realisedPnlByFamily.get(entry.getKey()))
                ))
                .filter(position -> position.quantity().signum() > 0)
                // F-4: Aave variableDebt*/stableDebt* receipts are liability markers, never held
                // assets. Exclude them from displayed positions; the borrow is surfaced via
                // borrow_liabilities and subtracted once in the conservation gate (no double-count).
                .filter(position -> !AccountingAssetIdentitySupport.isDebtIdentity(position.symbol()))
                .filter(position -> position.priceIssue() != null            // unpriced — never filter
                        || position.marketValueUsd().signum() < 0          // debt / short — always shown
                        || position.marketValueUsd().compareTo(MINIMUM_POSITION_VALUE_USD) >= 0)
                .sorted(Comparator.comparing(
                        TokenPositionView::marketValueUsd,
                        Comparator.nullsLast(BigDecimal::compareTo)
                ).reversed())
                .toList();

        // ADR-062: break-even (effective-cost) enrichment. Realized P&L of fully-exited children
        // (e.g. cmETH → FAMILY:ETH) still credits the parent even without a held row, so the input
        // set is the union of displayed families and any family that carries realized P&L.
        Map<String, BreakEvenCalculator.BreakEvenResult> breakEvenByFamily = computeBreakEvenByFamily(
                baseTokenPositions,
                realisedPnlByFamily,
                netRealisedPnlByFamily
        );
        List<TokenPositionView> tokenPositions = baseTokenPositions.stream()
                .map(view -> {
                    BreakEvenCalculator.BreakEvenResult result = breakEvenByFamily.get(view.familyIdentity());
                    return result == null
                            ? view.withBreakEven(null, null, null, null)
                            : view.withBreakEven(
                                    result.breakEvenUsd(),
                                    result.lockedSurplusUsd(),
                                    result.incomeReceivedUsd(),
                                    result.attributionTargetFamily()
                            );
                })
                .toList();

        BigDecimal portfolioValueUsd = tokenPositions.stream()
                .map(TokenPositionView::marketValueUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalUnrealizedPnlUsd = tokenPositions.stream()
                .map(TokenPositionView::unrealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalProvableBasisUsd = tokenPositions.stream()
                .map(TokenPositionView::provableBasisUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalUnrealizedPnlPct = totalProvableBasisUsd.signum() <= 0
                ? BigDecimal.ZERO
                : totalUnrealizedPnlUsd.multiply(BigDecimal.valueOf(100), MC).divide(totalProvableBasisUsd, MC);

        LinkedHashSet<String> sessionNetworkNames = new LinkedHashSet<>();
        List<WalletView> wallets = new ArrayList<>();
        for (UserSession.SessionWallet wallet : session.getWallets()) {
            if (wallet == null) {
                continue;
            }
            if (wallet.getNetworks() != null) {
                wallet.getNetworks().forEach(network -> sessionNetworkNames.add(network.name()));
            }
            wallets.add(new WalletView(
                    wallet.getAddress(),
                    wallet.getLabel(),
                    wallet.getColor(),
                    wallet.getNetworks() == null ? List.of() : wallet.getNetworks().stream().map(Enum::name).toList()
            ));
        }
        if (session.getIntegrations() != null) {
            for (UserSession.SessionIntegration integration : session.getIntegrations()) {
                if (integration == null || integration.getStatus() == UserSession.IntegrationStatus.DISABLED) {
                    continue;
                }
                String accountRef = integration.getAccountRef();
                if (accountRef == null || accountRef.isBlank()) {
                    continue;
                }
                WalletRef ref = WalletRef.parse(accountRef);
                if (ref.domain() != WalletDomainKind.CEX) {
                    continue;
                }
                String venueLabel = ref.venueId().substring(0, 1).toUpperCase(Locale.ROOT)
                        + ref.venueId().substring(1).toLowerCase(Locale.ROOT);
                String label = blank(integration.getDisplayName()) ? venueLabel : integration.getDisplayName();
                String walletRef = ref.umbrellaKey().toLowerCase(Locale.ROOT);
                String venueNetwork = ref.venueId().toUpperCase(Locale.ROOT);
                wallets.add(new WalletView(walletRef, label, null, List.of(venueNetwork)));
            }
        }

        PortfolioConservationGate.ConservationResult conservation = portfolioConservationGate.evaluate(
                new PortfolioConservationGate.ConservationInputs(
                        universeScope.accountingUniverseId(),
                        portfolioValueUsd,
                        inScopeRealisedPnlUsd,
                        totalUnrealizedPnlUsd,
                        tokenPositions
                )
        );

        return new SessionDashboardView(
                session.getId(),
                new SummaryView(
                        portfolioValueUsd,
                        totalUnrealizedPnlUsd,
                        totalUnrealizedPnlPct,
                        totalRealisedPnlUsd,
                        conservation.netExternalCapitalUsd(),
                        conservation.lifetimeExternalInflowUsd(),
                        conservation.markToMarketUsd(),
                        conservation.expectedPnlUsd(),
                        conservation.reportedPnlUsd(),
                        conservation.conservationDeltaUsd(),
                        conservation.conservationThresholdUsd(),
                        conservation.conservationBreached()
                ),
                wallets,
                tokenPositions
        );
    }

    private static BigDecimal physicalQuantityAfter(AssetLedgerPoint latestPoint) {
        if (latestPoint == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal quantityAfter = zeroIfNull(latestPoint.getQuantityAfter());
        BigDecimal shortfallAfter = zeroIfNull(latestPoint.getQuantityShortfallAfter());
        return quantityAfter.subtract(shortfallAfter, MC);
    }

    /**
     * For each CEX umbrella accumulator, compares the ledger-computed quantity against the
     * authoritative live balance for that integration. Phantom positions (ledger > live) are
     * clamped; ledger shortfalls (ledger < live) are inflated. AVCO metrics are scaled proportionally.
     */
    private void clampCexUmbrellaToLive(
            Map<FamilyRowKey, TokenPositionAccumulator> rows,
            UserSession session,
            Set<String> enabledCexVenueRefs
    ) {
        if (enabledCexVenueRefs.isEmpty() || session.getIntegrations() == null) {
            return;
        }
        Map<String, Map<String, BigDecimal>> liveByAccountRef = new LinkedHashMap<>();
        Map<String, CexLiveBalancePort.Availability> liveAvailabilityByAccountRef = new LinkedHashMap<>();
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getAccountRef() == null
                    || integration.getIntegrationId() == null) {
                continue;
            }
            WalletRef ref = WalletRef.parse(integration.getAccountRef());
            if (ref.domain() != WalletDomainKind.CEX) {
                continue;
            }
            Optional<CexLiveBalancePort.SnapshotView> snapshotView =
                    cexLiveBalancePort.getSnapshotView(integration.getIntegrationId());
            if (snapshotView.isEmpty()) {
                continue;
            }
            CexLiveBalancePort.SnapshotView view = snapshotView.get();
            String accountRef = normalizeAddress(integration.getAccountRef());
            liveAvailabilityByAccountRef.put(accountRef, view.availability());
            if (view.availability() == CexLiveBalancePort.Availability.UNKNOWN) {
                continue;
            }
            liveByAccountRef.put(accountRef, view.umbrella());
        }
        if (liveByAccountRef.isEmpty() && liveAvailabilityByAccountRef.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<FamilyRowKey, TokenPositionAccumulator>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FamilyRowKey, TokenPositionAccumulator> entry = iterator.next();
            FamilyRowKey key = entry.getKey();
            TokenPositionAccumulator accumulator = entry.getValue();
            if (key.walletAddress() == null) {
                continue;
            }
            WalletRef walletRef = WalletRef.parse(key.walletAddress());
            if (walletRef.domain() != WalletDomainKind.CEX) {
                continue;
            }
            Map<String, BigDecimal> live = liveByAccountRef.get(key.walletAddress());
            CexLiveBalancePort.Availability availability = liveAvailabilityByAccountRef.get(key.walletAddress());
            if (availability == CexLiveBalancePort.Availability.KNOWN_EMPTY) {
                iterator.remove();
                continue;
            }
            if (live == null || live.isEmpty()) {
                // No authoritative live snapshot yet — preserve ledger rows.
                continue;
            }
            BigDecimal liveQty = CexUmbrellaSupport.liveQuantityForCandidates(
                    live,
                    accumulator.priceLookupCandidates(),
                    accumulator.priceLookupSymbol()
            );
            BigDecimal ledgerQty = accumulator.getQuantity();
            if (ledgerQty == null || ledgerQty.signum() <= 0) {
                continue;
            }
            if (liveQty == null) {
                // Symbol absent from live balance map → live data unavailable for this asset;
                // keep the ledger row rather than dropping it as a phantom position.
                continue;
            }
            if (liveQty.signum() <= 0) {
                iterator.remove();
                continue;
            }
            if (ledgerQty.compareTo(liveQty) > 0) {
                accumulator.clampQuantity(liveQty);
            } else if (ledgerQty.compareTo(liveQty) < 0) {
                accumulator.inflateToLive(liveQty);
            }
        }
    }

    /**
     * Creates dashboard rows for CEX assets that appear in the live balance but have no ledger entries.
     */
    private void addMissingLiveCexRows(
            Map<FamilyRowKey, TokenPositionAccumulator> rows,
            UserSession session
    ) {
        if (session.getIntegrations() == null) {
            return;
        }
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getAccountRef() == null
                    || integration.getIntegrationId() == null) {
                continue;
            }
            WalletRef ref = WalletRef.parse(integration.getAccountRef());
            if (ref.domain() != WalletDomainKind.CEX) {
                continue;
            }
            String umbrellaWallet = ref.umbrellaKey().toLowerCase(Locale.ROOT);
            Map<String, BigDecimal> live = cexLiveBalancePort.getSnapshotView(integration.getIntegrationId())
                    .map(CexLiveBalancePort.SnapshotView::umbrella)
                    .orElse(Map.of());
            if (live == null || live.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> liveEntry : live.entrySet()) {
                String symbol = liveEntry.getKey();
                BigDecimal liveQty = liveEntry.getValue();
                if (liveQty == null || liveQty.signum() <= 0) {
                    continue;
                }
                String normalizedSymbol = normalizeSymbol(symbol);
                if (normalizedSymbol.isBlank()) {
                    continue;
                }
                String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(normalizedSymbol, null);
                if (familyIdentity == null || familyIdentity.isBlank()) {
                    familyIdentity = "SYMBOL:" + normalizedSymbol;
                }
                FamilyRowKey rowKey = new FamilyRowKey(umbrellaWallet, null, familyIdentity);
                if (rows.containsKey(rowKey)) {
                    continue;
                }
                boolean alreadyCovered = rows.entrySet().stream()
                        .anyMatch(e -> umbrellaWallet.equals(e.getKey().walletAddress())
                                && e.getValue().priceLookupCandidates().contains(normalizedSymbol));
                if (alreadyCovered) {
                    continue;
                }
                TokenPositionAccumulator accumulator = new TokenPositionAccumulator(
                        familyIdentity,
                        normalizedSymbol,
                        displayName(normalizedSymbol),
                        null,
                        umbrellaWallet
                );
                accumulator.inflateToLive(liveQty);
                accumulator.setIssueDirect(ISSUE_MISSING_REPLAY_POINT);
                rows.put(rowKey, accumulator);
            }
        }
    }

    private List<OnChainBalance> loadOnChainBalances(String sessionId, Collection<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("sessionId").is(sessionId),
                        Criteria.where("walletAddress").in(walletAddresses)
                ))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("assetContract"),
                        Sort.Order.asc("capturedAt")
                ));
        return mongoOperations.find(query, OnChainBalance.class);
    }

    private List<AssetLedgerPoint> loadAssetLedgerPoints(String accountingUniverseId) {
        if (accountingUniverseId == null || accountingUniverseId.isBlank()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("accountingUniverseId").is(accountingUniverseId))
                .with(Sort.by(
                        Sort.Order.asc("walletAddress"),
                        Sort.Order.asc("networkId"),
                        Sort.Order.asc("accountingAssetIdentity"),
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("replaySequence")
                ));
        return mongoOperations.find(query, AssetLedgerPoint.class);
    }

    private Map<String, DashboardPriceSnapshot> loadLatestPrices(
            Collection<String> symbols,
            Instant responseTime
    ) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, DashboardPriceSnapshot> latestPrices = new LinkedHashMap<>();

        // Stablecoin pins — handled locally to avoid mock dependencies in tests
        for (String symbol : symbols) {
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, symbol, null)) {
                latestPrices.put(normalizeSymbol(symbol), DashboardPriceSnapshot.stablecoin(responseTime));
            }
        }

        // Resolve current prices via shared service (multi-source selection)
        Map<String, ResolvedPrice> resolved = currentPriceReadService.resolveLatest(symbols);
        for (Map.Entry<String, ResolvedPrice> entry : resolved.entrySet()) {
            String sym = normalizeSymbol(entry.getKey());
            if (!latestPrices.containsKey(sym)) {
                latestPrices.put(sym, DashboardPriceSnapshot.fromResolved(entry.getValue(), responseTime));
            }
        }

        // Historical fallback for symbols not covered by the latest-price refresh subsystem
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (String symbol : symbols) {
            if (!latestPrices.containsKey(normalizeSymbol(symbol))) {
                missing.add(symbol);
            }
        }
        if (!missing.isEmpty()) {
            Query historicalQuery = Query.query(Criteria.where("symbol").in(missing))
                    .with(Sort.by(
                            Sort.Order.desc("bucketStart"),
                            Sort.Order.desc("fetchedAt")
                    ));
            List<HistoricalPriceDocument> historicalPrices = mongoOperations.find(historicalQuery, HistoricalPriceDocument.class);
            if (historicalPrices != null) {
                for (HistoricalPriceDocument document : historicalPrices) {
                    String symbol = normalizeSymbol(document.getSymbol());
                    latestPrices.putIfAbsent(symbol, DashboardPriceSnapshot.historicalFallback(document, responseTime));
                }
            }
        }

        return latestPrices;
    }

    private Map<BucketKey, OnChainBalance> latestBalanceByBucket(List<OnChainBalance> balances) {
        Map<BucketKey, OnChainBalance> latest = new LinkedHashMap<>();
        for (OnChainBalance balance : balances) {
            String assetIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    balance.getNetworkId(),
                    balance.getAssetSymbol(),
                    balance.getAssetContract()
            );
            BucketKey key = new BucketKey(
                    normalizeAddress(balance.getWalletAddress()),
                    balance.getNetworkId(),
                    assetIdentity
            );
            latest.put(key, balance);
        }
        return latest;
    }

    private Map<BucketKey, AssetLedgerPoint> latestLedgerPointByBucket(List<AssetLedgerPoint> points) {
        Map<BucketKey, AssetLedgerPoint> latest = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            BucketKey key = new BucketKey(
                    normalizeAddress(point.getWalletAddress()),
                    point.getNetworkId(),
                    point.getAccountingAssetIdentity()
            );
            latest.put(key, point);
        }
        return latest;
    }

    private Map<FamilyRowKey, BigDecimal> pnlDeltaByFamily(
            List<AssetLedgerPoint> points,
            LinkedHashSet<String> enabledCexVenueRefs,
            java.util.function.Function<AssetLedgerPoint, BigDecimal> deltaExtractor
    ) {
        Map<FamilyRowKey, BigDecimal> totals = new LinkedHashMap<>();
        for (AssetLedgerPoint point : points) {
            if (blank(point.getAccountingFamilyIdentity())) {
                continue;
            }
            String aggregatedWallet;
            if (point.getNetworkId() == null) {
                // CEX wallets have no networkId; filter and aggregate by umbrella key.
                if (!CexUmbrellaSupport.cexLedgerMatchesEnabledVenue(point.getWalletAddress(), enabledCexVenueRefs)) {
                    continue;
                }
                aggregatedWallet = CexUmbrellaSupport.ledgerWalletKeyForAggregation(
                        point.getWalletAddress(),
                        enabledCexVenueRefs
                );
            } else {
                aggregatedWallet = normalizeAddress(point.getWalletAddress());
            }
            String familyIdentity = resolvedFamilyIdentity(
                    point,
                    point.getNetworkId(),
                    point.getAssetSymbol(),
                    point.getAssetContract()
            );
            FamilyRowKey key = new FamilyRowKey(
                    aggregatedWallet,
                    point.getNetworkId(),
                    familyIdentity
            );
            totals.merge(key, zeroIfNull(deltaExtractor.apply(point)), (left, right) -> left.add(right, MC));
        }
        return totals;
    }

    private Map<String, BreakEvenCalculator.BreakEvenResult> computeBreakEvenByFamily(
            List<TokenPositionView> positions,
            Map<FamilyRowKey, BigDecimal> realisedPnlByFamily,
            Map<FamilyRowKey, BigDecimal> netRealisedPnlByFamily
    ) {
        Map<String, BigDecimal> marketBasisByFamily = new LinkedHashMap<>();
        Map<String, BigDecimal> coveredByFamily = new LinkedHashMap<>();
        Map<String, String> symbolByFamily = new LinkedHashMap<>();
        for (TokenPositionView position : positions) {
            String family = position.familyIdentity();
            if (blank(family)) {
                continue;
            }
            marketBasisByFamily.merge(family, zeroIfNull(position.provableBasisUsd()), (left, right) -> left.add(right, MC));
            coveredByFamily.merge(family, zeroIfNull(position.coveredQuantity()), (left, right) -> left.add(right, MC));
            symbolByFamily.putIfAbsent(family, position.symbol());
        }
        Map<String, BigDecimal> marketPnlByFamily = aggregatePnlByFamily(realisedPnlByFamily);
        Map<String, BigDecimal> netPnlByFamily = aggregatePnlByFamily(netRealisedPnlByFamily);

        LinkedHashSet<String> families = new LinkedHashSet<>();
        families.addAll(marketBasisByFamily.keySet());
        families.addAll(marketPnlByFamily.keySet());
        families.addAll(netPnlByFamily.keySet());
        List<BreakEvenCalculator.FamilyBreakEvenInput> inputs = new ArrayList<>();
        for (String family : families) {
            inputs.add(new BreakEvenCalculator.FamilyBreakEvenInput(
                    family,
                    BreakEvenAttributionService.representativeSymbolFor(family, symbolByFamily.get(family)),
                    zeroIfNull(marketBasisByFamily.get(family)),
                    zeroIfNull(coveredByFamily.get(family)),
                    zeroIfNull(marketPnlByFamily.get(family)),
                    zeroIfNull(netPnlByFamily.get(family))
            ));
        }
        return breakEvenCalculator.compute(inputs);
    }

    private static Map<String, BigDecimal> aggregatePnlByFamily(Map<FamilyRowKey, BigDecimal> byRow) {
        Map<String, BigDecimal> byFamily = new LinkedHashMap<>();
        for (Map.Entry<FamilyRowKey, BigDecimal> entry : byRow.entrySet()) {
            String family = entry.getKey().familyIdentity();
            if (blank(family)) {
                continue;
            }
            byFamily.merge(family, zeroIfNull(entry.getValue()), (left, right) -> left.add(right, MC));
        }
        return byFamily;
    }

    private static String fallbackFamilyIdentity(NetworkId networkId, String assetSymbol, String assetContract) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(assetSymbol, assetContract);
        if (familyIdentity != null && familyIdentity.startsWith("FAMILY:")) {
            return familyIdentity;
        }
        String symbol = normalizeSymbol(assetSymbol);
        String identity = AccountingAssetIdentitySupport.positionAssetIdentity(networkId, assetSymbol, assetContract);
        if (!blank(identity)) {
            return identity;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static String displayName(String symbol) {
        return switch (normalizeSymbol(symbol)) {
            case "BTC" -> "Bitcoin";
            case "ETH" -> "Ethereum";
            case "AVAX" -> "Avalanche";
            case "USDC" -> "USD Coin";
            case "WBTC" -> "Wrapped Bitcoin";
            case "MNT" -> "Mantle";
            case "BNB" -> "BNB";
            case "MATIC" -> "Polygon";
            default -> blank(symbol) ? "Unknown asset" : symbol.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static DashboardPriceSnapshot resolvePrice(Map<String, DashboardPriceSnapshot> latestPricesBySymbol, String symbol) {
        return resolvePrice(latestPricesBySymbol, CexUmbrellaSupport.priceLookupCandidates(symbol));
    }

    private static DashboardPriceSnapshot resolvePrice(
            Map<String, DashboardPriceSnapshot> latestPricesBySymbol,
            Collection<String> candidates
    ) {
        for (String candidate : candidates) {
            DashboardPriceSnapshot price = latestPricesBySymbol.get(candidate);
            if (price != null && price.priceUsd() != null) {
                return price;
            }
        }
        return DashboardPriceSnapshot.missing();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String resolvedFamilyIdentity(
            AssetLedgerPoint latestPoint,
            NetworkId networkId,
            String assetSymbol,
            String assetContract
    ) {
        String fallbackFamilyIdentity = fallbackFamilyIdentity(networkId, assetSymbol, assetContract);
        if (latestPoint == null || blank(latestPoint.getAccountingFamilyIdentity())) {
            return fallbackFamilyIdentity;
        }
        String latestFamilyIdentity = latestPoint.getAccountingFamilyIdentity();
        if (!latestFamilyIdentity.startsWith("FAMILY:")
                && fallbackFamilyIdentity != null
                && fallbackFamilyIdentity.startsWith("FAMILY:")) {
            return fallbackFamilyIdentity;
        }
        return latestFamilyIdentity;
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SessionDashboardView(
            String sessionId,
            SummaryView summary,
            List<WalletView> wallets,
            List<TokenPositionView> tokenPositions
    ) {
    }

    public record SummaryView(
            BigDecimal portfolioValueUsd,
            BigDecimal totalUnrealizedPnlUsd,
            BigDecimal totalUnrealizedPnlPct,
            BigDecimal totalRealizedPnlUsd,
            BigDecimal netExternalCapitalUsd,
            BigDecimal lifetimeExternalInflowUsd,
            BigDecimal markToMarketUsd,
            BigDecimal expectedPnlUsd,
            BigDecimal reportedPnlUsd,
            BigDecimal conservationDeltaUsd,
            BigDecimal conservationThresholdUsd,
            boolean conservationBreached
    ) {
    }

    public record WalletView(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }

    public record TokenPositionView(
            String familyIdentity,
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal priceUsd,
            BigDecimal marketValueUsd,
            String priceSource,
            Instant pricedAt,
            Long stalenessSeconds,
            Boolean isLiveQuote,
            String priceIssue,
            BigDecimal avcoUsd,
            BigDecimal netAvcoUsd,
            BigDecimal unrealizedPnlPct,
            BigDecimal unrealizedPnlUsd,
            BigDecimal realizedPnlUsd,
            String networkId,
            String walletAddress,
            String issue,
            String valuationModel,
            String valuationUnderlyingSymbol,
            String unsupportedValuationReason,
            /** Wallet domain: EVM, SOLANA, TON, or CEX. Derived from walletAddress via WalletRef. */
            String domain,
            /** CEX venue id (e.g. "bybit", "dzengi"); null for on-chain wallets. */
            String venueId,
            /** CEX sub-account kind (e.g. "FUND", "UTA", "EARN"); null if not applicable. */
            String subAccount,
            /** ADR-062 break-even (effective-cost) per unit; null when covered quantity is zero. */
            BigDecimal breakEvenUsd,
            /** ADR-062 realized profit already past break-even (effective basis floored at zero). */
            BigDecimal lockedSurplusUsd,
            /** ADR-062 informational zero-basis income booked against this family's cluster. */
            BigDecimal incomeReceivedUsd,
            /** ADR-062 parent family this row's realized P&L contributes to; null when self. */
            String attributionTargetFamily
    ) {
        public BigDecimal marketValueUsd() {
            return marketValueUsd == null ? BigDecimal.ZERO : marketValueUsd;
        }

        public BigDecimal provableBasisUsd() {
            return avcoUsd.multiply(coveredQuantity, MC);
        }

        public TokenPositionView withBreakEven(
                BigDecimal breakEvenUsd,
                BigDecimal lockedSurplusUsd,
                BigDecimal incomeReceivedUsd,
                String attributionTargetFamily
        ) {
            return new TokenPositionView(
                    familyIdentity, symbol, name, quantity, coveredQuantity, priceUsd, marketValueUsd,
                    priceSource, pricedAt, stalenessSeconds, isLiveQuote, priceIssue, avcoUsd, netAvcoUsd,
                    unrealizedPnlPct, unrealizedPnlUsd, realizedPnlUsd, networkId, walletAddress, issue,
                    valuationModel, valuationUnderlyingSymbol, unsupportedValuationReason, domain, venueId,
                    subAccount, breakEvenUsd, lockedSurplusUsd, incomeReceivedUsd, attributionTargetFamily
            );
        }
    }

    private record BucketKey(
            String walletAddress,
            NetworkId networkId,
            String accountingAssetIdentity
    ) {
    }

    private record FamilyRowKey(
            String walletAddress,
            NetworkId networkId,
            String familyIdentity
    ) {
    }

    private static final class TokenPositionAccumulator {
        private final String familyIdentity;
        private final String symbol;
        private final String name;
        private final NetworkId networkId;
        private final String walletAddress;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal coveredQuantity = BigDecimal.ZERO;
        private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
        private BigDecimal totalNetCostBasisUsd = BigDecimal.ZERO;
        private String issue;
        private ProtocolValuationMetadata valuationMetadata;

        private TokenPositionAccumulator(
                String familyIdentity,
                String symbol,
                String name,
                NetworkId networkId,
                String walletAddress
        ) {
            this.familyIdentity = familyIdentity;
            this.symbol = symbol;
            this.name = name;
            this.networkId = networkId;
            this.walletAddress = walletAddress;
        }

        private void addBalance(BigDecimal currentQuantity, AssetLedgerPoint latestPoint, String protocolLookupSymbol) {
            quantity = quantity.add(currentQuantity, MC);
            BigDecimal exactCoveredQuantity = latestPoint == null
                    ? BigDecimal.ZERO
                    : zeroIfNull(latestPoint.getBasisBackedQuantityAfter()).min(currentQuantity);
            coveredQuantity = coveredQuantity.add(exactCoveredQuantity, MC);
            if (latestPoint != null && exactCoveredQuantity.signum() > 0) {
                if (latestPoint.getAvcoAfterUsd() != null) {
                    totalCostBasisUsd = totalCostBasisUsd.add(
                            latestPoint.getAvcoAfterUsd().multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
                BigDecimal netAvcoPoint = latestPoint.getNetAvcoAfterUsd() != null
                        ? latestPoint.getNetAvcoAfterUsd()
                        : latestPoint.getAvcoAfterUsd();
                if (netAvcoPoint != null) {
                    totalNetCostBasisUsd = totalNetCostBasisUsd.add(
                            netAvcoPoint.multiply(exactCoveredQuantity, MC),
                            MC
                    );
                }
            }
            issue = mergeIssueCodes(issue, classifyIssue(latestPoint, currentQuantity, exactCoveredQuantity));
            String valuationLookup = blank(protocolLookupSymbol) ? symbol : protocolLookupSymbol;
            valuationMetadata = mergeValuationMetadata(valuationMetadata, protocolValuationMetadata(valuationLookup));
        }

        private BigDecimal getQuantity() {
            return quantity;
        }

        /**
         * Cycle/5 N15: scales the accumulator's running totals proportionally so that {@code quantity}
         * does not exceed the supplied authoritative live value. AVCO-relevant totals
         * ({@code coveredQuantity}, {@code totalCostBasisUsd}) are kept proportional so the displayed
         * cost basis remains a true scalar of the clamped position; unrealized PnL and AVCO are then
         * derived correctly in {@link #toView}.
         */
        private void clampQuantity(BigDecimal liveLimit) {
            if (liveLimit == null || liveLimit.signum() < 0 || quantity == null || quantity.signum() <= 0) {
                return;
            }
            if (quantity.compareTo(liveLimit) <= 0) {
                return;
            }
            BigDecimal scale = liveLimit.divide(quantity, MC);
            quantity = liveLimit;
            coveredQuantity = coveredQuantity.multiply(scale, MC);
            totalCostBasisUsd = totalCostBasisUsd.multiply(scale, MC);
            totalNetCostBasisUsd = totalNetCostBasisUsd.multiply(scale, MC);
        }

        /**
         * Inflates the displayed quantity to the authoritative live value when the ledger tracks
         * fewer units than the Bybit API reports. AVCO metrics ({@code coveredQuantity},
         * {@code totalCostBasisUsd}) are left unchanged — the delta between the live quantity and
         * the ledger quantity is genuinely uncovered inventory with no provable cost basis.
         */
        private void inflateToLive(BigDecimal liveQty) {
            if (liveQty == null || liveQty.signum() <= 0 || quantity == null) {
                return;
            }
            if (quantity.compareTo(liveQty) >= 0) {
                return;
            }
            quantity = liveQty;
        }

        private void setIssueDirect(String issueCode) {
            this.issue = issueCode;
        }

        private String priceLookupSymbol() {
            return normalizeSymbol(symbol);
        }

        private List<String> priceLookupCandidates() {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.addAll(CexUmbrellaSupport.priceLookupCandidates(priceLookupSymbol()));
            if (valuationMetadata != null && !blank(valuationMetadata.underlyingSymbol())) {
                candidates.addAll(CexUmbrellaSupport.priceLookupCandidates(valuationMetadata.underlyingSymbol()));
            }
            return List.copyOf(candidates);
        }

        private TokenPositionView toView(DashboardPriceSnapshot priceSnapshot, BigDecimal realizedPnlUsd) {
            BigDecimal priceUsd = priceSnapshot.priceUsd() == null ? BigDecimal.ZERO : priceSnapshot.priceUsd();
            DashboardPriceSnapshot effectivePriceSnapshot = applyProtocolValuation(priceSnapshot, valuationMetadata);
            priceUsd = effectivePriceSnapshot.priceUsd() == null ? BigDecimal.ZERO : effectivePriceSnapshot.priceUsd();
            // marketValueUsd is null when price is unavailable (priceUsd=0 from missing snapshot) so
            // that the minimum-position-value filter does not silently drop unpriced positions.
            BigDecimal marketValueUsd = priceUsd.signum() <= 0
                    ? null
                    : quantity
                            .multiply(priceUsd, MC)
                            .multiply(BigDecimal.valueOf(valuationMetadata == null ? 1 : valuationMetadata.quantitySign()), MC);
            BigDecimal rawAvcoUsd = coveredQuantity.signum() <= 0
                    ? BigDecimal.ZERO
                    : totalCostBasisUsd.divide(coveredQuantity, MC);
            // Cycle/19: when coverage is extremely low (< 5%), the AVCO denominator is tiny and
            // produces absurd values (e.g. LTC $3.76B). Cap at 10× market price when coverage is
            // below 5% so the dashboard shows a directional hint rather than a misleading number.
            BigDecimal coverageRatio = quantity.signum() > 0
                    ? coveredQuantity.divide(quantity, MC)
                    : BigDecimal.ONE;
            BigDecimal avcoUsd = rawAvcoUsd;
            BigDecimal rawNetAvcoUsd = coveredQuantity.signum() <= 0
                    ? BigDecimal.ZERO
                    : totalNetCostBasisUsd.divide(coveredQuantity, MC);
            BigDecimal netAvcoUsd = rawNetAvcoUsd;
            if (coverageRatio.compareTo(AVCO_CAP_COVERAGE_THRESHOLD) < 0
                    && priceUsd.signum() > 0
                    && rawAvcoUsd.compareTo(priceUsd.multiply(AVCO_CAP_PRICE_MULTIPLIER, MC)) > 0) {
                avcoUsd = priceUsd;
            }
            if (coverageRatio.compareTo(AVCO_CAP_COVERAGE_THRESHOLD) < 0
                    && priceUsd.signum() > 0
                    && rawNetAvcoUsd.compareTo(priceUsd.multiply(AVCO_CAP_PRICE_MULTIPLIER, MC)) > 0) {
                netAvcoUsd = priceUsd;
            }
            BigDecimal unrealizedPnlUsd = coveredQuantity.multiply(priceUsd, MC).subtract(totalCostBasisUsd, MC);
            BigDecimal unrealizedPnlPct = totalCostBasisUsd.signum() <= 0
                    ? BigDecimal.ZERO
                    : unrealizedPnlUsd.multiply(BigDecimal.valueOf(100), MC).divide(totalCostBasisUsd, MC);
            WalletRef walletRef = WalletRef.parse(walletAddress);
            return new TokenPositionView(
                    familyIdentity,
                    symbol,
                    name,
                    quantity,
                    coveredQuantity,
                    priceUsd,
                    marketValueUsd,
                    effectivePriceSnapshot.priceSource(),
                    effectivePriceSnapshot.pricedAt(),
                    effectivePriceSnapshot.stalenessSeconds(),
                    effectivePriceSnapshot.isLiveQuote(),
                    effectivePriceSnapshot.priceIssue(),
                    avcoUsd,
                    netAvcoUsd,
                    unrealizedPnlPct,
                    unrealizedPnlUsd,
                    realizedPnlUsd,
                    dashboardNetworkLabel(networkId, walletAddress),
                    walletAddress,
                    issue,
                    valuationMetadata == null ? null : valuationMetadata.model(),
                    valuationMetadata == null ? null : valuationMetadata.underlyingSymbol(),
                    effectivePriceSnapshot.unsupportedValuationReason(),
                    walletRef.domain().name(),
                    walletRef.venueId(),
                    walletRef.subAccount(),
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    /**
     * CEX wallets replay with {@code networkId == null}; derive the dashboard network label from
     * the venue ID embedded in the wallet address via {@link WalletRef}.
     */
    private static String dashboardNetworkLabel(NetworkId networkId, String walletAddress) {
        if (networkId != null) {
            return networkId.name();
        }
        if (walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() == WalletDomainKind.CEX) {
            return ref.venueId().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static ProtocolValuationMetadata mergeValuationMetadata(
            ProtocolValuationMetadata current,
            ProtocolValuationMetadata candidate
    ) {
        return current == null ? candidate : current;
    }

    private static ProtocolValuationMetadata protocolValuationMetadata(String symbol) {
        String normalized = normalizeSymbol(symbol);
        String aaveUnderlying = aaveUnderlyingSymbol(normalized);
        if (aaveUnderlying != null) {
            return new ProtocolValuationMetadata(
                    VALUATION_AAVE_INDEX_ACCRUING,
                    aaveUnderlying,
                    normalized.startsWith("VARIABLEDEBT") ? -1 : 1
            );
        }
        if (normalized.startsWith("GM:") || normalized.startsWith("GLV")) {
            return new ProtocolValuationMetadata(VALUATION_GMX_MARKET_TOKEN_SNAPSHOT, null, 1);
        }
        return null;
    }

    private static String aaveUnderlyingSymbol(String symbol) {
        if (blank(symbol)) {
            return null;
        }
        if (symbol.startsWith("VARIABLEDEBT")) {
            return stripAaveNetworkPrefix(symbol.substring("VARIABLEDEBT".length()));
        }
        for (String prefix : List.of("AMAN", "AARB", "AAVA", "AETH", "ALIN", "AZKS")) {
            if (symbol.startsWith(prefix) && symbol.length() > prefix.length()) {
                return stripAaveWrappedPrefix(symbol.substring(prefix.length()));
            }
        }
        return null;
    }

    private static String stripAaveNetworkPrefix(String symbol) {
        for (String prefix : List.of("MAN", "ARB", "AVA", "ETH", "LIN", "ZKS")) {
            if (symbol.startsWith(prefix) && symbol.length() > prefix.length()) {
                return stripAaveWrappedPrefix(symbol.substring(prefix.length()));
            }
        }
        return stripAaveWrappedPrefix(symbol);
    }

    private static String stripAaveWrappedPrefix(String symbol) {
        return switch (symbol) {
            case "WETH" -> "ETH";
            case "WBTC" -> "BTC";
            case "WMNT" -> "MNT";
            case "WAVAX" -> "AVAX";
            default -> symbol;
        };
    }

    private static DashboardPriceSnapshot applyProtocolValuation(
            DashboardPriceSnapshot priceSnapshot,
            ProtocolValuationMetadata metadata
    ) {
        if (metadata == null) {
            return priceSnapshot;
        }
        if (VALUATION_GMX_MARKET_TOKEN_SNAPSHOT.equals(metadata.model())
                && (priceSnapshot == null || priceSnapshot.priceUsd() == null || priceSnapshot.priceUsd().signum() <= 0)) {
            return DashboardPriceSnapshot.unsupportedProtocolValuation("GMX market token snapshot is unavailable");
        }
        if (VALUATION_AAVE_INDEX_ACCRUING.equals(metadata.model())) {
            return priceSnapshot == null
                    ? DashboardPriceSnapshot.missing()
                    : priceSnapshot.withSource(VALUATION_AAVE_INDEX_ACCRUING);
        }
        if (VALUATION_GMX_MARKET_TOKEN_SNAPSHOT.equals(metadata.model())) {
            return priceSnapshot == null
                    ? DashboardPriceSnapshot.unsupportedProtocolValuation("GMX market token snapshot is unavailable")
                    : priceSnapshot.withSource(PRICE_SOURCE_PROTOCOL_SNAPSHOT);
        }
        return priceSnapshot == null ? DashboardPriceSnapshot.missing() : priceSnapshot;
    }

    private static String classifyIssue(
            AssetLedgerPoint latestPoint,
            BigDecimal currentQuantity,
            BigDecimal exactCoveredQuantity
    ) {
        if (latestPoint == null) {
            return ISSUE_MISSING_REPLAY_POINT;
        }
        boolean uncoveredQuantity = exactCoveredQuantity.compareTo(currentQuantity) < 0;
        boolean incompleteHistory = Boolean.TRUE.equals(latestPoint.getHasIncompleteHistoryAfter());
        boolean unresolvedFlags = Boolean.TRUE.equals(latestPoint.getHasUnresolvedFlagsAfter());
        if (uncoveredQuantity && !incompleteHistory && !unresolvedFlags && isYieldAccrualCandidate(latestPoint)) {
            return ISSUE_YIELD_ACCRUAL;
        }
        if (uncoveredQuantity) {
            return ISSUE_COVERAGE_GAP;
        }
        if (incompleteHistory || unresolvedFlags) {
            if (uncoveredQuantity) {
                return ISSUE_COVERAGE_GAP;
            }
            return ISSUE_HISTORY_FLAGS;
        }
        return null;
    }

    /**
     * Returns true if {@code walletAddress} is a bare umbrella CEX address (no sub-account suffix).
     * A bare umbrella has exactly two colon-delimited parts: {@code venue:uid}.
     */
    private static boolean isBareCexUmbrellaWallet(String walletAddress) {
        if (walletAddress == null) {
            return false;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        return ref.domain() == WalletDomainKind.CEX && (ref.subAccount() == null || ref.subAccount().isBlank());
    }

    /**
     * Returns true if any sub-account wallet for the given umbrella has a ledger point with the
     * matching family and non-zero raw quantity.
     */
    private static boolean hasCexVenueSubLedgerForFamily(
            Map<BucketKey, AssetLedgerPoint> latestPointByBucket,
            String umbrellaWallet,
            String familyIdentity,
            Set<String> enabledCexVenueRefs
    ) {
        if (latestPointByBucket == null || umbrellaWallet == null || familyIdentity == null) {
            return false;
        }
        String base = normalizeAddress(umbrellaWallet);
        for (Map.Entry<BucketKey, AssetLedgerPoint> entry : latestPointByBucket.entrySet()) {
            BucketKey bucketKey = entry.getKey();
            String wallet = bucketKey.walletAddress();
            if (wallet == null || wallet.equals(base) || !wallet.startsWith(base + ":")) {
                continue;
            }
            WalletRef ref = WalletRef.parse(wallet);
            if (ref.domain() != WalletDomainKind.CEX || ref.subAccount() == null || ref.subAccount().isBlank()) {
                continue;
            }
            if (!CexUmbrellaSupport.cexLedgerMatchesEnabledVenue(wallet, enabledCexVenueRefs)) {
                continue;
            }
            AssetLedgerPoint point = entry.getValue();
            if (point == null) {
                continue;
            }
            String pointFamily = resolvedFamilyIdentity(
                    point,
                    bucketKey.networkId(),
                    point.getAssetSymbol(),
                    point.getAssetContract()
            );
            if (familyIdentity.equals(pointFamily) && CexUmbrellaSupport.cexRawQuantityAfter(point).signum() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isYieldAccrualCandidate(AssetLedgerPoint latestPoint) {
        if (latestPoint.getBasisEffect() != AssetLedgerPoint.BasisEffect.REALLOCATE_IN) {
            return false;
        }
        return latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.LENDING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.STAKING
                || latestPoint.getLifecycleKind() == AssetLedgerPoint.LifecycleKind.VAULT;
    }

    private static String mergeIssueCodes(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return issueRank(right) > issueRank(left) ? right : left;
    }

    private static int issueRank(String issue) {
        return switch (issue) {
            case ISSUE_MISSING_REPLAY_POINT -> 4;
            case ISSUE_COVERAGE_GAP -> 3;
            case ISSUE_HISTORY_FLAGS -> 2;
            case ISSUE_YIELD_ACCRUAL -> 1;
            default -> 0;
        };
    }

    private record DashboardPriceSnapshot(
            BigDecimal priceUsd,
            String priceSource,
            Instant pricedAt,
            Long stalenessSeconds,
            Boolean isLiveQuote,
            String priceIssue,
            String unsupportedValuationReason
    ) {
        private static DashboardPriceSnapshot stablecoin(Instant responseTime) {
            return new DashboardPriceSnapshot(
                    BigDecimal.ONE,
                    PriceSource.STABLECOIN.name(),
                    responseTime,
                    0L,
                    true,
                    null,
                    null
            );
        }

        private static DashboardPriceSnapshot current(CurrentPriceQuoteDocument document, Instant responseTime) {
            Instant pricedAt = document.getPricedAt() == null ? document.getFetchedAt() : document.getPricedAt();
            Long stalenessSeconds = stalenessSeconds(pricedAt, responseTime);
            String priceIssue = stalenessSeconds != null && stalenessSeconds > CURRENT_QUOTE_STALE_AFTER_SECONDS
                    ? PRICE_ISSUE_STALE
                    : null;
            return new DashboardPriceSnapshot(
                    document.getPriceUsd(),
                    document.getSource() == null ? null : document.getSource().name(),
                    pricedAt,
                    stalenessSeconds,
                    true,
                    priceIssue,
                    null
            );
        }

        private static DashboardPriceSnapshot fromResolved(ResolvedPrice resolved, Instant responseTime) {
            Long stalenessSeconds = stalenessSeconds(resolved.pricedAt(), responseTime);
            String priceIssue = resolved.stale() ? PRICE_ISSUE_STALE : null;
            return new DashboardPriceSnapshot(
                    resolved.priceUsd(),
                    resolved.source() == null ? null : resolved.source().name(),
                    resolved.pricedAt(),
                    stalenessSeconds,
                    !resolved.stale(),
                    priceIssue,
                    null
            );
        }

        private static DashboardPriceSnapshot historicalFallback(HistoricalPriceDocument document, Instant responseTime) {
            Instant pricedAt = document.getFetchedAt() == null ? document.getBucketStart() : document.getFetchedAt();
            return new DashboardPriceSnapshot(
                    document.getPriceUsd(),
                    document.getSource() == null ? null : document.getSource().name(),
                    pricedAt,
                    stalenessSeconds(pricedAt, responseTime),
                    false,
                    PRICE_ISSUE_HISTORICAL_FALLBACK,
                    null
            );
        }

        private static DashboardPriceSnapshot missing() {
            return new DashboardPriceSnapshot(
                    BigDecimal.ZERO,
                    null,
                    null,
                    null,
                    false,
                    PRICE_ISSUE_MISSING,
                    null
            );
        }

        private static DashboardPriceSnapshot unsupportedProtocolValuation(String reason) {
            return new DashboardPriceSnapshot(
                    BigDecimal.ZERO,
                    "PROTOCOL_SNAPSHOT",
                    null,
                    null,
                    false,
                    PRICE_ISSUE_UNSUPPORTED_PROTOCOL_VALUATION,
                    reason
            );
        }

        private DashboardPriceSnapshot withSource(String source) {
            return new DashboardPriceSnapshot(
                    priceUsd,
                    source,
                    pricedAt,
                    stalenessSeconds,
                    isLiveQuote,
                    priceIssue,
                    unsupportedValuationReason
            );
        }

        private static Long stalenessSeconds(Instant pricedAt, Instant responseTime) {
            if (pricedAt == null || responseTime == null) {
                return null;
            }
            return Math.max(0L, Duration.between(pricedAt, responseTime).toSeconds());
        }
    }

    private record ProtocolValuationMetadata(
            String model,
            String underlyingSymbol,
            int quantitySign
    ) {
    }

    private record AllowedScope(Map<String, Set<NetworkId>> networksByAddress) {
        private static AllowedScope from(List<UserSession.SessionWallet> wallets) {
            Map<String, Set<NetworkId>> mapping = new LinkedHashMap<>();
            for (UserSession.SessionWallet wallet : wallets) {
                String address = normalizeAddress(wallet.getAddress());
                if (address.isBlank()) {
                    continue;
                }
                mapping.computeIfAbsent(address, ignored -> new LinkedHashSet<>())
                        .addAll(wallet.getNetworks() == null ? List.of() : wallet.getNetworks());
            }
            return new AllowedScope(mapping);
        }

        private List<String> walletAddresses() {
            return List.copyOf(networksByAddress.keySet());
        }

        private boolean includes(String walletAddress, NetworkId networkId) {
            if (networkId == null) {
                return false;
            }
            Set<NetworkId> allowedNetworks = networksByAddress.get(normalizeAddress(walletAddress));
            return allowedNetworks != null && allowedNetworks.contains(networkId);
        }
    }
}
