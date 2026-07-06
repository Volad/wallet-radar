package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.Set;

/**
 * Shared LI.FI / Jumper route-tag detection used by bridge-start and bridge-pair logic.
 */
public final class LiFiRouteSupport {

    private static final Set<String> ROUTE_TAGS = Set.of(
            "stargate",
            "across",
            "relay",
            "cctp",
            "hop",
            "symbiosis",
            "gaszipbridge",
            "gaszip",
            "jumper.exchange",
            "lifiadapter",
            "lifiadapterv2",
            // WS-2: additional LI.FI underlying bridge identifiers
            "cbridgebridge",
            "cbridge",
            "amarok",
            "amarokbridge",
            "hyperlane",
            "hyperlanebridge",
            "mayanbridge",
            "mayan",
            "squidbridge",
            "squid"
    );

    private LiFiRouteSupport() {
    }

    public static boolean hasRouteTag(OnChainRawTransactionView view) {
        return view != null && hasRouteTag(view.inputData());
    }

    public static boolean hasRouteTag(String inputData) {
        return CalldataDecodingSupport.containsAnyAsciiFragment(
                inputData,
                ROUTE_TAGS.toArray(String[]::new)
        );
    }
}
