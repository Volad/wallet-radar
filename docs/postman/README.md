# Postman collection

**WalletRadar-API.postman_collection.json** — Postman Collection v2.1 for the WalletRadar API (see [04-api.md](../04-api.md)).

## Import

1. Open Postman → Import → Upload the `WalletRadar-API.postman_collection.json` file.
2. Variables: `baseUrl` defaults to `http://localhost:8080`. Set `walletAddress` and `jobId` as needed.

## Usage

- **Wallets:** Add wallet, get status, balance refresh.
- **Sync:** Trigger manual sync.
- **Assets:** Get asset list (and transaction history when implemented).
- **Transactions:** Manual compensating tx, override set/revert.
- **Portfolio:** Snapshots, asset chart.
- **Recalc:** Get recalc job status (use `jobId` from override or manual tx response).

Run backend with `./gradlew :backend:bootRun` and start dependencies with `docker compose up -d` (MongoDB).
