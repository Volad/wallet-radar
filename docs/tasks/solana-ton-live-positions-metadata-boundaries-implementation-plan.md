# Solana/TON Live Positions, Metadata & Module Boundaries — Implementation Plan

Status: **REVIEWED (Phase-3 complete) — awaiting user approval (Phase 4 gate not passed)**
Phase-3 verdicts: financial **APPROVE-WITH-CHANGES**, business **APPROVE-WITH-CHANGES**, architecture
**APPROVE-WITH-CHANGES** — all required revisions folded into this document.
Owner: TBD · Slug: `solana-ton-live-positions-metadata-boundaries`
Related prior plan: `docs/tasks/solana-ton-dashboard-and-defi-correctness-implementation-plan.md`
Phase-1 audit artifacts: `results/authoritative-reconstruction.md`, `results/reconciliation.md`,
`results/discrepancies.md`, `results/blockers.md`, `results/required-changes.md`

---

## 0. Scope

Address six user-reported defects with **long-term, SPI-driven** changes (no hardcoding, no
per-transaction hotfixes):

1. Jupiter Lend health factor is wrong (accounting estimate, not live).
2. Live SOL collateral must show on the lending page and in dashboard portfolio quantity (Aave-parity).
3. Move-basis effective cost is wrong/incomplete for SOL & TON.
4. TON "bonus program" (≈118 TON + ≈64 USDT) should surface with live balance + APY.
5. `token-metadata.json` is a fragile hand-maintained store; identity should be descriptor/live-driven.
6. Module boundaries: no hardcoded wallets/network specs, adapters in the right layer.

Constraints: backend-first; frontend only where a new surface is required (custodial earn venue,
live badges). Renormalization + AVCO replay required for classification changes.

---

## 1. Verified truth (Phase-1 audit — authoritative)

| Fact | Live / correct | System shows today | Root stage |
|---|---|---|---|
| Jupiter Lend collateral | **5.4225 SOL** | ~10.04 SOL (~$762) | classification (B1) |
| Jupiter Lend debt | **233.38 USDT** (mint `Es9v…`) | 210 USDT, frozen | classification + interest (B1/B3) |
| Jupiter Lend HF | **≈1.51** (LT 0.85; LTV ≈56.4% = the "≈50%") | 2.54 (estimate) | live refresh (B3) |
| Debt asset | **USDT**, not USDC | — | data note |
| TON "GRAM" | **native TON coin** (not a jetton) | jetton-ish handling | classification (B2) |
| TON "bonus program" | **Telegram Wallet "Доход" (Earn)** — custodial in-app program; deposits pooled to operator wallet `0:ab3f2953…`. Live: **119.9254 GRAM ($171.33) @ 14.64% APY** + **61.05 USDe ($61.04) @ 9% APY** (deposits USDT, balance denominated in USDe) | invisible | counterparty attribution (B5); **balance/APY held off-chain in Wallet backend, auth-gated — UNSUPPORTED_SCOPE_PROVEN** |
| pTON | transient Ston.fi proxy-TON, must net to 0 | phantom ~45 pTON @ bogus ~$6.1 avco | classification (B2) |
| SOL move-basis | AVCO $63.29 but on incomplete history (~0.67 SOL shortfall) | unreliable | caused by B1 |
| TON native AVCO | healthy ($1.54) | ok | — |
| Unpriced identities | SNAI, DUKO, PIAI, DOOD, vPtS4ywr, AuFy4U (SPL); STON, XAUt, AMZNx, MSTRx (TON) | no quotes | pricing (B4) |

