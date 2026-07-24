# Solana Normalization Rules

Status: Active family rule scaffold (ADR-066, ADR-063)

Covers Solana (Helius Enhanced Transactions) counterparty/protocol resolution and
transfer direction/dust/external-capital rules landed with PR1 of the
Solana/TON classification & counterparty plan.

## Scope

Own program-ID-first classification and counterparty resolution for Solana rows
built from `rawData.heliusParsed`. EVM behaviour is unchanged: resolution is
dispatched per network family through the `CounterpartyResolver` SPI
(`EvmCounterpartyResolver` holds the verbatim EVM logic).

## Ingestion / Backfill Rules (Phase 0)

Correctness of every rule below depends on the raw layer actually containing 2 years
of Solana history. Three ingestion invariants enforce the platform "new wallets
backfill 2 years" rule:

- **2-year backfill window.** `walletradar.ingestion.network.SOLANA.window-blocks =
  165_000_000` with `avg-block-time-seconds = 0.41` (measured ≈ 0.41 s/block). Without
  this override SOLANA inherited the global `backfill.window-blocks = 5_500_000`
  (≈ 26 days). `SourceSyncPlanner.resolveWindowBlocksForNetwork` prefers the
  per-network window.
- **ATA-inbound capture.** Helius `/addresses/{owner}/transactions`
  (owner `getSignaturesForAddress`) does **not** return SPL transfers where the wallet
  is only the recipient (its ATA, not the owner pubkey, is in the account keys).
  `HeliusSolanaNetworkAdapter.fetchTransactions` therefore also resolves the wallet's
  SPL / Token-2022 token accounts (`getTokenAccountsByOwner`), pages each ATA's
  signatures back to the 2-year horizon (`getSignaturesForAddress`), re-enriches the
  new signatures through the same Helius parse endpoint, and **unions the result with
  owner history deduped by signature**. This is what makes inbound bridge / received
  SPL transfers land. If token-account resolution fails, it logs a warning and falls
  back to owner-history-only.
- **No-complete-on-partial-fetch.** A mid-stream `RpcException` (not a natural end =
  empty/short page) is **rethrown** so `BackfillNetworkExecutor` marks the segment
  FAILED and retries it (client-level backoff handles rate limits). Previously a
  partial fetch returned early and the segment was marked COMPLETE, advancing the
  checkpoint past un-fetched history and creating permanent gaps.

## Authoritative Evidence

- `SolanaRawTransactionView` over `rawData.heliusParsed`
  (`instructions[].programId` incl. inner instructions, `nativeTransfers`,
  `tokenTransfers`, `accountData[].tokenBalanceChanges`, `events.swap`).
- `protocol-registry.json` Solana entries (`networks: ["SOLANA"]`), keyed on the
  **case-sensitive base58 program ID** (never `0x`-normalised / lowercased).
- `AccountingUniverseService.classify(peer, SOLANA)` for transfer-peer ownership.

## Program-ID → Counterparty Rule (RC-S2, RC-S3)

- The first `instructions[].programId` (including inner instructions) that
  resolves to a Solana registry entry sets `counterpartyType = PROTOCOL`
  (or `BRIDGE` for bridge-role entries), `protocolName`, and the program ID as
  the transaction/flow counterparty. Resolution state `RESOLVED_EXACT`,
  evidence `SOLANA_PROGRAM_ID_REGISTRY`.
- Non-protocol rows resolve the transfer peer (the non-wallet side of
  `nativeTransfers`/`tokenTransfers`) and classify it via the accounting
  universe → `PERSONAL_WALLET` / `CEX` / `UNKNOWN_EOA`
  (evidence `SOLANA_TRANSFER_PEER`, else `SOLANA_COUNTERPARTY_INFERRED`).
- Every non-fee flow receives a `counterpartyAddress` (protocol program/pool or
  peer). FEE legs get `UNKNOWN:NETWORK_FEE` / `GENUINE_MISSING_SOURCE`.
- Base58 identifiers are preserved verbatim end-to-end (registry key, raw-doc
  lookup in `CounterpartyEnrichmentService.loadRaw`, `accountRef`).

### Program-ID authenticity (RC-S2c)

Every registered Solana program ID must be verified against an authoritative
source (protocol docs, verified explorer/SDK constants, or an on-chain
`getAccountInfo` `executable` check) before it drives classification. A wrong
anchor silently misbooks real economic transactions. Corrections applied:

