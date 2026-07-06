# ADR-018: LP Protocol-Family Flow Materialization

Status: Accepted (amended 2026-06-14 — RC-5 staking-wrapper canonicalization + RC-6 V4 PositionManager identity; 2026-06-13 — RC-1 contract-keyed CL-NFT position identity; 2026-05-29 — ETH-C10 Katana/Pendle CMETH registration)  
Date: 2026-05-27

## Context

Prod audit showed LP principal exits existed in raw evidence but normalized rows lacked
`LP-RECEIPT` flows and misclassified harvest-only txs as principal `LP_EXIT`. A single
`LP-RECEIPT` template does not fit GMX async markets, Pendle LPT, fungible BPT, or gauge
stake lifecycles.

## Decision

Introduce protocol-family materialization at normalization time:

| Family | Correlation key | Flow materialization |
|---|---|---|
| A — NFT CL | `lp-position:{net}:{nfpmContract}:{tokenId}` | Synthetic `LP-RECEIPT` from ERC721 mint/burn + ERC20 principal legs |
| B — GMX async | `gmx-lp:{net}:{marketSlug}` | Preserve GM/GLV share legs; no NFT receipt pool |
| C — Pendle | `pendle-lp:{net}:{marketId}` | Preserve PENDLE-LPT legs |
| D — Fungible LP | composite `lp:` bucket | Outbound BPT/LP burn; no synthetic NFT receipt |
| E — Gauge/farm | optional link only | `LP_POSITION_STAKE/UNSTAKE` ≠ NFT position close |

Shared gate `LpPrincipalCloseEvidence` downgrades inbound-only / collect-only shapes to
`LP_FEE_CLAIM` unless position-reduction evidence exists (decrease, burn, negative
ModifyLiquidity, ERC721 from wallet).

## Consequences

- Re-normalize + full replay required; no ledger zeroing sweeps.
- Replay handlers remain family-specific (`LpReceipt*`, `GmxLp*`, composite `lp:`).
- Acceptance uses `scripts/audit/lp-position-lifecycle-audit.mongosh.js`.

## Amendment (2026-05-29) — ETH-C10: Katana SushiSwap and Pendle CMETH

Prod audit (`lp-pool-avco-table.md`, Issues E and F) identified two unregistered LP protocols:

### Katana SushiSwap (Angle vbETH-vbUSDC vault) — Classification: `classification` stage

- LP_ENTRY tx (2025-09-12) for 0.45 ETH @ $4,527 had `correlationId=null`, `protocolName=null`.
- Root cause: Katana SushiSwap vault contract not in the protocol registry.
- **Fix:** Add Katana SushiSwap contract to protocol registry; emit `lp-position:katana:sushiswap:{poolId}` (Family A — NFT CL pattern).
- Fee claim txs (vbETH, SUSHI, vbUSDC) must inherit the same `correlationId`.

### Pendle CMETH LP (Mantle network) — Classification: `classification` stage

- Multiple LP_ENTRY/LP_EXIT txs on Mantle have `protocolName=Pendle` but `correlationId=null`.
- Root cause: Mantle CMETH Pendle market not registered in `PendleLpCorrelationSupport` (symbol `PENDLE-LPT` / `CMETH-LPT` → marketId map missing).
- **Fix:** Register Mantle CMETH Pendle market in `PendleLpCorrelationSupport`; ensure `pendle-lp:mantle:{marketId}` is emitted (Family C — Pendle).
- PENDLE fee token claims must inherit the same `correlationId`.

### Acceptance

- A11: ≥1 Katana LP tx has `correlationId` matching `lp-position:katana:sushiswap:*`; ≥1 associated fee-claim tx has same `correlationId`.
- A12: ≥1 Pendle CMETH LP tx has `correlationId` matching `pendle-lp:mantle:*`; ≥1 PENDLE fee-claim has same `correlationId`.

## Amendment (2026-06-13) — RC-1: CL-NFT position identity keyed by the NFPM contract

Prod audit (`results/blockers.md` B-ETH-01, `results/protocol-rule-pack.md` RP-1) found one
concentrated-liquidity NFT position split across **two** receipt pools because the Family A
key embedded the **protocol slug**, and the slug was not a pure function of the
position-manager contract:

- LP_ENTRY `0x5532ff4b…` (a `multicall` to the PancakeSwap V3 NonfungiblePositionManager
  `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364` on BASE) was claimed by the generic
  `LpClassifier` at `PRE_PROTOCOL_REVIEW`, which **silently defaulted the slug to `uniswap`**
  → `lp-position:base:uniswap:938761`.
- LP_EXIT `0x0a757aee…` (same NFPM) was claimed by `LpRegistryClassifier`, which resolved the
  slug from the registry → `lp-position:base:pancakeswap:938761`.

