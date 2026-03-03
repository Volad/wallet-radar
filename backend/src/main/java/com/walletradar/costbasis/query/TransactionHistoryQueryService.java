package com.walletradar.costbasis.query;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only transaction history sourced from CONFIRMED normalized transactions.
 */
@Service
@RequiredArgsConstructor
public class TransactionHistoryQueryService {

    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public HistoryPage findByAsset(String assetId, String cursor, Integer limit, String direction) {
        int pageSize = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        boolean asc = "ASC".equalsIgnoreCase(direction);
        Cursor decodedCursor = decodeCursor(cursor);
        String needle = assetId == null ? "" : assetId.trim().toLowerCase(Locale.ROOT);

        List<HistoryFlow> all = new ArrayList<>();
        List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.CONFIRMED);
        for (NormalizedTransaction tx : confirmed) {
            if (tx.getFlows() == null) continue;
            for (int i = 0; i < tx.getFlows().size(); i++) {
                NormalizedTransaction.Flow leg = tx.getFlows().get(i);
                if (!matchesAsset(leg, needle)) continue;
                String eventId = tx.getId() + ":" + i;
                all.add(new HistoryFlow(tx, leg, eventId));
            }
        }

        Comparator<HistoryFlow> comparator = Comparator
                .comparing((HistoryFlow h) -> h.tx().getBlockTimestamp(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(HistoryFlow::eventId);
        if (!asc) {
            comparator = comparator.reversed();
        }
        all.sort(comparator);
        List<HistoryFlow> filtered = applyCursor(all, decodedCursor, asc);
        boolean hasMore = filtered.size() > pageSize;
        List<HistoryFlow> page = filtered.stream().limit(pageSize).toList();
        String nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : null;

        List<HistoryItem> items = page.stream()
                .map(this::toDto)
                .toList();
        return new HistoryPage(items, nextCursor, hasMore);
    }

    private HistoryItem toDto(HistoryFlow h) {
        String eventType = toEventType(h.tx().getType(), h.leg().getQuantityDelta() != null ? h.leg().getQuantityDelta().signum() : 0);
        return new HistoryItem(
                h.eventId(),
                h.tx().getTxHash(),
                h.tx().getNetworkId(),
                h.tx().getWalletAddress(),
                h.tx().getBlockTimestamp(),
                eventType,
                h.leg().getAssetSymbol(),
                h.leg().getAssetContract(),
                h.leg().getQuantityDelta(),
                h.leg().getUnitPriceUsd(),
                h.leg().getPriceSource(),
                h.leg().getValueUsd(),
                h.leg().getRealisedPnlUsd(),
                h.leg().getAvcoAtTimeOfSale(),
                h.tx().getStatus() != null ? h.tx().getStatus().name() : null,
                false
        );
    }

    private static String toEventType(NormalizedTransactionType type, int qtySign) {
        if (type == NormalizedTransactionType.SWAP) {
            return qtySign >= 0 ? "SWAP_BUY" : "SWAP_SELL";
        }
        return type != null ? type.name() : "UNKNOWN";
    }

    private static List<HistoryFlow> applyCursor(List<HistoryFlow> all, Cursor cursor, boolean asc) {
        if (cursor == null) return all;
        List<HistoryFlow> out = new ArrayList<>();
        for (HistoryFlow h : all) {
            int cmp = compareCursor(h, cursor);
            if (asc && cmp > 0) {
                out.add(h);
            }
            if (!asc && cmp < 0) {
                out.add(h);
            }
        }
        return out;
    }

    private static int compareCursor(HistoryFlow h, Cursor cursor) {
        Instant ts = h.tx().getBlockTimestamp();
        int tsCmp = Comparator.nullsLast(Instant::compareTo).compare(ts, cursor.timestamp());
        if (tsCmp != 0) return tsCmp;
        return h.eventId().compareTo(cursor.eventId());
    }

    private static boolean matchesAsset(NormalizedTransaction.Flow leg, String assetIdLower) {
        if (leg == null) return false;
        String byContract = leg.getAssetContract() != null ? leg.getAssetContract().toLowerCase(Locale.ROOT) : "";
        String bySymbol = leg.getAssetSymbol() != null ? leg.getAssetSymbol().toLowerCase(Locale.ROOT) : "";
        return byContract.equals(assetIdLower) || bySymbol.equals(assetIdLower);
    }

    private static String encodeCursor(HistoryFlow h) {
        String raw = (h.tx().getBlockTimestamp() != null ? h.tx().getBlockTimestamp().toString() : "") + "|" + h.eventId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int idx = decoded.indexOf('|');
            if (idx <= 0 || idx == decoded.length() - 1) return null;
            Instant ts = decoded.substring(0, idx).isBlank() ? null : Instant.parse(decoded.substring(0, idx));
            String eventId = decoded.substring(idx + 1);
            return new Cursor(ts, eventId);
        } catch (Exception e) {
            return null;
        }
    }

    private record HistoryFlow(NormalizedTransaction tx, NormalizedTransaction.Flow leg, String eventId) {}

    private record Cursor(Instant timestamp, String eventId) {}
}
