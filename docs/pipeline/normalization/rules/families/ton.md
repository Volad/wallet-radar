# TON Normalization Rules

Status: Active family rule scaffold (ADR-066, ADR-064)

Covers TON (TON Center v3) address canonicalization, native TON + jetton transfer
direction, jetton decimals/symbol resolution, and counterparty resolution landed
with PR3 (RC-T1/RC-T2) of the Solana/TON classification & counterparty plan.

## Scope

Native TON value transfers and jetton (Jetton standard) transfers for the tracked
TON wallets built from `rawData.transaction` + `rawData.jettonTransfers`
(TON Center v3). **Deep TON DeFi protocol accounting is explicitly out of scope**
(bounded per the plan): a TON row that carries jetton/DeFi economic value the
current evidence cannot book is surfaced as `UNKNOWN` / `NEEDS_REVIEW` with the
`TON_ONCHAIN_UNRESOLVED_VALUE` marker (never silently confirmed empty). EVM and
Solana behaviour is unchanged: counterparty resolution is dispatched per network
family through the `CounterpartyResolver` SPI (`TonCounterpartyResolver`).

## Ingestion / Backfill Rules (Phase 0)

Correctness of every rule below depends on the raw layer actually containing 2 years
of TON history. Two ingestion invariants enforce the platform "new wallets backfill
2 years" rule:

- **2-year backfill window.** `walletradar.ingestion.network.TON.window-blocks =
  165_000_000` with `avg-block-time-seconds = 0.41` (measured ≈ 0.41 s/block). Without
  this override TON inherited the global `backfill.window-blocks = 5_500_000`
  (≈ 26 days). `SourceSyncPlanner.resolveWindowBlocksForNetwork` prefers the
  per-network window.
- **No-complete-on-partial-fetch.** In `TonNetworkAdapter.fetchTransactions` a
  mid-stream `RpcException` (not a natural end = empty/short page) is **rethrown** so
  `BackfillNetworkExecutor` marks the segment FAILED and retries it (client-level
  backoff handles rate limits). Previously a partial fetch returned early and the
  segment was marked COMPLETE, advancing the checkpoint past un-fetched history and
  creating permanent gaps. Jetton-transfer fetch remains independently resilient (it
  never aborts a page and only logs on persistent failure).

## Authoritative Evidence

- `TonRawTransactionView` over `rawData.transaction`
  (`in_msg` source/destination/value/`decoded_opcode`, `out_msgs[]`,
  `total_fees`, `description.compute_ph.exit_code` for failure) and
  `rawData.jettonTransfers` (owner-addressed `source`/`destination`/`amount`/
  optional `jetton_content`/`jetton_master`).

## Jetton Transfer Correlation Rule (trace_id)

- `TonNetworkAdapter` fetches owner-account transactions from
  `/api/v3/transactions?account=<friendly-addr>` and jetton transfers separately from
  `/api/v3/jetton/transfers?owner_address=<friendly-addr>`, then attaches jetton
  transfers to each transaction.
- **Correlate by `trace_id`, not `transaction_hash`.** In TON a jetton transfer
  spans a chain of transactions (sender jetton-wallet tx, receiver jetton-wallet
  tx, owner notification tx); the `transaction_hash` on a jetton transfer names a
  *different* transaction in the trace than the owner-account transaction row, so
  hash-matching drops 100% of jetton evidence. Both the owner transaction
  (`transaction.trace_id`) and the jetton transfer (`jetton_transfers[].trace_id`)
  carry the **same** `trace_id` — the correct correlation key. `transaction_hash`
  is retained only as a secondary fallback.
- **Owner address must be passed in friendly form** (`UQ…`/`EQ…`); the raw
  `0:hex` form causes `/jetton/transfers` to time out. The adapter already stores
  the friendly wallet, so `walletAddress` is used as-is.
- **Both directions** are captured: no direction filter is applied, so incoming
  (received) and outgoing (sent) jetton transfers are both returned.
