# FAMILY:ETH residual — LP NFT protocol-identity collision + bridge corridor carry

Status: **PLAN — awaiting approval (Phase 3 review + user sign-off before any code).**
Universe/session: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`.
Audit artifacts: `results/blockers.md`, `results/accounting-failure-analysis.md`, `results/protocol-rule-pack.md`, `results/required-changes.md`, `results/discrepancies.md`, `results/eth_basis.md`.

## Scope

- **Wallets/networks:** on-chain wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` across BASE, ARBITRUM, OPTIMISM, LINEA, UNICHAIN, ZKSYNC (FAMILY:ETH).
- **Assets:** FAMILY:ETH (ETH/WETH), and the cross-asset LP receipt pools that return ETH (ETH+USDC concentrated-liquidity NFT positions).
- **Blocker IDs:** B-ETH-01 (dominant), B-ETH-02, B-ETH-03, B-ETH-04 (cosmetic), B-ETH-05 (display-only, no change).
- **Goal:** close the remaining ~$1,550 in-scope conservation residual (reportedPnl overstates expectedPnl by +$1,757) by fixing the LP receipt pool identity collision and the cross-network bridge corridor carry, without disturbing the verified-genuine FAMILY:ETH realised gains or the held loss-bearing positions.

## Root cause (by earliest failed stage)

### B-ETH-01 (DOMINANT) — LP NFT tokenId protocol-attribution collision · stage: classification/normalization
The LP receipt pool key is `lp-position:<network>:<protocolSlug>:<tokenId>` (and the receipt asset symbol `LP-RECEIPT:<NET>:<PROTO>:<tokenId>`). The `<protocolSlug>` is supplied by the **caller's resolved protocol name**, not derived from the position-manager contract. PancakeSwap V3 is a Uniswap-V3 fork sharing the same NonfungiblePositionManager interface and method selectors, so:

- **LP_ENTRY** `0x5532ff4b…` → `LP-RECEIPT:BASE:UNISWAP:938761` (classifier defaulted to `uniswap` from the generic V3 signature).
- **LP_EXIT** `0x0a757aee…` → `LP-RECEIPT:BASE:PANCAKESWAP:938761` (resolved `pancakeswap` from the registry by contract).

Both transactions interact with the **same** PancakeSwap NFPM `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364` (confirmed: exit flow `counterpartyAddress = 0x46a15b0b…`, Blockscout-tagged PancakeSwap). The position's basis therefore splits into two pools:

| pool | qtyHeld | basisHeldUsd | avcoUsd | state |
|---|---|---|---|---|
| `…:base:uniswap:938761:FAMILY:ETH` | 0.26229 ETH | **$781.68** | $2,980 | phantom OPEN (never drained) |
| `…:base:uniswap:938761:FAMILY:USDC` | 1148.84 USDC | **$1,303.55** | $1.135 | phantom OPEN |
| `…:base:pancakeswap:938761:FAMILY:ETH` | 0 | 0 | — | STARVED (exit drained this) |
| `…:base:pancakeswap:938761:FAMILY:USDC` | 0 | 0 | — | STARVED |

Result: the one-sided ETH exit drained the **starved** `pancakeswap:938761` pool → 0.796 ETH returned at avco **$151** (basis ~$120 captured only) instead of ~$2,770 (the real ~$2,085 ETH-side basis is stranded in the phantom `uniswap:938761` pool). Confirmed on a second position: tokenId **204401 (ARBITRUM)**, where the exit fabricates `UNKNOWN` basisEffect (~$1,141). Net residual contribution ≈ **$2.1k (938761) + $1.1k (204401)** of mis-attributed FAMILY:ETH basis, which is the dominant driver of the +$1,757 gap.

**Why now:** protocol slug is not a pure function of the NFPM contract. Entry and exit can disagree, splitting one CL position across two receipt pools. tokenId is reused across protocols (each NFPM has its own ERC-721 id space), so `tokenId` alone is not a position identity.

