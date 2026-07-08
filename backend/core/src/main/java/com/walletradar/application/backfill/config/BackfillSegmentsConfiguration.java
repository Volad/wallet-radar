package com.walletradar.application.backfill.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Backfill segment profiles:
 * defaults - used for all sync methods unless overridden;
 * byRpc - optional overrides for RPC sync method.
 */
@NoArgsConstructor
@Getter
@Setter
public class BackfillSegmentsConfiguration {

    private BackfillSegmentConfiguration defaults = BackfillSegmentConfiguration.defaults();
    private BackfillSegmentConfiguration byRpc = new BackfillSegmentConfiguration();
}