- **Resilient fetch:** `/jetton/transfers` is flaky on the free tier (identical
  requests return `count=3`, then `count=0`, then a `timeout` on consecutive
  calls). The fetch retries on timeout / 5xx / empty
  (`walletradar.ingestion.ton.jetton-fetch-max-attempts`, backoff
  `jetton-fetch-backoff-millis`). A persistent failure is logged at WARN with the
  address + offset (never silently swallowed) and never aborts the page — the
  native transaction is still ingested with an empty jetton list.
- `TonAddressCanonicalizer` (domain) for canonical address equality.
- `TonJettonMetadataRegistry` (now backed by the descriptor `token-overrides` map in
  `network-descriptors.yml` `TON` section; the retired `token-metadata.json` was
  deleted, ADR-073) for jetton decimals/symbol when `jetton_content` is absent.
- `AccountingUniverseService.classify(peer, TON)` for transfer-peer ownership.

## Canonical Address Equality Rule (RC-T1)

- The stored wallet is user-friendly (`UQ…`/`EQ…`) while TON Center emits the raw
  `workchain:hex` (`0:hex`) form in `in_msg`/`out_msgs`/jetton `source`/
  `destination`. **Never compare with `equalsIgnoreCase`.** Two TON addresses are
  equal iff their `TonAddressCanonicalizer.lookupKeys(...)` key sets (friendly +
  raw, raw lowercased) intersect. `TonNormalizedTransactionBuilder.sameTonAddress`
  applies this in `classify` and every native/jetton flow builder;
  `isOwnAddress` uses `AccountingUniverseService` membership (canonicalizer-aware).
- Before RC-T1 the friendly↔raw mismatch collapsed every native TON + jetton
  transfer to `UNKNOWN` with a fee-only flow (silent value loss).

## Native TON vs Jetton/DeFi Machinery Rule (RC-T1)

- Native TON flows are built **only** from plain value-transfer messages
  (opcode absent, `text_comment`, or `excess` — the jetton-wallet gas refund).
  Jetton/DeFi machinery opcodes (`jetton_transfer`, `jetton_notify`,
  `jetton_burn`, `internal_transfer`, `dedust_swap`, `dedust_payout`,
  `pton_ton_transfer`, …) merely forward gas and are **never** booked as native
  TON value.
- Jetton economic flows come from `rawData.jettonTransfers` (owner-addressed).
- Direction: `destination == wallet` → inbound (`EXTERNAL_TRANSFER_IN`);
  `source == wallet` → outbound (`EXTERNAL_TRANSFER_OUT`); own↔own → `INTERNAL_TRANSFER`.

## Jetton Fan-out Deduplication Rule (RULE 2)

TON's async message model surfaces **one logical jetton transfer as several
transactions in a single trace** (owner `jetton_transfer` out → jetton-wallet
`internal_transfer` → destination jetton-wallet → `excess` gas refund →
`jetton_notify`). The adapter correlates jetton transfers by `trace_id`, so the
same owner-addressed `jettonTransfers` entry is **replicated onto every one of the
wallet's raw rows in that trace**. Booking each row inflates the ledger N× (phantom
residuals — e.g. +53 USDT held while on-chain balance is 0; an own↔own send counted
twice on the sender).

- **Stable identity key.** A jetton transfer is identified by its TON Center
  `transaction_hash` (the trace/transfer hash shared by every replicated copy) or,
  when absent, the composite `{jettonMaster, query_id, amount, source, destination}`.
- **Within-row dedup.** `TonNormalizedTransactionBuilder.dedupeWithinRow` collapses
  replicated entries inside a single row's `jettonTransfers` list before flow
  emission.
- **Cross-row dedup (canonical owner).** The same logical transfer is booked on
  **exactly one** of the wallet's rows: the canonical sibling = the row with the
  **lowest `txHash`** among the wallet's rows carrying that `transaction_hash`. This
  is decided by `TonNormalizedTransactionBuilder.JettonFanoutClaim`, which
  `TonNormalizationService` backs with
  `RawTransactionRepository.countTonJettonFanoutSiblingsBefore` (a row is canonical
  iff no wallet sibling with the same jetton `transaction_hash` sorts before it).
  The rule is deterministic and idempotent (order-independent min), so it is stable
  across renormalization.