### B-ETH-02 — cross-chain LiFi bridge corridor carry not attached · stage: linking/replay
Source `BRIDGE_OUT` reserves `CARRY_OUT` keyed `bridge:lifi:<outHash>` with `continuityCandidate=true`, but the destination `BRIDGE_IN` on a **different** network does not consume it (UNICHAIN→ZKSYNC, BASE→LINEA, BASE→ARBITRUM). Likely the ADR-019 P0-b same-`networkId` pass-through guard (and/or ADR-020 late-attach) restricts carry consumption to same-network. The destination is then market-repriced by F-5(a). **Net basis impact is small** (F-5(a) re-prices near source AVCO ≈ $20–200), but ~$2,945 of `CARRY_OUT` is orphaned in the continuity store — a determinism/latent-divergence risk, not a large $ driver.

### B-ETH-03 — F-5(a) cross-network price gap (LINEA) · stage: pricing
LINEA `BRIDGE_IN` `0x2108883281…` entered at avco **$0** because neither paired carry (B-ETH-02) nor a cross-network ETH canonical quote resolved for LINEA, and F-5(a) did not route the truly-unresolvable uncovered tail to PENDING. ~$17 fabricated basis.

### B-ETH-04 — NFT_MINT ETH spend cosmetic · stage: move_basis (cosmetic)
`0x2dc06caa…` BASE NFT_MINT books a 0.000004 ETH dust DISPOSE at avco 0 (~$1.30). Immaterial.

### Confirmed NON-issues (no change)
- **Anchor #4** `0xbc3fe1a5…` ARB INTERNAL_TRANSFER ETH→CEX: correct `CARRY_OUT` 3.06 ETH @ avco $2,350 to `BYBIT:…:FUND`; quantity drops, AVCO preserved. Not a defect.
- **Anchor #3** `0xa6a38d63…` lending REALLOCATE within FAMILY:ETH: clean, avco preserved.
- **Anchor #7 / B-ETH-05** OPTIMISM "AVCO changes with no tx between": `TimelineAvcoAuthority` (ADR-017) selects different per-bucket authoritative points across events; underlying flows are dust. **Display read-model nuance only** — no replay change. (Optionally documented; could add a read-model smoothing note, but out of accounting scope.)

## Changes (ordered, upstream pipeline stages first)

> **Phase 3 review applied (auditor + business-analyst + system-architect, all REVISE→incorporated).** Key corrections: (a) the LP key embeds the **NFPM contract address** (protocol↔NFPM is not bijective — Uniswap V3 NFPM and V4 PoolManager share slug `uniswap`); (b) **fail-loud / contract-as-identity** on unregistered NFPMs (the silent `uniswap` default is the recurrence vector); (c) the bridge carry key is already network-agnostic — the real gap is **linking-stage** propagation, and the same-network guard lives in **ADR-020 P0-b (not ADR-019)**; (d) RC-2 target is **orphaned `CARRY_OUT` → 0, NOT CARRY net → 0** (the +$8,566 CARRY net is legitimate net bridged-in inventory); (e) the one-sided exit basis restore is already owned by **ADR-022** (no new exit code) — add explicit cross-asset/USDC conservation guards.

