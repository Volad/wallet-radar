# WalletRadar architecture & hardening audit (post Solana/TON integration)

> **Date:** 2026-07-21
> **Type:** DESIGN-ONLY architecture assessment + hardcode-externalization migration plan (no code changes)
> **Scope:** full backend (`backend/core`, `backend/platform`, `backend/domain`) + `resources/**` config planes,
> with emphasis on the Solana/TON code paths introduced after the EVM consolidation waves W1–W11.
> **Companion:** [hardcoded-registry-consolidation-proposal.md](hardcoded-registry-consolidation-proposal.md)
> (W1–W11 inventory of record). This document opens **W12+** and treats those waves as done.
> **Lens (system-architect skill):** cost-first (free/self-hosted, public RPC, snapshot-first GET,
> deterministic AVCO, batching/caching); no managed DBs / paid indexers / K8s / microservices for MVP;
> no dataset-specific logic keyed by real tx hashes / wallets.

---

## A. Summary, decisions & assumptions

**Headline.** The EVM plane is now well-externalized (W1–W11: `network-descriptors.yml`,
`protocol-registry.json`, `counterparty-hints.json`, `token-metadata.json`, `direct-method-types.json`,
`lp-position-manager-abi.json`, per-protocol `protocols/*.json`). The **Solana integration re-introduced the
exact anti-pattern those waves removed**: a hardcoded in-code program-ID registry
(`SolanaProgramIds`) that **duplicates data already present in `protocol-registry.json`**, plus scattered
native-asset/mint/decimal literals across balance, lending and pricing readers. TON is comparatively clean
(config-seeded via `ton-protocol-registry.json` + `network-descriptors.yml` token-overrides).

**Most important structural finding.** WalletRadar now has **two parallel Solana program-ID sources of
truth** that must be kept in sync by hand:

- `SolanaCounterpartyResolver` (linking) is **registry-driven** — it looks up `programId` in
  `protocol-registry.json` via `ProtocolRegistryService`. This is the target pattern. ✅
- `SolanaTransactionClassifier` (normalization) is **hardcoded-constant-driven** — it branches on
  `SolanaProgramIds.METEORA_DLMM`, `.RAYDIUM_CLMM`, `.KAMINO_LEND`, etc., **all of which already exist in
  `protocol-registry.json`** with `protocol` / `family` / `event_type`. ❌

The registry already carries Jupiter, Meteora (DLMM/Vault/DAMM v1+v2/Farm), Raydium (AMM v4/CLMM/CPMM),
Kamino (Lend/Vault), Marinade, Jito. `SolanaProgramIds` re-declares the same base58 strings as Java
constants (only Jupiter Lend is registry-derived, and even that keeps a hardcoded anchor string). This is
W1-class drift risk: a program redeployment or a new venue updated in the registry silently diverges from
the classifier, and vice-versa.

**Decisions (proposed, cost-neutral):**

1. **D-1 — One Solana program registry.** Make `SolanaTransactionClassifier` read program→(protocol,
   family, event_type) from `protocol-registry.json` (through a Solana-scoped registry view), the same way
   `SolanaCounterpartyResolver` already does. `SolanaProgramIds` becomes a thin set of *role* accessors
   derived from the registry (like `SolanaProtocolPrograms.jupiterLendProgramIds()`), not a literal store.
2. **D-2 — Native asset identity is descriptor-derived.** `NATIVE:SOLANA` / `TONCOIN` / native decimals (9)
   are duplicated across ≥8 classes. Introduce a `NetworkNativeAsset` accessor over `network-descriptors.yml`
   (native symbol already there; add native `contract`/sentinel + `native-decimals`) and delete the local
   constants.
3. **D-3 — SPL/jetton value-asset sets come from descriptors.** `SolanaProgramIds.WSOL_MINT/USDC_MINT/
   USDT_MINT/SOLANA_USD_STABLE_MINTS` and `JupiterLendLivePositionReader`'s mint/symbol map duplicate
   `SOLANA.wrapped-native` + `SOLANA.usd-stable-contracts` + `token-overrides` in `network-descriptors.yml`.
   Derive them.
4. **D-4 — No new config plane needed for Solana programs** — reuse `protocol-registry.json`. TON already has
   `ton-protocol-registry.json`. Only additive descriptor fields (native contract/decimals) are new.

**Assumptions:** the current dataset renormalizes green under `--skip-frontend`; each wave below is
independently shippable and verified with `--skip-frontend` renormalization + `financial-logic-auditor`
(off-AVCO-path items are `--backend-only`). No behavior change is intended for D-1..D-3 (pure source-of-truth
consolidation) except the explicit Token-2022 balance fix (F-perf-2) which is a correctness improvement.

---

