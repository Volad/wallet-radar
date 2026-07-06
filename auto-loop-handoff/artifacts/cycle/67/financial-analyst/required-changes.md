# Cycle 67 required changes

## Normalization

- Split supported receipt-token exits into principal continuity plus explicit excess acquisition before replay. This is required for:
  - `AMANWETH -> ETH`
  - `aAvaWAVAX -> AVAX`
  - `eUSDC-6 -> USDC`
  - `AARBWBTC -> WBTC/BTC-family` dust tails
- Do not leave supported LP exits as `basisEffect=UNKNOWN`. Canonical LP exits must emit deterministic basis-allocation inputs for returned assets and preserve the carried LP-position identity.
- Keep routed bridge start rows and destination settlement rows in one deterministic lifecycle without collapsing them into external transfer disposal/reacquisition.

## Clarification

- When a row is already canonically typed, clarification-time enrichment should fill `protocolName` and `protocolVersion` from deterministic current evidence instead of leaving the row blank.
- Clarification should also fill `counterpartyAddress` from row-local interacted-contract evidence for vault, lending, swap, wrap/unwrap, and bridge families.
- Add a repair sweep for historical rows where economics are already correct but metadata is still null.

## Protocol detection

- Extend deterministic protocol detection for canonical wrapper contracts where registry coverage already exists but rows still miss labels:
  - `BASE / OPTIMISM / UNICHAIN / 0x4200000000000000000000000000000000000006`
  - `AVALANCHE / 0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7`
- Add registry or audited enrichment coverage for current high-volume missing targets:
  - `0x89c6340b1a1f4b25d36cd8b063d49045caf3f818` bridge corridor
  - `0x0000000000001ff3684f28c67538d4d072c22734` Arbitrum swap router
  - `0xac4c6e212a361c968f1725b4d055b47e63f80b75` Katana swap router
  - `0x5828a3c0f07c6b841205d12660e0abb869bf98dc` Linea reward distributor
- Keep `protocolName` as best-effort metadata only. Do not let protocol enrichment silently redefine economics that are already canonically correct.

## Counterparty construction

- Use `counterpartyAddress` only for the row-local counterparty:
  - swaps, wraps, lending, vault, LP, reward claim: interacted contract
  - external transfer in/out: unique external sender or recipient from transfer evidence
  - bridge source: source bridge contract
  - bridge destination: deterministic settlement contract if unique
- Use `correlationId` and reciprocal `matchedCounterparty` for lifecycle pairing across rows. Do not overload `counterpartyAddress` with lifecycle identity.
- Add a repair sweep that backfills `counterpartyAddress` when raw evidence is already persisted.

## Linking

- Materialize deterministic same-wallet bridge pairs when current evidence already proves one unique destination candidate.
- Persist reciprocal `matchedCounterparty` on both ends of the bridge lifecycle.
- Preserve source protocol branding separately from destination settlement evidence; do not use `protocolName` as the lifecycle key.

## Pricing

- Price only explicit excess yield/reward legs. Supported principal carry across bridges, wrappers, vaults, and lending receipt tokens must not wait on pricing in order to preserve basis continuity.
- For LP exits, if fair-value pricing is available, allocate the exiting LP-position basis proportionally by returned asset value. If price is temporarily missing, keep the allocation rule explicit and isolate the price gap instead of falling back to `UNKNOWN`.

## Move basis

- Carry basis across same-family continuity classes:
  - bridge source -> bridge destination
  - receipt token -> underlying withdraw
  - underlying -> receipt token deposit
  - native asset -> staking wrapper
  - LP position -> returned principal assets on exit
- Preserve full source basis on the carried principal amount and leave only positive unmatched excess uncovered.

## Cost basis

- Do not reset cost basis on supported bridge or wrapper continuity.
- For receipt-token exits, move historical cost basis from the carried receipt lot into the underlying principal quantity.
- For LP exits, close the carried LP-position lot and distribute its cost basis deterministically onto returned assets before any AVCO recomputation.

## AVCO

- AVCO must consume the post-move-basis canonical output only.
- Carried continuity quantity must leave AVCO unchanged.
- Only explicit `BUY`, `SELL`, reward excess, or other true acquisition/disposal effects may update AVCO.
- Do not try to "repair" uncovered quantities inside AVCO once normalization and move-basis semantics are still wrong upstream.

## Replay

- Clear and rerun derived accounting surfaces after the canonical fixes above. Do not patch `asset_ledger_points` or `on_chain_balances` directly.
- Recompute exact and family scorecard rows on the same live basis after rerun.

## Verification

- Validate against the cycle-local scorecard written in this run, not against archived earlier-cycle snapshots.
- Keep exact and family acceptance separate.
- Preserve `NOT COMPARABLE` notes when prior-cycle basis changed.
- Continue treating explicit Bybit unsupported/shadow tails as scope-policy exclusions and keep them out of supported-flow pass claims.
