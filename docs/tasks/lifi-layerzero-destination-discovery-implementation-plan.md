# LiFi LayerZero destination discovery — implementation plan

> **Status:** APPROVED / IMPLEMENTED  
> **Audit date:** 2026-06-09  
> **Symptom:** AVCO visually drops after LiFi bridge OUT `0x4890e907…`; user reports bridge “without pair”  
> **Blockers:** L-LIFI-DEST-MISS-01, L-BRIDGE-BASIS-GAP-01 (P0); AVCO-SPONSORED-GAS-01 (P1, **out of scope**)  
> **Related audit:** `results/blockers.md`, `results/accounting-failure-analysis.md`, `results/required-changes.md`

## Scope

| Item | Value |
|------|-------|
| Session wallet | `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` |
| Source tx | `0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7` (ARBITRUM, LiFi `BRIDGE_OUT`) |
| Expected destination | `0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa` (BASE, LayerZero `execute302`) |
| User secondary tx | `0xeb9438b625dbee23745b6928697d74720247b0eb246450d6bc0516cd48398643` — **June 3** 1inch swap (**before** bridge; AVCO **falls** $2060.77→$2035.04, not post-bridge recovery) |
| Networks | ARBITRUM → BASE (LiFi/Stargate/LayerZero v2) |
| Assets | ETH family (`NATIVE:ARBITRUM` → `NATIVE:BASE`) |

## Root cause (two upstream gates)

**Symptom stage:** `linking` → `replay` (orphan `CARRY_OUT`). **L-BRIDGE-BASIS-GAP-01** is downstream of **L-LIFI-DEST-MISS-01**.

| Gate | Stage | Failure | Evidence state |
|------|-------|---------|----------------|
| **A** | `clarification` / discovery | `LiFiReceivingTransactionDiscoveryService.hasWalletTouchEvidence()` rejects destination — wallet only in calldata | `EVIDENCE_PRESENT_UNUSABLE` |
| **B** | `classification` / flow extraction | `BridgeSettlementClassifier` needs inbound movement legs; Blockscout shows no wallet-touch transfers on `0x25550cf1…` | `EVIDENCE_PRESENT_UNUSABLE` |
| **Symptom** | `linking` | `seedSourceAnchorFromStatus` sets source metadata only; `continuityCandidate=false`; no `BRIDGE_IN` | `EVIDENCE_PRESENT_UNLINKED` |

LiFi `DONE` seeds **source-only anchor** (`correlationId`, `matchedCounterparty`) but destination never enters Mongo. Replay: `CARRY_OUT` −0.080966 ETH, −**$164.63** basis, no `CARRY_IN`.

**Chart clarification:** immediate dip **$2035.04 → $2033.25** is **sponsored gas** (`0xe71109c0…`, 3s before bridge), not bridge row blanking. Bridge row AVCO stays ~**$2033.25** per identity; family basis still leaks **$164.63**.

## Supported vs unsupported

### Supported (P0)

- LiFi `BRIDGE_OUT` with status `DONE` + `receivingTxHash` + `receivingNetworkId`
- LayerZero v2 / Stargate executor family (`execute302` + bounded allowlist)
- Same-family ETH↔ETH continuity when qty within existing LiFi drift rules
- Wallet evidence: touch paths **or** LiFi `DONE` + bounded calldata beneficiary decode **or** trace credit in already-fetched raw (no new RPC)

### Unsupported / out of scope

- Bridges without LiFi `DONE` + `receivingTxHash` (e.g. `0x73cb2b88…` USDC — Task 5 P2)
- Generic calldata address match without protocol allowlist
- Asset-changing routes (continuity stays false)
- **Task 4 sponsored-gas AVCO dilution** — separate P1 backlog / product decision
- CEX, NFT, rebase, tax

**Regression guard:** supplemental LiFi pairs, Mayan, CCTP, Across, Relay paths unchanged.

## Changes (ordered, upstream first)

### Task 0 — ADR-027 (prerequisite)

**Deliverable:** `docs/adr/ADR-027-lifi-calldata-destination-discovery.md`

Define evidence ladder (deterministic):

1. `WALLET_TOUCH` (existing)
2. `LIFI_CALLDATA` (`DONE` + registry-backed executor + bounded beneficiary decode)
3. `TRACE` (pre-enriched receipt/raw only — **no** `debug_traceTransaction`)

Update `docs/pipeline/normalization/rules/protocols/li-fi.md` (replace calldata-only prohibition with ADR contract).

### Task 1 — LiFi destination discovery (P0)

**Files:** `LiFiReceivingTransactionDiscoveryService.java`, tests

- Add `isWalletRelevantForLiFiDiscovery(raw, wallet, status)` — **LiFi-scoped**; do not widen shared `hasWalletTouchEvidence` (Mayan uses it)
- Try **source wallet first** before full tracked-wallet scan
- Accept when Gate A passes; log `discoveryPath` (`WALLET_TOUCH` | `LIFI_CALLDATA` | `TRACE`)

