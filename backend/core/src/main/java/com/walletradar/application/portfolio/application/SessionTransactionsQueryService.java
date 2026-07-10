package com.walletradar.application.portfolio.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.portfolio.application.port.SessionTransactionsReadPort;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-through session transaction view over canonical normalized transactions.
 * No separate projection collection is persisted at this stage.
 */
@Service
@RequiredArgsConstructor
public class SessionTransactionsQueryService implements SessionTransactionsReadPort {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final List<String> SPAM_LIKE_REASON_CODES = List.of(
            "PROMO_SPAM_PHISHING",
            "CLAIM_LIKE_SPAM_OR_AIRDROP",
            "SPOOF_TOKEN_CONFUSABLE_SYMBOL"
    );
    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );
    private static final List<NormalizedTransactionStatus> VISIBLE_STATUSES = List.of(
            NormalizedTransactionStatus.CONFIRMED,
            NormalizedTransactionStatus.PENDING_PRICE,
            NormalizedTransactionStatus.NEEDS_REVIEW
    );

    // ── Transaction category type buckets ─────────────────────────────────────

    static final Set<NormalizedTransactionType> SWAP_TYPES = EnumSet.of(
            NormalizedTransactionType.SWAP,
            NormalizedTransactionType.WRAP,
            NormalizedTransactionType.UNWRAP,
            NormalizedTransactionType.DEX_ORDER_REQUEST,
            NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
            NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
            NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
            NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
            NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
            NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
            NormalizedTransactionType.NFT_MINT,
            NormalizedTransactionType.UNKNOWN,
            NormalizedTransactionType.MANUAL_COMPENSATING
    );

    static final Set<NormalizedTransactionType> LP_TYPES = EnumSet.of(
            NormalizedTransactionType.LP_ENTRY_REQUEST,
            NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
            NormalizedTransactionType.LP_EXIT_REQUEST,
            NormalizedTransactionType.LP_EXIT_SETTLEMENT,
            NormalizedTransactionType.LP_ENTRY,
            NormalizedTransactionType.LP_EXIT,
            NormalizedTransactionType.LP_EXIT_PARTIAL,
            NormalizedTransactionType.LP_EXIT_FINAL,
            NormalizedTransactionType.LP_ADJUST,
            NormalizedTransactionType.LP_POSITION_STAKE,
            NormalizedTransactionType.LP_POSITION_UNSTAKE,
            NormalizedTransactionType.LP_FEE_CLAIM
    );

    static final Set<NormalizedTransactionType> LENDING_TYPES = EnumSet.of(
            NormalizedTransactionType.LENDING_DEPOSIT,
            NormalizedTransactionType.LENDING_LOOP_OPEN,
            NormalizedTransactionType.LENDING_LOOP_REBALANCE,
            NormalizedTransactionType.LENDING_LOOP_DECREASE,
            NormalizedTransactionType.LENDING_LOOP_CLOSE,
            NormalizedTransactionType.LENDING_WITHDRAW,
            NormalizedTransactionType.EARN_FLEXIBLE_SAVING,
            NormalizedTransactionType.BORROW,
            NormalizedTransactionType.REPAY,
            NormalizedTransactionType.VAULT_DEPOSIT,
            NormalizedTransactionType.VAULT_WITHDRAW,
            NormalizedTransactionType.STAKING_DEPOSIT,
            NormalizedTransactionType.STAKING_WITHDRAW_REQUEST,
            NormalizedTransactionType.STAKING_WITHDRAW,
            NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT,
            NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
    );

    static final Set<NormalizedTransactionType> BRIDGE_TYPES = EnumSet.of(
            NormalizedTransactionType.BRIDGE_IN,
            NormalizedTransactionType.BRIDGE_OUT
    );

    static final Set<NormalizedTransactionType> EXTERNAL_TRANSFER_TYPES = EnumSet.of(
            NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
            NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
    );

    static final Set<NormalizedTransactionType> INTERNAL_TRANSFER_TYPES = EnumSet.of(
            NormalizedTransactionType.INTERNAL_TRANSFER
    );

    static final Set<NormalizedTransactionType> REWARD_TYPES = EnumSet.of(
            NormalizedTransactionType.REWARD_CLAIM
    );

    static final Set<NormalizedTransactionType> DUST_TYPES = EnumSet.of(
            NormalizedTransactionType.APPROVE,
            NormalizedTransactionType.FEE,
            NormalizedTransactionType.ADMIN_CONFIG,
            NormalizedTransactionType.SPONSORED_GAS_IN
    );

    public static final List<TransactionCategory> DEFAULT_CATEGORIES = List.of(
            TransactionCategory.SWAP,
            TransactionCategory.LP,
            TransactionCategory.LENDING,
            TransactionCategory.BRIDGE,
            TransactionCategory.EXTERNAL_TRANSFER,
            TransactionCategory.INTERNAL_TRANSFER,
            TransactionCategory.REWARD,
            TransactionCategory.NEED_REVIEW
    );

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final MongoOperations mongoOperations;

    public Optional<SessionTransactionsView> findSessionTransactions(String sessionId, TransactionsQuery query) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> toTransactionsView(session, query));
    }

    public Optional<RebuildTransactionsView> rebuildSessionTransactions(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(session -> {
                    long projectedTransactions = countVisibleTransactions(accountingUniverseService.resolveScope(session).memberRefs());
                    return new RebuildTransactionsView(
                            session.getId(),
                            projectedTransactions,
                            "Session transactions refreshed from canonical normalized transactions"
                    );
                });
    }

    public static int validateLimitOrThrow(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return requestedLimit;
    }

    public static int validateOffsetOrThrow(Integer requestedOffset) {
        if (requestedOffset == null) {
            return 0;
        }
        if (requestedOffset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        return requestedOffset;
    }

    public static List<TransactionCategory> parseCategories(Collection<String> rawCategories) {
        if (rawCategories == null || rawCategories.isEmpty()) {
            return DEFAULT_CATEGORIES;
        }
        List<TransactionCategory> parsed = new ArrayList<>();
        for (String raw : rawCategories) {
            if (raw == null || raw.isBlank()) continue;
            try {
                parsed.add(TransactionCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // skip unknown category values
            }
        }
        return parsed.isEmpty() ? DEFAULT_CATEGORIES : parsed;
    }

    public static TransactionsQuery normalizeQuery(
            Integer requestedLimit,
            Integer requestedOffset,
            String rawSearch,
            Collection<String> rawCategories,
            Collection<String> requestedWalletRefs,
            Collection<NetworkId> requestedNetworkIds
    ) {
        return new TransactionsQuery(
                validateLimitOrThrow(requestedLimit),
                validateOffsetOrThrow(requestedOffset),
                normalizeSearch(rawSearch),
                parseCategories(rawCategories),
                normalizeValues(requestedWalletRefs),
                requestedNetworkIds == null ? List.of() : requestedNetworkIds.stream().filter(Objects::nonNull).distinct().toList()
        );
    }

    private SessionTransactionsView toTransactionsView(UserSession session, TransactionsQuery query) {
        Collection<String> scopedMemberRefs = accountingUniverseService.resolveScope(session).memberRefs();
        List<String> walletRefs = resolveWalletRefs(scopedMemberRefs, query.walletRefs());
        if (scopedMemberRefs == null || scopedMemberRefs.isEmpty() || walletRefs.isEmpty()) {
            return new SessionTransactionsView(session.getId(), query.offset(), query.limit(), 0, false, List.of());
        }
        Criteria criteria = transactionsCriteria(walletRefs, query);
        long totalCount = mongoOperations.count(Query.query(criteria), NormalizedTransaction.class);
        List<NormalizedTransaction> transactions = loadVisibleTransactions(
                criteria,
                query.offset(),
                query.limit()
        );
        return new SessionTransactionsView(
                session.getId(),
                query.offset(),
                query.limit(),
                totalCount,
                query.offset() + transactions.size() < totalCount,
                transactions.stream()
                        .map(this::toItemView)
                        .toList()
        );
    }

    private List<NormalizedTransaction> loadVisibleTransactions(Criteria criteria, int offset, int limit) {
        Query query = Query.query(criteria)
                .with(Sort.by(
                        Sort.Order.desc("blockTimestamp"),
                        Sort.Order.desc("transactionIndex"),
                        Sort.Order.desc("id")
                ))
                .skip(offset)
                .limit(limit);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private long countVisibleTransactions(Collection<String> memberRefs) {
        if (memberRefs == null || memberRefs.isEmpty()) {
            return 0;
        }
        return mongoOperations.count(Query.query(transactionsCriteria(
                memberRefs,
                new TransactionsQuery(DEFAULT_LIMIT, 0, null, DEFAULT_CATEGORIES, List.of(), List.of())
        )), NormalizedTransaction.class);
    }

    private Criteria transactionsCriteria(Collection<String> walletRefs, TransactionsQuery query) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("walletAddress").in(walletRefs));
        if (!query.networkIds().isEmpty()) {
            // Include CEX transactions (networkId=null) regardless of the network filter;
            // the network filter applies only to on-chain transactions.
            criteria.add(new Criteria().orOperator(
                    Criteria.where("networkId").in(query.networkIds()),
                    Criteria.where("networkId").exists(false)
            ));
        }
        criteria.add(categoryCriteria(query.categories()));

        Criteria searchCriteria = searchCriteria(query.search());
        if (searchCriteria != null) {
            criteria.add(searchCriteria);
        }

        return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
    }

    private Criteria categoryCriteria(List<TransactionCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return Criteria.where("_id").exists(false);
        }
        Criteria spamCrit = spamLikeCriteria();
        List<Criteria> orParts = new ArrayList<>();
        for (TransactionCategory category : categories) {
            switch (category) {
                case SPAM -> orParts.add(spamCrit);
                case DUST -> orParts.add(new Criteria().andOperator(
                        Criteria.where("status").in(VISIBLE_STATUSES),
                        ACTIVE_ACCOUNTING_CRITERIA,
                        new Criteria().norOperator(spamCrit),
                        Criteria.where("type").in(DUST_TYPES)
                ));
                case NEED_REVIEW -> orParts.add(new Criteria().andOperator(
                        Criteria.where("status").in(
                                NormalizedTransactionStatus.PENDING_PRICE,
                                NormalizedTransactionStatus.NEEDS_REVIEW
                        ),
                        ACTIVE_ACCOUNTING_CRITERIA,
                        new Criteria().norOperator(spamCrit)
                ));
                case SWAP -> orParts.add(typeBucketCriteria(SWAP_TYPES, spamCrit));
                case LP -> orParts.add(typeBucketCriteria(LP_TYPES, spamCrit));
                case LENDING -> orParts.add(typeBucketCriteria(LENDING_TYPES, spamCrit));
                case BRIDGE -> orParts.add(typeBucketCriteria(BRIDGE_TYPES, spamCrit));
                case EXTERNAL_TRANSFER -> orParts.add(typeBucketCriteria(EXTERNAL_TRANSFER_TYPES, spamCrit));
                case INTERNAL_TRANSFER -> orParts.add(typeBucketCriteria(INTERNAL_TRANSFER_TYPES, spamCrit));
                case REWARD -> orParts.add(typeBucketCriteria(REWARD_TYPES, spamCrit));
            }
        }
        if (orParts.isEmpty()) {
            return Criteria.where("_id").exists(false);
        }
        return new Criteria().orOperator(orParts.toArray(Criteria[]::new));
    }

    private Criteria typeBucketCriteria(Set<NormalizedTransactionType> types, Criteria spamCrit) {
        return new Criteria().andOperator(
                Criteria.where("status").in(VISIBLE_STATUSES),
                ACTIVE_ACCOUNTING_CRITERIA,
                new Criteria().norOperator(spamCrit),
                Criteria.where("type").in(types)
        );
    }

    private Criteria spamLikeCriteria() {
        Criteria excludedSpam = new Criteria().andOperator(
                Criteria.where("excludedFromAccounting").is(Boolean.TRUE),
                Criteria.where("accountingExclusionReason").regex("SPAM", "i")
        );
        Criteria reasonTaggedSpam = Criteria.where("missingDataReasons").in(SPAM_LIKE_REASON_CODES);
        return new Criteria().orOperator(excludedSpam, reasonTaggedSpam);
    }

    private Criteria searchCriteria(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = Pattern.quote(search);
        return new Criteria().orOperator(
                Criteria.where("_id").regex(pattern, "i"),
                Criteria.where("txHash").regex(pattern, "i"),
                Criteria.where("flows.assetSymbol").regex(pattern, "i")
        );
    }

    private List<String> resolveWalletRefs(Collection<String> scopedMemberRefs, Collection<String> requestedWalletRefs) {
        if (scopedMemberRefs == null || scopedMemberRefs.isEmpty()) {
            return List.of();
        }
        if (requestedWalletRefs == null || requestedWalletRefs.isEmpty()) {
            return List.copyOf(scopedMemberRefs);
        }

        Map<String, String> scopedByNormalizedRef = new LinkedHashMap<>();
        for (String scopedMemberRef : scopedMemberRefs) {
            if (scopedMemberRef != null && !scopedMemberRef.isBlank()) {
                scopedByNormalizedRef.put(scopedMemberRef.trim().toLowerCase(Locale.ROOT), scopedMemberRef);
            }
        }

        return requestedWalletRefs.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .map(scopedByNormalizedRef::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private ItemView toItemView(NormalizedTransaction transaction) {
        return new ItemView(
                transaction.getId(),
                mapSourceType(transaction),
                blankToNull(transaction.getTxHash()),
                transaction.getNetworkId() == null ? null : transaction.getNetworkId().name(),
                blankToNull(transaction.getWalletAddress()),
                blankToNull(transaction.getMatchedCounterparty()),
                transaction.getBlockTimestamp(),
                mapFrontendType(transaction.getType()),
                transaction.getStatus() == null ? null : transaction.getStatus().name(),
                issueCode(transaction),
                bridgeStatus(transaction),
                realisedPnlUsdTotal(transaction),
                null,
                transaction.getFlows().stream()
                        .map(flow -> toFlowView(transaction, flow))
                        .toList()
        );
    }

    private FlowView toFlowView(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        BigDecimal unitPrice = flow.getUnitPriceUsd();
        BigDecimal valueUsd = flow.getValueUsd();
        // LP receipt tokens (GM, GLV) from settlement carry no reliable historical price —
        // the system falls back to current market price which misleads the reader into
        // thinking the deposit settled at a lower value. Cost basis is propagated via the
        // replay bucket (not from unitPriceUsd), so it is safe to suppress the price here.
        if (isSuppressedSettlementReceiptFlow(transaction, flow)) {
            unitPrice = null;
            valueUsd = null;
        }
        return new FlowView(
                flow.getRole() == null ? null : flow.getRole().name(),
                blankToNull(flow.getAssetContract()),
                blankToNull(flow.getAssetSymbol()),
                flow.getQuantityDelta(),
                unitPrice,
                valueUsd,
                flow.getPriceSource() == null ? null : flow.getPriceSource().name(),
                flow.getLogIndex()
        );
    }

    private boolean isSuppressedSettlementReceiptFlow(NormalizedTransaction tx, NormalizedTransaction.Flow flow) {
        if (tx.getType() != NormalizedTransactionType.LP_ENTRY_SETTLEMENT) {
            return false;
        }
        String sym = flow.getAssetSymbol();
        if (sym == null || sym.isBlank()) {
            return false;
        }
        // GM:ETH/USD [WETH-USDC] and GLV [...] tokens — receipt tokens with no historical price feed
        return sym.regionMatches(true, 0, "GM:", 0, 3)
                || sym.regionMatches(true, 0, "GLV", 0, 3);
    }

    private String mapSourceType(NormalizedTransaction transaction) {
        if (transaction.getType() == NormalizedTransactionType.MANUAL_COMPENSATING) {
            return "MANUAL";
        }
        return "CHAIN";
    }

    private String mapFrontendType(NormalizedTransactionType type) {
        if (type == null) {
            return "UNCLASSIFIED";
        }
        if (type == NormalizedTransactionType.UNKNOWN) {
            return "UNCLASSIFIED";
        }
        return type.name();
    }

    private String bridgeStatus(NormalizedTransaction transaction) {
        if (!isBridgeType(transaction.getType())) {
            return null;
        }
        if (transaction.getStatus() == NormalizedTransactionStatus.NEEDS_REVIEW) {
            return "REVIEW";
        }
        if (!blank(transaction.getMatchedCounterparty())) {
            return "MATCHED";
        }
        if (transaction.getType() == NormalizedTransactionType.BRIDGE_OUT) {
            return "BRIDGE_OUT";
        }
        if (transaction.getType() == NormalizedTransactionType.BRIDGE_IN) {
            return "BRIDGE_IN";
        }
        return null;
    }

    private boolean isBridgeType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.BRIDGE_OUT || type == NormalizedTransactionType.BRIDGE_IN;
    }

    private String issueCode(NormalizedTransaction transaction) {
        if (isSpamTransaction(transaction)) {
            return "spam";
        }
        if (transaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE || hasMissingPrice(transaction)) {
            return "missing_price";
        }
        if (transaction.getStatus() != null && transaction.getStatus() != NormalizedTransactionStatus.CONFIRMED) {
            return "unconfirmed";
        }
        return null;
    }

    private boolean hasMissingPrice(NormalizedTransaction transaction) {
        if (isContinuityTransfer(transaction)) {
            return false;
        }
        return transaction.getFlows().stream()
                .filter(flow -> flow.getRole() != null)
                .filter(flow -> flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY
                        || flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.SELL)
                .anyMatch(flow -> flow.getUnitPriceUsd() == null);
    }

    private boolean isContinuityTransfer(NormalizedTransaction transaction) {
        if (blank(transaction.getMatchedCounterparty()) || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT, INTERNAL_TRANSFER, BRIDGE_IN, BRIDGE_OUT -> true;
            default -> false;
        };
    }

    private BigDecimal realisedPnlUsdTotal(NormalizedTransaction transaction) {
        return transaction.getFlows().stream()
                .map(NormalizedTransaction.Flow::getRealisedPnlUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
    }

    private boolean isSpamTransaction(NormalizedTransaction transaction) {
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())
                && transaction.getAccountingExclusionReason() != null
                && transaction.getAccountingExclusionReason().toUpperCase(Locale.ROOT).contains("SPAM")) {
            return true;
        }
        if (transaction.getMissingDataReasons() == null || transaction.getMissingDataReasons().isEmpty()) {
            return false;
        }
        LinkedHashSet<String> normalizedReasons = new LinkedHashSet<>();
        for (String reason : transaction.getMissingDataReasons()) {
            if (reason != null && !reason.isBlank()) {
                normalizedReasons.add(reason.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalizedReasons.stream().anyMatch(SPAM_LIKE_REASON_CODES::contains);
    }

    private static List<String> normalizeValues(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static String normalizeSearch(String rawSearch) {
        if (rawSearch == null) {
            return null;
        }
        String trimmed = rawSearch.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SessionTransactionsView(
            String sessionId,
            int offset,
            int limit,
            long totalCount,
            boolean hasMore,
            List<ItemView> items
    ) {
    }

    public record TransactionsQuery(
            int limit,
            int offset,
            String search,
            List<TransactionCategory> categories,
            List<String> walletRefs,
            List<NetworkId> networkIds
    ) {
    }

    public enum TransactionCategory {
        SWAP, LP, LENDING, BRIDGE, EXTERNAL_TRANSFER, INTERNAL_TRANSFER, REWARD, DUST, NEED_REVIEW, SPAM
    }

    public record ItemView(
            String id,
            String sourceType,
            String txHash,
            String networkId,
            String walletAddress,
            String matchedCounterparty,
            Instant blockTimestamp,
            String type,
            String status,
            String issue,
            String bridgeStatus,
            BigDecimal realisedPnlUsdTotal,
            Long avcoSnapshotVersion,
            List<FlowView> flows
    ) {
    }

    public record FlowView(
            String role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            String priceSource,
            Integer logIndex
    ) {
    }

    public record RebuildTransactionsView(
            String sessionId,
            long projectedTransactions,
            String message
    ) {
    }
}
