# Blockers — Phase 1 Financial-Correctness Audit (FAMILY:ETH residual)

> Universe/session `df5e69cc-a0c0-4910-8b7d-74488fa266e2`. Readonly audit, no app code, no prod data changes.
> Source counts: raw_transactions 3348, normalized_transactions 7414 (all CONFIRMED, 0 NEEDS_REVIEW, 0 PENDING),
> asset_ledger_points 10151 (FAMILY:ETH 3883). Residual conservation gap before this cycle ≈ **+$1,757** (reportedPnl
> overstates expectedPnl). All 7 user anchors reconstructed from raw + explorer.

## Ranked clusters (earliest failed stage → USD impact → fix direction)

| ID | Cluster | Earliest failed stage | Evidence state | ETH-side USD impact | Residual driver? |
|----|---------|-----------------------|----------------|---------------------|------------------|
| **B-ETH-01** | **LP NFT tokenId protocol-attribution collision** (entries to PancakeSwap NFPM `0x46a15b0b…` get `correlationId` segment `uniswap:<tokenId>`, exits use `pancakeswap:<tokenId>`) → one position's basis split between a phantom *open* `uniswap:` pool and a *starved* `pancakeswap:` pool | **classification / clarification** (LP correlationId protocol segment) | `EVIDENCE_PRESENT_UNUSABLE` | ~$2,085 (938761) + ~$1,141 (204401) understated/fabricated on exit; ~$2,451 ETH-side stranded in phantom pools | **YES — dominant** |
| **B-ETH-02** | **Cross-chain bridge corridor carry not attached** — matching `correlationId=bridge:lifi:<outHash>` + `continuityCandidate=true`, source `BRIDGE_OUT` does `CARRY_OUT` but destination `BRIDGE_IN` stays uncovered and is market-repriced by F-5(a) (UNICHAIN→ZKSYNC, BASE→LINEA, BASE→ARBITRUM) | **linking / replay** (pass-through P0-b networkId guard + ADR-020 late-bridge-carry across networks) | `EVIDENCE_PRESENT_UNLINKED` | ~0.97 ETH uncovered (~$3.2k notional); net basis impact small (F-5(a) re-prices near source AVCO) except LINEA `avcoA=0` (~$17 fabricated) | Minor (near-conserved) |
| **B-ETH-03** | **F-5(a) cross-network price gap** — LINEA `BRIDGE_IN` (anchor #6) entered at `avcoA=0` because neither paired carry nor cross-network quote resolved | **pricing** (`ReplayMarketAuthority` cross-network quote miss for LINEA) | `EVIDENCE_PRESENT_UNUSABLE` | ~$17 (0.0116 ETH @ $0) | Negligible |
| **B-ETH-04** | NFT_MINT ETH spend (anchor #5) booked as DISPOSE of dust (0.000004 ETH) at `avcoA=0`; SELL flow valueUsd $1.30 | move_basis (cosmetic) | `EVIDENCE_PRESENT_UNUSABLE` | ~$1.30 | Negligible / cosmetic |
| **B-ETH-05** | Timeline-AVCO read-model dips with "no intervening tx" (anchor #7 OPTIMISM May 2025) — `TimelineAvcoAuthority` picks a different per-bucket authoritative point between events; underlying flows are dust (0.0002 ETH) | verification (read model, ADR-017) | `UNSUPPORTED_SCOPE` (read-model artifact, not a basis error) | $0 (display only) | No |

## Anchor → cluster mapping

| # | Anchor | Verdict | Cluster |
|---|--------|---------|---------|
| 1 | `0x0a757aee…` BASE LP_EXIT PancakeSwap 938761 | ETH re-enters at **avco $151** (receipt basis $120.34) vs verified on-chain withdrawal **0.796 ETH ≈ $1,507**. Deposits captured only ~$120; the 0.262 ETH + 1148 USDC deposit (`0x5532ff4b`, sent to PancakeSwap NFPM) was misrouted to phantom `uniswap:938761`. Basis understated ~$2,085. | **B-ETH-01** |
| 2 | `0xb9ad84bb…` BASE BRIDGE_OUT ↔ `0x1e793f25…` ARB BRIDGE_IN | Corridor **is** linked (carried $3.76); inbound `UNKNOWN_EOA` but carry attached. Uncovered tail 0.00774 ETH because BASE source bucket under-covered. AVCO ≈ $2,875 (market), no material drop. | B-ETH-02 |
| 3 | `0xa6a38d63…` ARB LENDING_DEPOSIT … `0x360988…` ARB BRIDGE_OUT | ETH/WETH/aArbWETH flow **clean** (REALLOCATE within FAMILY:ETH, avco preserved ~$2,353). The "strange" AVCO move in this window is the same-day 938761 LP_EXIT (B-ETH-01) + read-model picking. | B-ETH-01 / B-ETH-05 |
| 4 | `0xbc3fe1a5…` ARB INTERNAL_TRANSFER ETH → CEX | **Correct**: `CARRY_OUT` 3.06 ETH @ avco $2,350 to `BYBIT:33625378:FUND`, basis $7,190 carried, avco unchanged. Quantity (not AVCO) drops — corridor carry, not a defect. | (none) |
| 5 | `0x2dc06caa…` BASE NFT_MINT | $1.30 ETH spend; dust DISPOSE at avco 0. Immaterial. | B-ETH-04 |
| 6 | `0x4bd7b04b…` BASE BRIDGE_OUT ↔ `0x2108883281…` LINEA BRIDGE_IN | Linked by correlationId; BASE source bucket empty (`CARRY_OUT` 0.000005 ETH) → LINEA inbound 0.0116 ETH **uncovered at avco $0**. F-5(a) cross-network price gap. | B-ETH-02 / B-ETH-03 |
| 7 | `0x75d595a7…` OPTIMISM BRIDGE_IN … `0x266c0258…` OPTIMISM BRIDGE_OUT | Dust corridor (0.0002 ETH), correctly carried (avco $2,493). The AVCO "change with no tx between" is `TimelineAvcoAuthority` selecting different buckets across events. | B-ETH-05 |

## Residual attribution (headline)

The bridge-linking/AVCO-continuity defects the user suspected as the primary cause are **real but largely conservation-neutral** on this dataset (F-5(a) re-prices uncovered inbounds near the source AVCO; net ETH basis impact from bridges ≈ $20–200). The **dominant driver of the ~$1,550 residual is B-ETH-01** — the LP NFT tokenId protocol-attribution collision, which gross-mis-attributes ~$2.1k (938761) + ~$1.1k (204401) of ETH basis between phantom-open and starved LP receipt pools. See `accounting-failure-analysis.md` and `protocol-rule-pack.md`.