## B. Ranked findings

Severity × category. Effort: S ≤ 0.5d, M ≈ 1–2d, L ≈ 3–5d.

| # | Sev | Category | Location (file / class) | Current state | Target plane / refactor | Effort |
|---|-----|----------|-------------------------|---------------|-------------------------|--------|
| **1** | **Critical** | architecture + hardcode | `SolanaTransactionClassifier` + `SolanaProgramIds` | Classifier branches on hardcoded base58 constants; **same IDs already in `protocol-registry.json`** → two sources of truth | Registry-driven classification via a Solana registry view (D-1); `SolanaProgramIds`→role accessors | L |
| **2** | High | hardcode | `SolanaProgramIds` (Meteora/Raydium/Kamino/Marinade/Jito/DFLOW/OKX/Jupiter swap + RFQ) | ~18 base58 program IDs as `public static final` literals | Delete; derive from `protocol-registry.json` (`SolanaProtocolPrograms.programIdsForProtocol(...)`) | M |
| **3** | High | hardcode | `JupiterLendLivePositionReader` (WSOL/USDC/USDT mints, SOL/stable decimals, `canonicalSymbol` map, `NATIVE:SOLANA`) | Triplicates mints + symbol/decimals already in `network-descriptors.yml SOLANA` | Descriptor-derived mint/symbol/decimals (D-2/D-3) | M |
| **4** | High | performance / correctness | `SolanaOnChainBalanceProvider.fetchSplTokens` | `getTokenAccountsByOwner` queried **only** for classic SPL Token program; **Token-2022 balances are never enumerated** | Add a second `getTokenAccountsByOwner` call for `TOKEN_2022_PROGRAM` (batch/merge) | S |
| **5** | High | hardcode | Native identity literals `NATIVE:SOLANA` / `TONCOIN` / `SOL_DECIMALS=9` / `TON_DECIMALS=9` in ≥8 classes | Scattered private constants (balance providers, lending readers, pricing latest providers, TON/Solana builders, `CanonicalMetadataEnricher`) | `NetworkNativeAsset` accessor over descriptors (D-2) | M |
| **6** | Medium | hardcode | `SolanaTransactionClassifier` inline protocol display names + families ("Meteora DLMM", "kamino-lend", "LENDING"/"YIELD"/"LP"/"AGGREGATOR") | Duplicated from `protocol-registry.json` `name`/`protocol`/`family`/`event_type` | Fold into D-1 (registry supplies name/protocol/family) | M |
| **7** | Medium | modularity / duplication | `SolanaBase58` exists **twice**: `platform.networks.solana` and `application.liquiditypools.enrichment.solana` | Byte-identical duplicate codec | Delete the enrichment copy; depend on the platform one (allowed: application→platform) | S |
| **8** | Medium | hardcode | `SolanaProgramIds` native/system programs (System, Token, Token-2022, ATA, ComputeBudget, Memo×2, Stake, Lighthouse) + `SolanaOnChainBalanceProvider.SPL_TOKEN_PROGRAM_ID` re-declared | Solana runtime "standards" as constants; SPL token program id duplicated in balance provider | Keep as **one** Solana constants holder (these are protocol-agnostic standards, §2.2 rule) but **dedupe** the balance-provider copy against it | S |
| **9** | Medium | architecture | `SolanaProgramIds` lives in `application.normalization.pipeline.solana` but is imported by `costbasis.balance`, `liquiditypools.enrichment`, `lending` | Normalization-owned constants leak across bounded contexts | Move Solana chain-vocabulary (system program ids, base58) to `platform.networks.solana`; keep protocol semantics in the registry | M |
| **10** | Medium | smell | `SolanaTransactionClassifier` — ~975 lines, 18-rule `if`-chain, flow-shape math, Helius string sets, mint math all in one class | God class mixing rule ordering, leg math, and metadata | Extract flow-shape/net-by-mint helper + rule table; shrinks after D-1 | L |
| **11** | Medium | smell / dead code | `SolanaTransactionClassifier:271-273` staking branch: both ternary arms return `STAKING_DEPOSIT` | `STAKING_DEPOSIT_TYPES` set is computed but the branch can never yield anything else | Fix ternary or drop the dead set | S |
| **12** | Low | hardcode | Helius `type`/`source` string sets (`OTHER_AGGREGATOR_SOURCES`, `HOUSEKEEPING_TYPES`, `NFT_MINT_TYPES`, `STAKING_*_TYPES`, `LP_*_TYPES`) in `SolanaTransactionClassifier` | Provider-vocabulary literals inline | Optional `helius-vocabulary.json` (or keep — they are Helius-API vocab, not chain data) | S |
| **13** | Low | hardcode | `SolanaLpPositionReader` protocol labels ("Meteora DLMM"/"Raydium CLMM") + Anchor byte offsets | Display names duplicate registry; offsets are ABI layout | Names from registry (D-1); offsets are legitimate ABI (keep, like `lp-position-manager-abi.json`) | S |
| **14** | Low | consistency | `LendingReceiptLessActiveMarketSource`, `JupiterLendLockedCollateralProvider`, `TonJettonLatestPriceProvider`, `JupiterPriceLatestPriceProvider` each re-declare `NATIVE:SOLANA`/`TONCOIN`/`WRAPPED_SOL_MINT` | Same literals as #5/#3 | Fold into D-2/D-3 accessors | S |
| **15** | Low | smell | `SolanaProgramIds.SOL_DECIMALS`/native handling assumes 9; TON `DEFAULT_JETTON_DECIMALS=9` fallback in builder | Magic precision defaults | Descriptor `native-decimals`; keep jetton fallback documented | S |