- **Non-canonical siblings** are set `UNKNOWN` / `NEEDS_REVIEW`, excluded from
  accounting with `accountingExclusionReason = TON_JETTON_FANOUT_DUPLICATE`
  (`JETTON_FANOUT_DUPLICATE_REASON`), and carry no value flow (fee leg preserved for
  observability). This is **not dropped value** (the transfer is booked on the
  canonical sibling), so RC-T2 is preserved — the exclusion marker distinguishes a
  dedup artifact from `TON_ONCHAIN_UNRESOLVED_VALUE`.
- **Genuinely distinct transfers are never merged.** Two transfers of the same
  amount at different `transaction_hash`/`lt`/timestamps have different identity keys
  and are booked independently.

## Own-Wallet Symmetric Carry Rule (RULE 3)

- **Canonicalization before ownership.** Every TON address is compared on its
  `TonAddressCanonicalizer.lookupKeys(...)` set (raw `0:hex` + friendly `UQ…`/`EQ…`),
  so a wallet stored friendly matches the raw `0:hex` forms TON Center emits, and a
  universe membership lookup (`AccountingUniverseService`) is canonicalizer-aware.
- **Owner-addressed jetton transfers.** TON Center `/jetton/transfers` returns the
  owner `source`/`destination` (the jetton-wallet contract is the separate
  `source_wallet` field), so ownership resolves directly against owner addresses; no
  live jetton-wallet → owner (`get_wallet_data`) lookup is required for jetton flows.
- **Symmetric move-basis.** When both `source` and `destination` (after
  canonicalization) resolve to universe-owned wallets, the transfer is
  `INTERNAL_TRANSFER` on **both** sides (`continuityCandidate = true`). Because
  direction is derived from the transfer's owner `source`/`destination` versus the
  wallet, and fan-out dedup guarantees each side books the value **once**, the
  sender books a single negative (CARRY_OUT) leg and the receiver a single positive
  (CARRY_IN) leg of equal magnitude. The existing EVM MOVE_BASIS/CARRY replay
  handlers then pair them (same basis, no realization) — TON move-basis is symmetric
  and non-empty. Before dedup the sender booked the value on both its
  `jetton_transfer` and `excess` rows, so the CARRY_OUT total was 2× the receiver's
  CARRY_IN and the pair no longer matched.

## Ston.fi / Dedust Swap & Proxy-TON Netting Rule (WS-2 / B2)

DeFi protocol detection is **registry/descriptor-driven** via
`ton-protocol-registry.json` (a TON mirror of `protocol-registry.json`), loaded once by
`TonProtocolRegistry`. Two concerns:

- **Proxy-TON (pTON) netting.** Ston.fi wraps native TON as a proxy jetton (pTON) for
  routing. The registered pTON masters (`proxyTonMasters`: pTON v2 `0:671963…`, pTON v1
  `0:8cdc…`) are **netted to native TON**: any pTON leg is emitted as a `TONCOIN` / `TON`
  flow (9 decimals), **never** a held pTON jetton. This kills the phantom ~45 pTON
  inventory and its bogus ~$6.1 avco (B2). Netting happens both on the swap path (before
  legs are paired) and in `buildJettonFlow` (a bare pTON receive books as native TON).
- **Ston.fi / Dedust swap.** A swap is detected from the wallet's deduped jetton legs when a
  DEX marker is present — a `stonfi_swap*` / `dedust*` `decoded_forward_payload.@type`, or a
  proxy-TON leg (pTON exists only for Ston.fi routing) — **and** the net shape (after pTON→TON
  folding) is exactly **one net-out asset + one net-in asset**. It is typed `SWAP` with a
  `SELL` (out) + `BUY` (in) leg, so the acquired asset gets a **swap-derived basis** instead of
  being mistaken for external capital-in (keeps native TON AVCO healthy). pTON is netted to 0
  **before** it can become a swap sibling leg. Anchor: XAUT-out / pTON-in Ston.fi v2 swap
  (`stonfi_swap_v2_forward_payload`) → `SELL XAUT` + `BUY 8.87 TON`, no pTON asset.
