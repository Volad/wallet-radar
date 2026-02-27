package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * User-supplied cost price replacing system-derived price for an on-chain event.
 * Stored in cost_basis_overrides; active=true supersedes original priceUsd in AVCO replay (INV-08).
 */
@Document(collection = "cost_basis_overrides")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CostBasisOverride {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    @Indexed
    private String economicEventId;
    private BigDecimal priceUsd;
    @Field("isActive")
    private boolean active;
    private String note;
    private Instant createdAt;
}
