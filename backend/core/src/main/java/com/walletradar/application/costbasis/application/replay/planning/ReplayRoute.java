package com.walletradar.application.costbasis.application.replay.planning;

public enum ReplayRoute {
    EULER_LOOP,
    GMX_LP_ENTRY_REQUEST,
    GMX_LP_ENTRY_SETTLEMENT,
    ASYNC_LP_EXIT_SETTLEMENT,
    LP_RECEIPT_ENTRY,
    POSITION_SCOPED_LP_EXIT,
    // ADR-083: cluster-carry (PnL=0 intra-cluster cross-canonical conversion). Superset of the
    // former LIQUID_STAKING same-family carry — now also covers cross-family same-cluster carries
    // (ETH↔mETH, SOL↔mSOL, AVAX↔sAVAX) and intra-cluster SWAP/VAULT conversions.
    CLUSTER_CARRY,
    FAMILY_EQUIVALENT_CUSTODY,
    GENERIC
}
