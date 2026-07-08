package com.walletradar.costbasis.application.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only port for persisted Bybit live balance rows used by replay conservation guards.
 */
public interface BybitLiveBalanceReadPort {

    String EMPTY_UMBRELLA_SYMBOL = "__EMPTY_UMBRELLA__";

    record Row(
            String integrationId,
            String assetSymbol,
            BigDecimal fundQty,
            BigDecimal earnQty,
            BigDecimal utaQty
    ) {
    }

    List<Row> findAll();
}
