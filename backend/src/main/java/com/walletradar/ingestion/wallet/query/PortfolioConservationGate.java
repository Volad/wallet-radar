package com.walletradar.ingestion.wallet.query;

import com.walletradar.costbasis.domain.BorrowLiability;
import com.walletradar.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Portfolio conservation invariant at dashboard read time (ADR-014 §D2).
 */
@Service
@RequiredArgsConstructor
public class PortfolioConservationGate {

    private static final Logger log = LoggerFactory.getLogger(PortfolioConservationGate.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ABSOLUTE_FLOOR_USD = new BigDecimal("50");
    private static final BigDecimal RELATIVE_MTM_FRACTION = new BigDecimal("0.01");
    private static final int DIAGNOSTIC_LIMIT = 20;
    private static final String BYBIT_PREFIX = "bybit:";
    /** Matches {@code BYBIT:<uid>:FUND} deposit/withdraw anchors (Cycle/11 S3). */
    private static final String BYBIT_FUND_WALLET_PATTERN = "^BYBIT:[^:]+:FUND$";
    private static final Set<String> STABLECOIN_SYMBOLS = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "USD"
    );

    private final CounterpartyBasisPoolRepository counterpartyBasisPoolRepository;
    private final BorrowLiabilityRepository borrowLiabilityRepository;
    private final AccountingUniverseRepository accountingUniverseRepository;
    private final MongoOperations mongoOperations;

