package com.walletradar.application.pricing.latest;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Represents an asset whose latest price should be refreshed by the independent refresh job.
 * One document per canonical symbol; TTL-pruned when not seen for {@code registryPruneTtlDays} days.
 *
 * <p><strong>Collection: {@code tracked_price_assets}</strong></p>
 */
@Document(collection = "tracked_price_assets")
@NoArgsConstructor
@Getter
@Setter
public class TrackedPriceAssetDocument {

    @Id
    private String id;

    /** Canonical market symbol (upper-cased), e.g. "ETH", "TSLA". */
    private String symbol;

    /** Asset kind used to guide provider selection. */
    private Kind kind;

    /**
     * Ordered list of preferred price sources for this symbol.
     * First entry has highest priority. May be empty (means: try all providers).
     */
    private List<String> preferredSources;

    /** When this symbol was last observed in any session's balances or LP positions. */
    @Indexed
    private Instant lastSeenAt;

    public enum Kind {
        CRYPTO,
        EQUITY,
        STABLECOIN,
        UNKNOWN
    }

    public static String composeId(String canonicalSymbol) {
        return canonicalSymbol == null ? null : canonicalSymbol.trim().toUpperCase(Locale.ROOT);
    }
}