- **Swap-aware fan-out.** A DEX swap is replicated across every trace row like any jetton
  transfer. The swap is booked **once**, on the canonical sibling for its **primary leg**
  identity (the lexicographically-smallest `transaction_hash` among the swap's legs, resolved
  via the same `JettonFanoutClaim`). Non-canonical siblings are excluded as
  `TON_JETTON_FANOUT_DUPLICATE` (no double-book). *Validated fully only after
  renormalization* — the isolated-builder tests use `CLAIM_ALL`.

## GRAM = Native TON (descriptor)

"GRAM" is **native TON**, not a jetton. The TON network descriptor sets `native-symbol: TON`
(`network-descriptors.yml`), so native value transfers book as `TON` / `TONCOIN`; there is no
GRAM jetton master in the registry and none is synthesized.

## Registry-Driven Staking (Affluent) — mechanism-ready

A wallet↔registered-`STAKING`-vault jetton move classifies as `STAKING_DEPOSIT`
(wallet is source) / `STAKING_WITHDRAW` (wallet is destination), driven by a
`family: STAKING` entry under `protocols` in `ton-protocol-registry.json`
(`classifyStaking`). The mechanism is **inert until a vault is configured**. Affluent
(`affGOLDm` staking) has **no occurrences in the current dataset**, so its vault address is
**not fabricated**; add the confirmed master under `protocols` with `family: STAKING` once
verified. Detection is registry-driven (no hardcoded wallet address).

## Jetton Decimals / Symbol Rule (RC-T1)

Resolution precedence (normalization/background time only — never on a read path):

1. Raw `jetton_content.decimals` / `jetton_content.symbol` when present & valid.
2. **Descriptor override** — `TonJettonMetadataRegistry` keyed by jetton **master**
   (any canonical form), backed by `network-descriptors.yml` `TON.token-overrides`.
   **USDT-TON master `EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs` is seeded
   to 6 decimals + symbol USDT.** Without this, a `jetton_content`-absent USDT
   transfer would book at the native default (9) — off by 1000×. xStock/RWA masters
   (AMZNx/MSTRx decimals 8, XAUT0 decimals 6) are seeded here too.
3. **Persistent cache → live TON jetton resolver (write-through)** — unseeded masters
   resolve live via `TonJettonMetadataResolver` (TON Center jetton-master metadata,
   throttled ≈1 rps) at the enrichment seam, persisted to `token_metadata_cache` so
   replay is RPC-free (ADR-073).
4. Fallback: explicit `unresolved` (never a fabricated wrong-decimals default); a raw
   9-decimals + shortened-master symbol is used only where the flow must stay
   well-formed without any identity hit.

Decimals are cached (Caffeine) keyed by the master's canonical key. The jetton
asset contract is the raw `workchain:hex` form (matches the DefiLlama `ton:<master>`
pricing key from ADR-064). Membership/stablecoin checks lowercase both sides
symmetrically (`CanonicalAssetCatalog.isUsdStablecoin`,
`network-descriptors.yml` TON `usd-stable-contracts`).

**USDT-TON already books as `STABLE_USD`.** The seeded jetton symbol `USDT`
drives the symbol-based `AssetFamily.resolve → STABLE_USD` (mirroring the Solana
USDC/USDT RC-S9 wiring), and the TON `usd-stable-contracts` master enforces $1
parity by contract. No additional TON wiring is required.

## Counterparty Rule (RC-T1.4)

TON has **no protocol-registry entries** → transfer-peer only (no PROTOCOL path).