| Program ID | Was (wrong) | Is (verified) | Effect of the bug |
|---|---|---|---|
| `61DFfeTKM7trxYcPQCM78bJ794ddZprZpAwAnLiwTpYH` | Meteora Vault (`VAULT_DEPOSIT`) | **Jupiter RFQ / Order Engine** — a swap | 36 swaps booked as one-sided vault deposits; the inbound (often native SOL) BUY leg was dropped → phantom disposals in AVCO |
| `L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95` | Meteora Vault | **Lighthouse** — wallet-injected runtime assertion (no economic flow) | never a DeFi counterparty; now ignored like ComputeBudget/Memo so a guarded plain transfer still classifies by net-flow shape |
| `Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EkAW7vP` | Meteora Dynamic AMM | non-existent on-chain → replaced with real DAMM v1 `…EQVn5UaB`; DAMM v2 `cpamdp…` added | DAMM swaps missed program-ID anchoring |

Jupiter RFQ is classified `SWAP` (`protocolName = "Jupiter RFQ"`); its legs are
reconstructed from `accountData[].tokenBalanceChanges` + the wallet native-SOL
delta (RC-S4), so native-SOL delivery is captured as a BUY. The real Meteora
Dynamic Vault program (`24Uqj9JCLxUeoC3hGfh5W3s9FM9uCHDS2SG3LYwBpyTi`) remains
the only `VAULT_DEPOSIT`/`VAULT_WITHDRAW` anchor.

## Swap-Leg Completeness Rule (RC-S4)

For `SWAP` rows the buy/sell legs are derived in this precedence:

1. `events.swap` (`tokenInputs`/`tokenOutputs`/`nativeInput`/`nativeOutput`).
2. If that does not yield **both** a SELL and a BUY leg, reconstruct net
   per-mint wallet deltas from `accountData[].tokenBalanceChanges`
   (`userAccount == wallet`) plus the native SOL delta
   (`nativeBalanceChange` + tx fee added back). Each net-negative mint → SELL,
   each net-positive mint → BUY; multi-hop swaps net per mint so intermediate
   hops cancel to exactly one SELL + one BUY.
3. Final fallback: per-transfer classification from `tokenTransfers`.

## CLMM Swap-vs-LP Disambiguation Rule (RC-S7)

Concentrated-liquidity AMMs (CLMM) — **Raydium CLMM** (`CAMMCzo5…`) and any
structurally-identical concentrated-liquidity AMM — expose a single program for
both swaps and LP add/remove. They must **never** be hard-forced to `SWAP`: an LP
add/remove booked as a swap drops one leg and blocks the AVCO conservation gate
(`STAT_SWAP_MISSING_BUY_LEG` / `STAT_SWAP_MISSING_SELL_LEG`). Decision precedence
in `SolanaTransactionClassifier.resolveClmmType`:

1. **Explicit Helius liquidity discriminators** when reliably present:
   `*_ADD_LIQUIDITY` / `*_INCREASE_LIQUIDITY` / `*_OPEN_POSITION` → `LP_ENTRY`;
   `*_REMOVE_LIQUIDITY` / `*_DECREASE_LIQUIDITY` / `*_CLOSE_POSITION` → `LP_EXIT`;
   `COLLECT_FEE` / `CLAIM_FEE` / `COLLECT_REWARD` → `LP_FEE_CLAIM`.
2. **Flow-shape inference** (`inferClmmTypeByFlowShape`) otherwise, from the
   wallet's net per-mint deltas (authoritative `accountData[].tokenBalanceChanges`
   when present, else `tokenTransfers` + native SOL; dust excluded, native SOL fee
   added back):
   - **1 mint net-out + 1 different mint net-in → `SWAP`** (both legs materialize).
   - **≥1 mint net-out and 0 net-in → `LP_ENTRY`** (add/increase liquidity,
     including single-sided adds near a range edge).
   - **≥1 mint net-in and 0 net-out → `LP_EXIT`** (remove/decrease liquidity,
     including single-sided removes).
   - Any other non-clean shape (e.g. 2 net-out + 1 tiny residual net-in) → the
     **dominant side** decides `LP_ENTRY` / `LP_EXIT`: a CLMM interaction that is
     not a clean 1-out/1-in swap is an LP op, never a broken swap.
   - No observable wallet movement: a present `events.swap` keeps `SWAP`;
     otherwise the fee-only row defaults to `LP_ENTRY` (no swap-leg validation).

`LP_ENTRY` / `LP_EXIT` principal legs are emitted as `TRANSFER` (see RC-S-LP), so
no swap-leg validation applies and basis carries through the position pool.

### Routed-swap guard (RC-S7a) — aggregator CPI into a CLMM pool is a SWAP

