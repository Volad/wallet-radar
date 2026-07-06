package com.walletradar.liquiditypools.persistence;

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
import java.time.LocalDate;

@Document(collection = "lp_earning_points")
@CompoundIndexes({
        @CompoundIndex(name = "lp_earn_corr_day_idx", def = "{'correlationId': 1, 'day': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LpEarningPoint {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String correlationId;
    private String universeId;
    private LocalDate day;
    private BigDecimal cumulativeEarnedUsd;
    private BigDecimal dailyEarnedUsd;
    private BigDecimal dailyAprPct;
    private BigDecimal positionValueUsd;
    private Instant updatedAt;

    public static String composeId(String correlationId, LocalDate day) {
        return correlationId + ":" + day;
    }
}