- Each non-fee flow's `counterpartyAddress` (raw peer set by the builder) is
  classified via `AccountingUniverseService.classify(peer, TON)` →
  `PERSONAL_WALLET` / `CEX` / `UNKNOWN_EOA`
  (evidence `TON_TRANSFER_PEER`, else `TON_COUNTERPARTY_INFERRED`).
- FEE legs → `UNKNOWN:NETWORK_FEE` / `GENUINE_MISSING_SOURCE`.
- Transaction-level counterparty reconciled via
  `FlowCounterpartySupport.applyTransactionCounterparty`.
- `EXTERNAL_TRANSFER_IN/OUT` whose peer is `PERSONAL_WALLET` is promoted to
  `INTERNAL_TRANSFER` (basis continuity, exclusion cleared).
- **Cross-network economics:** an inbound whose peer is CEX (e.g. Bybit) or an own
  wallet is **never** booked as an external-capital market ACQUIRE; only a
  genuinely unknown-external inbound is external-capital. SOL/TON ↔ EVM same-owner
  moves are `INTERNAL_TRANSFER` with basis continuity, not disposal+acquisition.

Wired into `TonNormalizationService.normalize` via
`CanonicalMetadataEnricher.enrichTon` (protocol-name + counterparty only, mirroring
the Solana step set), after `builder.build(...)` and before upsert. The universe is
bound per batch via `bindUniverseIfPresent`.

## Telegram-Wallet Custodial-Operator Registry Rule (ADR-079)

Some well-known TON operators are **shared custodial hot-wallet pools** (e.g. Telegram
Wallet) that a user cannot be expected to configure. A move to/from such an operator is
an off-chain custody boundary — the counterparty is a custodian we cannot read into, not
a personal wallet, not a CEX account, and not a DEX router.

- **Global registry (config plane).** `TonCustodialOperatorRegistry` loads
  `classpath:ton-custodial-operators.json` once (mirrors `TonProtocolRegistry` /
  ADR-059 counterparty-hints), keyed by `TonAddressCanonicalizer.lookupKeys(...)` so a
  configured raw `0:hex` operator matches any canonical peer form (raw + friendly, hex
  lowercased; TON addresses are never `0x`/case normalized as words). Seeded with the two
  Telegram-Wallet custodial hot wallets
  `0:DD6FF02C59634745529B99A8D5BEEEA9F6C38A9188E6A7E96A424E3820C8AC0A` and
  `0:023895AEF955024920A291C6F3715E291DF1B3DD254EAFA8B09E21A2D58D5897`
  (`provider = "Telegram Wallet"`, `type = EXTERNAL_CUSTODY`).
- **Relabel semantics (reuses ADR-072).** On a match `TonCounterpartyResolver` relabels
  the flow + transaction counterparty to `CounterpartyType.EXTERNAL_CUSTODY` with label
  "Telegram Wallet" and stamps the venue-neutral `custodialOffChain` capability flag,
  via the shared `ExternalCustodyDestinationSupport.applyCustodyLabel`. The resolver
  consults **both** sources with identical semantics: the per-session, user-designated
  `ExternalCustodyDestinationRegistry` (ADR-072) **and** this global registry (the global
  registry acting as a maintained default fallback).
- **MATERIAL — type is preserved.** The row stays `EXTERNAL_TRANSFER_IN/OUT` with its
  `CARRY_IN`/`CARRY_OUT` effects. Because `EXTERNAL_CUSTODY` is not `PERSONAL_WALLET`, the
  `INTERNAL_TRANSFER` promotion guard never fires (verified). It is **never** converted to
  ACQUIRE/DISPOSE and inbound basis is **not** zeroed — standard external-transfer AVCO
  applies (capital leaves scope on deposit; new capital at market on the count-on-exit
  withdrawal). The custodian is never a universe member, so no phantom balance is created
  and the conservation gate is unaffected.
- **Deterministic, offline-only.** tonapi's account `name`/`interfaces` and the
  `wallet_highload_v3r1` interface are **offline discovery aids only**, used to seed/extend
  the JSON — there is **no runtime tonapi client or per-row lookup** (the stored TonCenter
  payload carries neither peer `interfaces` nor `name`). Re-clarification is idempotent:
  the registry yields the identical labeling on every rerun.

