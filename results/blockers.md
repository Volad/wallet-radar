# Current blockers

- `FA76-B1` ETH exact carry leak and ETH-family receipt-token continuity are not final-clean. First failed stage: `normalization + move_basis`. Terminal state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`.
- `FA76-B2` AVAX native and family continuity still lose basis across receipt-token and staking-wrapper transitions. First failed stage: `move_basis`. Terminal state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`.
- `FA76-B3` USDC exact and family continuity remain broken on Arbitrum vault and bridge corridors. First failed stage: `normalization + move_basis`. Terminal state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`.
- `FA76-B5` Protocol detection is materially incomplete on rows where the protocol is already provable from current raw evidence. First failed stage: `protocol enrichment`. Terminal state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`.
- `FA76-B6` Counterparty construction is incomplete on core bridge, swap, lending, and vault families. First failed stage: `clarification + linking`. Terminal state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`.
- `FA76-B8` Bybit family reconstruction remains broader-goal blocked because the live DB snapshot has no raw CEX source collection. First failed stage: `source availability`. Terminal state: `GENUINE_EVIDENCE_MISSING_PROVEN`.
