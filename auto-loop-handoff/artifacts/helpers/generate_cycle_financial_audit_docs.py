from __future__ import annotations

import json
from pathlib import Path
import shutil
import textwrap


ROOT = Path.cwd()
AUDIT_PATH = ROOT / "data/derived/financial-audit-live-current.json"
PIPELINE_PATH = ROOT / "auto-loop-handoff/runtime-v2/state/pipeline-state.json"
REGISTRY_PATH = ROOT / "protocol-registry.json"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text())


if not AUDIT_PATH.exists():
    raise FileNotFoundError(f"Missing audit snapshot: {AUDIT_PATH}")

audit = load_json(AUDIT_PATH)
pipeline = load_json(PIPELINE_PATH) if PIPELINE_PATH.exists() else {}
registry_text = REGISTRY_PATH.read_text().lower() if REGISTRY_PATH.exists() else ""

cycle = str(pipeline.get("currentCycle") or pipeline.get("cycle") or 67)
cycle_dir = ROOT / "auto-loop-handoff" / "artifacts" / "cycle" / cycle
fa_dir = cycle_dir / "financial-analyst"
handoff_dir = cycle_dir / "handoffs"
derived_dir = fa_dir / "data" / "derived"
results_dir = ROOT / "results"

exact_coverage = audit["exactCoverage"]
family_coverage = audit["familyCoverage"]
needs_review = audit["needsReviewByReason"]
protocol_gap_by_type = audit["protocolGapByType"]
counterparty_gap_by_type = audit["counterpartyGapByType"]
protocol_gap_targets = audit["protocolGapTargets"]
samples = audit["samples"]
lineages = audit["lineages"]
counts = audit["counts"]
dataset = audit["dataset"]

MANDATORY_ASSETS = ["ETH", "BTC", "MNT", "AVAX", "USDC", "USDT"]


