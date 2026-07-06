package com.walletradar.costbasis.application.replay.model;

public record SimpleFamilyCustodyPair(
        IndexedFlow outbound,
        IndexedFlow inbound
) {
}