---

## C. Hardcode → config plane migration list

Each row: **literal → where it lives now → target plane → why.** "Keep" = legitimately code-resident (§2.2
rule from the prior proposal: chain/ABI standards and security heuristics stay in code).

### C.1 Solana program IDs (the primary ask)

| Literal(s) | Current location | Target plane | Why |
|---|---|---|---|
| `JUPITER_SWAP_V6/V4`, `JUPITER_RFQ_ORDER_ENGINE` | `SolanaProgramIds` constants | **`protocol-registry.json`** (already present) → read via registry | Already in registry with `protocol=Jupiter`, `event_type=SWAP`; constant is a duplicate |
| `METEORA_DLMM`, `METEORA_VAULT`, `METEORA_DYNAMIC_AMM`, `METEORA_DAMM_V2`, `METEORA_FARM` | `SolanaProgramIds` | `protocol-registry.json` (present) | Duplicate of registry rows (Meteora family) |
| `RAYDIUM_AMM_V4`, `RAYDIUM_CLMM`, `RAYDIUM_CPMM` | `SolanaProgramIds` | `protocol-registry.json` (present) | Duplicate; `RAYDIUM_CLMM` also used by `SolanaLpPositionReader` memcmp — expose via registry accessor |
| `KAMINO_LEND`, `KAMINO_VAULT` | `SolanaProgramIds` | `protocol-registry.json` (present) | Duplicate |
| `MARINADE`, `JITO_STAKE_POOL` | `SolanaProgramIds` | `protocol-registry.json` (present) | Duplicate |
| `HAWKSIGHT`, `DFLOW`, `OKX_DEX_ROUTER`, `BUBBLEGUM` | `SolanaProgramIds` | `protocol-registry.json` (add rows if missing; Hawksight/DFLOW/OKX/Bubblegum) | Bring into the one registry so classifier + counterparty agree |
| `WSOL_MINT`, `USDC_MINT`, `USDT_MINT`, `SOLANA_USD_STABLE_MINTS` | `SolanaProgramIds` | **`network-descriptors.yml`** `SOLANA.wrapped-native` + `usd-stable-contracts` (present) | Duplicate of descriptor sets; derive via `NetworkRegistry`/`NetworkStablecoinContracts` |
| WSOL/USDC/USDT mints + `USDT`/`USDC`/`SOL` symbol map + `SOL_DECIMALS`/`STABLE_DECIMALS` | `JupiterLendLivePositionReader` | `network-descriptors.yml SOLANA.token-overrides` (present) + descriptor native | Triplication; use `NetworkTokenOverrides` + native accessor |
| System/Token/Token-2022/ATA/ComputeBudget/Memo/Stake/Lighthouse program ids | `SolanaProgramIds` + `SolanaOnChainBalanceProvider.SPL_TOKEN_PROGRAM_ID` | **Keep** in one `platform.networks.solana` constants holder; **dedupe** balance-provider copy | Chain runtime standards (§2.2), not protocol data — but must not be duplicated |
| Anchor byte offsets (Meteora `PositionV2`, Raydium `PersonalPositionState`) | `SolanaLpPositionReader` | **Keep** (ABI layout, like `lp-position-manager-abi.json`) | Structural ABI, not a registry entry |

### C.2 Native asset identity