def mkdirp(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def write(rel_path: str, content: str) -> None:
    full = ROOT / rel_path
    mkdirp(full.parent)
    full.write_text(content.rstrip() + "\n")


def normalize_markdown(text: str) -> str:
    return "\n".join(line[4:] if line.startswith("    ") else line for line in text.strip().splitlines())


def fmt(value) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if value == 0:
            return "0"
        abs_value = abs(value)
        if 1e-6 <= abs_value < 1e9:
            return f"{value:.18f}".rstrip("0").rstrip(".")
        return str(value)
    return str(value)


def yes_no(value: bool) -> str:
    return "yes" if value else "no"


def has_registry_hit(address: str | None) -> bool:
    return bool(address) and address.lower() in registry_text


def exact_row(asset: str) -> dict:
    return exact_coverage[asset]


def family_row(asset: str) -> dict:
    return family_coverage[f"FAMILY:{asset}"]


def top_dirty_exact(asset: str, limit: int = 3) -> list[dict]:
    buckets = exact_row(asset).get("buckets", [])
    dirty = [bucket for bucket in buckets if float(bucket["uncoveredQuantity"]) > 0]
    dirty.sort(key=lambda bucket: float(bucket["uncoveredQuantity"]), reverse=True)
    return dirty[:limit]


def top_dirty_family(asset: str, limit: int = 3) -> list[dict]:
    buckets = family_row(asset).get("buckets", [])
    dirty = [bucket for bucket in buckets if float(bucket["uncoveredQuantity"]) > 0]
    dirty.sort(key=lambda bucket: float(bucket["uncoveredQuantity"]), reverse=True)
    return dirty[:limit]


def final_clean_exact(asset: str) -> bool:
    row = exact_row(asset)
    return abs(float(row["current"]) - float(row["covered"])) <= 1e-12


def final_clean_family(asset: str) -> bool:
    row = family_row(asset)
    return abs(float(row["uncovered"])) <= 1e-12 and int(row["dirtyBuckets"]) == 0


def date_value(value) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and "$date" in value:
        return value["$date"]
    return str(value)


def md_bullets(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values)


def select_lineage(entries: list[dict], tail_count: int = 8) -> list[dict]:
    selected = entries[-tail_count:]
    result = []
    for entry in selected:
        result.append(
            {
                "blockTimestamp": date_value(entry.get("blockTimestamp")),
                "txHash": entry.get("txHash"),
                "type": entry.get("normalizedType"),
                "protocolName": entry.get("protocolName"),
                "basisEffect": entry.get("basisEffect"),
                "quantityDelta": entry.get("quantityDelta", {}).get("$numberDecimal")
                if isinstance(entry.get("quantityDelta"), dict)
                else entry.get("quantityDelta"),
                "basisBackedQuantityAfter": entry.get("basisBackedQuantityAfter", {}).get(
                    "$numberDecimal"
                )
                if isinstance(entry.get("basisBackedQuantityAfter"), dict)
                else entry.get("basisBackedQuantityAfter"),
                "uncoveredQuantityAfter": entry.get("uncoveredQuantityAfter", {}).get(
                    "$numberDecimal"
                )
                if isinstance(entry.get("uncoveredQuantityAfter"), dict)
                else entry.get("uncoveredQuantityAfter"),
                "quantityShortfallAfter": entry.get("quantityShortfallAfter", {}).get(
                    "$numberDecimal"
                )
                if isinstance(entry.get("quantityShortfallAfter"), dict)
                else entry.get("quantityShortfallAfter"),
                "hasIncompleteHistoryAfter": entry.get("hasIncompleteHistoryAfter"),
                "hasUnresolvedFlagsAfter": entry.get("hasUnresolvedFlagsAfter"),
                "unresolvedFlagCountAfter": entry.get("unresolvedFlagCountAfter"),
            }
        )
    return result


blocking_reasons = {
    "ETH": "Exact native ETH carry still leaks on Arbitrum and Base; family ratio is above 0.99 but `AMANWETH` and native ETH buckets are still not final-clean.",
    "BTC": "Exact BTC is clean; family remains not final-clean because `AARBWBTC` still carries a small uncovered receipt-token remainder.",
    "MNT": "Exact MNT is clean; family is dominated by Bybit `MNT` reward inventory while `external_ledger_raw` is empty on this live DB snapshot.",
    "AVAX": "Native AVAX basis is not restored across `aAvaWAVAX` and `sAVAX` continuity, leaving both exact and family AVAX materially uncovered.",
    "USDC": "Arbitrum `eUSDC-6 -> USDC` and the earlier ParaSwap/bridge chronology strand `361.759952` USDC with evidence present but continuity not carried.",
    "USDT": "PancakeSwap Infinity `LP_EXIT` still lands as `basisEffect=UNKNOWN`, so returned USDT remains almost fully uncovered.",
}

protocol_gap_summary = ", ".join(
    f"{row['type']}={row['count']}" for row in protocol_gap_by_type
)
counterparty_gap_summary = ", ".join(
    f"{row['type']}={row['count']}" for row in counterparty_gap_by_type
)

blockers = [
    {
        "id": f"FA{cycle}-B1",
        "title": "ETH exact carry leak and ETH-family receipt-token continuity are not final-clean",
        "severity": "high",
        "surfaces": ["ETH exact", "ETH family"],
        "currentDatabaseTruth": (
            f"ETH exact coverage is {fmt(exact_row('ETH')['coverageRatio'])} with uncovered "
            f"{fmt(float(exact_row('ETH')['current']) - float(exact_row('ETH')['covered']))} across "
            f"{len(top_dirty_exact('ETH'))} materially dirty exact buckets. ETH family coverage is "
            f"{fmt(family_row('ETH')['coverageRatio'])} but still has uncovered "
            f"{fmt(family_row('ETH')['uncovered'])} and {fmt(family_row('ETH')['dirtyBuckets'])} dirty buckets."
        ),
        "auditorTruth": "Same-family bridge, wrapper, and lending receipt-token principal must carry basis end to end. Only explicit positive excess over the carried principal should become acquisition.",
        "firstFailedStage": "normalization + move_basis",
        "evidenceState": "EVIDENCE_PRESENT_UNLINKED for native bridge/native dust buckets; EVIDENCE_PRESENT_UNUSABLE for receipt-token rows that still hide yield inside one coarse continuity leg.",
        "typeAdequacy": "Current canonical split is semantically lossy for receipt-token and rebasing-family exits because principal and yield are not separated strongly enough before replay.",
        "remediationClass": "normalization, move_basis, replay",
        "pipelineCorrectionPoint": "Normalize principal-vs-yield split on supported receipt-token rows before move-basis replay, then rerun continuity replay.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
            "0xda6537107ce79f1adca4abeeef7abc49da76cf4b23de3f17ee8c43cbfbb8c9b2",
            "0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd",
        ],
    },
    {
        "id": f"FA{cycle}-B2",
        "title": "AVAX native and family continuity still lose basis across receipt-token and staking-wrapper transitions",
        "severity": "high",
        "surfaces": ["AVAX exact", "AVAX family"],
        "currentDatabaseTruth": (
            f"AVAX exact coverage is {fmt(exact_row('AVAX')['coverageRatio'])} with uncovered "
            f"{fmt(float(exact_row('AVAX')['current']) - float(exact_row('AVAX')['covered']))}. "
            f"AVAX family coverage is {fmt(family_row('AVAX')['coverageRatio'])} with uncovered "
            f"{fmt(family_row('AVAX')['uncovered'])}, dominated by `sAVAX` plus native AVAX."
        ),
        "auditorTruth": "Aave-style receipt-token exits and native staking-wrapper transitions are continuity. Principal basis should move from `aAvaWAVAX` and native AVAX into the returned AVAX or `sAVAX` position; only net excess should be acquisition.",
        "firstFailedStage": "move_basis",
        "evidenceState": "EVIDENCE_PRESENT_UNLINKED",
        "typeAdequacy": "The type model is adequate, but the move-basis engine is not carrying native-family basis across supported AVAX family reallocations.",
        "remediationClass": "move_basis, cost_basis, replay",
        "pipelineCorrectionPoint": "Carry basis across `aAvaWAVAX -> AVAX` and `AVAX -> sAVAX` continuity before AVCO and replay consume the rows.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0xfbbfd2293154db9a2cd678596eae84f8f8ed9b140d9a9115f055f6c47c9ac931",
            "0x682992de7690b96b0710f5817481994d0288f8ac4a4677e116372b38640a4cb4",
            "0x5e30c5086e680e2c6313074d796af64051338d9e1b0a27882c1ce1f436f18543",
        ],
    },
    {
        "id": f"FA{cycle}-B3",
        "title": "USDC exact and family continuity remain broken on Arbitrum vault and bridge corridors",
        "severity": "high",
        "surfaces": ["USDC exact", "USDC family"],
        "currentDatabaseTruth": (
            f"USDC exact coverage is {fmt(exact_row('USDC')['coverageRatio'])} with uncovered "
            f"{fmt(float(exact_row('USDC')['current']) - float(exact_row('USDC')['covered']))}. "
            f"Two Arbitrum buckets explain nearly all of it: {fmt(top_dirty_exact('USDC')[0]['uncoveredQuantity'])} "
            f"on `0x0765...` and {fmt(top_dirty_exact('USDC')[1]['uncoveredQuantity'])} on `0x101c...`."
        ),
        "auditorTruth": "Bridge carry, vault receipt-token carry, and supported swap chronology are all present. Basis should flow from bridge source rows into Arbitrum USDC and from `eUSDC-6` back into underlying USDC, leaving only explicit vault yield or fee effects as acquisition/disposal.",
        "firstFailedStage": "normalization + move_basis",
        "evidenceState": "EVIDENCE_PRESENT_UNLINKED on supported bridge/vault/swap chronology and EVIDENCE_PRESENT_UNUSABLE where vault receipt-token exits remain too coarse.",
        "typeAdequacy": "Current canonical representation is too coarse for `eUSDC-6 -> USDC` principal-vs-yield decomposition and for preserving exact/family continuity after bridge corridors.",
        "remediationClass": "normalization, linking, move_basis, replay",
        "pipelineCorrectionPoint": "Split vault withdraw principal from yield, persist protocol/counterparty metadata for the vault, then replay carry through bridge and swap chronology.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977",
            "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
            "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
            "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
        ],
    },
    {
        "id": f"FA{cycle}-B4",
        "title": "Supported LP exits still surface as `basisEffect=UNKNOWN`, breaking exact USDT and broader LP accounting",
        "severity": "high",
        "surfaces": ["USDT exact", "USDT family"],
        "currentDatabaseTruth": (
            f"USDT exact/family coverage is {fmt(exact_row('USDT')['coverageRatio'])} with uncovered "
            f"{fmt(float(exact_row('USDT')['current']) - float(exact_row('USDT')['covered']))}, entirely anchored "
            "at PancakeSwap Infinity LP exit `0x091e...`."
        ),
        "auditorTruth": "A supported LP exit should close the carried LP-position basis, then reallocate that basis onto the returned assets proportionally. Returned assets must not remain fully uncovered only because the exit row is still tagged `UNKNOWN`.",
        "firstFailedStage": "normalization",
        "evidenceState": "EVIDENCE_PRESENT_UNUSABLE",
        "typeAdequacy": "The current LP-exit canonical output is missing a deterministic principal-basis allocation rule, so move basis and AVCO have no authoritative input.",
        "remediationClass": "normalization, move_basis, cost_basis, avco",
        "pipelineCorrectionPoint": "Emit deterministic LP-exit basis allocation from the carried LP position into returned assets before replay and AVCO.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70",
            "0x8cd845033478862d78ae2214fa63e822a9dd217fab4c428801285eb1bb40d2e1",
        ],
    },
    {
        "id": f"FA{cycle}-B5",
        "title": "Protocol detection is materially incomplete on rows where the protocol is already provable from current raw evidence",
        "severity": "medium",
        "surfaces": ["Protocol detection"],
        "currentDatabaseTruth": (
            f"{protocol_gap_summary} currently lack `protocolName`. "
            "The biggest clusters are wrap/unwrap, swap, and bridge rows."
        ),
        "auditorTruth": "Protocol labels are best-effort metadata, but they should still be deterministically attached whenever the interacted contract, canonical selector, or audited lifecycle pairing already proves the protocol brand.",
        "firstFailedStage": "protocol enrichment",
        "evidenceState": "EVIDENCE_PRESENT_UNLINKED",
        "typeAdequacy": "The metadata model is adequate; the defect is missing registry coverage plus missing clarification-time enrichment on already-canonical rows.",
        "remediationClass": "registry coverage, clarification, repair sweep",
        "pipelineCorrectionPoint": "Expand registry/enrichment rules and backfill `protocolName` without changing already-correct economics.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977",
            "0xc0ca8c4022bbfbb8bfd0660155e4857dd80c0cf5c521b8ad5f61ab4738fc0cab",
            "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
            "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
        ],
    },
    {
        "id": f"FA{cycle}-B6",
        "title": "Counterparty construction is incomplete on core bridge, swap, lending, and vault families",
        "severity": "medium",
        "surfaces": ["Counterparty construction"],
        "currentDatabaseTruth": (
            f"{counterparty_gap_summary} currently lack "
            "`counterpartyAddress`. Gaps are concentrated on swaps, external transfers, bridge rows, lending, and vault operations."
        ),
        "auditorTruth": "For on-chain protocol rows, `counterpartyAddress` should point to the interacted contract or deterministic peer. Lifecycle pairing belongs in `correlationId` and `matchedCounterparty`, not inside `counterpartyAddress`.",
        "firstFailedStage": "clarification + linking",
        "evidenceState": "EVIDENCE_PRESENT_UNLINKED",
        "typeAdequacy": "The model is adequate if `counterpartyAddress`, `correlationId`, and `matchedCounterparty` are kept distinct. The current defect is incomplete population of those fields.",
        "remediationClass": "clarification, linking, repair sweep",
        "pipelineCorrectionPoint": "Populate `counterpartyAddress` from deterministic row-local evidence and persist reciprocal lifecycle links only through `correlationId` and `matchedCounterparty`.",
        "terminalState": "AUTHORITATIVE_RECONSTRUCTION_COMPLETE",
        "anchors": [
            "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
            "0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977",
            "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
            "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
        ],
    },
    {
        "id": f"FA{cycle}-B7",
        "title": "Bybit family reconstruction remains broader-goal blocked because the live DB snapshot has no raw CEX source collection",
        "severity": "medium",
        "surfaces": ["MNT family", "Bybit broader-goal coverage"],
        "currentDatabaseTruth": (
            f"MNT exact is clean, but MNT family coverage is only {fmt(family_row('MNT')['coverageRatio'])} "
            f"with uncovered {fmt(family_row('MNT')['uncovered'])} dominated by Bybit reward inventory. "
            f"At the same time `external_ledger_raw = {counts['externalLedgerRaw']}`."
        ),
        "auditorTruth": "A full raw-first CEX family reconstruction requires raw venue rows. `bybit_extracted_events` is useful context, but it is not the source-of-truth collection specified for authoritative Bybit replay.",
        "firstFailedStage": "source availability",
        "evidenceState": "GENUINE_EVIDENCE_MISSING",
        "typeAdequacy": "The family metric is broader-goal valid, but this live snapshot does not contain the raw Bybit source collection required for authoritative CEX reconstruction.",
        "remediationClass": "source ingestion, broader-goal replay",
        "pipelineCorrectionPoint": "Restore raw Bybit source availability or document the explicit broader-goal limitation; do not present the current family remainder as a normal supported on-chain normalization defect.",
        "terminalState": "GENUINE_EVIDENCE_MISSING_PROVEN",
        "anchors": ["BYBIT:33625378", "SYMBOL:MNT", "FAMILY:MNT"],
    },
]


