package com.walletradar.costbasis.domain;

import com.walletradar.domain.common.PriceSource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Open crypto-loan liability keyed by Bybit {@code orderId} (ADR-012 §D1).
 */
@Document(collection = "borrow_liabilities")
@CompoundIndexes({
        @CompoundIndex(
                name = "borrow_liability_universe_asset_open_idx",
                def = "{'universeId': 1, 'asset': 1, 'qtyOpen': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BorrowLiability {

    @Id
    @EqualsAndHashCode.Include
    private String compositeId;

    private String universeId;
    private String accountRef;
    private String orderId;
    private String asset;

    private BigDecimal qtyBorrowed;
    private BigDecimal qtyOpen;

    private BigDecimal portfolioAvcoAtOpen;
    private PriceSource portfolioAvcoSource;

    private Instant openedAt;
    private Instant lastTouchedAt;
    private Instant closedAt;

    /** OPEN | CLOSED | PARTIAL | OPEN_FROM_REPAY */
    private String status;
    private String accountingExclusionReason;

    public static String compositeId(String universeId, String orderId) {
        return universeId + ":" + orderId;
    }
}
