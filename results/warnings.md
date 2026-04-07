# Warnings

- clarification is currently disabled, so the `PENDING_CLARIFICATION=300` queue is expected to persist even though reason hygiene is now correct
- `NEEDS_REVIEW=182` is still non-trivial in absolute size, but the composition is now healthy: mostly promo/phishing and honest no-evidence tails
- live router-overload residuals are now only:
  - Optimism `0x416b433906b1b72fa758e166e239c43d68dc6f29 + 0xac9650d8 = 6`
  - Base `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364 + 0xac9650d8 = 1`
- no `zkSync` router-overload rows remain in the current snapshot
- current selector-recoverable review rows are down to `16` and are mostly no-transfer/no-log containers, not broad selector-parity failures
- `CLAIM_WITHOUT_MOVEMENT` remains financially correct per-wallet behavior and should not be force-collapsed just to shrink review volume