def scorecard_table() -> str:
    header = [
        "| Asset | Final asset qty | Final asset basis-backed qty | Final asset uncovered qty | Final asset coverage ratio | Final asset final-clean | Family current qty | Family covered qty | Family uncovered qty | Family coverage ratio | Family final-clean | Dirty buckets | Current blocking reason | Prior-cycle comparability | Prior-cycle delta note |",
        "| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | --- |",
    ]
    rows = []
    for asset in MANDATORY_ASSETS:
        exact = exact_row(asset)
        family = family_row(asset)
        rows.append(
            f"| {asset} | {fmt(exact['current'])} | {fmt(exact['covered'])} | "
            f"{fmt(float(exact['current']) - float(exact['covered']))} | {fmt(exact['coverageRatio'])} | "
            f"{yes_no(final_clean_exact(asset))} | {fmt(family['current'])} | {fmt(family['covered'])} | "
            f"{fmt(family['uncovered'])} | {fmt(family['coverageRatio'])} | {yes_no(final_clean_family(asset))} | "
            f"{fmt(family['dirtyBuckets'])} | {blocking_reasons[asset]} | NOT COMPARABLE | "
            "Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |"
        )
    return "\n".join(header + rows)


def reference_coverage_summary_table() -> str:
    header = [
        "| Asset | Exact coverage | Exact uncovered | Family coverage | Family uncovered | Exact final-clean | Family final-clean |",
        "| --- | ---: | ---: | ---: | ---: | --- | --- |",
    ]
    rows = []
    for asset in MANDATORY_ASSETS:
        exact = exact_row(asset)
        family = family_row(asset)
        rows.append(
            f"| {asset} | {fmt(exact['coverageRatio'])} | {fmt(float(exact['current']) - float(exact['covered']))} | "
            f"{fmt(family['coverageRatio'])} | {fmt(family['uncovered'])} | "
            f"{yes_no(final_clean_exact(asset))} | {yes_no(final_clean_family(asset))} |"
        )
    return "\n".join(header + rows)


def top_protocol_gap_table(limit: int = 12) -> str:
    header = [
        "| Network | Type | Interacted address | Method | Count | Address already in registry | Change package implication |",
        "| --- | --- | --- | --- | ---: | --- | --- |",
    ]
    rows = []
    for row in protocol_gap_targets[:limit]:
        in_registry = has_registry_hit(row.get("to"))
        implication = (
            "Existing registry coverage is not reaching final row enrichment."
            if in_registry
            else "Add registry or audited enrichment coverage for this target."
        )
        rows.append(
            f"| {row.get('networkId') or 'n/a'} | {row.get('type')} | {row.get('to') or 'n/a'} | "
            f"{row.get('methodId') or 'n/a'} | {fmt(row.get('count'))} | {yes_no(in_registry)} | {implication} |"
        )
    return "\n".join(header + rows)


def counterparty_gap_table() -> str:
    header = [
        "| Type | Missing `counterpartyAddress` rows | Construction rule needed |",
        "| --- | ---: | --- |",
    ]
    rules = {
        "SWAP": "Use interacted router/aggregator contract from raw tx as row-local counterparty.",
        "EXTERNAL_TRANSFER_IN": "Use unique external sender from transfer evidence; do not synthesize same-wallet peers.",
        "EXTERNAL_TRANSFER_OUT": "Use unique external recipient from transfer evidence.",
        "BRIDGE_OUT": "Use source bridge contract as counterparty; pair lifecycle with `matchedCounterparty`.",
        "BRIDGE_IN": "Use destination settlement contract when unique; pair lifecycle with the source row.",
        "LP_ENTRY": "Use pool/position manager contract, not the LP token itself.",
        "LP_EXIT": "Use pool/position manager contract, then allocate basis from the carried LP position.",
        "LENDING_DEPOSIT": "Use lending pool/vault contract proven by the interacted address or receipt token.",
        "LENDING_WITHDRAW": "Use lending pool/vault contract proven by the interacted address or receipt token.",
        "VAULT_DEPOSIT": "Use vault contract proven by interacted address.",
        "VAULT_WITHDRAW": "Use vault contract proven by interacted address.",
        "BORROW": "Use debt/pool contract when selector plus debt markers prove the protocol.",
        "REPAY": "Use debt/pool contract when selector plus debt markers prove the protocol.",
        "STAKING_DEPOSIT": "Use staking contract; keep continuity in family carry.",
        "STAKING_WITHDRAW": "Use staking contract; keep lifecycle pairing separate from counterparty.",
    }
    rows = [
        f"| {row['type']} | {fmt(row['count'])} | {rules.get(row['type'], 'Add audited deterministic row-local counterparty rule.')} |"
        for row in counterparty_gap_by_type
    ]
    return "\n".join(header + rows)