    public record ConservationResult(
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

    public record ConservationInputs(
            String accountingUniverseId,
            BigDecimal dashboardMarkToMarketUsd,
            BigDecimal totalRealisedPnlUsd,
            BigDecimal totalUnrealisedPnlUsd,
            List<SessionDashboardQueryService.TokenPositionView> tokenPositions
    ) {
    }

    public ConservationResult evaluate(ConservationInputs inputs) {
        if (inputs == null || blank(inputs.accountingUniverseId())) {
            return emptyResult(inputs);
        }
        String universeId = inputs.accountingUniverseId().trim();
        List<CounterpartyBasisPool> pools = counterpartyBasisPoolRepository.findByUniverseId(universeId);

        Map<String, AccountingUniverse.Member> membersByRef = loadMembersByRef(universeId);
        // Cycle/11 S3: NEC and dashboard "Net Inflow" count priced EXTERNAL_TRANSFER
        // into {@code BYBIT:*:FUND} from non-universe counterparties (crypto + fiat).
        BigDecimal lifetimeInflow = computeLifetimeFundInflow(membersByRef);
        BigDecimal lifetimeOutflow = computeLifetimeFundOutflow(membersByRef);
        BigDecimal nec = lifetimeInflow.subtract(lifetimeOutflow, MC);
        BigDecimal mtm = computeMarkToMarket(inputs, pools, membersByRef);
        BigDecimal totalLiabilityUsd = computeOpenLiabilityUsd(universeId);
        BigDecimal adjustedMtm = mtm.subtract(totalLiabilityUsd, MC);

        BigDecimal reportedPnl = zero(inputs.totalRealisedPnlUsd()).add(zero(inputs.totalUnrealisedPnlUsd()), MC);
        BigDecimal expectedPnl = adjustedMtm.subtract(nec, MC);
        BigDecimal delta = reportedPnl.subtract(expectedPnl, MC);
        BigDecimal threshold = conservationThreshold(adjustedMtm);
        boolean breached = delta.abs().compareTo(threshold) > 0;

        if (breached) {
            logConservationBreach(
                    universeId,
                    delta,
                    expectedPnl,
                    reportedPnl,
                    nec,
                    lifetimeInflow,
                    lifetimeOutflow,
                    adjustedMtm,
                    threshold,
                    pools,
                    inputs.tokenPositions()
            );
        }

        return new ConservationResult(
                nec,
                lifetimeInflow,
                adjustedMtm,
                expectedPnl,
                reportedPnl,
                delta,
                threshold,
                breached
        );
    }

    public static BigDecimal conservationThreshold(BigDecimal markToMarketUsd) {
        BigDecimal mtm = zero(markToMarketUsd).abs();
        BigDecimal relative = mtm.multiply(RELATIVE_MTM_FRACTION, MC);
        return ABSOLUTE_FLOOR_USD.max(relative);
    }

    /**
     * Cycle/11 S3: gross lifetime deposits into {@code BYBIT:*:FUND} shown as dashboard "Net Inflow".
     *
     * <p>Counts priced {@code EXTERNAL_TRANSFER_IN} on the FUND sub-account where the
     * counterparty is <em>not</em> a universe member (excludes internal Bybit↔EVM corridors and
     * registered external venues like Paradex/MEX).</p>
     */
    private BigDecimal computeLifetimeFundInflow(Map<String, AccountingUniverse.Member> membersByRef) {
        return sumFundExternalTransfers(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                membersByRef
        );
    }

    /**
     * Cycle/11 S3: gross lifetime withdrawals from {@code BYBIT:*:FUND}, symmetric to inflow.
     */
    private BigDecimal computeLifetimeFundOutflow(Map<String, AccountingUniverse.Member> membersByRef) {
        return sumFundExternalTransfers(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                membersByRef
        );
    }

    private BigDecimal sumFundExternalTransfers(
            NormalizedTransactionType transferType,
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(transferType),
                Criteria.where("walletAddress").regex(BYBIT_FUND_WALLET_PATTERN)
        ));
        query.fields()
                .include("walletAddress")
                .include("txHash")
                .include("flows")
                .include("counterpartyAddress")
                .include("matchedCounterparty");
        List<NormalizedTransaction> transactions = mongoOperations.find(query, NormalizedTransaction.class);
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> seenDepositKeys = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction transaction : transactions) {
            if (transaction == null || transaction.getFlows() == null) {
                continue;
            }
            if (!isUniverseMember(membersByRef, transaction.getWalletAddress())) {
                continue;
            }
            String dedupeKey = depositDedupeKey(transaction);
            if (dedupeKey != null && !seenDepositKeys.add(dedupeKey)) {
                continue;
            }
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                    continue;
                }
                if (!isNonUniverseCounterparty(membersByRef, transaction, flow)) {
                    continue;
                }
                BigDecimal valueUsd = pricedFlowValueUsd(flow);
                if (valueUsd == null || valueUsd.signum() == 0) {
                    continue;
                }
                total = total.add(valueUsd, MC);
            }
        }
        return total;
    }

    private static boolean isNonUniverseCounterparty(
            Map<String, AccountingUniverse.Member> membersByRef,
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String counterparty = resolveCounterpartyAddress(transaction, flow);
        if (counterparty == null || counterparty.isBlank()) {
            return true;
        }
        return !isUniverseMember(membersByRef, counterparty);
    }

    private static String resolveCounterpartyAddress(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow != null && flow.getCounterpartyAddress() != null && !flow.getCounterpartyAddress().isBlank()) {
            return flow.getCounterpartyAddress();
        }
        if (transaction != null && transaction.getCounterpartyAddress() != null && !transaction.getCounterpartyAddress().isBlank()) {
            return transaction.getCounterpartyAddress();
        }
        if (transaction != null && transaction.getMatchedCounterparty() != null && !transaction.getMatchedCounterparty().isBlank()) {
            return transaction.getMatchedCounterparty();
        }
        return null;
    }

    private static String depositDedupeKey(NormalizedTransaction transaction) {
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return transaction.getWalletAddress() + "|" + transaction.getTxHash().trim().toLowerCase(Locale.ROOT);
        }
        return transaction.getId();
    }

    private static boolean isUniverseMember(
            Map<String, AccountingUniverse.Member> membersByRef,
            String counterpartyAddress
    ) {
        return resolveMember(membersByRef, counterpartyAddress) != null;
    }

    /**
     * Cycle/9 S1: Bybit wallet refs ({@code BYBIT:<uid>:FUND/UTA/EARN}) are stored as
     * {@code BYBIT:<uid>} in {@link AccountingUniverse#getMembers()}. Strip the sub-account
     * suffix before lookup so that all three sub-ledgers of an integrated Bybit account count
     * as universe members.
     */

    private static BigDecimal pricedFlowValueUsd(NormalizedTransaction.Flow flow) {
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() != 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null
                && flow.getQuantityDelta() != null
                && flow.getUnitPriceUsd().signum() != 0) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        String symbol = flow.getAssetSymbol();
        if (symbol != null
                && STABLECOIN_SYMBOLS.contains(symbol.trim().toUpperCase(Locale.ROOT))
                && flow.getQuantityDelta() != null) {
            return flow.getQuantityDelta().abs();
        }
        return null;
    }

    private BigDecimal computeMarkToMarket(
            ConservationInputs inputs,
            List<CounterpartyBasisPool> pools,
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        BigDecimal mtm = zero(inputs.dashboardMarkToMarketUsd());
        if (pools == null || pools.isEmpty()) {
            return mtm;
        }
        for (CounterpartyBasisPool pool : pools) {
            if (!Boolean.TRUE.equals(pool.getIsMemberAtLastTouch())) {
                continue;
            }
            BigDecimal qtyHeld = zero(pool.getQtyHeld());
            if (qtyHeld.signum() <= 0) {
                continue;
            }
            String counterparty = pool.getCounterpartyAddress();
            if (counterparty == null || counterparty.isBlank()) {
                continue;
            }
            AccountingUniverse.Member member = resolveMember(membersByRef, counterparty);
            if (member == null) {
                continue;
            }
            if (memberBackfillEnabled(member)) {
                continue;
            }
            mtm = mtm.add(qtyHeld.multiply(zero(pool.getAvcoUsd()), MC), MC);
        }
        return mtm;
    }

    private BigDecimal computeOpenLiabilityUsd(String universeId) {
        return borrowLiabilityRepository.findByUniverseId(universeId).stream()
                .filter(liability -> liability.getQtyOpen() != null && liability.getQtyOpen().signum() > 0)
                .map(this::liabilityMarketValueUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
    }

    private BigDecimal liabilityMarketValueUsd(BorrowLiability liability) {
        BigDecimal qty = zero(liability.getQtyOpen());
        BigDecimal avco = zero(liability.getPortfolioAvcoAtOpen());
        return qty.multiply(avco, MC);
    }

    private Map<String, AccountingUniverse.Member> loadMembersByRef(String universeId) {
        List<AccountingUniverse.Member> members = accountingUniverseRepository.findById(universeId)
                .map(AccountingUniverse::getMembers)
                .orElse(List.of());
        if (members == null || members.isEmpty()) {
            return Map.of();
        }
        return members.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        member -> normalizeRef(member.getRef()),
                        member -> member,
                        (left, right) -> left
                ));
    }

    private static AccountingUniverse.Member resolveMember(
            Map<String, AccountingUniverse.Member> membersByRef,
            String counterpartyAddress
    ) {
        if (membersByRef == null || membersByRef.isEmpty() || counterpartyAddress == null || counterpartyAddress.isBlank()) {
            return null;
        }
        String normalized = normalizeRef(counterpartyAddress);
        AccountingUniverse.Member direct = membersByRef.get(normalized);
        if (direct != null) {
            return direct;
        }
        if (normalized.startsWith(BYBIT_PREFIX)) {
            String rootBybitRef = bybitRootRef(normalized);
            if (rootBybitRef != null) {
                AccountingUniverse.Member root = membersByRef.get(rootBybitRef);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    private static String bybitRootRef(String normalizedRef) {
        if (normalizedRef == null || !normalizedRef.startsWith(BYBIT_PREFIX)) {
            return null;
        }
        int firstColon = normalizedRef.indexOf(':');
        int secondColon = normalizedRef.indexOf(':', firstColon + 1);
        if (secondColon <= 0) {
            return normalizedRef;
        }
        return normalizedRef.substring(0, secondColon);
    }

    private static boolean memberBackfillEnabled(AccountingUniverse.Member member) {
        if (member == null || member.getBackfillEnabled() == null) {
            return true;
        }
        return member.getBackfillEnabled();
    }

    private void logConservationBreach(
            String universeId,
            BigDecimal delta,
            BigDecimal expectedPnl,
            BigDecimal reportedPnl,
            BigDecimal nec,
            BigDecimal lifetimeFundInflowUsd,
            BigDecimal lifetimeFundOutflowUsd,
            BigDecimal mtm,
            BigDecimal threshold,
            List<CounterpartyBasisPool> pools,
            List<SessionDashboardQueryService.TokenPositionView> positions
    ) {
        List<Map<String, Object>> topNonMemberPools = pools.stream()
                .filter(pool -> !Boolean.TRUE.equals(pool.getIsMemberAtLastTouch()))
                .sorted(Comparator.<CounterpartyBasisPool, BigDecimal>comparing(
                        pool -> zero(pool.getLifetimeInBasisUsd()).subtract(zero(pool.getLifetimeOutBasisUsd()), MC).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(pool -> Map.<String, Object>of(
                        "counterpartyAddress", String.valueOf(pool.getCounterpartyAddress()),
                        "networkId", pool.getNetworkId() == null ? "" : pool.getNetworkId().name(),
                        "assetFamily", String.valueOf(pool.getAssetFamily()),
                        "lifetimeInBasisUsd", zero(pool.getLifetimeInBasisUsd()),
                        "lifetimeOutBasisUsd", zero(pool.getLifetimeOutBasisUsd()),
                        "lastTouchedAt", pool.getLastTouchedAt() == null ? "" : pool.getLastTouchedAt().toString()
                ))
                .toList();

        List<Map<String, Object>> topMemberPools = pools.stream()
                .filter(pool -> Boolean.TRUE.equals(pool.getIsMemberAtLastTouch()))
                .filter(pool -> zero(pool.getQtyHeld()).signum() > 0)
                .sorted(Comparator.<CounterpartyBasisPool, BigDecimal>comparing(
                        pool -> zero(pool.getQtyHeld()).multiply(zero(pool.getAvcoUsd()), MC).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(pool -> Map.<String, Object>of(
                        "counterpartyAddress", String.valueOf(pool.getCounterpartyAddress()),
                        "qtyHeld", zero(pool.getQtyHeld()),
                        "avcoUsd", zero(pool.getAvcoUsd())
                ))
                .toList();

        List<Map<String, Object>> pendingPositions = positions == null ? List.of() : positions.stream()
                .sorted(Comparator.<SessionDashboardQueryService.TokenPositionView, BigDecimal>comparing(
                        position -> zero(position.unrealizedPnlUsd()).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(position -> Map.<String, Object>of(
                        "walletAddress", position.walletAddress(),
                        "symbol", position.symbol(),
                        "unrealisedPnlUsd", zero(position.unrealizedPnlUsd())
                ))
                .toList();

        log.warn(
                "conservationBreached universeId={} conservationDelta={} expectedPnl={} reportedPnl={} nec={} "
                        + "lifetimeFundInflowUsd={} lifetimeFundOutflowUsd={} mtm={} threshold={} "
                        + "topNonMemberPoolsByNetCapitalDelta={} topMemberPoolsByQtyHeld={} pendingPositions={}",
                universeId,
                delta,
                expectedPnl,
                reportedPnl,
                nec,
                lifetimeFundInflowUsd,
                lifetimeFundOutflowUsd,
                mtm,
                threshold,
                topNonMemberPools,
                topMemberPools,
                pendingPositions
        );
    }

    private static ConservationResult emptyResult(ConservationInputs inputs) {
        BigDecimal reported = inputs == null
                ? BigDecimal.ZERO
                : zero(inputs.totalRealisedPnlUsd()).add(zero(inputs.totalUnrealisedPnlUsd()), MC);
        return new ConservationResult(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                reported,
                reported,
                ABSOLUTE_FLOOR_USD,
                true
        );
    }

    private static String normalizeRef(String ref) {
        return ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
