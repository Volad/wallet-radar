import { WalletDomain } from '../models/dashboard.models';

/**
 * Derives the wallet domain from an address string using the same grammar as the backend WalletRef parser.
 * Use this only when a domain field is not already available on the DTO.
 *
 * Prefer reading `position.domain` over calling this function.
 */
export function parseWalletDomain(address: string | null | undefined): WalletDomain | null {
  if (!address || address.trim().length === 0) {
    return null;
  }
  const trimmed = address.trim();
  if (trimmed.startsWith('0x') || trimmed.startsWith('0X')) {
    return 'EVM';
  }
  if (trimmed.includes(':') && trimmed.indexOf(':') > 0) {
    return 'CEX';
  }
  const tonPattern = /^(UQ|EQ|Ef|kQ)[A-Za-z0-9_-]{46}$|^-?[0-9]+:[0-9a-fA-F]{64}$/;
  if (tonPattern.test(trimmed)) {
    return 'TON';
  }
  return 'SOLANA';
}

/**
 * Returns true when the wallet address (or domain) is an on-chain (non-CEX) wallet.
 * Accepts either the raw address string or the pre-parsed domain.
 */
export function isOnChainAddress(addressOrDomain: string | WalletDomain | null | undefined): boolean {
  if (!addressOrDomain) {
    return false;
  }
  const domain = (addressOrDomain === 'EVM' || addressOrDomain === 'SOLANA' || addressOrDomain === 'TON' || addressOrDomain === 'CEX')
    ? addressOrDomain as WalletDomain
    : parseWalletDomain(addressOrDomain);
  return domain !== null && domain !== 'CEX';
}

/**
 * Returns true when the wallet address (or domain) is a CEX wallet.
 */
export function isCexAddress(addressOrDomain: string | WalletDomain | null | undefined): boolean {
  if (!addressOrDomain) {
    return false;
  }
  const domain = (addressOrDomain === 'EVM' || addressOrDomain === 'SOLANA' || addressOrDomain === 'TON' || addressOrDomain === 'CEX')
    ? addressOrDomain as WalletDomain
    : parseWalletDomain(addressOrDomain);
  return domain === 'CEX';
}

/**
 * Extracts the venue ID from a CEX wallet address string (e.g. "BYBIT:uid:FUND" → "BYBIT").
 * Returns null for on-chain addresses or empty/null input.
 */
export function parseVenueId(address: string | null | undefined): string | null {
  if (!address || address.trim().length === 0) {
    return null;
  }
  const trimmed = address.trim();
  const colonIdx = trimmed.indexOf(':');
  if (colonIdx <= 0) {
    return null;
  }
  return trimmed.slice(0, colonIdx).toUpperCase();
}

/**
 * Extracts the sub-account suffix from a CEX wallet address (e.g. "BYBIT:uid:FUND" → "FUND").
 * Returns null when there is no sub-account segment.
 */
export function parseSubAccount(address: string | null | undefined): string | null {
  if (!address || address.trim().length === 0) {
    return null;
  }
  const parts = address.trim().split(':');
  if (parts.length < 3) {
    return null;
  }
  const sub = parts[2].trim();
  return sub.length > 0 ? sub.toUpperCase() : null;
}