| Literal | Current location(s) | Target plane | Why |
|---|---|---|---|
| `NATIVE:SOLANA` | `SolanaOnChainBalanceProvider`, `JupiterLendLivePositionReader`, `JupiterLendLockedCollateralProvider`, `JupiterPriceLatestPriceProvider`, `LendingReceiptLessActiveMarketSource` | `network-descriptors.yml` (native accessor `NetworkNativeAsset.identity(SOLANA)`) | 5+ copies of one sentinel |
| `TONCOIN` | `TonOnChainBalanceProvider`, `TonNormalizedTransactionBuilder`, `TonJettonLatestPriceProvider`, `CanonicalMetadataEnricher`, `OutOfScopeFamilySupport` | native accessor | 5 copies |
| `SOL_DECIMALS=9`, `TON_DECIMALS=9`, `DEFAULT_JETTON_DECIMALS=9` | Solana/TON builders + balance providers | descriptor `native-decimals` | magic precision |
| `NATIVE_SOL_SYMBOL="SOL"`, `NATIVE_TON_SYMBOL="TON"` | balance providers | descriptor `native-symbol` (already present) | duplicate of existing field |

### C.3 Provider/API vocabulary (optional / keep)

| Literal | Location | Decision |
|---|---|---|
| Helius `type`/`source` sets | `SolanaTransactionClassifier` | **Keep** or move to `helius-vocabulary.json` (low value; it is Helius-API vocabulary, not chain data) |
| Raydium/Meteora base URLs + URI paths | `RaydiumClmmApiClient`, `MeteoraDlmmApiClient` | **Already externalized** to `LiquidityPoolsProperties.Solana` (base URLs); URI paths are API contract — keep |
| `SPL_TOKEN_PROGRAM_ID` for `getTokenAccountsByOwner` | `SolanaOnChainBalanceProvider` | Dedupe against the platform Solana constants holder; **add Token-2022** (see F-perf-2) |

### C.4 TON — already externalized (no action)

`ton-protocol-registry.json` (pTON masters, DEX/STAKING vaults), `network-descriptors.yml TON.token-overrides`
(USDT-TON, AMZNx/MSTRx decimals), `TonAddressCanonicalizer` cross-form keys. TON is the reference shape for
how Solana should look after W12.

---

## D. Architecture gaps & module boundaries

### D.1 Current module map (as-built)

```
                         ┌─────────────────────────── api (BFF) ───────────────────────────┐
                         │  controllers · BFF mappers · DTOs   (GET = datastore-only)        │
                         └───────────────▲───────────────────────────────▲──────────────────┘
                                         │                                │
        ┌────────────────────────────── application (bounded contexts) ──┴───────────────────┐
        │ normalization ─ linking ─ costbasis ─ pricing ─ portfolio ─ lending ─ liquiditypools│
        │   │                                                                                  │
        │   ├─ normalization.pipeline.solana                                                   │
        │   │     SolanaTransactionClassifier ──(hardcoded)──► SolanaProgramIds  ◄── LEAK: ────┼─┐
        │   │     SolanaProtocolPrograms ──(registry-derived)──► protocol-registry.json        │ │
        │   ├─ normalization.pipeline.ton    (TonProtocolRegistry ──► ton-protocol-registry)   │ │
        │   ├─ costbasis.balance  (Solana/TonOnChainBalanceProvider, NonEvmOnChainBalanceLoader)│ │
        │   ├─ liquiditypools.enrichment.solana (SolanaBase58 ← DUPLICATE of platform)  ◄───────┼─┤
        │   └─ lending.application (JupiterLendLivePositionReader ← duplicated mints/native)     │ │
        └───────────────▲───────────────────────────────────────────────────────────────────┘ │
                        │  (allowed: application → platform)                                     │
        ┌───────────────┴──────────────── platform (technical) ──────────────────────────────┐ │
        │ networks.solana (RpcClient, adapters, Helius, SolanaBase58, SolanaBlockHeight…)     │◄┘
        │ networks.ton (RpcClient, adapters, metadata, price)                                  │
        │ networks.evm (rpc, abi, registries)                                                  │
        └───────────────▲─────────────────────────────────────────────────────────────────────┘
                        │
        ┌───────────────┴──────────────── domain / canonical ──────────────────────────────┐
        │ NetworkId · ton.TonAddressCanonicalizer · correlation prefixes · vocabulary       │
        └──────────────────────────────────────────────────────────────────────────────────┘

  config planes (backend/core/src/main/resources):
    network-descriptors.yml · protocol-registry.json · ton-protocol-registry.json
    counterparty-hints.json · token-metadata.json · direct-method-types.json
    lp-position-manager-abi.json · break-even-attribution.json · protocols/*.json
```

**Leaks flagged (`◄── LEAK`):**

- **G-1 (boundary leak).** `SolanaProgramIds` (Solana **chain vocabulary** + **protocol semantics**) lives in
  `application.normalization.pipeline.solana` yet is imported by `costbasis.balance`, `liquiditypools.enrichment`
  and `lending`. Chain vocabulary (system program ids, base58, native mint) belongs in
  `platform.networks.solana`; protocol semantics belong in the registry. Today application code embeds
  network literals that should be in `platform`/descriptors — the exact leak the layer contract forbids.
