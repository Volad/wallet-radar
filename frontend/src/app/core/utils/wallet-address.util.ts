/**
 * Multi-domain wallet address utilities.
 * Supports EVM (0x…), Solana (base58), and TON (UQ/EQ friendly + raw).
 * Case-preservation: EVM → lowercase; Solana/TON → original case.
 */

export type WalletAddressDomain = 'EVM' | 'SOLANA' | 'TON' | 'UNKNOWN';

const EVM_PATTERN = /^0x[a-fA-F0-9]{40}$/iu;
const SOLANA_PATTERN = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/u;
const TON_FRIENDLY_PATTERN = /^(?:UQ|EQ|Ef|kQ)[A-Za-z0-9_-]{46}$/u;
const TON_RAW_PATTERN = /^-?[0-9]+:[0-9a-fA-F]{64}$/u;

export function detectWalletDomain(address: string): WalletAddressDomain {
  if (!address || !address.trim()) return 'UNKNOWN';
  const a = address.trim();
  if (EVM_PATTERN.test(a)) return 'EVM';
  if (TON_FRIENDLY_PATTERN.test(a) || TON_RAW_PATTERN.test(a)) return 'TON';
  if (SOLANA_PATTERN.test(a)) return 'SOLANA';
  return 'UNKNOWN';
}

export function isValidWalletAddress(address: string): boolean {
  return detectWalletDomain(address) !== 'UNKNOWN';
}

/** EVM → lowercase; Solana/TON → preserve case. */
export function normalizeWalletAddress(address: string): string {
  if (!address) return address;
  const trimmed = address.trim();
  return detectWalletDomain(trimmed) === 'EVM' ? trimmed.toLowerCase() : trimmed;
}

/** Network IDs to assign based on domain. EVM returns [] — caller provides the full EVM list. */
export function networksForDomain(domain: WalletAddressDomain): readonly string[] {
  switch (domain) {
    case 'SOLANA': return ['SOLANA'];
    case 'TON': return ['TON'];
    default: return [];
  }
}
