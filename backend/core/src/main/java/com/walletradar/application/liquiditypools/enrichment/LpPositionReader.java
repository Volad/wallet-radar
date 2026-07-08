package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;

import java.util.Optional;

public interface LpPositionReader {

    boolean supports(LpPositionContext context);

    Optional<LpPositionSnapshot> read(LpPositionContext context);
}