- **G-2 (duplicate abstraction).** Two `SolanaBase58` codecs (platform + enrichment). The enrichment copy
  should be deleted; `application → platform` is an allowed dependency.
- **G-3 (missing seam — the extensibility gap).** There is **no `NetworkClassificationDescriptor` / program
  registry SPI** that both the classifier and the counterparty resolver consume. Adding a new Solana venue
  today means editing `SolanaProgramIds` **and** `protocol-registry.json` **and** the classifier `if`-chain.
  The EVM plane solved this with `ProtocolResourceDefinition` + `ProtocolRegistryClassifier`; Solana needs the
  parallel seam.
- **G-4 (no native-asset abstraction).** Every family re-derives native identity from local constants; there
  is no `NetworkNativeAsset` accessor (native symbol/contract/decimals) over `network-descriptors.yml`.

### D.2 Target boundary changes (for seamless network/protocol scaling)

1. **Solana program registry view (fixes G-1, G-3, finding #1/#2/#6).**
   Introduce a Solana-scoped read over `protocol-registry.json` (extend `SolanaProtocolPrograms` or add
   `SolanaProgramRegistry`) exposing `Optional<SolanaProgramClass> classify(programId)` where
   `SolanaProgramClass = {protocol, protocolKey, family, eventTypeHint}`. `SolanaTransactionClassifier`
   consumes it for rule dispatch; flow-shape inference (LP entry/exit/swap, lending net-flow) stays in Java
   because it is genuine economic logic, not data. Net effect: the base58 strings, protocol names and family
   labels all come from the one registry.

2. **Promote Solana chain vocabulary to `platform.networks.solana`.**
   Move system program ids (System/Token/Token-2022/ATA/ComputeBudget/Memo/Stake/Lighthouse), `WSOL_MINT`
   and `SolanaBase58` into `platform.networks.solana` as a single `SolanaChain` holder. `costbasis.balance`,
   `liquiditypools.enrichment`, `lending` depend on `platform` (allowed) instead of on a normalization class.

3. **`NetworkNativeAsset` accessor (fixes G-4, finding #5/#14).**
   Add `native-decimals` and an explicit native `contract`/sentinel to each descriptor entry (SOLANA→
   `NATIVE:SOLANA`, TON→`TONCOIN`, EVM→existing native symbol); expose
   `NetworkNativeAsset.symbol/contract/decimals(NetworkId)`. Delete the per-class copies.

4. **Value-asset sets from descriptors (finding #3).**
   `SOLANA_USD_STABLE_MINTS` → `NetworkStablecoinContracts.forNetwork(SOLANA)`; WSOL → wrapped-native
   accessor. `JupiterLendLivePositionReader` symbol/decimals → `NetworkTokenOverrides.find(SOLANA, mint)`.

5. **"Add a network" checklist stays config-only.** After W12–W15 the extensibility guide
   (`docs/reference/extensibility/add-a-network.md`) can promise: new network = descriptor entry +
   registry rows + an `OnChainBalanceProvider` + a `*NormalizedTransactionBuilder`/classifier that reads the
   registry — **zero new hardcoded program/mint constants**. That is the seamless-scaling target.

---

## E. Performance & bad smells

- **E-perf-1 (High, correctness) — Token-2022 balances dropped.** `SolanaOnChainBalanceProvider.fetchSplTokens`
  calls `getTokenAccountsByOwner` **only** with the classic `SPL_TOKEN_PROGRAM_ID`. Token-2022 holdings are
  never enumerated, so a wallet holding a Token-2022 asset **under-reports on-chain balance** (portfolio +
  conservation-gate impact). The classifier and `SolanaLpPositionResolver` already know `TOKEN_2022_PROGRAM`;
  the balance provider must issue a second call (or a batched pair) and merge. Cost-neutral (one extra RPC per
  wallet refresh, off the GET path).
- **E-perf-2 (Medium) — LP enrichment RPC fan-out.** `SolanaLpPositionReader.readRaydium` issues
  `getAccountInfo` → `getProgramAccounts` (memcmp) → Raydium API per position; Meteora does `getAccountInfo`
  (legacy) → Meteora API per position. This runs per-position in the background refresh (correct: off GET),
  but confirm it is not called on a read path and that the rotator retry/backoff (`RpcEndpointRotator`) bounds
  fan-out. Prefer the captured `lpPoolAddress` fast-path (already implemented for Meteora) to skip the decode
  RPC — extend the same "captured pool address at normalization" optimization to Raydium to drop one RPC/position.
- **E-perf-3 (Medium) — `getProgramAccounts` selectivity.** `findProgramAccountData` uses a memcmp on a
  unique mint with a `dataSlice`; this is the correct cost-minimizing shape. Keep it; document that unfiltered
  `getProgramAccounts` must never be added (public-RPC-hostile). No change needed — noted as a guardrail.
- **E-smell-1 (Medium) — `SolanaTransactionClassifier` god class (~975 lines).** Mixes: 18-rule ordered
  dispatch, Helius vocabulary sets, net-by-mint math, dust thresholds, loop/foreign-swap heuristics. After D-1
  the rule table shrinks; extract `SolanaWalletNetFlow` (net-by-mint/native) and `SolanaFlowShape` helpers.
- **E-smell-2 (Low) — dead branch.** `SolanaTransactionClassifier:271-273` — both ternary arms return
  `STAKING_DEPOSIT`, so `STAKING_DEPOSIT_TYPES` never influences output. Fix or delete.
- **E-smell-3 (Medium) — duplicated `SolanaBase58`** (finding #7): two byte-identical codecs.
- **E-smell-4 (Low) — inconsistent metadata resolvers.** Solana symbol resolution goes through
  `JupiterSplTokenMetadataResolver` + `SolanaSplTokenMetadataRegistry` + `SolanaLiveTokenMetadataResolver` +
  `TokenMetadataResolutionService`; TON through `TonJettonMetadataRegistry` + `TonJettonMetadataResolver` +
  the same `TokenMetadataResolutionService`. The resolution order is documented but the number of entry points
  is a comprehension cost — consider one `TokenMetadataResolver` facade per family behind a shared SPI.
- **E-smell-5 (Low) — `NonEvmOnChainBalanceLoader` swallow-all `catch (Exception)`** per scope returns
  `List.of()` — acceptable for resilience but ensure it increments a telemetry counter so a systematically
  failing provider is observable (snapshot-first correctness depends on this refresh succeeding).
- **Indexes / Mongo:** no new hot query was introduced by Solana/TON on the GET path (dashboards read
  `on_chain_balances` / snapshots). Confirm `on_chain_balances` has its `{sessionId, walletAddress, networkId}`
  compound index covering the new SOLANA/TON rows; the id is already scoped `prefix:wallet:network:identity`.
  No action unless the index audit shows a gap.

---

## F. Prioritized roadmap (waves W12+)

Continues the W1–W11 numbering. Each wave: independently shippable, behavior-preserving unless noted, verified
with `--skip-frontend` renorm + `financial-logic-auditor` (or `--backend-only` for off-AVCO items).

| Wave | Scope | From → To | Risk | Value | Verify |
|---|---|---|---|---|---|
| **W12** | **Solana classifier → registry** (findings #1, #2, #6) | `SolanaProgramIds` literals + inline names/families → `protocol-registry.json` via a Solana program-registry view | Medium (touches normalization output) | **High** (kills the two-source-of-truth drift) | full renorm + auditor; golden test pins program→family |
| **W13** | **Token-2022 balance fix** (E-perf-1 / #4) | add Token-2022 `getTokenAccountsByOwner` call + merge | Low | High (balance correctness) | `--backend-only`; balance-refresh test |
| **W14** | **Native asset accessor** (findings #5, #14) | `NATIVE:SOLANA`/`TONCOIN`/native decimals/symbols → `NetworkNativeAsset` over descriptors | Low | Medium (kills ~15 copies) | golden test + renorm |
| **W15** | **Jupiter Lend reader de-dup** (finding #3) | reader mints/symbols/decimals → `NetworkTokenOverrides` + native accessor | Low | Medium | `--backend-only` (live read) + lending test |
| **W16** | **Move Solana chain vocab to platform + delete duplicate Base58** (findings #7, #8, #9, G-1/G-2) | system program ids + `SolanaBase58` + WSOL → `platform.networks.solana.SolanaChain`; dedupe balance-provider copy | Medium (import churn) | Medium (boundary hygiene) | `:backend:core:test` + ArchUnit `ModuleBoundaryTest` |
| **W17** | **God-class extraction + dead branch** (E-smell-1/2, #10, #11) | extract `SolanaWalletNetFlow`/`SolanaFlowShape`; fix staking ternary | Low | Medium (maintainability) | unit tests unchanged output |
| **W18** *(optional)* | **Helius vocabulary externalization** (#12) | `helius-vocabulary.json` | Low | Low | golden test |
| **W19** *(optional)* | **Metadata resolver facade** (E-smell-4) | one `TokenMetadataResolver` per family behind shared SPI | Medium | Low–Medium | equivalence test |

**Suggested order:** W13 (fast correctness win) → W12 (highest structural value) → W14 → W16 → W15 → W17 →
W18/W19 as capacity allows. W12 and W16 are the two that most reduce future per-network effort.

---

## G. Cost analysis (cost-first lens)

- **No new spend.** All migrations move literals into existing config planes or existing free public APIs
  (Raydium v3, Meteora data API, Jupiter, Helius free, TON Center). No paid indexer, no managed DB, no K8s.
- **W13 (Token-2022)** adds **one** extra RPC per wallet balance refresh (background, off GET). Batchable with
  the existing SPL call; net cost ≈ +1 request/wallet/refresh cycle — negligible under the rotator.
- **W12** is compute-neutral: registry lookup replaces constant comparison; the registry is loaded once at
  startup (same as today's `SolanaProtocolPrograms.load()`), so no per-tx cost.
- **Snapshot-first preserved.** None of the waves add RPC to a GET path; all live reads remain in background
  refresh services. Dashboard/lending/LP GET stays datastore-only. ✅
- **Determinism preserved.** AVCO inputs are unchanged by D-1..D-3 (same program→family mapping, just sourced
  from config); W13 changes *balance completeness*, not replay math (balances are read-model, not AVCO input).

---

## H. Risks & mitigations

| Risk | Mitigation |
|---|---|
| W12 changes normalization output if a registry row's `family`/`event_type` differs from a hardcoded branch | Before deleting constants, add a golden test asserting registry `family/event_type` == the current hardcoded mapping for every Solana program; only then flip the classifier. Ship behind the standard renorm + auditor gate. |
| Base58 case-sensitivity (Solana) vs lowercasing (EVM) — a registry-driven lookup must not lowercase Solana keys | The registry loader (`SolanaProtocolPrograms`) already preserves case; keep the Solana lookup path case-sensitive (the counterparty resolver already does). Add a test with a mixed-case control. |
| Token-2022 fix could double-count a mint present under both programs | A mint is owned by exactly one token program; merge by mint key defensively (putIfAbsent + sum) — already the loader's shape. |
| Moving `SolanaProgramIds` across modules breaks many imports | Do W16 as a pure move + re-export shim first, then delete the shim; run `ModuleBoundaryTest`. |
| Native-asset accessor mis-maps a network with no wrapped-native (TON) | Native contract/decimals are explicit descriptor fields; TON has no wrapped-native and uses `TONCOIN` sentinel — encode that directly, don't derive from wrapped-native. |
| Registry rows missing for Hawksight/DFLOW/OKX/Bubblegum | Add them to `protocol-registry.json` as part of W12 (they are currently classifier-only) so classifier + counterparty agree; verify each base58 against on-chain before adding (no fabricated addresses — same rule as the TON registry's Affluent note). |

---

## Currency review — 2026-07-24

Re-verified every headline finding against the committed `solana-ton-network-support` branch (commit `e5b45e6`). **The plan is current**; waves W12–W19 are still open with one partial building block:

| Wave | Status | Evidence |
|---|---|---|
| W12 | **Open** | `SolanaTransactionClassifier` still branches on **33** `SolanaProgramIds.*` constant refs; `SolanaProtocolPrograms` exposes only `programIdsForProtocol` (protocol→IDs), **not** the reverse `programId→{protocol,family,eventTypeHint}` lookup the classifier needs. |
| W13 | **Open** | `SolanaOnChainBalanceProvider.fetchSplTokens` queries only `SPL_TOKEN_PROGRAM_ID`; Token-2022 holdings never enumerated. |
| W14 | **Partial** | `domain.common.NetworkNativeAssets` exists (native **symbol** + wrapped-native + native-alias contracts) — reuse it, but it has **no native-identity sentinel** (`NATIVE:SOLANA`/`TONCOIN`) accessor and **no `native-decimals`**; `SolanaOnChainBalanceProvider` still hardcodes `NATIVE_SOL_SYMBOL`/`SOL_DECIMALS=9`. W14 = extend `NetworkNativeAssets` (identity + decimals) and delete per-class copies. |
| W15 | **Open** | `JupiterLendLivePositionReader` mint/symbol/decimals map still local. |
| W16 | **Open** | `SolanaBase58` duplicated in `platform.networks.solana` **and** `application.liquiditypools.enrichment.solana`; `SolanaProgramIds` still imported across `costbasis.balance`/`liquiditypools`/`lending`. |
| W17 | **Open** | Dead staking ternary confirmed (`SolanaTransactionClassifier` lines 271-273 — both arms return `STAKING_DEPOSIT`); god class intact. |
| W18/W19 | **Open (optional)** | Helius vocab inline; multiple metadata resolver entry points. |

**Execution order (unchanged):** W13 → W12 → W14 → W16 → W15 → W17 → W18/W19. Each wave behavior-preserving unless noted (W13 balance-completeness fix); AVCO-path waves (W12) verified with `--skip-frontend` renorm + `financial-logic-auditor`, off-path waves (W13/W15/W16) with `--backend-only` + tests. **No regression without cause** — W12 lands behind a golden test asserting registry `family/event_type` == the current hardcoded mapping for every Solana program before any constant is deleted.

## Wave completion log — 2026-07-24

All mandatory waves executed and verified on branch `solana-ton-network-support`.

| Wave | Status | Result |
|---|---|---|
| **W13** | ✅ Done | `SolanaOnChainBalanceProvider.fetchSplTokens` now enumerates classic SPL **and** Token-2022, merged by mint (a mint is owned by exactly one program → keep-first dedup). Regression test added. `:backend:core:test` green. |
| **W12** | ✅ Done | `SolanaTransactionClassifier` dispatches off a reverse registry view (`SolanaProtocolPrograms.classify`) instead of `SolanaProgramIds` constants; protocol constants removed; `BUBBLEGUM` added to `protocol-registry.json`. 21-method golden test asserts registry mapping == pre-W12 hardcoded mapping. Flow-shape/economic logic untouched. |
| **W14** | ✅ Done | `native-identity` + `native-decimals` added to `network-descriptors.yml`; `NetworkNativeAssets.nativeIdentity/nativeDecimals` accessors; per-class `NATIVE:SOLANA`/`TONCOIN`/decimals copies deleted (byte-identical). EVM native handling unchanged. |
| **W15** | ✅ Done | `JupiterLendLivePositionReader` mints/symbols/decimals now via `NetworkStablecoinContracts.forNetwork(SOLANA)` + `NetworkTokenOverrides` + native accessor; `DEFAULT_JETTON_DECIMALS` fallback retained (documented). |
| **W16** | ✅ Done | Chain vocabulary moved to `platform.networks.solana.SolanaChain`; `SolanaProgramIds` and the duplicate `SolanaBase58` deleted; balance-provider SPL-program copies deduped. **Latent bug fixed:** `NetworkRegistry.normalizeContract` was lowercasing non-EVM (case-sensitive base58) contracts — now case-preserved, so Solana stable/wSOL matching is correct. `ModuleBoundaryTest` green (2875 tests). |
| **W17** | ✅ Done | Dead staking ternary removed (`STAKING_DEPOSIT_TYPES` deleted; output unchanged); classifier shrank 944→458 lines via `SolanaWalletNetFlow`/`SolanaFlowShape` extraction (pure refactor + pinning tests). Latent staking-withdraw fallback noted for a future financial-audit item (report-only). |

**Verification:** full `--skip-frontend` renormalization ran clean end-to-end (0 errors; bybit=4406, solana=654, ton=117, linking=570, pricing=6060, costbasis-replay=8674, snapshot=108). Independent `financial-logic-auditor` regression audit returned **no Critical/High/Medium findings**: per-family AVCO/effective cost clean, conservation clean (uncovered=0/shortfall=0 for ETH/SOL/AVAX/BTC/TON), no phantom income/double-count. The `assetLedgerPoints` delta (12360→12419) is a benign correctness gain from the W16 case-preservation fix.

### Optional waves — decisions

- **W18 (Helius vocabulary externalization) — resolved: keep in code.** These `type`/`source` sets are Helius-API enum vocabulary matched 1:1 by the classifier's parse branches (co-located in `SolanaWalletNetFlow`/`SolanaFlowShape` after W17), not chain/registry data that drifts. Externalizing to `helius-vocabulary.json` would split one logical unit across two files and require editing both to add a type — worse maintainability. Section C.3 explicitly permits "keep". No code change.
- **W19 (metadata resolver facade) — deferred.** Medium risk (equivalence refactor across `JupiterSplTokenMetadataResolver`/`SolanaSplTokenMetadataRegistry`/`SolanaLiveTokenMetadataResolver`/TON resolvers + `TokenMetadataResolutionService`), Low–Medium value, no functional driver, and the resolvers passed the post-renorm audit clean. Deferring avoids needless regression risk (§"No regression without cause"). Revisit when a third non-EVM family is onboarded and the facade earns its keep.

## Related

- [Hardcoded registry consolidation proposal (W1–W11)](hardcoded-registry-consolidation-proposal.md)
- [Architecture](../overview/03-architecture.md) · [Architecture decisions (SAD)](../overview/architecture-decisions.md)
- [Supported networks and protocols](../reference/supported-networks-and-protocols.md)
- [Add a network](../reference/extensibility/add-a-network.md) · [Add a protocol](../reference/extensibility/add-a-protocol.md)
- ADR-063 / ADR-066 / ADR-067 (Solana normalization, counterparty, non-EVM balances); ADR-059 (counterparty-hints plane)
