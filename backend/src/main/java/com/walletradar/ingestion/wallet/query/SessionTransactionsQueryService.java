package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-through session transaction view over canonical normalized transactions.
 * No separate projection collection is persisted at this stage.
 */
@Service
@RequiredArgsConstructor
public class SessionTransactionsQueryService {

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

    public static BridgeFilter validateBridgeFilterOrThrow(String rawBridgeFilter) {
        if (rawBridgeFilter == null || rawBridgeFilter.isBlank()) {
            return BridgeFilter.ALL;
        }
        try {
            return BridgeFilter.valueOf(rawBridgeFilter.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("bridgeStatus must be one of ALL, BRIDGE_OUT, BRIDGE_IN, MATCHED, REVIEW");
        }
    }

    public static SpamFilter validateSpamFilterOrThrow(String rawSpamFilter) {
        if (rawSpamFilter == null || rawSpamFilter.isBlank()) {
            return SpamFilter.HIDE_SPAM;
        }
        try {
            return SpamFilter.valueOf(rawSpamFilter.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("spamFilter must be one of HIDE_SPAM, ALL, SPAM_ONLY");
        }
    }

    public static TransactionsQuery normalizeQuery(
            Integer requestedLimit,
            Integer requestedOffset,
            String rawSearch,
            String rawBridgeFilter,
            String rawSpamFilter,
            Collection<String> requestedWalletRefs,
            Collection<NetworkId> requestedNetworkIds
    ) {
        return new TransactionsQuery(
                validateLimitOrThrow(requestedLimit),
                validateOffsetOrThrow(requestedOffset),
                normalizeSearch(rawSearch),
                validateBridgeFilterOrThrow(rawBridgeFilter),
                validateSpamFilterOrThrow(rawSpamFilter),
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
                new TransactionsQuery(DEFAULT_LIMIT, 0, null, BridgeFilter.ALL, SpamFilter.HIDE_SPAM, List.of(), List.of())
        )), NormalizedTransaction.class);
    }

    private Criteria transactionsCriteria(Collection<String> walletRefs, TransactionsQuery query) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("walletAddress").in(walletRefs));
        if (!query.networkIds().isEmpty()) {
            criteria.add(Criteria.where("networkId").in(query.networkIds()));
        }
        criteria.add(visibilityCriteria(query.spamFilter()));

        Criteria bridgeCriteria = bridgeCriteria(query.bridgeFilter());
        if (bridgeCriteria != null) {
            criteria.add(bridgeCriteria);
        }

        Criteria searchCriteria = searchCriteria(query.search());
        if (searchCriteria != null) {
            criteria.add(searchCriteria);
        }

        return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
    }

    private Criteria visibilityCriteria(SpamFilter spamFilter) {
        Criteria activeVisible = new Criteria().andOperator(
                Criteria.where("status").in(VISIBLE_STATUSES),
                ACTIVE_ACCOUNTING_CRITERIA,
                meaningfulEconomicRowCriteria(),
                nonSpamLikeCriteria()
        );
        Criteria spamVisible = spamLikeCriteria();

        return switch (spamFilter) {
            case HIDE_SPAM -> activeVisible;
            case ALL -> new Criteria().orOperator(activeVisible, spamVisible);
            case SPAM_ONLY -> spamVisible;
        };
    }

    private Criteria meaningfulEconomicRowCriteria() {
        return new Criteria().orOperator(
                Criteria.where("type").ne(NormalizedTransactionType.UNKNOWN),
                Criteria.where("flows.0").exists(true)
        );
    }

    private Criteria nonSpamLikeCriteria() {
        return new Criteria().norOperator(spamLikeCriteria());
    }

    private Criteria spamLikeCriteria() {
        Criteria excludedSpam = new Criteria().andOperator(
                Criteria.where("excludedFromAccounting").is(Boolean.TRUE),
                Criteria.where("accountingExclusionReason").regex("SPAM", "i")
        );
        Criteria reasonTaggedSpam = Criteria.where("missingDataReasons").in(SPAM_LIKE_REASON_CODES);
        return new Criteria().orOperator(excludedSpam, reasonTaggedSpam);
    }

    private Criteria bridgeCriteria(BridgeFilter bridgeFilter) {
        return switch (bridgeFilter) {
            case ALL -> null;
            case REVIEW -> new Criteria().andOperator(
                    Criteria.where("type").in(NormalizedTransactionType.BRIDGE_OUT, NormalizedTransactionType.BRIDGE_IN),
                    Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW)
            );
            case MATCHED -> new Criteria().andOperator(
                    Criteria.where("type").in(NormalizedTransactionType.BRIDGE_OUT, NormalizedTransactionType.BRIDGE_IN),
                    Criteria.where("matchedCounterparty").exists(true),
                    Criteria.where("matchedCounterparty").ne("")
            );
            case BRIDGE_OUT -> new Criteria().andOperator(
                    Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                    Criteria.where("status").ne(NormalizedTransactionStatus.NEEDS_REVIEW),
                    unmatchedCounterpartyCriteria()
            );
            case BRIDGE_IN -> new Criteria().andOperator(
                    Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                    Criteria.where("status").ne(NormalizedTransactionStatus.NEEDS_REVIEW),
                    unmatchedCounterpartyCriteria()
            );
        };
    }

    private Criteria unmatchedCounterpartyCriteria() {
        return new Criteria().orOperator(
                Criteria.where("matchedCounterparty").exists(false),
                Criteria.where("matchedCounterparty").is(null),
                Criteria.where("matchedCounterparty").is("")
        );
    }

    private Criteria searchCriteria(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = Pattern.quote(search);
        return new Criteria().orOperator(
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
                        .map(this::toFlowView)
                        .toList()
        );
    }

    private FlowView toFlowView(NormalizedTransaction.Flow flow) {
        return new FlowView(
                flow.getRole() == null ? null : flow.getRole().name(),
                blankToNull(flow.getAssetContract()),
                blankToNull(flow.getAssetSymbol()),
                flow.getQuantityDelta(),
                flow.getUnitPriceUsd(),
                flow.getValueUsd(),
                flow.getPriceSource() == null ? null : flow.getPriceSource().name(),
                flow.getLogIndex()
        );
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
            BridgeFilter bridgeFilter,
            SpamFilter spamFilter,
            List<String> walletRefs,
            List<NetworkId> networkIds
    ) {
    }

    public enum BridgeFilter {
        ALL,
        BRIDGE_OUT,
        BRIDGE_IN,
        MATCHED,
        REVIEW
    }

    public enum SpamFilter {
        HIDE_SPAM,
        ALL,
        SPAM_ONLY
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
