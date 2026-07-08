package com.walletradar.application.costbasis.application.replay.model;

import java.util.List;
import java.util.Map;

public record SimpleFamilyCustodySelection(
        List<SimpleFamilyCustodyPair> pairs,
        Map<Integer, IndexedFlow> selectedByIndex
) {
    public static SimpleFamilyCustodySelection empty() {
        return new SimpleFamilyCustodySelection(List.of(), Map.of());
    }
}