### RC-1 (DOMINANT) — Protocol-stable, contract-keyed LP receipt pool identity · amends **ADR-018**
- **Where:** LP protocol/identity resolution feeding `LpPositionCorrelationSupport.correlationId(...)` and the receipt symbol in `LpNftClFlowMaterializer` / `LpReceiptSymbolSupport`. Candidate files: `LpClassifier`, `LpRegistryClassifier`, `LpNftClFlowMaterializer`, `LpPositionCorrelationSupport`, `LpReceiptSymbolSupport`, protocol registry.
- **Change (R2 — key shape, architect):** make the CL-NFT position identity a pure function of the **NonfungiblePositionManager contract address**, embedded in the key, since protocol slug ↔ NFPM is NOT bijective (Uniswap V3 NFPM and V4 PoolManager both → `uniswap`). Canonical key: `lp-position:<network>:<nfpmContractLowercased>:<tokenId>` (protocol slug retained as a derived **display** label only, resolved from the registry by contract). The contract address is identical for LP_ENTRY and LP_EXIT of the same position, so entry/exit can never split. `(network, NFPM contract, tokenId)` is the position identity; `tokenId` is never protocol/contract-global.
- **Change (fail-loud on unknown NFPM, business-analyst P0):** never silently default an unrecognized V3-interface NFPM to `uniswap`. Because the key is the contract address itself, an unregistered NFPM still keys entry and exit consistently (no split); the registry is only for the human-readable protocol label, and an unknown NFPM yields an explicit `unknown`/contract-derived label (no `uniswap` masquerade). Optionally emit a one-time warning when an LP NFPM is absent from the registry so coverage can be extended.
- **Universal invariant (business-analyst P0):** assert across the whole universe that no `(network, tokenId)` resolves to more than one position identity (contract), and that every LP receipt pool's identity equals the contract derived from its entry's `rawData.to`.
- **Registry coverage — data-driven (auditor + business-analyst):** enumerate **every** LP NFPM contract present in the universe and register it (PancakeSwap V3 `0x46a15b0b…` BASE/ARBITRUM; Uniswap V3 NFPM BASE `0x03a520b3…`, ARBITRUM `0xc36442b4…`; and sweep for other Uniswap-V3-interface forks likely present — Aerodrome/Velodrome Slipstream on BASE/OPTIMISM, SushiSwap V3, etc.). Do NOT scope to PancakeSwap-only.
- **Cross-asset / one-sided exit (auditor + architect, ADR-022):** the 938761/204401 `$2,085`/`$1,141` is the **combined ETH+USDC** receipt-pool basis collapsed onto a one-sided ETH exit — already handled by ADR-022's per-asset cross-pool drain (no new exit code), contingent on the exit being genuinely one-sided. The fix is purely making both legs land in ONE pool. Add conservation guards (see acceptance): the USDC leg of the pool drains to 0, no fabricated separate USDC ACQUIRE, FAMILY:USDC stays conserved.
- **Negative cases:** genuine Uniswap positions (`rawData.to` = real Uniswap NFPM, e.g. `base:uniswap:5248110`; Uniswap V4 PoolManager) must NOT be re-tagged — resolve only from the actual contract, never from tokenId.
- **Requires:** re-normalization (identity/symbol) + accounting replay. Pricing cache unaffected. Full reset+replay rebuilds pools — no in-place migration.
- **Expected effect:** 938761 LP_EXIT avco $151 → ≈ $2,770; 204401 exit stops fabricating UNKNOWN basis; FAMILY:ETH `UNKNOWN` cbDelta +$1,304 → 0; phantom open pools collapse; in-scope residual closed.

### RC-2 — Cross-network LiFi bridge corridor carry attachment · linking-first; narrow **ADR-020** amendment
- **Where (architect R3/R4):** the bridge-pair carry key in `ReplayPendingTransferKeyFactory` is **already network-agnostic** — so the primary gap is **linking-stage**: the `LINKED:<outHash>` corridor relationship (ADR-027 / RC-4 counterparty typing) is not propagated so the destination carry never attaches. Treat **RC-4 as a prerequisite** for RC-2. Only if a residual same-network restriction remains, it is the **ADR-020 P0-b** pass-through guard (NOT ADR-019); relax it **strictly for out-hash-unique LiFi corridors** and nothing else (a broad relaxation would re-break the bug ADR-020 fixed).
- **Change:** allow a destination `BRIDGE_IN` to consume the source `BRIDGE_OUT`'s reserved carry when uniquely matched by `correlationId = bridge:lifi:<outHash>` across networks, preserving all uniqueness/ambiguity guards (out-hash equality required; asset+minute alone must NOT link). Inbound inherits source carried AVCO; genuine excess tail still promotes at market-at-timestamp via F-5(a) (no double application).
- **Requires:** accounting replay.
- **Expected effect (auditor correction):** ZKSYNC inbounds inherit source AVCO; **orphaned `CARRY_OUT` basis → 0** (the determinism fix). **NOT** "CARRY net → 0" — the +$8,566 CARRY net is legitimate net bridged-in inventory and must be preserved.