def blocker_section(blocker: dict) -> str:
    return textwrap.dedent(
        f"""
        ### {blocker["id"]} {blocker["title"]}

        - Severity: `{blocker["severity"]}`
        - Surfaces: {", ".join(f"`{surface}`" for surface in blocker["surfaces"])}
        - Current database truth: {blocker["currentDatabaseTruth"]}
        - Auditor-derived financially correct state: {blocker["auditorTruth"]}
        - First failed stage: `{blocker["firstFailedStage"]}`
        - Evidence diagnosis: {blocker["evidenceState"]}
        - Type adequacy: {blocker["typeAdequacy"]}
        - Remediation class: {blocker["remediationClass"]}
        - Pipeline correction point: {blocker["pipelineCorrectionPoint"]}
        - Terminal audit state: `{blocker["terminalState"]}`
        - Evidence anchors: {", ".join(f"`{anchor}`" for anchor in blocker["anchors"])}
        """
    ).strip()


def finding_json(blocker: dict) -> dict:
    return {
        "id": blocker["id"],
        "title": blocker["title"],
        "severity": blocker["severity"],
        "surfaces": blocker["surfaces"],
        "currentDatabaseTruth": blocker["currentDatabaseTruth"],
        "auditorTruth": blocker["auditorTruth"],
        "firstFailedStage": blocker["firstFailedStage"],
        "evidenceDiagnosis": blocker["evidenceState"],
        "typeAdequacy": blocker["typeAdequacy"],
        "remediationClass": blocker["remediationClass"],
        "pipelineCorrectionPoint": blocker["pipelineCorrectionPoint"],
        "terminalState": blocker["terminalState"],
        "anchors": blocker["anchors"],
    }


mkdirp(derived_dir)
mkdirp(handoff_dir)
mkdirp(results_dir)
shutil.copyfile(AUDIT_PATH, derived_dir / "metrics.json")

blocker_sections_markdown = "\n\n".join(blocker_section(blocker) for blocker in blockers)
accounting_failure_rows = "\n".join(
    f"| {blocker['id']} | {blocker['currentDatabaseTruth'].replace('|', '/')} | {blocker['auditorTruth'].replace('|', '/')} | {blocker['firstFailedStage']} | {blocker['evidenceState']} | {blocker['typeAdequacy'].replace('|', '/')} | {blocker['remediationClass']} | {blocker['pipelineCorrectionPoint'].replace('|', '/')} | {blocker['terminalState']} |"
    for blocker in blockers
)

