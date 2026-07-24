package com.walletradar.platform.networks.solana.metaplex;

import java.util.Optional;

/**
 * Reads on-chain Metaplex Token Metadata ({@code name} / {@code symbol}) for an SPL mint via the
 * shared Solana JSON-RPC transport (WS-7, ADR-073 secondary Solana tier).
 *
 * <p>Used as a fallback when the Jupiter Tokens API returns no symbol (long-tail / unverified
 * mints): the metadata is stored on-chain in a PDA derived from the mint, so it resolves a stable
 * symbol without any hand-maintained registry.</p>
 *
 * <p>Best-effort by contract: implementations must never throw. Any transport error, missing
 * metadata account, or malformed data resolves to {@link Optional#empty()}.</p>
 */
public interface MetaplexMetadataClient {

    /**
     * Resolves the on-chain Metaplex metadata for a single SPL mint.
     *
     * @param mint base58 SPL mint address (case-sensitive)
     * @return metadata when a metadata account exists and decodes, else {@link Optional#empty()}
     */
    Optional<MetaplexTokenMetadata> fetchMetadata(String mint);

    /** Minimal Metaplex metadata subset used by WalletRadar; either field may be {@code null}. */
    record MetaplexTokenMetadata(String name, String symbol) {
    }
}
