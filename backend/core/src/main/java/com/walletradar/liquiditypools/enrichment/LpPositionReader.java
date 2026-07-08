package com.walletradar.liquiditypools.enrichment;

import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;

import java.util.Optional;

public interface LpPositionReader {

    boolean supports(LpPositionContext context);

    Optional<LpPositionSnapshot> read(LpPositionContext context);
}