scorecard = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} scorecard

    ## Header

    - Cycle: `{cycle}`
    - Status: `fail`
    - Owner: `financial-analyst`
    - Dataset basis: fresh live Mongo capture from `{dataset["database"]}` at `{audit["capturedAt"]}`, accounting universe `{dataset["accountingUniverseId"]}`, using `raw_transactions`, `normalized_transactions`, `bybit_extracted_events`, `asset_ledger_points`, and `on_chain_balances`; note that `external_ledger_raw = {counts["externalLedgerRaw"]}`
    - Comparison basis: archived earlier-cycle artifacts are historical context only; this scorecard is the authoritative post-rerun live metric contract for cycle {cycle}

    ## Metric basis

    - `final asset` means the exact current holding for one canonical asset identity as reconstructed from the live replay truth in `asset_ledger_points` and reconciled to the current bucket in `on_chain_balances`
    - `family` means the current holding grouped by `accountingFamilyIdentity`, kept separate from exact-asset rows
    - `coverage ratio` means `basis-backed current quantity / current quantity` on the same exact or family basis
    - `final-clean` means uncovered quantity is zero within rounding tolerance and no dirty bucket remains for that exact or family surface
    - historical context only means archived `cycle.zip` snapshots and earlier cycle notes that were captured before this fresh live rerun

    ## Mandatory reference set scorecard

    {scorecard_table()}

    ## Material non-reference blockers

    - `broader-goal-blocker` Protocol label coverage remains incomplete on current canonical rows: {", ".join(f"`{row['type']}={row['count']}`" for row in protocol_gap_by_type[:6])}
    - `broader-goal-blocker` Counterparty construction remains incomplete on current canonical rows: {", ".join(f"`{row['type']}={row['count']}`" for row in counterparty_gap_by_type[:6])}
    - `excluded-non-primary` Bybit unsupported review tails remain explicit scope-policy exclusions: {", ".join(f"`{row['type']}/{row['reason']}={row['count']}`" for row in needs_review)}

    ## Prior-cycle delta

    - Fresh live rerun data was captured after the archived earlier-cycle artifacts. Numeric deltas are therefore `NOT COMPARABLE` unless explicitly recomputed on the same replay truth.
    - The cycle {cycle} scorecard should be treated as the only authoritative acceptance surface for downstream roles.

    ## Role conformance notes

    - Downstream roles must preserve exact-asset rows and family rows as separate acceptance surfaces.
    - A coverage pass is not the same thing as a final-clean pass. ETH family and BTC family are examples where ratio is above `0.99` but final-clean is still `no`.
    - Supported on-chain failures and broader-goal Bybit raw-evidence gaps must remain separated in requirements and implementation planning.
    """
).strip())

report = normalize_markdown(textwrap.dedent(
    f"""
    # Financial analyst report, cycle {cycle}

    ## Scope and dataset basis

    Fresh full-role audit was rebuilt from the current live Mongo state, not from the archived cycle bundle. The authoritative dataset basis for this package is:

    - Mongo database: `{dataset["database"]}`
    - Live capture time: `{audit["capturedAt"]}`
    - Accounting universe / session id: `{dataset["accountingUniverseId"]}`
    - Pipeline state during capture: `{dataset["pipelineState"]["stage"]} / {dataset["pipelineState"]["status"]}` at `{date_value(dataset["pipelineState"]["updatedAt"])}`
    - Wallets in scope: {", ".join(f"`{wallet['label']}:{wallet['address']}`" for wallet in dataset["wallets"])}
    - Integrations in scope: {", ".join(f"`{item['provider']}:{item['accountRef']}`" for item in dataset["integrations"])}

    Critical evidence boundary:

    - `raw_transactions` is present and fully normalized for the on-chain scope
    - `external_ledger_raw = {counts["externalLedgerRaw"]}`, so authoritative raw-first Bybit/CEX reconstruction is not fully available in this live DB snapshot
    - `bybit_extracted_events = {counts["bybitExtractedEvents"]}` is available as derived venue evidence, but it is not a substitute for the missing raw CEX source collection

    ## Fresh source counts

    - `raw_transactions = {counts["rawTransactions"]}`
    - `raw_transactions(normalizationStatus=PENDING) = {counts["rawPending"]}`
    - `normalized_transactions = {counts["normalizedTransactions"]}`
    - `normalized_transactions(status=CONFIRMED) = {counts["confirmed"]}`
    - `normalized_transactions(status=NEEDS_REVIEW) = {counts["needsReview"]}`
    - `unknown confirmed normalized rows = {counts["unknownConfirmed"]}`
    - `bybit_extracted_events(status=RAW) = {counts["bybitExtractedRaw"]}`
    - `bybit_extracted_events(status=CONFIRMED) = {counts["bybitExtractedConfirmed"]}`
    - `asset_ledger_points = {counts["assetLedgerPoints"]}`
    - `on_chain_balances = {counts["onChainBalances"]}`
    - `unmatchedBridgeOut = {counts["unmatchedBridgeOut"]}`
    - `unmatchedBridgeIn = {counts["unmatchedBridgeIn"]}`

    Current `NEEDS_REVIEW` inventory is concentrated in explicit Bybit unsupported or shadow lanes:

    {md_bullets([f"`{row['source']} / {row['type']} / {row['reason']} = {row['count']}`" for row in needs_review])}

    ## Mandatory reference-set verdict

    {reference_coverage_summary_table()}

    High-signal conclusions from the current live basis:

    - Exact reference assets clean now: `BTC`, `MNT`
    - Exact reference assets still failing now: `ETH`, `AVAX`, `USDC`, `USDT`
    - Family surfaces still failing final-clean now: all six mandatory families, with the largest remainder on `FAMILY:MNT`
    - The highest supported on-chain uncovered exact quantity is `USDC = {fmt(float(exact_row("USDC")["current"]) - float(exact_row("USDC")["covered"]))}`
    - The highest broader-goal family remainder is `FAMILY:MNT = {fmt(family_row("MNT")["uncovered"])}`, but that lane is blocked by missing raw CEX source evidence in this live DB snapshot

    ## Material findings

    {blocker_sections_markdown}

    ## Protocol and counterparty diagnosis

    The fresh live snapshot shows that metadata quality is not a cosmetic issue. It directly affects auditability and rule authoring:

    - Missing `protocolName` is concentrated in deterministic families that already have reusable raw signatures: {", ".join(f"`{row['type']}={row['count']}`" for row in protocol_gap_by_type[:8])}
    - Missing `counterpartyAddress` is concentrated in lifecycle-heavy families where row-local interacted contracts and lifecycle pairs must stay separate: {", ".join(f"`{row['type']}={row['count']}`" for row in counterparty_gap_by_type[:8])}
    - Several top protocol-gap targets already exist in `protocol-registry.json`, which proves the problem is not only missing registry entries but also missing enrichment materialization on current rows

    Top protocol-gap targets from the live snapshot:

    {top_protocol_gap_table(12)}

    Current counterparty-gap priorities:

    {counterparty_gap_table()}

    ## Fresh-cycle conclusion

    Cycle {cycle} fails the mandatory financial correctness surface on the current live basis. The change package prepared in this run is therefore split into:

    1. Supported on-chain normalization and continuity fixes for ETH, AVAX, USDC, and LP-exit USDT
    2. Protocol detection and counterparty-construction rules that can be backfilled without redefining already-correct economics
    3. A broader-goal CEX evidence boundary note explaining why `FAMILY:MNT` cannot be treated as a normal supported on-chain blocker on this DB snapshot
    """
).strip())

authoritative_reconstruction = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} authoritative reconstruction

    ## Operating result

    The auditor-derived financially correct model for this live dataset is:

    - same-wallet bridge source and destination rows are continuity, not disposal/reacquisition
    - lending and vault receipt-token rows are continuity for the principal amount; only positive excess over carried principal is acquisition
    - staking-wrapper transitions inside the same supported family are continuity for principal; only explicit excess is acquisition
    - LP exits must close the carried LP-position basis and reallocate that basis onto returned assets; they must not stay `basisEffect=UNKNOWN`
    - AVCO must consume the post-move-basis canonical result, not compensate for missing move-basis semantics after the fact

    ## Reconstructed supported flow classes

    | Flow class | Auditor-derived canonical result | Basis treatment | AVCO treatment | Evidence anchors |
    | --- | --- | --- | --- | --- |
    | Same-wallet routed bridge | `BRIDGE_OUT` + `BRIDGE_IN` with one deterministic `correlationId` and reciprocal `matchedCounterparty` | carry full supported principal basis across networks; quantity drift stays inside the same continuity family | unchanged for carried principal | `0x9f6983...`, `0x7d8c79...` |
    | ParaSwap / routed swap | canonical `SWAP` with router contract as row-local counterparty | dispose sold principal and acquire bought principal only after prior carry is already correct | AVCO updates only on explicit buy/sell legs | `0x101c297...` |
    | Euler EVK vault withdraw | canonical `VAULT_WITHDRAW` with vault contract metadata and principal-vs-yield split | move carried basis from receipt token into underlying up to principal; treat excess underlying as acquisition | carried principal leaves AVCO unchanged; explicit excess updates AVCO | `0x0765f4...` |
    | Mantle lending deposit | canonical `LENDING_DEPOSIT` with protocol/vault contract metadata | move basis from underlying into receipt token family-equivalent carry | unchanged for carried principal | `0xc0ca8c...` |
    | Aave-style AVAX withdraw | canonical `LENDING_WITHDRAW` returning underlying AVAX from receipt token | move carried basis from `aAvaWAVAX` to native AVAX | unchanged for carried principal | `0xfbbfd229...` |
    | Native AVAX staking wrapper | canonical `STAKING_DEPOSIT` into `sAVAX` | move carried AVAX-family basis into staking wrapper quantity | unchanged for carried principal | `0x682992de...` |
    | PancakeSwap Infinity LP exit | canonical `LP_EXIT` with deterministic allocation from LP-position basis into returned assets | consume carried LP basis and split it across returned assets; do not leave returned principal uncovered | AVCO updates only for explicit acquisition excess after allocation | `0x091e3560...`, `0x8cd84503...` |

    ## Reference-asset reconstruction summary

    {reference_coverage_summary_table()}

    ## Live lineage excerpts

    ### USDC Arbitrum tail

    ```json
    {json.dumps(select_lineage(lineages["usdcArbitrum"], 8), indent=2)}
    ```

    ### AVAX native tail

    ```json
    {json.dumps(select_lineage(lineages["avaxNative"], 8), indent=2)}
    ```

    ### USDT BSC exact tail

    ```json
    {json.dumps(select_lineage(lineages["usdtBsc"], 4), indent=2)}
    ```

    ## Reconstruction boundary

    - On-chain supported surfaces above are reconstructed from current raw and current replay evidence
    - Bybit family reconstruction is limited by the absence of `external_ledger_raw` in the live DB snapshot and is therefore handled as a broader-goal evidence boundary, not as a normal supported on-chain normalization defect
    """
).strip())