### Negative cases (must keep priority)

- **Highload interface alone is never sufficient.** An operator that is *not* listed in the
  registry (and not resolvable offline) stays `UNKNOWN_EOA` — it is **never** guessed as
  "Telegram" just because it is a highload wallet.
- **Bybit highload stays `EXCHANGE_ACCOUNT`.** A Bybit hot wallet resolves to
  `CEX` via `AccountingUniverseService` and is not overridden to custody.
- **TON DEX routers stay DEX.** Ston.fi / DeDust / Omniston routers are classified via
  `TonProtocolRegistry` (family `DEX`, normalization plane) and keep priority — the custody
  registry does not contain and does not sweep them.
- **Off-chain "Доход"/Earn income is out of scope.** Yield accrued *inside* the Telegram
  Wallet custodial venue is off-chain and not booked; only the on-chain custody-boundary
  transfer legs are recorded (as `EXTERNAL_CUSTODY`). This matches ADR-072's treatment of
  external custody destinations we cannot read into.

Cross-references: ADR-079 (registry), ADR-072 (`EXTERNAL_CUSTODY` type + `custodialOffChain`
flag + `ExternalCustodyDestinationRegistry`/`Support` reuse).

## Scam / Dust & Out-of-Scope Exclusion Rule

- The native TON wallet receives unsolicited scam jetton `transfer_notification`
  messages (opcode `0x7362d09c` → `jetton_notify`, `in_msg.value = 1` nanoTON,
  unknown jetton masters, `out_msgs = []`). These carry no resolvable jetton
  transfer and no bookable native value.
- A row that is `UNKNOWN` (needs review) **and** has no resolvable
  `jettonTransfers` **and** no bookable native TON value movement
  (`TonNormalizedTransactionBuilder.isUnbookableOutOfScope`) is **excluded from
  accounting**: `excludedFromAccounting = true`,
  `accountingExclusionReason = TON_UNSUPPORTED_SCOPE`
  (`TonNormalizedTransactionBuilder.UNSUPPORTED_SCOPE_REASON`), and the reason is
  added to `missingDataReasons`. The row stays `NEEDS_REVIEW` and visible in the
  UI (scam dust / deep TON DeFi out of scope), but is **non-blocking** for the
  portfolio conservation / AVCO gate — excluded rows are skipped by
  `PortfolioConservationGate` and `StatValidationService`.
- **Excluding is not confirming.** This does not weaken RC-T2: dropped value is
  never silently promoted to `CONFIRMED`; the row is routed to
  excluded-from-accounting instead of keeping the whole portfolio gate closed. A
  real jetton transfer with resolved `jettonTransfers` (e.g. USDT) is booked
  normally and is **never** excluded.
- The fee leg is always preserved on excluded rows for observability.

## Replay-Safe Promotion Guard (RC-T2, ADR-014 amendment)

An on-chain `UNKNOWN` row that the builder flagged with
`TON_ONCHAIN_UNRESOLVED_VALUE` (non-zero raw value that collapsed to a fee-only /
empty flow set) is **not** replay-safe: `StatValidationService` keeps it
`NEEDS_REVIEW` (both `validate` and `promoteReplaySafeNeedsReview`) instead of
silently confirming an empty/fee-only row. EVM rows never carry this marker, so
EVM replay-safe promotion is provably unchanged. When such a row is also
genuinely unbookable / out of scope it is additionally excluded from accounting
(see the scam/dust rule above) so it is non-blocking while remaining visible.

## Clarification EVM-Family Guard (FIX A)

TON rows have no EVM receipt, so they are excluded from full-receipt clarification
by the same config-driven EVM-family guard applied to Solana:
`PendingReceiptClarificationQueryService` selection queries filter
`networkId ∈ NetworkRegistry.evmWalletSupportedNetworks()`, and
`ClarificationPolicyService.isReceiptClarificationEligible` rejects any non-EVM
`networkId` via `NetworkAddressFormat.isEvm`. A TON
`NEEDS_REVIEW`/`PENDING_CLARIFICATION` row is therefore never pulled into the EVM
clarification path. See `docs/pipeline/normalization/rules/families/solana.md`
(Clarification EVM-Family Guard) for the full description.

