# Warnings

Universe `df5e69cc…`.

- **W-1 (HIGH):** LP NFT correlationId protocol segment can diverge from `protocolName` / position-manager contract → receipt-pool starvation. Confirmed `base:938761`, `arbitrum:204401`. Sweep all LP positions for `correlationId.protocol != registry.protocol(rawData.to)`.
- **W-2 (MED):** 34 `BRIDGE_IN` rows are `counterpartyType=UNKNOWN_EOA` though they belong to LiFi corridors (`bridge:lifi:<outHash>`). Mislabels counterparty and blocks deterministic cross-network carry.
- **W-3 (MED):** 0.97 ETH of `BRIDGE_IN` quantity carries `uncoveredQuantityAfter>0`; mostly market-repriced by F-5(a), but represents $2,945 of orphaned `CARRY_OUT` basis in the continuity store.
- **W-4 (LOW):** LINEA `BRIDGE_IN` `0x2108883281` entered at avco $0 — F-5(a) cross-network price/fail-safe gap (~$17).
- **W-5 (LOW/display):** `TimelineAvcoAuthority` selects different per-bucket points across adjacent events → chart shows AVCO "changes with no intervening tx" (anchor #7). ADR-017 read-model behavior, not a basis error.
- **W-6 (INFO):** FAMILY:ETH `UNKNOWN` basisEffect present on 2 LP_EXIT points (+$1,304). UNKNOWN effect should never *create* basis; treat as a fabrication symptom of W-1.