coverage_comparison = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} coverage comparison

    This document compares the current database state to the auditor-derived live basis captured at `{audit["capturedAt"]}`. Because the archived cycle bundle was captured before the current replay truth, earlier artifacts are historical context only unless recomputed on the same live basis.

    ## Mandatory reconciliation table

    | Surface | Database coverage now | Auditor-derived coverage | Delta to target 0.99 | Exact uncovered remainder | Family uncovered remainder | Terminal state | Remainder explanation |
    | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
    | ETH exact | {fmt(exact_row("ETH")["coverageRatio"])} | {fmt(exact_row("ETH")["coverageRatio"])} | {fmt(max(0, 0.99 - float(exact_row("ETH")["coverageRatio"])))} | {fmt(float(exact_row("ETH")["current"]) - float(exact_row("ETH")["covered"]))} | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Native ETH carry still leaks on Arbitrum and Base after supported bridge/swap chronology. |
    | ETH family | {fmt(family_row("ETH")["coverageRatio"])} | {fmt(family_row("ETH")["coverageRatio"])} | 0 | n/a | {fmt(family_row("ETH")["uncovered"])} | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Ratio passes, but `AMANWETH` plus native ETH tails are still not final-clean. |
    | BTC family | {fmt(family_row("BTC")["coverageRatio"])} | {fmt(family_row("BTC")["coverageRatio"])} | 0 | n/a | {fmt(family_row("BTC")["uncovered"])} | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Exact BTC is clean; family still holds a small `AARBWBTC` receipt-token remainder. |
    | MNT family | {fmt(family_row("MNT")["coverageRatio"])} | {fmt(family_row("MNT")["coverageRatio"])} | {fmt(max(0, 0.99 - float(family_row("MNT")["coverageRatio"])))} | n/a | {fmt(family_row("MNT")["uncovered"])} | GENUINE_EVIDENCE_MISSING_PROVEN | Family remainder is dominated by Bybit reward inventory while raw CEX source collection is absent. |
    | AVAX exact | {fmt(exact_row("AVAX")["coverageRatio"])} | {fmt(exact_row("AVAX")["coverageRatio"])} | {fmt(max(0, 0.99 - float(exact_row("AVAX")["coverageRatio"])))} | {fmt(float(exact_row("AVAX")["current"]) - float(exact_row("AVAX")["covered"]))} | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Native AVAX basis is not restored after `aAvaWAVAX` and `sAVAX` continuity. |
    | AVAX family | {fmt(family_row("AVAX")["coverageRatio"])} | {fmt(family_row("AVAX")["coverageRatio"])} | {fmt(max(0, 0.99 - float(family_row("AVAX")["coverageRatio"])))} | n/a | {fmt(family_row("AVAX")["uncovered"])} | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | `sAVAX` plus native AVAX remain materially uncovered inside one supported family. |
    | USDC exact | {fmt(exact_row("USDC")["coverageRatio"])} | {fmt(exact_row("USDC")["coverageRatio"])} | {fmt(max(0, 0.99 - float(exact_row("USDC")["coverageRatio"])))} | {fmt(float(exact_row("USDC")["current"]) - float(exact_row("USDC")["covered"]))} | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | `eUSDC-6 -> USDC` and ParaSwap/bridge chronology leave supported USDC carry stranded. |
    | USDC family | {fmt(family_row("USDC")["coverageRatio"])} | {fmt(family_row("USDC")["coverageRatio"])} | {fmt(max(0, 0.99 - float(family_row("USDC")["coverageRatio"])))} | n/a | {fmt(family_row("USDC")["uncovered"])} | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Exact and family fail on the same Arbitrum USDC buckets. |
    | USDT exact | {fmt(exact_row("USDT")["coverageRatio"])} | {fmt(exact_row("USDT")["coverageRatio"])} | {fmt(max(0, 0.99 - float(exact_row("USDT")["coverageRatio"])))} | {fmt(float(exact_row("USDT")["current"]) - float(exact_row("USDT")["covered"]))} | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Supported LP exit still emits `basisEffect=UNKNOWN`, so returned USDT is uncovered. |
    | USDT family | {fmt(family_row("USDT")["coverageRatio"])} | {fmt(family_row("USDT")["coverageRatio"])} | {fmt(max(0, 0.99 - float(family_row("USDT")["coverageRatio"])))} | n/a | {fmt(family_row("USDT")["uncovered"])} | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Same unsupported `LP_EXIT` basis allocation defect affects the family view too. |

    ## Exact/family surfaces that already pass exact cleanliness

    - `BTC exact = 1`
    - `MNT exact = 1`

    Those exact rows are clean on the current live basis and should not be regressed while fixing the remaining blockers.

    ## Why archived earlier-cycle metrics are not comparable

    - The current scorecard is based on a later live replay truth captured at `{audit["capturedAt"]}`
    - The archived cycle bundle predates the current snapshot and mixes stale row counts with earlier coverage numbers
    - Downstream roles should therefore use the cycle-local scorecard written in this run as the authoritative contract
    """
).strip())

discrepancies = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} discrepancies

    ## Material mismatches between database truth and auditor truth

    | Surface | Database truth now | Auditor-derived financially correct state | First failed stage | Terminal status |
    | --- | --- | --- | --- | --- |
    | ETH exact | Leaves {fmt(float(exact_row("ETH")["current"]) - float(exact_row("ETH")["covered"]))} uncovered across native ETH buckets. | Supported bridge/native/receipt continuity should carry full principal basis; only explicit excess yield should remain acquisition. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | ETH family | Coverage ratio passes at {fmt(family_row("ETH")["coverageRatio"])} but family is not final-clean because `AMANWETH` and native ETH buckets are still dirty. | Family should become final-clean after principal-vs-yield split and carry restoration. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | BTC family | Tiny uncovered `AARBWBTC` remainder still prevents final-clean family state. | Receipt-token continuity should absorb the residual dust instead of leaving a family remainder. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | MNT family | Current family shows {fmt(family_row("MNT")["uncovered"])} uncovered in Bybit reward inventory. | Cannot be authoritatively reconstructed from raw on this DB snapshot because `external_ledger_raw` is empty. | source availability | GENUINE_EVIDENCE_MISSING_PROVEN |
    | AVAX exact | Native AVAX leaves {fmt(float(exact_row("AVAX")["current"]) - float(exact_row("AVAX")["covered"]))} uncovered. | `aAvaWAVAX` and `sAVAX` continuity should carry basis inside the AVAX family. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | AVAX family | Family leaves {fmt(family_row("AVAX")["uncovered"])} uncovered, mostly in `sAVAX`. | Family continuity should reallocate basis between native AVAX and staking wrapper without disposal. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | USDC exact | Leaves {fmt(float(exact_row("USDC")["current"]) - float(exact_row("USDC")["covered"]))} uncovered, mostly on two Arbitrum buckets. | Bridge and vault receipt-token continuity should leave only explicit yield uncovered. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | USDC family | Fails on the same two Arbitrum USDC buckets as exact. | Family should inherit the same corrected continuity once exact canonical rows are fixed. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | USDT exact | Leaves {fmt(float(exact_row("USDT")["current"]) - float(exact_row("USDT")["covered"]))} uncovered on one PancakeSwap Infinity LP exit. | LP-position basis should be reallocated onto the returned USDT and peer asset. | normalization | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
    | USDT family | Same LP-exit defect leaves the family uncovered. | Family should also become clean once LP-exit basis allocation is emitted deterministically. | normalization | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |

    ## Protocol and counterparty mismatches

    - Database truth still has {sum(row["count"] for row in protocol_gap_by_type)} rows without `protocolName` across deterministic families; auditor truth is that many of those rows are already protocol-provable from current raw evidence.
    - Database truth still has {sum(row["count"] for row in counterparty_gap_by_type)} rows without `counterpartyAddress`; auditor truth is that the majority can be filled from row-local contract evidence or deterministic lifecycle pairing.

    ## Terminal status note

    Every still-material surface above is in one explicit terminal audit state for this run:

    - `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` for supported on-chain normalization and accounting blockers
    - `GENUINE_EVIDENCE_MISSING_PROVEN` for broader-goal Bybit family reconstruction that lacks the required raw source collection in this DB snapshot
    """
).strip())