**Task 1 acceptance:**

- [ ] Raw + normalized row for `0x25550cf1…:BASE:0x1a87f12a…`
- [ ] Inbound principal flow present (may be `EXTERNAL_TRANSFER_IN` pre-link)
- [ ] `discoveryPath=LIFI_CALLDATA` logged for anchor
- [ ] Negative: no `DONE` → no calldata ingest
- [ ] Negative: non-allowlisted executor → no calldata ingest
- [ ] Multi-wallet: beneficiary ingests; other tracked wallets do not

### Task 1b — Inbound flow extraction for calldata-only LZ settlements (P0)

**Files:** `BridgeSettlementClassifier.java` or LiFi settlement leg enricher, tests

When Gate A passes but explorer omits wallet-touch transfers, materialize **~+0.080966 ETH** inbound leg using **LiFi-corroborated qty from paired source principal** (preferred Option A from audit).

**Guardrails:** never synthesize from calldata alone without LiFi `DONE`; qty must match source within existing LiFi tolerance.

**Task 1b acceptance:**

- [ ] Fixture `0x25550cf1…` shape: calldata wallet, empty wallet-touch transfers → inbound ETH flow exists
- [ ] Qty drift beyond tolerance → no `BRIDGE_IN` promotion

### Task 2 — Verify auto pair materialization (P0)

**Files:** verify `LiFiBridgePairLinkService.materializePair` (no redesign unless regression)

After Tasks 1+1b, existing `findOrDiscover` → `materializePair` should set reciprocal metadata. `BridgePairContinuityRepairService` is **legacy only**, not P0 path.

**Task 2 acceptance:**

- [ ] Both legs `continuityCandidate=true`
- [ ] Reciprocal `correlationId` / `matchedCounterparty`
- [ ] Destination `type=BRIDGE_IN`, inbound ETH +0.080966 (± tolerance), role `TRANSFER`
- [ ] `BridgePairLinkSupport.supportsPlainMoveBasis(source, destination)=true`
- [ ] Regression: supplemental LiFi top-up pairs unchanged

### Task 3 — Replay verification (P0)

**Files:** verify only (`ReplayPendingTransferKeyFactory`, replay handlers)

**Task 3 acceptance:**

- [ ] `CARRY_IN` on BASE ~seq 10166: `costBasisDeltaUsd ≈ +164.63`
- [ ] Family ETH `famCovered` **4.449724 → 4.449724** across seq 10165–10167 (not 4.368757)
- [ ] Family ETH `famBasis` **$7990.84 → $7990.84** (not $7826.21)
- [ ] Bridge ledger group: paired `CARRY_OUT` + `CARRY_IN`, not orphan carry

### Task 4 — Sponsored-gas AVCO dilution (P1 — **OUT OF SCOPE**)

Separate backlog. Does not block P0. Chart dip **$2035.04 → $2033.25** may remain until approved separately.

### Task 5 — USDC bridge `0x73cb2b88…` (P2)

Separate investigation (`BRIDGE_ON_CHAIN_LEG_NOT_FOUND`).

### Task 6 — Documentation

- ADR-027 + `li-fi.md`
- Cross-reference protocol rule package in `results/blockers.md`

## End-to-end acceptance

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

**Financial truth (hard gates):**

- Basis leak closed: **~$164.63** restored via paired carry
- Blockers L-LIFI-DEST-MISS-01 + L-BRIDGE-BASIS-GAP-01 → **RESOLVED**
- AVCO-SPONSORED-GAS-01 stays **OPEN** unless Task 4 approved separately

**Explicit non-goals (prevent false pass):**

- Per-identity AVCO on bridge row stays ~**$2033.25** — fix does not revert that
- Pre-bridge sponsored-gas dip may remain until Task 4
- `0xeb9438b6…` unchanged (INFO)

**Auditor:** re-run `financial-logic-auditor` against acceptance.

## Risks

| Risk | Mitigation |
|------|------------|
| Calldata false positive | Structured beneficiary decode + executor allowlist + negative tests |
| Shared `hasWalletTouchEvidence` blast radius | LiFi-scoped relevance method only |
| TRACE cost | Enriched raw only, no new RPC |
| Discovery without flow extraction | Task 1b mandatory (Gate B) |

## Review status

| Role | Verdict | Notes |
|------|---------|-------|
| financial-logic-auditor | REVISE → applied | Gate B + quantitative acceptance |
| business-analyst | REVISE → applied | DoD checklist, Task 4 out of scope |
| system-architect | REVISE → applied | ADR-027, narrow Task 2, LiFi-scoped gate |

**Stop gate:** No application code until user approves this plan.