A dedicated swap **router/aggregator** program routes a user swap *through* a CLMM
pool via CPI, so the transaction touches the CLMM program (`RAYDIUM_CLMM` /
`METEORA_DLMM`) even though it is a plain swap, not an LP add/remove. Before the
Meteora DLMM (rule 4) and Raydium CLMM (rule 7) rules can fire, `classify()` applies
a **routed-swap guard**: if the tx touches any of `OKX_DEX_ROUTER`
(`routeUGWg…`), `DFLOW`, `JUPITER_SWAP_V6`, `JUPITER_SWAP_V4`, or
`JUPITER_RFQ_ORDER_ENGINE`, it is classified `SWAP` (protocol/slug from the existing
router-naming rules 10/11: RFQ → "Jupiter RFQ", OKX → capitalized source, …). These
programs are **exclusively** swap aggregators — the CLMM they CPI is the swap venue,
not a position. The guard is placed **after** the Jupiter Lend rules (1–3, which must
keep winning for lending loop-open txs that legitimately carry `JUPITER_SWAP_V6`) and
**before** rule 4. Direct Meteora/Raydium LP adds/removes carry **no** router program,
so they still reach rules 4/7 and stay LP by flow shape.

Evidence anchor: `DAfqv9EM…` (296.04 USDC → 295.97 USD1 via `OKX_DEX_ROUTER`, Helius
source RAYDIUM) was mis-booked `LP_ENTRY` (phantom "Raydium CLMM" position with no
exit + a bogus `lp_receipt_basis_pools` row) because rule 7 fired on the CPI'd CLMM
before the router → SWAP rule; the guard now classifies it `SWAP`. The direct-LP
distinction (`raydiumClmmTwoOutboundReclassifiesToLpEntry`, no router present) stays
`LP_ENTRY`.

**Raydium CLMM position identity (NFT-based, verified against the amm-v3 IDL).**
`SolanaLpPositionResolver` derives
`correlationId = lp-position:solana:raydium-clmm:<positionNftAccount>` from the
**position NFT token account** — `accounts[1]` of the largest
`increaseLiquidity*` / `decreaseLiquidity*` leg (layout `[nftOwner==wallet,
nftAccount, …]`), or `accounts[3]` of a combined `openPosition*` leg (layout
`[payer==wallet, positionNftOwner==wallet, positionNftMint, positionNftAccount,
…]`). Both variants resolve to the **same** NFT account, so an entry and its later
exit share one correlation and route through the shared receipt-pool machinery
(`LpReceiptEntryReplayHandler` / `PositionScopedLpExitReplayHandler`) — identical
to Meteora DLMM, with no read-path RPC and deterministic on rerun.
`closePosition`-only management legs are below the liquidity-account threshold and
fall through to `null` (generic family continuity).

## Transfer Direction / Dust / External-Capital Rule (RC-S5)

- **Direction** of a plain `TRANSFER` is the net wallet delta across
  `nativeTransfers` + `tokenTransfers`: net positive → `EXTERNAL_TRANSFER_IN`,
  net negative → `EXTERNAL_TRANSFER_OUT`, every leg wallet↔wallet (self) →
  `INTERNAL_TRANSFER`. Mixed in+out picks the primary asset (largest absolute
  net delta). This replaces the previous hardcoded `TRANSFER → IN`.
- **Dust thresholds (per asset class):** native SOL inbound legs below
  `0.000001 SOL` (1,000 lamports) are dropped as dust/scam; SPL threshold is `0`
  (only strictly-zero amounts filtered, since SPL dust cannot be judged without
  decimals/USD value at normalization time). **The FEE leg is never dropped.**
- **Inbound taxonomy:** own/CEX peers → carry/continuity (no INFLOW stamp;
  own-wallet inflows promote to `INTERNAL_TRANSFER`). Unknown-external peers →
  external-capital `INFLOW` so replay books an `ACQUIRE` at market.
- **On-chain INFLOW stamping:** `OnChainBoundaryContractStamper`
  (a `NormalizedTransactionPostProcessor`) stamps
  `externalCapitalBoundary = INFLOW` only on a Solana `EXTERNAL_TRANSFER_IN`
  with `counterpartyType == UNKNOWN_EOA` and an inbound non-fee economic leg.
  Outbound/internal transfers are never stamped INFLOW; EVM rows are untouched.
  `ReplayDispatcher.resolveExternalCapitalInflowAcquisition` then relabels the
  priced inflow `UNKNOWN → ACQUIRE` (label only). Aligns with the
  external-capital-inflow ACQUIRE label plan.

## Unclassified Residuals Rule (RC-S6)

- Compressed-NFT mint (Metaplex Bubblegum program / `COMPRESSED_NFT_MINT`) →
  `NFT_MINT` (non-economic, fee-only).
- SPL/system housekeeping Helius types (`CLOSE_ACCOUNT`, `INITIALIZE_ACCOUNT`,
  `CREATE_ACCOUNT`, `SYNC_NATIVE`, …) with no DeFi program → `ADMIN_CONFIG`
  (non-economic, fee-only, replay-safe).
- Meteora farming program → `REWARD_CLAIM` / `LP_POSITION_STAKE` /
  `LP_POSITION_UNSTAKE` by Helius type.
- Genuinely unknown programs with a non-transfer/non-swap Helius type stay
  `UNKNOWN` + `SOLANA_UNCLASSIFIED` for review rather than being force-mapped.

