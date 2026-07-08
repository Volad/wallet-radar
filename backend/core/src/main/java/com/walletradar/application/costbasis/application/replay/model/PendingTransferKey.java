package com.walletradar.application.costbasis.application.replay.model;

public sealed interface PendingTransferKey permits TransferPendingKey, BridgePendingKey, BridgeSettlementPendingKey {
    String value();
}