### RC-3 — F-5(a) cross-network ETH quote coverage (LINEA) + PENDING fail-safe
- **Where:** `ReplayMarketAuthority.findCanonicalQuote` cross-network resolution; F-5(a) uncovered-inbound fail-safe.
- **Change:** extend cross-network ETH canonical quote resolution to LINEA (and any chain where ETH is the canonical native); route a truly-unresolvable uncovered inbound to PENDING/incomplete-history rather than leaving avco $0.
- **Requires:** replay (+ `--clear-pricing-cache` only if a quote source changes).
- **Expected effect:** removes ~$17 fabricated basis; prevents future $0-basis ETH inflows.

### RC-4 — Counterparty attribution for LiFi BRIDGE_IN (cosmetic, enables RC-2)
- **Where:** clarification counterparty typing.
- **Change:** a `BRIDGE_IN` whose `correlationId = bridge:lifi:<hash>` matches a known LiFi `BRIDGE_OUT` must be `counterpartyType = BRIDGE` (LiFi executor), not `UNKNOWN_EOA` (34 rows currently UNKNOWN_EOA), regardless of the relayer EOA.
- **Expected effect:** cosmetic; makes RC-2 corridor matching unambiguous. No direct $ impact.

## Documentation (before code, Phase 4)

- **Amend ADR-018** (`ADR-018-lp-protocol-family-materialization.md`, owns the `lp-position` key) — CL-NFT position identity is the **NFPM contract address** (`lp-position:<net>:<nfpmContract>:<tokenId>`); protocol slug is a derived display label resolved from the registry by contract; tokenId is never contract-global; fail-loud (no silent `uniswap` default). **Needs explicit user approval.**
- **Amend ADR-020** (`ADR-020-bridge-late-carry-passthrough-reservation.md`, owns the P0-b same-network pass-through guard) — relax **only** for out-hash-unique LiFi corridors (RC-2). NOT ADR-019. **Needs explicit user approval.**
- **Cross-reference ADR-022** (`ADR-022-lp-exit-per-asset-attribution-and-swap-multi-leg-pricing.md`) — the one-sided cross-pool drain already produces the corrected restore; no new exit code.
- **Cross-reference ADR-027** (`ADR-027-lifi-calldata-destination-discovery.md`) — corridor linking / `LINKED:<outHash>` propagation (RC-4 prerequisite for RC-2).
- `docs/pipeline/normalization/rules/protocols/` — Uniswap-V3-interface CL-NFT fork classification + protocol/identity-from-contract rule (PancakeSwap V3, Aerodrome/Velodrome Slipstream, SushiSwap V3, Uniswap V3/V4).
- `docs/pipeline/cost-basis/` (LP receipt basis) — contract-keyed pool identity + cross-asset one-sided exit basis restore.

## Acceptance (rerun checks + auditor sign-off)