## Non-DeFi Transfer Fallback Rule (RC-S8)

A plain SPL/native transfer often arrives with a non-`TRANSFER` Helius type
(`UNKNOWN`) — common for pump.fun memecoins and unpriced SPL tokens. Without a
fallback the row would stay `UNKNOWN`/`NEEDS_REVIEW` and get pulled into EVM
full-receipt clarification (see FIX A / clarification EVM-family guard),
terminalizing to `CLASSIFICATION_FAILED`.

`SolanaTransactionClassifier.nonDefiTransferFallback` runs **after** every
program-ID and Helius-type rule (last rule before `UNKNOWN`): when a transaction
touches **only** non-DeFi system programs — `SYSTEM_PROGRAM`, `TOKEN_PROGRAM`,
`TOKEN_2022_PROGRAM`, `ASSOCIATED_TOKEN_PROGRAM`, `COMPUTE_BUDGET_PROGRAM`,
`MEMO_PROGRAM(_LEGACY)` — **and** the wallet has a real net token/SOL movement, it
is classified by net-flow shape via the existing `resolveTransferType`
(`EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN` / `INTERNAL_TRANSFER`). If any
program outside the non-DeFi allowlist is present, or there is no wallet-net
movement, the guard does **not** fire and the row stays `UNKNOWN` for review — so
an unrecognised protocol is never silently mislabelled as a transfer. A
wallet-net-negative single-mint SPL transfer with an unknown/unpriced mint thus
books `EXTERNAL_TRANSFER_OUT` with a well-formed flow (quantity from the raw
amount, `assetContract = mint`) and lands in `PENDING_PRICE`, never
`CLASSIFICATION_FAILED`.

## SPL Mint Symbol / Decimals Resolution Rule (RC-S9)

Helius frequently returns a **null `symbol`** for major SPL tokens (USDC/USDT) and
for memecoins. `SolanaSplTokenMetadataRegistry` resolves symbol + decimals when the
payload omits them. It is now backed by the **descriptor `token-overrides`** map in
`network-descriptors.yml` (`SOLANA` section, keyed by the **case-sensitive** base58
mint) — the retired `token-metadata.json` was deleted (ADR-073). Descriptor seed at
minimum: `USDC` / `USDT` (decimals 6), wrapped SOL `So1111…1112` → `SOL` (decimals 9),
and `soUSDC` `decimal-override 12` (on-chain misreport). Unseeded mints resolve
**live** at the enrichment seam via the unified resolution order
(**descriptor override → persistent `token_metadata_cache` → Jupiter live resolver
(write-through) → explicit `unresolved`**, ADR-073), so replay stays RPC-free.

- **Symbol resolution precedence** (builder `resolveSymbol`): Helius payload
  `symbol`/`tokenSymbol` → `SolanaSplTokenMetadataRegistry.symbol(mint)` → wSOL→SOL
  alias → `null`. A resolved `USDC`/`USDT` symbol lets `AssetFamily.resolve` map the
  flow to the **`STABLE_USD`** family (symbol-driven). An unknown/unseeded mint
  keeps a `null` symbol — the flow is still well-formed and prices by contract.
- **Decimals fallback** (`rawTokenAmountToDecimal`): when a Helius
  `accountData[].tokenBalanceChanges.rawTokenAmount` omits `decimals`, the seeded
  registry decimals are applied so a known-mint raw amount is not booked at
  `10^decimals ×` the true quantity.
- **$1 parity** is enforced independently by `network-descriptors.yml`
  `SOLANA.usd-stable-contracts` (USDC/USDT mints) via the pricing/stablecoin path;
  RC-S9 only fixes the **symbol-driven family** classification.

## LP / Lending / Vault Move-Basis Continuity Rule (RC-S-LP)

Deposits into and withdrawals out of Solana LP / lending / vault positions are
**basis-carrying continuity moves**, never market `BUY`/`SELL`. Booking a
deposited underlying as a disposal (or a returned underlying as a fresh market
acquisition) breaks AVCO.

- **LP_ENTRY / LP_EXIT flow roles.** Every wallet principal leg of an `LP_ENTRY`
  / `LP_EXIT` is emitted with role `TRANSFER` (outbound negated on entry, inbound
  positive on exit) — never `BUY`/`SELL`. Same-transaction dust refunds net out
  safely in the receipt-pool path. `LP_FEE_CLAIM` keeps `LP_FEE_INCOME`
  (zero-cost harvest, not principal).