## Disallowed Fallbacks

- Do not select or mark TON rows eligible for EVM full-receipt clarification.
- Do not compare TON addresses with `equalsIgnoreCase` / lowercase-only.
- Do not correlate jetton transfers by `transaction_hash` (use `trace_id`).
- Do not book a jetton transfer on every trace row — collapse the fan-out and book
  once on the canonical (lowest-`txHash`) sibling; exclude the rest as
  `TON_JETTON_FANOUT_DUPLICATE` (not `TON_ONCHAIN_UNRESOLVED_VALUE`).
- Do not merge two genuinely distinct jetton transfers (different `transaction_hash`).
- Do not book an own↔own jetton transfer asymmetrically (external one side, no carry
  the other) — both sides are `INTERNAL_TRANSFER` with symmetric CARRY_OUT/CARRY_IN.
- Do not pass the raw `0:hex` owner form to `/jetton/transfers`.
- Do not silently swallow a persistent `/jetton/transfers` failure (log WARN).
- Do not book jetton/DeFi gas-forwarding messages as native TON value.
- Do not default a known jetton (USDT-TON) to 9 decimals.
- Do not confirm an on-chain row that dropped real economic value.
- Do not let an unbookable, out-of-scope TON row keep the whole portfolio AVCO
  gate closed — exclude it from accounting instead.
- Do not book proxy-TON (pTON) as a held pTON jetton — always net it to native TON.
- Do not detect DeFi protocols by hardcoded wallet address — use `ton-protocol-registry.json`
  (pTON masters, DEX/STAKING vaults) and the `stonfi_swap*`/`dedust*` forward-payload markers.
- Do not guess "Telegram Wallet" (or any custodial operator) from the highload interface
  alone — a custodial label is registry-deterministic (ADR-079); an unlisted operator stays
  `UNKNOWN_EOA`.
- Do not promote an `EXTERNAL_CUSTODY` custody-boundary transfer to `INTERNAL_TRANSFER`, and
  do not convert it to ACQUIRE/DISPOSE or zero its inbound basis.
- Do not add a runtime tonapi client / per-row peer-interface lookup — the registry is the
  offline source of truth.
- Do not override a Bybit `EXCHANGE_ACCOUNT` or a TON DEX-router `DEX` classification with
  custody labeling.

## Regression Anchors

- Canonical equality, native/jetton direction, USDT-TON decimals=6, failed-tx,
  dropped-value marker, scam/dust exclusion (non-blocking), resolved-jetton not
  excluded, jetton fan-out dedup (within-row + cross-row claim; distinct transfers
  not merged; non-canonical sibling excluded), own↔own symmetric carry,
  Ston.fi swap (pTON→native TON, SELL+BUY), bare pTON receive netted to TON,
  swap-aware fan-out exclusion (WS-2): `TonNormalizedTransactionBuilderTest`.
- Jetton `trace_id` correlation (hash mismatch), hash fallback, `/jetton/transfers`
  retry / persistent-failure non-abort: `TonNetworkAdapterTest`.
- Jetton master decimals/symbol across canonical forms: `TonJettonMetadataRegistryTest`.
- Peer classification (CEX / own / unknown) + FEE leg + internal promotion; ADR-079
  Telegram-Wallet custodial registry (seed operators → `EXTERNAL_CUSTODY` + `custodialOffChain`,
  type stays `EXTERNAL_TRANSFER_IN`; Bybit stays `EXCHANGE_ACCOUNT`; unlisted highload stays
  `UNKNOWN_EOA`; DEX-router peer keeps priority; idempotent rerun):
  `TonCounterpartyResolverTest`.
- RC-T2 guard + EVM-fixture regression: `StatValidationServiceTest`.
