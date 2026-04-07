# Blockers

| ID | Status | Summary | Evidence |
| --- | --- | --- | --- |
| B-24 | RESOLVED | Clarification reasons now match the actually missing receipt-safe fields in the live snapshot. | `PENDING_CLARIFICATION=300`; persisted reasons show `MISSING_EXECUTION_STATUS=300` and `MISSING_EFFECTIVE_GAS_PRICE=300`; raw-side missing counts match exactly |
| B-25 | RESOLVED | The previous `BSC` coverage gap remains closed in the current live snapshot. | raw `BSC=33`; normalized `BSC=33`; no new missing approve-only rows appeared |
| B-26 | OPEN-NONBLOCKING | A small intentional review tail remains for explicit promo/phishing, no-evidence router containers, and claim-without-movement rows. | `PROMO_SPAM_PHISHING=136`; `ROUTER_METHOD_OVERLOAD_UNSUPPORTED=7`; `CLAIM_WITHOUT_MOVEMENT=9`; these rows are explicit review by design, not broad normalization failure |
| B-27 | OPEN-NONBLOCKING | Residual `CLASSIFICATION_FAILED` families still exist, but they are narrow and no longer represent systemic classifier collapse. | `CLASSIFICATION_FAILED=23`; top groups are Avalanche `0xc16ae7a4=4`, Mantle `0x5d4df3bf=2`, plus isolated one-offs |