The position basis split: the ETH-side basis (~$2,085) stranded in a phantom-open
`uniswap:938761` pool while the exit drained the starved `pancakeswap:938761` pool, returning
0.796 ETH at avco **$151** instead of ~$2,770. A second position (tokenId `204401`, ARBITRUM)
fabricated `UNKNOWN` exit basis the same way.

### Decision

For **Family A (NFT CL)** the position identity is a pure function of the
NonfungiblePositionManager contract address, embedded in the correlation key:

```
lp-position:<network>:<nfpmContractLowercased>:<tokenId>
```

- The NFPM contract is the position-manager contract: it is resolved from the **position-NFT
  ERC-721 contract** (the `address` of the mint `zero→wallet` / burn `wallet→zero` `Transfer`
  log, which is router-independent), falling back to the transaction's `rawData.to` (the
  interacted position-manager contract) for direct NFPM calls and for
  `increaseLiquidity`/partial-decrease events that carry no mint/burn log. For direct NFPM
  interactions the log address equals `rawData.to`. The resolved contract is identical for
  LP_ENTRY and LP_EXIT of the same position, so entry and exit can **never** split into two
  pools — even when one leg is router-wrapped.
- `tokenId` is **never** contract-/protocol-global: each NFPM mints its own ERC-721 id space,
  so `tokenId 938761` on PancakeSwap is unrelated to `tokenId 938761` on Uniswap. The position
  identity is the triple `(network, NFPM contract, tokenId)`.
- The **protocol slug is display-only**, resolved from the registry **by contract**. Protocol
  slug ↔ NFPM is NOT bijective (Uniswap V3 NFPM and Uniswap V4 PoolManager both map to slug
  `uniswap`), so the slug must never be part of the identity.
- **Fail-loud, never `uniswap` masquerade:** an unrecognized V3-interface NFPM must not be
  silently labeled `uniswap`. Because the key is the contract address itself, an unregistered
  NFPM still keys entry and exit consistently (no split); the registry only supplies the human
  label, and a missing registry entry yields a contract-derived label plus a one-time
  `WARN`-level coverage log so the registry can be extended.
- The `LP-RECEIPT:<NET>:<NFPM>:<tokenId>` receipt symbol is derived from the correlation key
  (`LpReceiptSymbolSupport.fromLpPositionCorrelation`), so it inherits the contract identity
  automatically.

### Scope and non-changes

- This amendment applies only to the **CL-NFT (tokenId-bearing)** path. The vault-style
  fallback for position managers with no NFT tokenId (Katana SushiSwap / Angle vaults,
  amendment 2026-05-29) keeps its registry-resolved form — both entry and exit there already
  resolve via the registry on the same contract, so no split is possible.
- Family C (Pendle, `pendle-lp:{net}:{marketId}`) and Family B (GMX) are unaffected — they are
  not keyed by a tokenId and are not at risk of cross-protocol tokenId collision.
- **Negative cases:** genuine Uniswap positions (`rawData.to` = real Uniswap NFPM, e.g.
  `base:…:5248110`) and Uniswap V4 PoolManager flows must NOT be re-tagged — identity is
  resolved only from the actual contract, never from a tokenId guess.

### Universal invariant

For every LP receipt pool, identity == the NFPM contract derived from its entry's
`rawData.to`; no `(network, tokenId)` resolves to more than one contract identity. Asserted in
classification tests; recurrence is structurally prevented because the contract is the key.

### Acceptance (RC-1)

- A13: a `multicall` LP_ENTRY to a PancakeSwap NFPM and its registry-classified LP_EXIT share
  the identical `lp-position:<net>:0x46a15b0b…:<tokenId>` correlation id (no `uniswap` split).
- A14: an unregistered V3-interface NFPM yields a contract-keyed identity (not `uniswap`) and a
  one-time coverage warning.
- A15: genuine Uniswap V3/V4 positions resolve to their own contract identity (not re-tagged).

## Amendment (2026-06-14) — RC-5: staking/farming-wrapper LP identity canonicalizes to the underlying NFPM

Post-RC-1 prod audit (`results/blockers.md` B-PF-01, `results/required-changes.md` RC-5) found that
RC-1 (which keys the CL-NFT position by the position-manager contract) still split a single
position into **two** receipt pools when the LP NFT is **staked into a farming/gauge wrapper**:

- The wrapper (PancakeSwap MasterChefV3 `0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3` on BASE; the
  Arbitrum MasterChefV3; Velodrome/Aerodrome Slipstream gauges; …) **custodies** the position NFT,
  so `decreaseLiquidity`/`collect`/`harvest`/`withdraw` calls hit the wrapper. `rawData.to` is the
  wrapper, and no `mint→wallet`/`burn→wallet` ERC-721 log is present (the NFT lives in the wrapper),
  so the identity keyed `lp-position:<net>:<wrapperContract>:<tokenId>` — a **duplicate** of the
  underlying `lp-position:<net>:<NFPM>:<tokenId>` pool.