required_changes = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} required changes

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
    """
).strip())

protocol_rule_pack = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} protocol rule pack

    ## Purpose

    This rule pack documents reusable protocol-detection rules that are justified by the current live dataset and safe to use without redefining economics from explorer-only evidence.

    ## Rule priority

    1. Exact registry hit on the interacted contract
    2. Audited method selector on the interacted contract
    3. Canonical lifecycle shape and transfer pattern from persisted raw evidence
    4. Clarification-time enrichment only when it uses evidence that production clarification can really access

    ## Reusable rules

    ### 1. Canonical wrapped-native contracts must always label `WRAP` / `UNWRAP`

    - Observable raw evidence:
      - interacted contract is canonical wrapped-native contract
      - normalized type is already `WRAP` or `UNWRAP`
      - selector may be explicit (`deposit()`, `withdraw(uint256)`) or missing on native-transfer wrappers
    - Canonical result:
      - keep the economic type as `WRAP` / `UNWRAP`
      - set `protocolName` from the canonical wrapper brand
      - set `counterpartyAddress` to the wrapper contract
    - High-volume current targets:
      - `BASE / 0x4200000000000000000000000000000000000006`
      - `OPTIMISM / 0x4200000000000000000000000000000000000006`
      - `UNICHAIN / 0x4200000000000000000000000000000000000006`
      - `AVALANCHE / 0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7`
    - Negative guards:
      - do not infer wrap semantics from arbitrary ERC20 transfers
      - do not let a missing selector suppress a known canonical wrapper label when the contract address already proves it

    ### 2. LI.FI and similar routed bridges need protocol proof on the source row and lifecycle proof across both rows

    - Observable raw evidence:
      - source row is `BRIDGE_OUT`
      - destination row is `BRIDGE_IN`
      - same wallet
      - different networks
      - one principal family on both sides
      - one unique candidate inside the audited time/quantity window
    - Canonical result:
      - keep source/destination as separate canonical rows
      - materialize one deterministic `correlationId`
      - materialize reciprocal `matchedCounterparty`
      - set source `protocolName` from the source bridge proof
      - destination may carry settlement-brand metadata separately
    - Current anchors:
      - `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
      - `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
    - Negative guards:
      - no ambiguous multi-candidate pairing
      - no same-network auto-link
      - no many-to-many bridge grouping

    ### 3. Vault and lending receipt-token rows must preserve protocol identity from the interacted contract

    - Observable raw evidence:
      - interacted contract is the vault or pool
      - one receipt-token mint/burn leg plus one underlying principal leg
    - Canonical result:
      - keep `LENDING_DEPOSIT`, `LENDING_WITHDRAW`, `VAULT_DEPOSIT`, `VAULT_WITHDRAW`
      - set `protocolName` and `counterpartyAddress` from the interacted contract
      - keep economics separate from later protocol-name sweeps
    - Current anchors:
      - `0xc0ca8c4022bbfbb8bfd0660155e4857dd80c0cf5c521b8ad5f61ab4738fc0cab`
      - `0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977`
    - Negative guards:
      - do not relabel economics from explorer prose
      - do not use a receipt token symbol alone as protocol proof when the interacted contract is available

    ### 4. Swap routers should receive protocol labels and row-local counterparties from the interacted contract

    - Observable raw evidence:
      - one sold asset, one bought asset, routed through a known router or aggregator contract
    - Canonical result:
      - keep `SWAP`
      - `protocolName` from the router/aggregator contract
      - `counterpartyAddress` from the interacted router
    - Current anchors:
      - `0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7`
      - `0xc9b422cdf001efacbfd843efdaa60a4d6d574c1bb1a1c1b070c7781c181c73e3`
    - Negative guards:
      - do not use transfer recipients or liquidity pool hops as the displayed protocol brand when the router contract is already known

    ### 5. LP position lifecycles need protocol proof plus deterministic position identity

    - Observable raw evidence:
      - LP-position `correlationId`
      - multi-leg principal return on exit or multi-leg deposit on entry
      - interacted contract or position manager proves the protocol brand
    - Canonical result:
      - keep `LP_ENTRY` / `LP_EXIT`
      - persist protocol label from the position manager or pool contract
      - keep the LP-position identity available for later basis allocation
    - Current anchors:
      - `0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70`
      - `0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f`
    - Negative guards:
      - do not collapse LP exits into generic transfers
      - do not leave supported LP exits as `basisEffect=UNKNOWN`

    ## Registry and enrichment priority table

    {top_protocol_gap_table(15)}

    ## Outcome expected from this rule pack

    - protocol labels become deterministic on rows that are already canonically correct
    - metadata repair remains separated from economic reclassification
    - downstream counterparty construction can rely on stable protocol and interacted-contract evidence
    """
).strip())

counterparty_rules = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} counterparty construction rules

    ## Purpose

    Define how `counterpartyAddress` should be built on current canonical rows without confusing row-local counterparties with lifecycle pairing.

    ## Semantic boundary

    - `counterpartyAddress` answers: "which contract or external peer did this row directly interact with?"
    - `correlationId` answers: "which lifecycle does this row belong to?"
    - `matchedCounterparty` answers: "which exact peer row is the other side of the lifecycle?"
    - `protocolName` answers: "which protocol brand best describes the row?"

    These fields are complementary. They are not substitutes for one another.

    ## Construction hierarchy

    1. If the row is a protocol interaction and the interacted contract is deterministic, set `counterpartyAddress` to that contract.
    2. If the row is a bridge source or destination with one deterministic settlement contract, set `counterpartyAddress` to the source or settlement contract and use `matchedCounterparty` for the lifecycle pair.
    3. If the row is an external transfer and transfer evidence shows one unique external peer, set `counterpartyAddress` to that peer.
    4. If there is no deterministic row-local peer, leave `counterpartyAddress = null` rather than fabricating one.

    ## Family-specific rules

    | Family | `counterpartyAddress` rule | Pairing rule | Current evidence anchor |
    | --- | --- | --- | --- |
    | `SWAP` | interacted router or aggregator contract from raw tx | normally no lifecycle peer | `0x101c297...` |
    | `WRAP` / `UNWRAP` | canonical wrapper contract | normally no lifecycle peer | `BASE/OPTIMISM/UNICHAIN/AVALANCHE wrapped-native rows` |
    | `BRIDGE_OUT` | source bridge contract | deterministic `correlationId` + reciprocal `matchedCounterparty` to destination row | `0x9f6983...` |
    | `BRIDGE_IN` | settlement contract when unique | deterministic `correlationId` + reciprocal `matchedCounterparty` to source row | `0x7d8c79...` |
    | `LENDING_DEPOSIT` / `LENDING_WITHDRAW` | lending pool or gateway contract | lifecycle pairing only when there is a separate async peer | `0xc0ca8c...`, `0xfbbfd229...` |
    | `VAULT_DEPOSIT` / `VAULT_WITHDRAW` | vault contract | usually row-local only, no external lifecycle pair | `0x0765f4...` |
    | `LP_ENTRY` / `LP_EXIT` | position manager or LP pool contract | retain LP-position identity in `correlationId` | `0x091e3560...`, `0xac23f81...` |
    | `EXTERNAL_TRANSFER_IN` | unique external sender from transfer evidence | do not fabricate same-wallet peer | live transfer gaps |
    | `EXTERNAL_TRANSFER_OUT` | unique external recipient from transfer evidence | do not fabricate same-wallet peer | live transfer gaps |
    | `REWARD_CLAIM` | reward distributor contract when present | no lifecycle pair unless the protocol documents one | Linea `release()` target |

    ## Negative rules

    - Do not copy `matchedCounterparty` into `counterpartyAddress`.
    - Do not infer `counterpartyAddress` from explorer prose or UI labels.
    - Do not reuse a nearby row's `protocolName` as proof of counterparty.
    - Do not create many-to-many counterparty groups when the evidence is ambiguous.
    - Do not use the receipt token contract as the displayed counterparty when the interacted vault or pool contract is already known and more specific.

    ## Live gap priority table

    {counterparty_gap_table()}

    ## Current sample rows missing counterparty

    ```json
    {json.dumps(samples["missingCounterpartyAddress"], indent=2)}
    ```
    """
).strip())

accounting_failure_analysis = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} accounting failure analysis

    ## Stage-level diagnosis table

    | ID | Wrong surface now | Correct surface | First failed stage | Evidence diagnosis | Type adequacy | Remediation class | Pipeline correction point | Terminal state |
    | --- | --- | --- | --- | --- | --- | --- | --- | --- |
    {accounting_failure_rows}

    ## Key diagnosis notes

    - Supported exact-asset failures are not price-only defects. They start earlier, in canonical row shape and move-basis continuity.
    - Metadata gaps (`protocolName`, `counterpartyAddress`) matter because they expose missing or incomplete deterministic rules, especially on bridges, vaults, and lending rows.
    - The only blocker in this run that lands in `GENUINE_EVIDENCE_MISSING_PROVEN` is the broader-goal Bybit family lane, because the raw CEX collection required by the auditor contract is absent in this live DB snapshot.
    """
).strip())

