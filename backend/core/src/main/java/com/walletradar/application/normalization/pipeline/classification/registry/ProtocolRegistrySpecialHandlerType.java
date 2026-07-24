package com.walletradar.application.normalization.pipeline.classification.registry;

public enum ProtocolRegistrySpecialHandlerType {
    BALANCER_VAULT,
    BALANCER_V3_VAULT,
    GMX_V2_EXCHANGE_ROUTER,
    LFJ_LB_PAIR,
    LFJ_LB_ROUTER,
    PENDLE_ROUTER,
    MORPHO_BUNDLER,
    // R6a: Aura Finance BoosterLite/Booster deposit router. deposit(pid, amount, stake) sends the
    // Balancer BPT to the reward pool and mints the Aura deposit-vault receipt token to the wallet.
    // The Booster keeps its REWARD_ROUTER role for pure getReward claims; this handler is consulted
    // by LpRegistryClassifier only when a BPT<->deposit-vault movement pair is present, so that the
    // deposit/withdraw is classified as LP_POSITION_STAKE/UNSTAKE (basis-neutral) rather than a
    // LENDING_DEPOSIT / REWARD_CLAIM disposal that would sever BPT basis continuity.
    AURA_BOOSTER
}