After `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (+ `--clear-pricing-cache` only if a pricing source changed), wait for normalization + replay, then:

1. **Universal identity invariant (no split pools):** for every LP receipt pool, identity == contract derived from its entry's `rawData.to`; no `(network, tokenId)` maps to >1 contract. `lp-position:base:uniswap:938761:*` no longer exists; the 938761 position lives in exactly ONE pool (PancakeSwap NFPM `0x46a15b0b…`).
2. **938761 exit basis:** LP_EXIT `0x0a757aee` ETH avco ≈ **$2,500–2,900** (not $151).
3. **204401:** exit no longer fabricates `UNKNOWN` basisEffect; FAMILY:ETH `UNKNOWN` cbDelta count → 0.
4. **Cross-asset / USDC conservation (ADR-022):** for the unified 938761 pool, the USDC leg drains to 0 on the one-sided ETH exit, **no fabricated separate USDC ACQUIRE** is created, and FAMILY:USDC realised/holdings are unchanged vs pre-fix except the legitimate move-basis transfer.
5. **Bridge corridors (RC-2):** ZKSYNC inbounds `0xa2e409cc`/`0x0417160b` avco ≈ source-carried ($3,199/$3,195), `uncoveredQuantityAfter ≈ 0`; **orphaned `CARRY_OUT` basis → 0** while **CARRY net is preserved (≈ +$8,566, NOT 0)**.
6. **LINEA (RC-3):** `0x2108883281` no longer avco $0; truly-unresolvable uncovered inbounds route to PENDING (no $0 fabrication). PENDING/NEEDS_REVIEW counts do not regress beyond the 0/0 baseline except deterministically-justified routes.
7. **Conservation (falsifiable, split in-scope vs total):** the in-scope LP+bridge clusters contribute ≈ **$0** to `conservationDelta` (B-ETH-01..03 drivers eliminated); reportedPnl → expectedPnl with the **only** remaining gap being the out-of-scope **TON +$204** member pool, i.e. total `|conservationDelta| ≈ $204` (not < $70 — that target wrongly assumed TON closed). State the exact post-replay delta and its TON attribution.
8. **Protected behavior (discrete pre/post equality, business-analyst P0):** verified-genuine FAMILY:ETH realised gains (cmETH 2025-09-10 disposals), genuine Uniswap positions (`base:uniswap:5248110`), and held loss-bearing positions (amanWETH/aWETH/GM/Aave LP, USDC cross-asset IL) are **byte-equal** pre vs post except the 938761/204401 corrections.
9. **Auditor sign-off:** re-run `financial-logic-auditor` to reconcile DB output vs authoritative reconstruction against checks 1–8.

## Risks

- **Protocol↔NFPM non-bijectivity (architect R2):** keying by protocol slug alone re-admits collisions (Uniswap V3 NFPM + V4 PoolManager → `uniswap`). Mitigation: key by the **NFPM contract address**; slug is display-only.
- **Silent fork recurrence (business-analyst P0):** any new Uniswap-V3-interface fork must not silently default to `uniswap`. Mitigation: contract-as-identity (consistent regardless of registry) + fail-loud warning on unregistered NFPM + universal invariant check.
- **False re-tagging:** must not re-tag genuine Uniswap positions or V4 PoolManager flows. Mitigation: resolve only from the actual contract; explicit negative-case tests (e.g. `base:uniswap:5248110`).
- **RC-2 re-breaking ADR-020:** a broad same-network-guard relaxation would re-break the bug ADR-020 fixed. Mitigation: prefer the linking-stage fix (RC-4 prerequisite); scope any guard relaxation strictly to out-hash-unique LiFi corridors; negative test (asset+minute without out-hash equality must NOT link).
- **Wrong RC-2 target:** "CARRY net → 0" would destroy legitimate bridged-in inventory basis. Mitigation: target only orphaned `CARRY_OUT` → 0; assert CARRY net preserved.
- **Migration:** applied via re-normalization + full reset+replay (pools rebuilt); no in-place migration. Verify no stale `uniswap:938761` pools persist; verify idempotency across reruns.
- **Ordering dependency:** RC-1 (re-normalization) precedes replay; RC-4 (corridor linking/typing) is a prerequisite for RC-2; RC-2/RC-3 are replay/pricing-stage.
- **Out-of-scope boundary:** TON +$204 remains a deliberate scope boundary (un-backfilled member pool), not closed by this plan; total residual ≈ $204 is the expected terminal state, not 0.
