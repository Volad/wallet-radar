package com.walletradar.platform.networks.solana.metaplex;

import com.walletradar.platform.networks.solana.SolanaBase58;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Derives the canonical Metaplex Token Metadata PDA for an SPL mint (program
 * {@code metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s}, seeds {@code ["metadata", metadataProgram,
 * mint]}). The address is a pure function of the mint, so no RPC is needed to compute it.
 *
 * <p>Mirrors Solana's {@code findProgramAddress}: hash {@code sha256(seeds || bump || programId ||
 * "ProgramDerivedAddress")} with {@code bump} counting down from 255, returning the first digest
 * that is <b>off</b> the Ed25519 curve (the canonical PDA). Never throws — an invalid mint resolves
 * to {@link Optional#empty()}.</p>
 */
public final class MetaplexMetadataPda {

    /** Metaplex Token Metadata program id. */
    public static final String METADATA_PROGRAM_ID = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s";

    private static final byte[] SEED_PREFIX = "metadata".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDA_MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROGRAM_BYTES = SolanaBase58.decode(METADATA_PROGRAM_ID);
    private static final int PUBKEY_LENGTH = 32;
    private static final int MAX_BUMP = 255;

    private MetaplexMetadataPda() {
    }

    /** @return the base58 metadata PDA for the given mint, or empty when the mint is not a valid pubkey. */
    public static Optional<String> metadataAddress(String mint) {
        if (mint == null || mint.isBlank()) {
            return Optional.empty();
        }
        byte[] mintBytes;
        try {
            mintBytes = SolanaBase58.decode(mint.trim());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (mintBytes.length != PUBKEY_LENGTH) {
            return Optional.empty();
        }
        for (int bump = MAX_BUMP; bump >= 0; bump--) {
            byte[] candidate = hash(mintBytes, (byte) bump);
            if (!Ed25519OnCurve.isOnCurve(candidate)) {
                return Optional.of(SolanaBase58.encode(candidate));
            }
        }
        return Optional.empty();
    }

    private static byte[] hash(byte[] mintBytes, byte bump) {
        MessageDigest digest = sha256();
        digest.update(SEED_PREFIX);
        digest.update(PROGRAM_BYTES);
        digest.update(mintBytes);
        digest.update(bump);
        digest.update(PROGRAM_BYTES);
        digest.update(PDA_MARKER);
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for Solana PDA derivation", ex);
        }
    }
}
