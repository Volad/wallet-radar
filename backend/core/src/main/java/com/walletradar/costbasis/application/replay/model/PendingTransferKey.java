package com.walletradar.costbasis.application.replay.model;

public sealed interface PendingTransferKey permits TransferPendingKey, BridgePendingKey, BridgeSettlementPendingKey {
    String value();
}