- **Meteora DLMM position identity (NFT positions, no fungible LP token).**
  `SolanaLpPositionResolver` derives a deterministic
  `correlationId = lp-position:solana:meteora-dlmm:<positionPda>` from the Helius
  payload **for direct wallet-level interactions only** (Hawksight-managed rows
  are excluded first — see UNSUPPORTED_SCOPE below): the position PDA is
  `accounts[0]` of the largest Meteora DLMM (`LBUZ…`) `addLiquidity*` /
  `removeLiquidity*` instruction (≥ 10 accounts; the smaller `claimFee` /
  `closePosition` legs are skipped). The same PDA appears on the entry and the
  exit, so the position never splits. The
  correlation routes the row through the shared EVM receipt-pool machinery
  (`LpReceiptEntryReplayHandler` deposits principal basis into
  `lp_receipt_basis_pools`; `PositionScopedLpExitReplayHandler` restores it),
  giving true per-position entry↔exit continuity with **no read-path RPC** and
  fully deterministic on rerun.
- **Lending / vault deposits & withdrawals.** These already emit a single
  `TRANSFER` principal leg (outbound on deposit, inbound on withdraw). The shared
  network-agnostic replay classifier (`ReplayTransferClassifier.isBucketOutbound`
  / `isBucketInbound` for `LENDING_DEPOSIT` / `LENDING_WITHDRAW` /
  `VAULT_DEPOSIT` / `VAULT_WITHDRAW`, plus `isFamilyEquivalentCustodyTransfer`)
  parks the deposited basis into a continuity bucket (`REALLOCATE_OUT`) and
  restores it on withdraw (`REALLOCATE_IN`) — identical to EVM lending/vault
  continuity. No Solana-specific replay handler is added.

### Full-close residual write-off (RC-S-LP-CLOSE / ADR-075)

A concentrated-liquidity (Meteora DLMM / Raydium CLMM) remove that **fully closes the
position** is promoted from `LP_EXIT` to **`LP_EXIT_FINAL`**, and the cost-basis replay
then **drains every residual per-asset basis pool for that position to zero**, writing
the leftover off as realized LP PnL. This fixes **phantom "open" LP positions**: a CLMM
position returns a *different asset ratio* than deposited (impermanent loss /
rebalancing), so on full closure one asset can drain fully while a sibling per-asset
pool keeps a residual `qtyHeld > 0` that no returned flow ever withdraws — inflating the
portfolio and leaving the sibling asset (e.g. SOL) reported "uncovered" in move-basis.

- **Full-close detection** (`SolanaLpPositionResolver.isFullPositionClose`, the
  network-neutral analogue of the EVM position-NFT burn): the **resolved position
  account is deallocated in the same transaction** — it appears in `accountData` with a
  strictly-negative `nativeBalanceChange` (rent lamports reclaimed). A Meteora DLMM
  position PDA / Raydium CLMM position NFT account is program-owned and never pays fees,
  so a negative lamport delta on that exact account can only be a close. A **partial**
  remove leaves the position account open (`nativeBalanceChange == 0`) and stays
  `LP_EXIT`, so residual basis legitimately persists until the terminal close.
- **Promotion** (`SolanaNormalizedTransactionBuilder`): a resolved `LP_EXIT` with
  `isFullPositionClose` becomes `LP_EXIT_FINAL` (position legs still built as `TRANSFER`;
  `lpConcentrated` capability flag preserved — it keys on the correlation-id prefix).
- **Drain** (`PositionScopedLpExitReplayHandler`): `LP_EXIT_FINAL` triggers
  `drainAllLpReceiptPoolsForCorrelation`, zeroing `qtyHeld` / `basisHeldUsd` /
  `netBasisHeldUsd` / `uncoveredQtyHeld` on every pool sharing the correlation id and
  zeroing the synthetic receipt position. Assets that *did* return are already credited
  at restored/spot basis by the settlement pass; the drained residual is the realized
  LP loss/gain.
- **No dataset-specific keys.** Keyed purely on the position account's on-chain
  rent-reclaim — never a wallet, tx hash, or curated bucket. Rerun-safe (Helius payload
  only, no read-path RPC).
- **Bounded limitation.** If a position's terminal close is genuinely absent from
  `raw_transactions` (ingestion gap → no rent-reclaim evidence), its residual is not
  drained — an ingestion-completeness concern, not a replay defect.

### UNSUPPORTED_SCOPE / bounded limitations

