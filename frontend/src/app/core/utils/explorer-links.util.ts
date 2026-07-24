/**
 * Returns a block-explorer URL for a given network address or transaction hash.
 * Returns null when no known explorer is configured for the network.
 */
export function getExplorerUrl(
  networkId: string,
  value: string,
  type: 'address' | 'tx' = 'address'
): string | null {
  switch (networkId) {
    case 'SOLANA':
      return type === 'tx'
        ? `https://solscan.io/tx/${value}`
        : `https://solscan.io/account/${value}`;
    case 'TON':
      return type === 'tx'
        ? `https://tonviewer.com/transaction/${value}`
        : `https://tonviewer.com/${value}`;
    case 'ETHEREUM':
      return type === 'tx'
        ? `https://etherscan.io/tx/${value}`
        : `https://etherscan.io/address/${value}`;
    case 'ARBITRUM':
      return type === 'tx'
        ? `https://arbiscan.io/tx/${value}`
        : `https://arbiscan.io/address/${value}`;
    case 'OPTIMISM':
      return type === 'tx'
        ? `https://optimistic.etherscan.io/tx/${value}`
        : `https://optimistic.etherscan.io/address/${value}`;
    case 'POLYGON':
      return type === 'tx'
        ? `https://polygonscan.com/tx/${value}`
        : `https://polygonscan.com/address/${value}`;
    case 'BASE':
      return type === 'tx'
        ? `https://basescan.org/tx/${value}`
        : `https://basescan.org/address/${value}`;
    case 'BSC':
      return type === 'tx'
        ? `https://bscscan.com/tx/${value}`
        : `https://bscscan.com/address/${value}`;
    case 'AVALANCHE':
      return type === 'tx'
        ? `https://snowtrace.io/tx/${value}`
        : `https://snowtrace.io/address/${value}`;
    case 'ZKSYNC':
      return type === 'tx'
        ? `https://explorer.zksync.io/tx/${value}`
        : `https://explorer.zksync.io/address/${value}`;
    case 'LINEA':
      return type === 'tx'
        ? `https://lineascan.build/tx/${value}`
        : `https://lineascan.build/address/${value}`;
    default:
      return null;
  }
}
