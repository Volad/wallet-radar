# 124 — Same-Universe Raw Peer Repair Closeout

Date: 2026-04-09
Owner slice: backend / normalization / clarification
Source audit: `results/stats/18/internal-transfer-clarification-verification.md`

## Problem

After run 18, reciprocal same-tx internal transfers promoted correctly, but the
live tail still contained one-sided rows such as:

- `0xffc959c27972e84a0e69860e9ed312dce3db85aa6e23f2e90f22e7969b447ca1`
- `0x3c011394be8b112beee14ece4a7eea3686ebee88015a4ed1f074edd4b96cafc7`
- `0xe2bf4c4ffb6de1ce01245768c4e672294d3ef8211e8a4af7720c4a28e5c28646`
- `0x4fa1f2a24b92cd234615050fb1cf3d48d4d7827bd7060a70c04bff726547e3c5`
- `0x7e5e74439c3e4c246ecb5093762d21fc3fd7d47c62d6a8069e3e7bed75b9c0ec`
- `0x04b1a5790c6f9aa72f19353f2226dab48d0e4630e679d7a18ad7b8ef9022c600`

All had a tracked counterparty, but only one wallet-local raw row existed.
Clarification therefore had no reciprocal canonical peer to promote.

## Root cause

This is an upstream raw-coverage omission for simple same-universe native
transfers:

- current raw already proves sender, recipient, value, and gas
- but only one wallet-local raw document landed in `raw_transactions`

## Decision

Prefer rerunnable raw repair over synthetic normalized-row fabrication.

Implemented policy:

1. during on-chain normalization, inspect the current raw batch
2. if a row proves a simple direct native transfer between two wallets in the
   same `accounting_universe`
3. and the opposite wallet-local raw row is missing
4. clone the raw row for the missing wallet with `normalizationStatus=PENDING`
5. let ordinary normalization + clarification promotion rebuild the canonical
   pair

## Implemented scope

### P0 raw peer repair

- add `InternalTransferRawPeerRepairService`
- supported lane:
  - direct native transfer
  - positive `value`
  - `methodId = 0x`
  - no token transfers
  - no internal transfers
  - sender and recipient are hex wallets
  - sender and recipient share one `accounting_universe`
  - opposite wallet-local raw row absent

### P0 owner-scope hardening

- reciprocal `INTERNAL_TRANSFER` promotion now also requires same-universe
  membership
- reciprocal tracked rows across different universes may not auto-promote

## Acceptance criteria

- audited one-sided tails above rebuild into reciprocal canonical rows after an
  on-chain rerun
- clarification then promotes them into `INTERNAL_TRANSFER`
- replay transfers basis through those repaired pairs
- tracked-wallet evidence across different universes does not promote or repair
  as owner continuity