- **Hawksight-managed positions** (Hawksight program present, `source =
  HAWKSIGHT` / `EXTENSION_EXECUTE`). The LP principal is custodied and rebalanced
  inside a Hawksight vault PDA that **owns** the concentrated-liquidity position.
  The Meteora DLMM `addLiquidity` / `removeLiquidity` runs as an **inner CPI under
  the Hawksight program**, not as a top-level wallet instruction — so it *is*
  visible in `SolanaRawTransactionView.flattenedInstructions()` (which walks inner
  instructions) with the position PDA at `accounts[0]`. `SolanaLpPositionResolver`
  must therefore **detect the Hawksight wrapper and return `null`**
  (`isHawksightManaged`: the registered Hawksight program id or the Helius
  `HAWKSIGHT` source — never a wallet/vault address) rather than fabricate a
  wallet-scoped position identity. Fabricating one produced **phantom "open" DLMM
  positions**: the wallet's deposit is routed wallet→vault (entry funds a
  per-position basis pool), but the Hawksight rebalance/close credits the wallet
  only position **rent** (near-zero net flow), which flow-shape inference books as
  a fee-only `LP_ENTRY` instead of an `LP_EXIT` — leaving the position-scoped basis
  pool permanently non-empty (ghost pool + inflated balances). With the resolver
  returning `null`, both the Hawksight-routed entry and the Hawksight close ride
  the **generic family-continuity bucket** symmetrically (no phantom
  disposal/acquisition). Wallet↔Hawksight-vault deposits/returns still book
  `TRANSFER` legs; per-position isolation for Hawksight is not reconstructable from
  wallet-level Helius evidence and is a documented, bounded limitation (coarse
  family continuity, never a wrong per-position basis).
- **Raydium CLMM.** Now classified by CLMM swap-vs-LP disambiguation (RC-S7) and
  resolved to an NFT-account position identity for entry↔exit continuity. Combined
  single-instruction open-position entries are handled via the `accounts[3]` NFT
  account; `closePosition`-only legs and non-self-payer opens fall through to
  generic family continuity (bounded, never a wrong per-position basis).
- **Meteora Dynamic AMM (DAMM / MLP fungible receipt) — ADR-081.** Constant-product AMM
  (not CLMM), issues a **fungible `MLP`** receipt (distinct from a DLMM position PDA/NFT).
  Detection grammar: DAMM pool program + MLP mint authority; two underlying legs out
  (SOL + mSOL/bSOL/…), one fungible MLP mint in.
  - **Correlation:** mint `lp-position:solana:meteora-damm:{poolAddress}:{walletLower}` on the
    DAMM `LP_ENTRY` (per pool + per wallet) so the position surfaces and gets a basis pool;
    the terminal `remove_liquidity` links as `LP_EXIT_FINAL` (MLP net → 0).
  - **Typing:** MLP receipt movements (mint, farm `LP_POSITION_STAKE`/`UNSTAKE`, burn) are
    **non-priced `TRANSFER`** — never role `SELL`/`BUY`; MLP stays **unpriced**. Basis lives on
    the underlying SOL/mSOL/bSOL legs / the DAMM basis pool.
  - **Accounting delta (pinned):** removes the mis-tagged MLP STAKE loss ⇒ realized P&L
    ≈ **+$12.46**; ≈ $133 basis re-routes to the underlying legs; net MLP = 0.
  - **Negative cases (do NOT match):** DLMM (concentrated, PDA — Family C); plain SPL transfers
    of unrelated tokens; MLP price feeds (MLP must remain unpriced). Distinguish DAMM (fungible
    MLP) from DLMM (position PDA/NFT, `lpConcentrated=true`).

## Jupiter Lend Borrow / Loop Classification & Accounting Rule (RULE 1 / ADR-069)

Jupiter Lend supply/borrow/withdraw/repay span **three cooperating programs** — the borrow
router (`jupr81…`), the earn sub-program (`jupei…`) and the shared liquidity layer
(`jupnw4…`). These program IDs are declared under `protocol: JupiterLend` in
**`protocol-registry.json` (the single source of truth, WS-1 DoD)** and derived at load into
`SolanaProgramIds.JUPITER_LEND_PROGRAM_IDS` via `SolanaProtocolPrograms` (a fail-fast guard
asserts the `jupr81…` anchor stays present). The classifier recognises a Jupiter Lend
interaction when **any** of these programs is touched, so an earn-only supply (no borrow
router) still classifies as lending rather than falling through to a generic transfer.

Helius returns a **generic/empty `type`** for these rows, so the event must be decided by the
wallet's **net asset flow**, never by the Helius `type` string. Assets in scope: collateral
**SOL** (`So11…112`), borrowable **USD-stable** mints (`SOLANA.usd-stable-contracts` =
USDC/USDT), and the protocol **position-receipt** token (e.g. jl-SOL `4Xuocg…`) which is a
non-inventory marker.

- **Net-flow classification** (`SolanaTransactionClassifier.resolveJupiterLendType`), using
  `walletNetByMint` (authoritative `accountData[].tokenBalanceChanges` else
  `tokenTransfers` + native SOL, fee added back). Only the SOL collateral net and the
  USD-stable net drive the decision; the receipt token and any other mint are ignored:
  1. Jupiter **Swap** (`JUP6Lkb…`/v4 or `source=JUPITER`) invoked in the same tx **and** a
     net collateral change → borrow→swap→re-supply within one tx → `LENDING_LOOP_OPEN`.
  2. Net-positive stablecoin (no matching outbound of it) → `BORROW` (amount = net delta).
  3. Net-negative stablecoin → `REPAY`.
  4. Net-positive collateral (SOL) → `LENDING_WITHDRAW`.
  5. Net-negative collateral (SOL) → `LENDING_DEPOSIT`.
  6. Receipt-dust-only (no SOL/stable movement) → `LENDING_DEPOSIT` (fee-only after leg building).
  - **Negative case:** a pure Jupiter *Swap* with **no** `jupr81…` is never lending — it
    stays `SWAP` (Jupiter Lend is matched before the Jupiter Swap rule).