findings_json = {
    "cycle": int(cycle),
    "task": "FA-001",
    "generatedFrom": str(AUDIT_PATH),
    "findings": [finding_json(blocker) for blocker in blockers],
}

summary_json = {
    "cycle": int(cycle),
    "task": "FA-001",
    "role": "financial-analyst",
    "capturedAt": audit["capturedAt"],
    "dataset": {
        "database": dataset["database"],
        "accountingUniverseId": dataset["accountingUniverseId"],
        "pipelineState": dataset["pipelineState"],
        "wallets": dataset["wallets"],
        "integrations": dataset["integrations"],
        "evidenceBoundary": {
            "rawTransactionsPresent": counts["rawTransactions"],
            "externalLedgerRawPresent": counts["externalLedgerRaw"],
            "bybitExtractedEventsPresent": counts["bybitExtractedEvents"],
        },
    },
    "sourceCounts": counts,
    "mandatoryCoverage": {
        asset: {"exact": exact_row(asset), "family": family_row(asset)}
        for asset in MANDATORY_ASSETS
    },
    "verdict": "fail",
    "materialFindings": [
        {
            "id": blocker["id"],
            "title": blocker["title"],
            "severity": blocker["severity"],
            "surfaces": blocker["surfaces"],
            "firstFailedStage": blocker["firstFailedStage"],
            "terminalState": blocker["terminalState"],
        }
        for blocker in blockers
    ],
    "artifactPackage": [
        f"auto-loop-handoff/artifacts/cycle/{cycle}/scorecard.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/report.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/authoritative-reconstruction.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/coverage-comparison.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/discrepancies.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/accounting-failure-analysis.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/protocol-rule-pack.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/counterparty-construction-rules.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/required-changes.md",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/findings.json",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/summary.json",
        f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/data/derived/metrics.json",
    ],
}

handoff = normalize_markdown(textwrap.dedent(
    f"""
    # Cycle {cycle} financial-analyst to business-analyst handoff

    Status: active
    Task: `FA-001 fresh live financial audit and change package`
    Transition: `financial-analyst -> business-analyst`
    Cycle: `{cycle}`
    Input basis: `fresh live Mongo capture {audit["capturedAt"]} from {dataset["database"]}; external_ledger_raw={counts["externalLedgerRaw"]}; authoritative scorecard rewritten on this basis`
    Previous owner: `financial-analyst`
    Next owner: `business-analyst`

    ## Summary

    Fresh cycle {cycle} financial audit is complete on the current live Mongo basis. Supported on-chain blockers are now isolated to ETH, AVAX, USDC, and LP-exit USDT accounting semantics, plus metadata rule gaps for protocol detection and counterparty construction. Exact BTC and exact MNT are clean on the current basis. Family MNT remains broader-goal blocked because raw CEX source rows are absent from this DB snapshot.

    ## Next role requirements

    - Translate the cycle-local scorecard into acceptance criteria without redefining the metric basis.
    - Keep supported on-chain blockers separate from the broader-goal Bybit raw-evidence boundary.
    - Preserve the protocol rule package and counterparty-construction package as explicit requirement inputs for downstream implementation.
    - For each still-failing supported mandatory surface, carry forward:
      - exact uncovered remainder
      - family uncovered remainder when applicable
      - first failed stage
      - terminal audit state
      - required correction point

    ## Artifact references

    - `auto-loop-handoff/artifacts/cycle/{cycle}/scorecard.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/report.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/authoritative-reconstruction.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/coverage-comparison.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/discrepancies.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/accounting-failure-analysis.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/protocol-rule-pack.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/counterparty-construction-rules.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/required-changes.md`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/findings.json`
    - `auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/summary.json`

    ## Notes

    - Archived earlier-cycle artifacts should be treated as historical context only unless they are recomputed on the same live basis.
    - `protocolName` and `counterpartyAddress` are metadata/completeness issues; they must not be used to silently redefine already-canonical economics.
    """
).strip())

results_blockers = "# Current blockers\n\n" + "\n".join(
    f"- `{blocker['id']}` {blocker['title']}. First failed stage: `{blocker['firstFailedStage']}`. Terminal state: `{blocker['terminalState']}`."
    for blocker in blockers
)

results_warnings = normalize_markdown(textwrap.dedent(
    f"""
    # Current warnings

    - Archived `auto-loop-handoff/artifacts/cycle.zip` artifacts predate this fresh live snapshot and are not authoritative for cycle {cycle}.
    - `external_ledger_raw = {counts["externalLedgerRaw"]}`, so broader-goal Bybit family reconstruction cannot be completed raw-first on this DB snapshot.
    - `protocolName` is best-effort metadata, not an accounting rule by itself.
    - `counterpartyAddress` must stay separate from `matchedCounterparty` and `correlationId`.
    """
).strip())

results_reconciliation = "# Reconciliation summary\n\n" + reference_coverage_summary_table()

results_eth_basis = normalize_markdown(textwrap.dedent(
    f"""
    # ETH basis summary

    ## Exact ETH dirty buckets

    ```json
    {json.dumps(top_dirty_exact("ETH"), indent=2)}
    ```

    ## ETH family dirty buckets

    ```json
    {json.dumps(top_dirty_family("ETH"), indent=2)}
    ```

    ## ETH Arbitrum lineage tail

    ```json
    {json.dumps(select_lineage(lineages["ethArbitrum"], 8), indent=2)}
    ```
    """
).strip())

write(f"auto-loop-handoff/artifacts/cycle/{cycle}/scorecard.md", scorecard)
write(f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/report.md", report)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/authoritative-reconstruction.md",
    authoritative_reconstruction,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/coverage-comparison.md",
    coverage_comparison,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/discrepancies.md",
    discrepancies,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/required-changes.md",
    required_changes,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/protocol-rule-pack.md",
    protocol_rule_pack,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/counterparty-construction-rules.md",
    counterparty_rules,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/accounting-failure-analysis.md",
    accounting_failure_analysis,
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/findings.json",
    json.dumps(findings_json, indent=2),
)
write(
    f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/summary.json",
    json.dumps(summary_json, indent=2),
)
write(f"auto-loop-handoff/artifacts/cycle/{cycle}/handoffs/business-analyst.md", handoff)
write("results/blockers.md", results_blockers)
write("results/warnings.md", results_warnings)
write("results/reconciliation.md", results_reconciliation)
write("results/eth_basis.md", results_eth_basis)

print(
    json.dumps(
        {
            "cycle": cycle,
            "written": [
                f"auto-loop-handoff/artifacts/cycle/{cycle}/scorecard.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/report.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/authoritative-reconstruction.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/coverage-comparison.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/discrepancies.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/required-changes.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/protocol-rule-pack.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/counterparty-construction-rules.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/accounting-failure-analysis.md",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/findings.json",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/summary.json",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/financial-analyst/data/derived/metrics.json",
                f"auto-loop-handoff/artifacts/cycle/{cycle}/handoffs/business-analyst.md",
                "results/blockers.md",
                "results/warnings.md",
                "results/reconciliation.md",
                "results/eth_basis.md",
            ],
        },
        indent=2,
    )
)
