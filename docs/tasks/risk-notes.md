# Risk notes (for implementation)

- **2-year "young" vs "older":** Definition of "wallet history within 2 years" must be fixed (e.g. "oldest event in wallet×network within last 2 years" or "hasIncompleteHistory false and oldest event < 2 years ago"). Tests and reconciliation logic should use the same rule. Ask system-architect if not defined in docs.
- **Tolerance ε:** Value and definition (absolute vs relative) for MATCH vs MISMATCH should be in config or ADR so tests and backend use the same ε.
- **OPEN-01 (InternalTransferReclassifier):** If "replay both wallets" vs "destination only" is not yet decided, tasks for backfill and internal transfer should explicitly depend on that decision and test both wallets' positions.
- **Override vs manual:** Override applies only to on-chain events; manual compensating events use their own `priceUsd`. Tests should cover mixed timeline (override on BUY + manual compensating SELL) to ensure no cross-application.