- **Net-flow leg building** (`SolanaNormalizedTransactionBuilder.buildLendingFlows`). Flows
  are built from the wallet's **net per-mint delta restricted to inventory assets** (SOL +
  USD-stable mints only; the receipt token and other mints are excluded so they never enter
  inventory). Netting is what removes the phantom `SELL` legs of a loop's borrowed principal
  (borrowed then swapped away nets ~0). Roles:
  - `LENDING_DEPOSIT` / `LENDING_WITHDRAW` / `LENDING_LOOP_OPEN`: `TRANSFER` (basis-carrying
    move into/out of the position; collateral stays owned), sign preserved. A loop emits
    **only** the net collateral leg — no stablecoin leg.
    - **Loop collateral from `accountData` (WS-1 B1 fix).** A leverage loop deposits collateral
      as **native SOL** (wrapped → swapped → supplied) while only a *dust* wSOL token residual
      stays in the wallet's ATA. `lendingValueNetByMint` now **sums both** the wallet's native
      SOL delta (fee re-added) **and** any wSOL `tokenBalanceChange`; the previous guard dropped
      the native leg whenever a dust wSOL token-change existed, emitting an empty/dust
      `LENDING_LOOP_OPEN` leg (anchor `5YMocs…`: native −0.100 SOL + wSOL residual +0.00016 →
      net collateral ≈ −0.0998 SOL). This is a **per-tx net loop cycle**, so summing across loop
      iterations reaches the summed-draws collateral without double-booking redeposits.
  - `BORROW`: the net-positive stablecoin is a `BUY` (the previously-dropped inbound borrow
    leg is now emitted); a same-tx net-negative collateral leg is emitted as `TRANSFER` so
    the dispatcher parks it in the borrow-collateral continuity bucket.
  - `REPAY`: the net-negative stablecoin is a `SELL` routed to the repay handler.

- **Accounting (reuses the EVM borrow/repay machinery — no Solana-specific handler).**
  - `BORROW` routes to `BorrowReplayHandler`: the borrowed principal is acquired at
    market-at-borrow basis in both lanes (ADR-040 §5) with a parallel `borrow_liabilities`
    row; the tracked liability — not a $0 asset basis — offsets net worth.
  - `REPAY` routes to `RepayReplayHandler`: reduces/closes the liability, zero realised PnL
    on the liability-matched principal, FX residual realised per policy.
  - `LENDING_DEPOSIT` / `LENDING_WITHDRAW` / `LENDING_LOOP_OPEN` carry basis via the shared
    continuity-transfer path (`REALLOCATE_OUT` / `REALLOCATE_IN`), identical to EVM lending.
  - **SOLANA `borrow_liabilities` id scheme.** The builder stamps a deterministic loan
    `correlationId = solana:jupiter-lend:<debtMint>:<wallet>` on `BORROW`/`REPAY`. The
    replay handlers use it as the `orderId`, so `compositeId = <universeId>:<orderId>`.
    `BorrowLiability` has no network column — network is encoded in the order id (and
    `accountRef` = the Solana wallet). Repeated borrows aggregate into one liability; a
    later repay nets against it.
  - **Off-program borrow disbursements (deferred to WS-4).** Some Jupiter Lend borrow draws
    are disbursed to the wallet as **plain SPL `TRANSFER`s** (Helius `type=TRANSFER`,
    `source=SOLANA_PROGRAM_LIBRARY`) from **rotating ephemeral** source accounts, carrying **no
    Jupiter Lend program ID** on-chain (anchors `48NMko…` +33.5 USDT, `5rnyyv…` +20 USDT).
    Reclassifying these as `BORROW` would require keying on the ephemeral disbursement address —
    per-tx / wallet hardcoding, which is disallowed. They remain `EXTERNAL_TRANSFER_IN` at the
    classification layer; the **WS-3/WS-4 live reader is the single authority** that trues the
    outstanding debt up to the summed draws (+ accrued interest). Program-present borrows (with a
    `jupr81…`/`jupei…`/`jupnw4…` instruction and a net-positive stablecoin) do classify as
    `BORROW` here.

## Clarification EVM-Family Guard (FIX A)