- Proven SAME position: tokenId `938761` had `LP_ENTRY`/`LP_FEE_CLAIM` under both
  `0x46a15b0b…` (PancakeSwap V3 NFPM) and `0xc6a2db66…` (MasterChefV3); ~$186.79 basis was trapped
  in the wrapper pools across ~4 tokenIds.

### Decision

The CL-NFT position identity must **canonicalize a known LP-staking/farming wrapper to the
underlying position-manager identity** `(network, NFPM-contract, tokenId)`:

- Maintain a **data-driven `wrapperContract → underlyingNFPM` registry** in the protocol registry
  (`underlyingPositionManager` on the `STAKE_CONTRACT` entry). MasterChefV3 → PancakeSwap V3 NFPM
  (per network); Slipstream gauges → the matching Slipstream PositionManager (per network).
- **Detection signal (reusable, no hashes):** the contract custodies a position-manager NFT (NFT
  `transferFrom`→wrapper observed) AND forwards `decreaseLiquidity`/`collect`/`harvest` on the same
  tokenId the NFPM minted. The registry mapping is the durable realization of this signal.
- After canonicalization `LP_ENTRY`/`LP_FEE_CLAIM`/`LP_EXIT` for one staked position all share
  `lp-position:<net>:<NFPM>:<tokenId>`; the wrapper pool disappears and the trapped basis flows back.
- **Negative case:** identity remains the triple `(network, NFPM contract, tokenId)`, so two genuinely
  distinct NFPM positions that happen to share a numeric tokenId are **never** collapsed.
- **Fail-loud:** an unregistered wrapper that custodies an NFPM NFT keeps a contract-keyed identity
  (the wrapper contract) and emits a one-time `WARN` coverage log so the registry can be extended —
  never silently collapsed.

## Amendment (2026-06-14) — RC-6: Uniswap V4 PositionManager identity per (network, PM, tokenId)

The same audit (`results/blockers.md` B-PF-02, RC-6) found a residual split on **Uniswap V4**
(UNICHAIN): a new-mint `modifyLiquidities` LP_ENTRY whose tokenId is assigned by the PositionManager
(and therefore absent from calldata, only present in the resulting ERC-721 mint log) fell through to a
**truncated-contract aggregate** correlation id `lp-position:unichain:uniswap:4529a01c7a041016`
(16-hex truncation of the PositionManager, **no tokenId**), while per-tokenId exits keyed
`lp-position:unichain:0x4529a01c…:<tokenId>`. Entry basis stranded in the aggregate; exits fabricated
`UNKNOWN` basis (ETH +$163, USDT +$762).

### Decision

- Apply the RC-1 protocol-stable identity to V4: key every V4 `LP_ENTRY`/`LP_EXIT` by the **full
  PositionManager contract + tokenId** (`lp-position:<net>:<fullPMcontractLower>:<tokenId>`).
- **Eliminate the truncated-contract aggregate branch** for NFT-based position managers. A direct
  V4 `modifyLiquidities` **mint** (mint action present / position-NFT mint log) is treated as a
  receipt-clarification shape (like the V3 multicall mint): it goes `PENDING_CLARIFICATION` with a
  `null` correlation id rather than aggregating, so once the mint log is fetched the tokenId resolves
  and entry + exit share the full-PM identity.
- The remaining per-contract fallback applies **only** to genuine no-NFT vault-style position
  managers (Katana SushiSwap / Angle vaults) and now uses the **full** contract (no 16-hex
  truncation) so entry and exit cannot drift apart there either.

### Related

- **ADR-022** (LP exit per-asset attribution): once entry and exit land in **one** pool, the
  one-sided ETH exit basis restore (combined ETH+USDC receipt-pool basis collapsed onto the
  returned ETH) is already produced by the existing per-asset cross-pool drain — RC-1 adds **no
  new exit code**. The USDC leg of the unified pool drains to 0 with no fabricated separate USDC
  ACQUIRE; FAMILY:USDC stays conserved.
- **ADR-027** (LiFi corridor linking) and **ADR-020** (RC-2 corridor carry) are the sibling
  bridge-corridor fixes from the same audit cycle.

## Alternatives considered

- Replay-only receipt synthesis — rejected (violates earliest-stage fix rule).
- One global `LP-RECEIPT` symbol for all protocols — rejected (GMX/Pendle/Curve mismatch).
- Keying Family A by the resolved protocol **slug** — rejected (RC-1): slug ↔ NFPM is not
  bijective (V3 NFPM and V4 PoolManager both → `uniswap`) and the slug is classifier-dependent,
  so it re-admits the entry/exit split. The contract address is the only stable identity.