**Design consequence of the TON finding:** point 4 is **not** an on-chain DeFi position — it is the
**Telegram Wallet "Доход" (Earn)** custodial program. The current balance, daily accruals and APY live
in the Wallet backend off-chain, keyed to the user's Telegram identity, with **no public/unauthenticated
readout** (further proven by the USDT-in / USDe-denominated-balance mismatch — the venue keeps its own
ledger). It therefore **cannot** be solved by an on-chain reader. **User decision:** treat it like a **known external
custody destination** (an exchange/vault we can't see into) — **not part of the accounting universe, not shown
in portfolio quantity, no physics tracked**. We record only "put X in, took Y out": deposits are external
transfers out to a labeled counterparty, withdrawals are external transfers in ("count on exit", yield = Y−X
at return). Fabricating a live position or a manual portfolio balance is rejected. See WS-5.

---

## 2. Root causes (with code references)

### Lending live-position architecture (points 1, 2)
- There is **no SPI for live lending positions/health/APY**. `LendingAaveV3HealthCollector`
  (`eth_call getUserAccountData`) and `LendingAaveV3MarketRateCollector` (`getReserveData`) are
  concrete Aave classes; `LendingHealthFactorRefreshService` hardcodes `AAVE_PROTOCOL_KEY` and
  `LendingCycleBuilder` gates live HF on `protocol.startsWith("AAVE")`. Everything else falls to
  `LendingMarketMetricEstimator` → `HF = supplyUsd × liquidationThreshold(0.70) / borrowUsd` on
  deposit-time basis (no interest, no price drift, no quantity growth) ⇒ HF 2.54.
- Aave collateral is live because aTokens are read via generic ERC-20 `balanceOf` into
  `on_chain_balances`, so it flows to dashboard + move-basis + lending automatically. Jupiter Lend
  issues **no fungible receipt**, so `LendingCycleBuilder.withSynthesizedOutstandingSupply`
  fabricates supply from accounting — a hotfix that drifts (10 SOL vs 5.42).

### Solana Jupiter Lend classification (point 1 upstream, and 3)
- Borrow draws are misclassified as `EXTERNAL_TRANSFER_IN`/`INTERNAL_TRANSFER`
  (`48NMko…` +33.5, `5rnyyv…` +20 USDT); `LENDING_LOOP_OPEN` emitted with empty legs; manual
  leverage-loop redeposits double-booked (only one withdraw captured). This is why *accounting*
  collateral/debt is wrong even before any live reader.
- `SolanaTransactionClassifier.resolveJupiterLendType` / `SolanaNormalizedTransactionBuilder.buildLendingFlows`.

### TON classification (points 3, 4)
- No TON protocol registry: 54/147 rows `UNKNOWN`, Ston.fi swaps not typed `SWAP`, pTON booked as a
  held asset (phantom inventory). `TonNormalizedTransactionBuilder` has no DEX/proxy-TON netting.

### Move-basis (point 3)
- Header reconciles `on_chain_balances` (wallet spot) vs `asset_ledger_points` — by design, same as
  EVM. SOL is wrong because of B1 (incomplete history); TON jettons are wrong because of B2
  (phantom pTON) and B4 (no prices). Once B1/B2/B4 and live collateral land, the existing
  reconciliation works unchanged.

### Metadata (point 5)
- Four parallel metadata paths + a quad-store (`token-metadata.json` +
  `network-descriptors.yml` + `SolanaProgramIds.SOLANA_USD_STABLE_MINTS` + `protocol-registry.json`).
  `JupiterSplTokenMetadataResolver` (live) exists but is **not wired into normalization**; TON has
  **no live jetton metadata resolver**. Hence RWA/xStock jettons were hand-added to JSON.

### Module boundaries (point 6)
- No user-wallet hardcoding in main source (only tests). Real risks: dual Solana program-ID registry
  (`SolanaProgramIds.java` ↔ `protocol-registry.json`); metadata quad-store; HTTP/RPC clients in
  `backend/core` (`MeteoraDlmmApiClient`, `RaydiumClmmApiClient`, pricing/CEX clients) instead of
  `backend/platform`; string protocol `lp-position:solana:*`; ad-hoc `isEvm()` branching.

---

## 3. Ordered changes (upstream-first)

Each workstream is independently shippable; ordering respects data dependencies.

### WS-1 — Solana Jupiter Lend classification & leg extraction *(fixes accounting collateral/debt; B1)*
- Recognize Jupiter Lend borrow program instructions: USDT inflow signed against the Jupiter Lend
  borrow vault ⇒ `BORROW` (increment `borrow_liabilities`), never a transfer.
- Extract collateral + borrow legs for `LENDING_LOOP_OPEN`/`_CLOSE` (currently empty).
- Model the manual leverage loop so redeposits are not double-booked (net loop cycle, not cumulative
  deposit−withdraw). Detection is program/instruction-shape driven via the protocol registry — **no
  per-tx hardcoding**.
- **Included from WS-8b (dependency):** make `protocol-registry.json` the single source of truth for the
  Jupiter Lend program IDs and derive/remove `SolanaProgramIds` duplicates — WS-1 detection depends on it.
- Files: `SolanaTransactionClassifier`, `SolanaNormalizedTransactionBuilder`, `protocol-registry.json`
  (Jupiter Lend borrow/earn program entries), regression tests + `docs/pipeline/normalization/rules/families/solana.md`.
- Acceptance (WS-1 alone): classification-derived collateral and debt reach the **summed-draws** figures
  (borrow draws `48NMko…` +33.5, `5rnyyv…` +20 now `BORROW`; loop redeposits not double-booked). The final
  live targets (collateral 5.42 SOL, debt 233 USDT incl. interest) are a **joint WS-1 + WS-3/WS-4 outcome**
  (see single-authority rule), not WS-1 in isolation.

### WS-2 — TON protocol classification (Ston.fi / pTON netting / Affluent) *(B2)*
- Introduce a TON protocol registry (mirror `protocol-registry.json` shape; feed
  `TonCounterpartyResolver` + classifier). Ston.fi router/pTON ⇒ `SWAP` with proxy-TON
  **unwrapped/netted to 0** (kills phantom ~45 pTON + $6.1 avco). Affluent (`affGOLDm 0:68c174ed…`)
  ⇒ stake/unstake.
- Treat "GRAM" as **native TON** (descriptor `native-symbol: TON`), not a jetton.
- Files: `TonNormalizedTransactionBuilder`, `TonCounterpartyResolver`, `SolanaProgramIds`-equivalent
  removed in favor of registry; `docs/pipeline/normalization/rules/families/ton.md`.
- Acceptance: TON `UNKNOWN` rows drop sharply; no phantom pTON; TON native AVCO stays healthy.

### WS-3 — Live lending position SPI + Jupiter Lend reader *(points 1, 2; B3)*
Generalize the Aave-hardcoded live path into SPIs so any protocol/network can plug in.

**SPI shape — TWO ports (architecture review), not one God reader** (per-wallet vs per-market granularity
+ different cadences + avoids per-wallet re-fetch of shared reserve rates):
- `LendingLivePositionReader` — per-wallet: `supports(protocolKey, networkId)`,
  `read(wallet)` → `{ collateralByAsset, debtByAsset, healthFactor, liquidationThreshold, source }`.
- `LendingMarketRateReader` — per-market: `supports(...)`, `read(market)` →
  `{ supplyApy, borrowApy, rewardApy, source }` (reserve read fetched once, shared across wallets).
- **I/O placement (corrects earlier wording):** SPIs **and their reader impls live in
  `core.application.lending`** (like today's Aave collectors); only raw HTTP/RPC transport
  (`EvmRpcClient`, new `JupiterLendClient`/Solana RPC) lives in `backend/platform` behind a port,
  reusing `HeliusRequestThrottle`.
- Refactor `LendingAaveV3HealthCollector` → `LendingLivePositionReader`, `LendingAaveV3MarketRateCollector`
  → `LendingMarketRateReader`; refresh services iterate `List<…Reader>` via `supports(...)`; delete the
  `AAVE_PROTOCOL_KEY` filter and the `startsWith("AAVE")` gate in `LendingCycleBuilder`. Mechanical, EVM
  parity preserved.
- Add `JupiterLendLivePositionReader` (Solana), background-only (never on a GET path).

**Single-authority rule (financial review — HIGHEST RISK, count the position exactly once):**
- The live reader is the **sole authority** for the leveraged position. It trues up **both**
  `on_chain_balances` **and** the SOL AVCO ledger to the same **5.42 SOL** (so WS-1's accounting and WS-3's
  live figure converge — otherwise move-basis shows a spurious inverted shortfall).
- Collateral is a **carry**, not a fresh acquisition: value it as SOL at market using the SOL AVCO already
  in the ledger; **do not mint new basis**.
- Live collateral surfaces as a **live SUPPLY position** so `LendingCycleBuilder.hasLiveSupplyPosition`
  fires and `withSynthesizedOutstandingSupply` **self-suppresses** (retire the synthesized path *via* this
  guard, atomically — don't delete first and leave a gap; keep it only as a clearly-stale fallback when the
  reader is unreachable).
- Dashboard grand total must **not** separately add the lending group `supplyUsd`/net-exposure on top of the
  `on_chain_balances`-derived rows (it currently doesn't — keep it that way).
- Write live HF to `lending_health_factor_snapshots` (`source=LIVE_PROTOCOL`), live APY to
  `lending_market_rate_snapshots`.
- Files: two SPIs + `JupiterLendLivePositionReader` (in core) + `JupiterLendClient` transport (platform);
  refactor Aave collectors, refresh services, `LendingCycleBuilder`;
  `OnChainBalanceProvider` protocol-locked collateral contribution (route via existing SPI, placement in
  `costbasis.balance`); new ADR.
- Acceptance: lending shows HF ≈1.51 (**and surfaces LTV ≈56.4%**), collateral 5.42 SOL, debt 233 USDT;
  dashboard SOL quantity includes locked collateral; source badge = LIVE_PROTOCOL; **no double-count** across
  ledger / `on_chain_balances` / `borrow_liabilities`.
- **Sequencing:** can run in parallel with WS-1/WS-2 for earliest user value (architecture + business review).

### WS-4 — Solana borrow-liability live true-up (interest accrual) *(B3)*
- `borrow_liabilities` for receipt-less networks reflect live outstanding debt via the live reader.
- **SET/override, not stack (financial review):** the live borrow figure is **authoritative and supersedes**
  the WS-1 classification-derived `qtyOpen` — it does not add on top (else `PortfolioConservationGate`
  over-subtracts). Debt = 233 USDT, once.
- Interest component (210→233 minus real draws) is a **real expense**: book **no realized income**; verify
  no `PortfolioConservationGate` breach (SOL/TON excluded via `OutOfScopeFamilySupport`, but the liability
  subtraction is universe-wide — confirm). USDT debt marks at $1; note non-stable debt would need current-mark.
- Files: `BorrowLiabilityTracker`/replay + live-reader override; ADR-069/040 amendment.

### WS-5 — External custody destination ("count on exit") *(point 4; B5)*
The TON "bonus program" is **Telegram Wallet "Доход" (Earn)** — custodial, off-chain, **not readable
on-chain** (balance/APY auth-gated in the Wallet backend; USDT-in / USDe-balance mismatch proves an
independent ledger). No on-chain reader, no hardcoded address.

**User decision (final): treat it like a known external custody destination (an exchange/vault we can't see
into) — NOT part of the accounting universe, NOT shown in portfolio quantity.** We do not track its physics
(balance/APY). We only record "put X in, took Y out". Rejected: the earlier in-universe basis-pool model and
any manual-declared portfolio balance.

**Model — labeled external counterparty, standard external-transfer semantics:**
- **Attribution:** descriptor-driven counterparty **label** for the pooled operator address the user
  designates (never hardcoded, never a universe member). Stamps `custodialOffChain` (WS-8).
- **Deposit:** funds sent to the venue → `EXTERNAL_TRANSFER_OUT` to the labeled counterparty (capital leaves
  tracked scope; standard AVCO treatment). No phantom balance appears → **no conservation-gate impact**.
- **Withdrawal ("on exit"):** funds returned → `EXTERNAL_TRANSFER_IN` from the labeled counterparty (new
  capital at market at return time). This is where yield materializes (Y − X), via normal external-in accounting.
- **Custody ledger (informational only):** a simple per-venue tally of **deposited X / withdrawn Y** so the
  user can see money is parked there. **Not** included in portfolio totals, AVCO, or dashboard quantity; not a
  universe holding.
- **No** live balance, **no** manual portfolio balance, **no** invented yield, **no** universe membership.
- Files: descriptor-driven external custody counterparty label (ingestion `CounterpartyResolver`/clarification),
  optional informational custody-ledger read + small UI line; new ADR. (No `on_chain_balances` contribution,
  no lending-group synthesis for this venue.)
- Acceptance: deposits/withdrawals to Telegram Earn are attributed to a labeled external custody counterparty;
  X-in/Y-out visible in an informational custody ledger; **not** counted in portfolio quantity/AVCO; on-chain
  USDT/TON AVCO uncorrupted; conservation gate unaffected.

### WS-6 — Pricing coverage: swap-derived first *(point 3 completion; B4)*
**Primary mechanism = the existing `SwapDerivedPriceResolver` (`@Order(20)`, event-local).** It derives
a leg's price from the counterpart legs in the same swap, so an external quote is needed **only for one
anchor/result leg** (docs: "prefer tx-local swap-derived over generic candles" for long-tail DeFi). Most
SPL memecoins / TON jettons were acquired via swaps, so their historical basis is swap-derived — **no
external feed required** for them.

**General principle (user clarification): swap-derived yields only a RATIO — it needs an external price for at
least one leg (a stable/native/externally-priced anchor). This is universal for ALL assets without a stable
leg, not SOL/TON-specific.** When both legs are non-stable / non-anchor (illiquid ↔ illiquid), swap-derived
alone cannot produce a USD value → the asset is **UNPRICED** (never $0 basis). The existing resolver already
behaves this way (it derives from siblings that carry a resolved quote); this WS just ensures the behavior and
the explicit UNPRICED state hold across networks.
- **Dependency:** swap-derived needs *both* legs correctly normalized ⇒ it "switches on" automatically
  once WS-1 (Solana loop/swap legs) and WS-2 (TON Ston.fi swap legs, pTON netting) land. This is the
  main fix for move-basis basis of illiquid SOL/TON assets.
- **External feeds become fallback only** for: (a) the anchor/result asset when neither leg is a known
  stable/native, and (b) current mark-to-market (live) quotes. Extend `JupiterPriceLatestPriceProvider`
  mint coverage for SPL; add a TON jetton price provider (STON, XAUt); map xStock jettons (AMZNx, MSTRx)
  to their equity quotes (e.g. DZENGI) where legitimate. Graceful explicit "unpriced" otherwise.
- Ensure SOL/TON majors are in `tracked_price_assets` before registry build (ordering fix if still present).
- **SOL/TON swap-derived correctness notes (financial review):**
  - pTON must be **netted to 0 in WS-2 before pricing** so it never becomes a swap sibling leg (else it
    re-enters as a phantom-priced counterpart).
  - wSOL must canonicalize to **SOL** (`So111…112`) so a wSOL leg neither trips the circular guard nor is
    treated as a distinct asset.
  - **No real SOL↔TON single "swaps"** — cross-network moves are **bridges**, not one SWAP row; never model a
    bridge as a SWAP.
  - illiquid→illiquid SPL/jetton swaps with no stable/native leg fall through to **explicit UNPRICED**, never
    zero-price covered quantity.
- Files: verify `SwapDerivedPriceResolver` fires for Solana/TON SWAP rows post WS-1/WS-2; `LatestPriceProvider`
  implementations (fallback + current marks), `TrackedAssetRegistryMaintainer`; ADR-068 amendment +
  docs/pipeline/pricing note that swap-derived is authoritative for long-tail SOL/TON basis.
- Acceptance: move-basis effective cost populated for SOL & TON primarily via SWAP_DERIVED; external feeds
  only for anchors + live marks; **genuinely unpriced memecoins (SNAI/DUKO/PIAI/DOOD/…) carry an explicit
  UNPRICED state** (business review) — not zero-price covered quantity, not a false $0 basis.

### WS-7 — Metadata architecture: **delete** `token-metadata.json` *(point 5)*
Goal per user: **remove the file entirely** — it has no residual value once live resolution + descriptor
cover its contents. Migration:
- Wire live metadata resolvers into **normalization** (not just read):
  - Solana: use `JupiterSplTokenMetadataResolver` in `SolanaNormalizedTransactionBuilder`/classifier.
  - TON: add a live `TonJettonMetadataResolver` (toncenter jetton master / API) used by builder + balance
    provider. Resolves RWA/xStock jettons live (no hand entries).
- **Replay determinism (architecture + financial review — MUST precede deletion):** normalization is a
  background, replayable pipeline and replay must not call RPC. Caffeine alone is insufficient (lost on
  restart; a 2-year renorm would re-hit Jupiter/toncenter for thousands of mints and could return different
  **symbols** across runs). Add a durable **write-through `token_metadata_cache`** Mongo collection
  (resolved identity + first-seen), resolution order: **descriptor override → persistent cache → live
  resolver (write-through) → explicit `unresolved`** (mirrors `historical_prices`/`v4_pool_state_cache`
  persist-then-replay). Integration seam: `CanonicalMetadataEnricher`.
- **Add `TonRequestThrottle`** (platform, ≈1 rps free tier) before wiring the TON resolver — there is no TON
  throttle today; the first full renorm would otherwise get throttled/banned.
- **Load-bearing decimals are financially critical** (`qty = raw / 10^decimals` feeds AVCO/value). Seed these
  as a **checked-in override/seed map in `network-descriptors.yml` BEFORE deletion**: `soUSDC=12` (on-chain
  misreport), **USDT-TON=6**, **SPL USDC/USDT=6**, **AMZNx/MSTRx=8, XAUT0=6**. A wrong live-defaulted decimals
  (e.g. jetton→9) silently corrupts an asset's entire recomputed history.
- **Migrate-then-delete (business review):** first migrate overrides + stand up cache/resolvers, then delete
  the JSON, gated on a **before/after renormalization equivalence diff** on decimals/symbol/quantity (not just
  EVM parity tests).
- Redundancy assessment (all removable):
  - `solanaSplTokens` (USDC/USDT/wSOL) → already in `network-descriptors.yml` (stables + wrapped-native)
    + live Jupiter resolver.
  - `tonJettons` (USDT + RWA) → USDT in descriptor; RWA via live TON resolver.
  - `fallbackTokens` / `builderTokens` (EVM) → live RPC `decimals()/symbol()` + descriptor stables.
- **Genuine overrides** (the only irreducible bit — e.g. `soUSDC decimalOverride=12` where the token
  misreports decimals on-chain) move into `network-descriptors.yml` as an explicit per-network
  token-override map (single source of truth), **not** a separate JSON file.
- Move all **identity** (native, wrapped-native, USD-stable) fully to `network-descriptors.yml` and drive
  stable/native detection there; reduce symbol-hardcoded `USD_STABLE_SYMBOLS`/`AssetFamily.STABLE_USD`
  reliance where a contract/descriptor suffices.
- Files: **delete** `token-metadata.json`; metadata resolvers + registries, `network-descriptors.yml`
  (override map), `CanonicalAssetCatalog`, `AssetFamily`; ADR for unified metadata resolution.
- Acceptance: `token-metadata.json` no longer exists; AMZNx/MSTRx/XAUt/STON/pTON + soUSDC etc. resolve via
  live resolvers + descriptor overrides; no regression on EVM/Solana/TON symbols/decimals.

### WS-8 — Invariant: network specifics irrelevant after normalization *(point 6)*
**Guiding principle (user-approved): after normalization, downstream must be network-agnostic.** The
justified work is eliminating **post-normalization network branching**, not generic code hygiene. This is
**not a new invention** — it is the direct generalization of the already-accepted, already-enforced **ADR-052
invariant ("venue specificity ends at normalization")** from the *venue* axis to the *network* axis (that
precedent makes the riskiest workstream the safest).

Core change — **stamp semantic capability flags at normalization time** so downstream reads attributes,
never the network:
- `receiptBearingCollateral` (false for Jupiter Lend / receipt-less) → replaces `isEvm()` guards in
  `LendingCycleBuilder.withSynthesizedOutstandingSupply` and the `startsWith("AAVE")` live-HF gate
  (which becomes the WS-3 SPI dispatch anyway).
- `lpConcentrated` / typed LP-correlation kind → replaces string `lp-position:solana:*` checks in
  `SessionLpQueryService.resolveClosed` and `LpPositionRefreshService`.
- `custodialOffChain` (WS-5) → replaces any venue/network special-casing on read.
- Canonical address / txHash stored **on the normalized record** so read services
  (`AssetLedgerReconciliationService`, dashboard) consume already-canonical values instead of
  re-normalizing per family.
- **Flags as an open semantic capability vocabulary** (protocol/venue capabilities), not a closed enum, to
  avoid future sprawl. Domain record stores **primitive/enum flags only** — never a resolver/registry type
  (keeps domain purity; `domain_must_not_depend_on_runtime_layers` already guards this).
- **Enforce by EXTENDING the existing rule (architecture review):** add a sibling to
  `ModuleDependencyArchTest.post_normalization_packages_must_not_depend_on_VenueRegistry_or_descriptors`
  that forbids **branching on** `NetworkId` members / `NetworkAddressFormat` in read/query packages
  (`..application.*.read..`/query services in costbasis, portfolio, pricing, lending, liquiditypools,
  linking read paths) — scoped so DTOs may still *carry* `NetworkId`, with an explicit serialization
  allowlist. Add a `VenuePrefixGuardTest`-style **source scan** for string literals like
  `"lp-position:solana:"` (ArchUnit sees types, not string constants).
- Files: normalized model fields + stamping in the normalization builders; downstream services read
  flags; extended ArchUnit rule + source-scan test; docs update.
- Acceptance: no `isEvm()` / `NetworkId ==` / `lp-position:solana:` branching in post-normalization read
  services; extended ArchUnit + source scan green.

### WS-8b — Code hygiene *(point 6; OPTIONAL — only if justified)*
Deferred unless it blocks WS-8 or correctness; these do **not** violate the post-normalization invariant
(they are ingestion/enrichment-layer concerns):
- ~~Single source of truth for Solana program IDs~~ → **promoted into WS-1's DoD** (WS-1 depends on it).
- Move HTTP/WebClient + RPC clients from `backend/core` to `backend/platform` behind ports
  (`MeteoraDlmmApiClient`, `RaydiumClmmApiClient`, pricing/CEX clients). Note: WS-3 already moves the Jupiter
  Lend transport to platform — extend opportunistically.
- Do only where it pays off; otherwise leave as-is.

---

## 3b. Edge cases & explicit out-of-scope (from Phase-3 review)

Edge cases each workstream must handle:
- **Live lending reader unreachable (WS-3):** serve last snapshot marked **stale**; never fabricate; if no
  snapshot, fall back to the guarded synthesized supply (clearly stale), not silent 0/Liquidation-risk.
- **Telegram Earn (WS-5):** external custody destination — deposits = external-out, withdrawals = external-in
  ("count on exit"). Informational custody ledger shows X-in / Y-out only; nothing in portfolio quantity/AVCO;
  no live/declared balance; yield realized as Y−X on return (USD-value based, asset may differ USDT→USDe).
- **Genuinely unpriced memecoins/jettons (WS-6):** explicit **UNPRICED** state (SNAI, DUKO, PIAI, DOOD,
  vPtS4ywr, AuFy4U; STON, XAUt, AMZNx, MSTRx if no swap/feed) — never $0 covered basis.
- **Metadata resolver timeout (WS-7):** persistent cache/override wins; else `unresolved` — never a wrong
  default-decimals corruption.

Explicit **out-of-scope** for this cycle:
- **SOL↔TON cross-network transfers/bridges** as a linked corridor — declared out-of-scope; must **not** be
  modeled as a single SWAP (WS-6 guard prevents mis-pricing). Revisit as a separate bridge-corridor task.
- **WS-8b** client-move/hygiene beyond what WS-1/WS-3 already require.
- **Live readout of the Telegram Earn balance/APY** (proven off-chain/auth-gated); also **no** in-portfolio
  balance for it (external custody, count on exit).

## 4. Documentation & ADRs
- New ADR: `Live lending position SPI (multi-network health/APY/collateral)`.
- New ADR: `Off-chain / custodial earn venue tracking`.
- New ADR: `Unified token metadata resolution (live-first, descriptor-driven; retire token-metadata.json)`.
- Amend: ADR-068 (pricing coverage), ADR-069/040 (Solana liability interest), ADR-070 (LP typed correlation).
- Update: `docs/pipeline/normalization/rules/families/{solana,ton}.md`, `docs/frontend/lending-ui.md`
  (live badges, off-chain venue), `docs/reference/` (TON protocol registry).

## 5. Acceptance criteria (mapped to the six points)
1. Jupiter HF shows live ≈1.51 (LIVE_PROTOCOL) **and surfaces LTV ≈56.4%**, not 2.54.
2. Live 5.42 SOL collateral visible on lending **and** counted in dashboard portfolio quantity, **counted
   exactly once** across AVCO ledger / `on_chain_balances` / `borrow_liabilities` (single-authority rule).
3. Move-basis effective cost populated & correct for SOL and TON — primarily via **SWAP_DERIVED** basis
   (external feeds only for anchors + live marks); no phantom pTON, no unpriced covered quantity.
4. Telegram Wallet Earn is treated as a **labeled external custody destination** — deposits/withdrawals
   attributed to it (X in / Y out visible in an informational custody ledger), **not** part of the universe,
   **not** counted in portfolio quantity/AVCO; yield realized on exit (Y−X).
5. `token-metadata.json` **deleted**; RWA/xStock/jetton + soUSDC etc. resolve via live resolvers +
   descriptor overrides; no symbol/decimals regression.
6. **No network branching after normalization** (no `isEvm()` / `NetworkId ==` / `lp-position:solana:` in
   read services); capability flags stamped at normalization; ArchUnit enforces the invariant & is green.
7. `/financial-audit` re-run after implementation shows no regressions and economically correct summary.

## 6. Risks & mitigations
- **Jupiter Lend API stability / rate limits** → reuse `HeliusRequestThrottle`, cache, guarded stale
  fallback (never fabricate; mark stale).
- **Renormalization blast radius** (WS-1/WS-2/WS-7 change classification) → run
  `prod-reset-rebuild-backend.sh --skip-frontend` (+`--clear-pricing-cache` for WS-6/WS-7), verify EVM
  parity via existing tests before/after.
- **Off-chain venue UX** (WS-5) — must be unmistakably "off-chain/estimate" to avoid false precision.
- **Boundary refactor regressions** (WS-8) — pure moves behind ports + ArchUnit; no behavior change.

## 7. Suggested sequencing (revised per Phase-3 review)
WS-1 + WS-2 (classification, renormalize) **‖ WS-3** (live lending SPIs — run in parallel for earliest
user-visible value) → WS-4 (live debt true-up, SET/override) → WS-6 (pricing; swap-derived **auto-unlocks**
once both legs are normalized) → WS-5 (off-chain earn venue) → WS-7 (**migrate overrides + stand up
cache/resolvers, THEN delete** the metadata file, gated on renorm equivalence diff) → WS-8
(post-normalization invariant + extended ArchUnit). WS-8b (hygiene) is optional/opportunistic; the Solana
program-ID single-source it used to contain is now part of WS-1's DoD.

**Single-authority reminder:** WS-1 (accounting) and WS-3 (live reader) both touch collateral/debt — WS-3 is
the sole authority that trues both to 5.42 SOL / 233 USDT; validate them together, not independently.