Full-receipt clarification decodes **EVM** receipts (execution status, effective
gas price, ERC-20/native transfer logs) — Solana (Helius) and TON (toncenter) rows
have no such receipt. Selection and eligibility are therefore restricted to the
EVM family via the config-driven predicate (`NetworkRegistry.evmWalletSupportedNetworks()`
in the selection queries, `NetworkAddressFormat.isEvm(networkId)` in the
eligibility gate — both derived from `address-format: EVM` in
`network-descriptors.yml`, not a hardcoded enum list):

- `PendingReceiptClarificationQueryService` adds an `evmFamilyCriteria()`
  (`networkId ∈ evm-family`) to **every** clarification selection query, so a
  SOLANA/TON `NEEDS_REVIEW`/`PENDING_CLARIFICATION` row is never selected.
- `ClarificationPolicyService.isReceiptClarificationEligible` returns `false` for
  any non-EVM `networkId` (defense in depth on the workflow path via
  `ReceiptClarificationWorkflowHandler` → `ClarificationPreparationHandler`).

This prevents the Solana/TON stall observed in the audit
(`CLARIFICATION_FULL_RECEIPT_UNAVAILABLE` → `CLASSIFICATION_FAILED`, resume-scheduler
`clarification→reclassification→linking` loop with `processed=0`).

## Disallowed Fallbacks

- Do not route Solana rows through the EVM `OnChainRawTransactionView` or the EVM
  receipt-shaped steps (`registryBridgeInboundTypeCorrectionService`,
  `enrichProtocolFromReceiptIdentity`, `lendingReceiptIdentityService`).
- Do not select or mark Solana/TON rows eligible for **full-receipt clarification**
  (EVM-only; see the Clarification EVM-Family Guard above).
- Do not lowercase / `0x`-normalise base58 program IDs, signatures, or wallets.
- Do not stamp `INFLOW` on outbound/internal transfers or on own/CEX inflows.

## Regression Anchors

- Program-ID → PROTOCOL and transfer-peer classification: `SolanaCounterpartyResolverTest`.
- Direction / dust / farm / NFT / housekeeping: `SolanaTransactionClassifierTest`.
- Non-DeFi transfer fallback (RC-S8, unknown-mint outbound/inbound, guard holds):
  `SolanaTransactionClassifierTest`, `SolanaNormalizedTransactionBuilderTest`.
- SPL mint symbol/decimals + `STABLE_USD` family (RC-S9): `SolanaSplTokenMetadataRegistryTest`,
  `SolanaNormalizedTransactionBuilderTest` (USDC/USDT cases).
- Clarification EVM-family guard (FIX A): `PendingReceiptClarificationQueryServiceTest`,
  `ClarificationPolicyServiceTest`.
- Swap-leg reconstruction from `accountData`: `SolanaNormalizedTransactionBuilderTest`.
- INFLOW stamping guarantees: `OnChainBoundaryContractStamperTest`.
- Base58 case preserved through `loadRaw`: `CounterpartyEnrichmentServiceLoadRawTest`.
- DLMM position PDA extraction + **Hawksight-wrapped/-routed DLMM resolves to
  `null`** (inner-CPI and top-level Hawksight-program shapes):
  `SolanaLpPositionResolverTest`.
- Hawksight close (inner DLMM `removeLiquidity`, wallet-rent-only) and
  Hawksight-routed DLMM entry do **not** fabricate a position / `lpConcentrated`
  (phantom-open regression): `SolanaNormalizedTransactionBuilderTest`.
- LP/lending/vault flow roles + `lp-position` correlation: `SolanaNormalizedTransactionBuilderTest` (RC-S-LP cases).
- Entry↔exit basis carry through the receipt pool: `SolanaLpContinuityReplayTest`.
- Full-close residual write-off (RC-S-LP-CLOSE / ADR-075): position-account
  rent-reclaim → `isFullPositionClose` (true on full close, false on partial /
  unresolved) in `SolanaLpPositionResolverTest`; `LP_EXIT_FINAL` drains residual pool /
  partial preserves it / DLMM full close writes off unreturned principal in
  `SolanaLpContinuityReplayTest`.
- Jupiter Lend borrow/loop net-flow classification (BORROW / DEPOSIT / WITHDRAW / LOOP /
  pure-swap negative): `SolanaTransactionClassifierTest` (RULE 1 cases).
- Jupiter Lend inbound borrow BUY leg, loop SOL-only carry (no phantom USDT), withdraw
  inbound TRANSFER: `SolanaNormalizedTransactionBuilderTest` (RULE 1 cases).
- Jupiter Lend loop collateral from `accountData` (native SOL + dust wSOL summed, no empty
  leg) and earn-only program still classifies as lending:
  `SolanaNormalizedTransactionBuilderTest` (WS-1 cases).
- SOLANA `borrow_liabilities` create-on-BORROW / close-on-REPAY + order-id scheme:
  `JupiterLendBorrowReplayTest`.
