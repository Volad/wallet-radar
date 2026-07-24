package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient;
import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient.MeteoraPool;
import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient.MeteoraToken;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient.RaydiumPool;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient.RaydiumToken;
import com.walletradar.application.liquiditypools.enrichment.solana.SolanaBase58;
import com.walletradar.application.liquiditypools.enrichment.solana.SolanaLpChainClient;
import com.walletradar.application.liquiditypools.enrichment.solana.SolanaLpChainClient.OnChainAccount;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.application.normalization.pipeline.solana.JupiterSplTokenMetadataResolver;
import com.walletradar.application.normalization.pipeline.solana.SolanaProgramIds;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SolanaLpPositionReaderTest {

    private static final String SOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String COM_MINT = "7F5Mq4Zjed4gYr5CE4tp7Yt2XdWvJZ7fpQz5tW8yopump";
    private static final String POOL = "5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6";
    private static final byte[] POOL_RAW = fromHex("013df47652b6dd4eb238be8ab232f0ee940508cb2e3540669cff004fa6711043");

    // The meteora-dlmm correlation pubkey is the on-chain PositionV2 account PDA (NOT the pool).
    private static final String METEORA_POSITION_PDA = "Agp2NtFdQFrCoom3Xx6yfXidk2pPRr3ntqr1Su6qc57Y";
    private static final String METEORA_CORR = "lp-position:solana:meteora-dlmm:" + METEORA_POSITION_PDA;
    // The lbPair (pool) pointer stored at offset 8 of the PositionV2 account decodes to this address.
    private static final byte[] LBPAIR_RAW = fromHex("013df47652b6dd4eb238be8ab232f0ee940508cb2e3540669cff004fa6711043");
    private static final String METEORA_POOL = SolanaBase58.encode(LBPAIR_RAW);
    private static final String RAYDIUM_NFT_ACCOUNT = "GycWS7LkeXhBQ7xdH2W4vMYypjVwD6DfZQ8ENy66txvF";
    private static final String RAYDIUM_CORR = "lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT;

    private final SolanaLpChainClient chainClient = mock(SolanaLpChainClient.class);
    private final MeteoraDlmmApiClient meteoraClient = mock(MeteoraDlmmApiClient.class);
    private final RaydiumClmmApiClient raydiumClient = mock(RaydiumClmmApiClient.class);
    private final JupiterSplTokenMetadataResolver metadataResolver = mock(JupiterSplTokenMetadataResolver.class);

    private SolanaLpPositionReader reader() {
        return new SolanaLpPositionReader(chainClient, meteoraClient, raydiumClient, metadataResolver);
    }

    private static LpPositionContext context(String correlationId) {
        return context(correlationId, null, false);
    }

    private static LpPositionContext context(String correlationId, String lpPoolAddress, boolean closed) {
        return new LpPositionContext(correlationId, "universe-1", NetworkId.SOLANA,
                "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG", null, "CL_NFT",
                null, null, null, null, null, lpPoolAddress, closed, false, null);
    }

    @Test
    void supportsSolanaMeteoraAndRaydiumCorrelations() {
        SolanaLpPositionReader reader = reader();
        assertThat(reader.supports(context(METEORA_CORR))).isTrue();
        assertThat(reader.supports(context(RAYDIUM_CORR))).isTrue();
    }

    @Test
    void doesNotSupportEvmOrClosedOrOtherNetworks() {
        SolanaLpPositionReader reader = reader();
        assertThat(reader.supports(context("lp-position:base:0xnfpm:1"))).isFalse();
        LpPositionContext evmNetwork = new LpPositionContext(METEORA_CORR, "u", NetworkId.ETHEREUM,
                "w", null, "CL_NFT", null, null, null, null, null, null, false, false, null);
        assertThat(reader.supports(evmNetwork)).isFalse();
        // A closed Meteora context WITHOUT a captured LbPair is not supported (pair unrecoverable).
        LpPositionContext closedNoPool = new LpPositionContext(METEORA_CORR, "u", NetworkId.SOLANA,
                "w", null, "CL_NFT", null, null, null, null, null, null, true, false, null);
        assertThat(reader.supports(closedNoPool)).isFalse();
    }

    @Test
    void supportsClosedMeteoraWhenLbPairCaptured() {
        // A closed Meteora position WITH a captured LbPair opts in: the shared LbPair pool persists
        // on-chain and lets the reader resolve the pair even though the position PDA is deallocated.
        SolanaLpPositionReader reader = reader();
        assertThat(reader.supports(context(METEORA_CORR, METEORA_POOL, true))).isTrue();
        // Raydium never captures an LbPair, so a closed Raydium context is still not supported.
        assertThat(reader.supports(context(RAYDIUM_CORR, METEORA_POOL, true))).isFalse();
    }

    @Test
    void meteoraDecodesPositionPdaToLbPairAndResolvesPair() {
        // The correlation pubkey is a PositionV2 PDA; getAccountInfo returns the account whose
        // lbPair@offset-8 base58-decodes to METEORA_POOL. fetchPoolResult MUST be called with the
        // DECODED pool address, not the PDA.
        when(chainClient.getAccountInfo(METEORA_POSITION_PDA))
                .thenReturn(Optional.of(new OnChainAccount("Owner1111", positionV2Account(LBPAIR_RAW))));
        MeteoraPool pool = new MeteoraPool(
                "COM-USDT",
                new MeteoraToken(COM_MINT, "COM", 6),
                new MeteoraToken(USDT_MINT, "USDT", 6),
                new BigDecimal("4.58e-6"), null, null);
        when(meteoraClient.fetchPoolResult(METEORA_POOL)).thenReturn(
                new MeteoraDlmmApiClient.MeteoraPoolResult(
                        MeteoraDlmmApiClient.Availability.RESOLVED, Optional.of(pool)));
        when(metadataResolver.resolveSymbol(anyString())).thenReturn(Optional.empty());

        LpPositionSnapshot snapshot = reader().read(context(METEORA_CORR)).orElseThrow();

        assertThat(snapshot.getProtocol()).isEqualTo("Meteora DLMM");
        assertThat(snapshot.getToken0().getSym()).isEqualTo("COM");
        assertThat(snapshot.getToken0().getContract()).isEqualTo(COM_MINT);
        assertThat(snapshot.getToken1().getSym()).isEqualTo("USDT");
        assertThat(snapshot.getToken1().getContract()).isEqualTo(USDT_MINT);
        assertThat(snapshot.getStatus()).isEqualTo("in_range");
        assertThat(snapshot.getSnapshotStale()).isFalse();
        assertThat(snapshot.getUnavailableReason()).isNull();
        assertThat(snapshot.getPriceCurrent()).isEqualByComparingTo("4.58e-6");
    }

    @Test
    void meteoraPrefersCapturedLbPairWithoutDecodingPositionPda() {
        // When the context carries the LbPair captured at normalization, the reader must fetch the
        // pool by that address directly and NOT call getAccountInfo to decode the position PDA.
        MeteoraPool pool = new MeteoraPool(
                "COM-USDT",
                new MeteoraToken(COM_MINT, "COM", 6),
                new MeteoraToken(USDT_MINT, "USDT", 6),
                new BigDecimal("4.58e-6"), null, null);
        when(meteoraClient.fetchPoolResult(METEORA_POOL)).thenReturn(
                new MeteoraDlmmApiClient.MeteoraPoolResult(
                        MeteoraDlmmApiClient.Availability.RESOLVED, Optional.of(pool)));
        when(metadataResolver.resolveSymbol(anyString())).thenReturn(Optional.empty());

        LpPositionSnapshot snapshot = reader().read(context(METEORA_CORR, METEORA_POOL, false)).orElseThrow();

        assertThat(snapshot.getToken0().getSym()).isEqualTo("COM");
        assertThat(snapshot.getToken1().getSym()).isEqualTo("USDT");
        assertThat(snapshot.getStatus()).isEqualTo("in_range");
        // No on-chain PDA decode when the LbPair is already captured.
        Mockito.verify(chainClient, Mockito.never()).getAccountInfo(anyString());
    }

    @Test
    void meteoraClosedPositionStillResolvesPairFromCapturedLbPair() {
        // The reported case: a CLOSED single-sided SOL deposit whose position PDA is deallocated.
        // With the LbPair captured at normalization the reader resolves the SOL/<SPL> pair and emits
        // a closed snapshot carrying it (TVL/fees zero, status closed) — no PDA decode.
        MeteoraPool pool = new MeteoraPool(
                "SOL-COM",
                new MeteoraToken(SOL_MINT, "WSOL", 9),
                new MeteoraToken(COM_MINT, "COM", 6),
                new BigDecimal("0.00012"), null, null);
        when(meteoraClient.fetchPoolResult(METEORA_POOL)).thenReturn(
                new MeteoraDlmmApiClient.MeteoraPoolResult(
                        MeteoraDlmmApiClient.Availability.RESOLVED, Optional.of(pool)));
        when(metadataResolver.resolveSymbol(anyString())).thenReturn(Optional.empty());

        LpPositionSnapshot snapshot = reader().read(context(METEORA_CORR, METEORA_POOL, true)).orElseThrow();

        assertThat(snapshot.getStatus()).isEqualTo("closed");
        assertThat(snapshot.getToken0().getSym()).isEqualTo("WSOL");
        assertThat(snapshot.getToken1().getSym()).isEqualTo("COM");
        assertThat(snapshot.getTvlUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.getUnclaimedFeesUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        Mockito.verify(chainClient, Mockito.never()).getAccountInfo(anyString());
    }

    @Test
    void meteoraReturnsClosedWhenPositionAccountReclaimed() {
        // Position PDA no longer exists on-chain (rent reclaimed) => position closed.
        when(chainClient.getAccountInfo(METEORA_POSITION_PDA)).thenReturn(Optional.empty());

        LpPositionSnapshot snapshot = reader().read(context(METEORA_CORR)).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo("closed");
    }

    @Test
    void meteoraReturnsEmptyWhenPositionDataTooShort() {
        // A truncated/undecodable account is transient; keep the existing shell (empty snapshot).
        when(chainClient.getAccountInfo(METEORA_POSITION_PDA))
                .thenReturn(Optional.of(new OnChainAccount("Owner1111", new byte[8])));

        assertThat(reader().read(context(METEORA_CORR))).isEmpty();
    }

    @Test
    void meteoraReturnsClosedWhenPoolNotFound() {
        // A durable HTTP 404 is the reliable closed signal for entry-only ghost positions.
        when(chainClient.getAccountInfo(METEORA_POSITION_PDA))
                .thenReturn(Optional.of(new OnChainAccount("Owner1111", positionV2Account(LBPAIR_RAW))));
        when(meteoraClient.fetchPoolResult(METEORA_POOL)).thenReturn(
                new MeteoraDlmmApiClient.MeteoraPoolResult(
                        MeteoraDlmmApiClient.Availability.NOT_FOUND, Optional.empty()));

        LpPositionSnapshot snapshot = reader().read(context(METEORA_CORR)).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo("closed");
    }

    @Test
    void meteoraReturnsEmptyWhenPoolUnavailable() {
        // A transient error must not mutate the position; keep the existing shell (empty snapshot).
        when(chainClient.getAccountInfo(METEORA_POSITION_PDA))
                .thenReturn(Optional.of(new OnChainAccount("Owner1111", positionV2Account(LBPAIR_RAW))));
        when(meteoraClient.fetchPoolResult(METEORA_POOL)).thenReturn(
                new MeteoraDlmmApiClient.MeteoraPoolResult(
                        MeteoraDlmmApiClient.Availability.UNAVAILABLE, Optional.empty()));

        assertThat(reader().read(context(METEORA_CORR))).isEmpty();
    }

    @Test
    void raydiumResolvesPoolTokensFromPersonalPositionState() {
        byte[] pps = raydiumPersonalPosition(-100, 100);
        when(chainClient.getTokenAccountMint(RAYDIUM_NFT_ACCOUNT)).thenReturn(Optional.of("NftMint1111"));
        when(chainClient.findProgramAccountData(eq(SolanaProgramIds.RAYDIUM_CLMM), anyInt(),
                anyString(), anyInt())).thenReturn(Optional.of(pps));
        when(raydiumClient.fetchPool(POOL)).thenReturn(Optional.of(new RaydiumPool(
                new RaydiumToken(SOL_MINT, "WSOL", 9),
                new RaydiumToken(USDC_MINT, "USDC", 6),
                new BigDecimal("76.1"))));
        when(metadataResolver.resolveSymbol(anyString())).thenReturn(Optional.empty());

        LpPositionSnapshot snapshot = reader().read(context(RAYDIUM_CORR)).orElseThrow();
        assertThat(snapshot.getProtocol()).isEqualTo("Raydium CLMM");
        assertThat(snapshot.getToken0().getSym()).isEqualTo("WSOL");
        assertThat(snapshot.getToken1().getSym()).isEqualTo("USDC");
        assertThat(snapshot.getStatus()).isEqualTo("in_range");
        assertThat(snapshot.getTickLower()).isEqualTo(-100);
        assertThat(snapshot.getTickUpper()).isEqualTo(100);
    }

    @Test
    void raydiumClosedWhenNftAccountReclaimed() {
        when(chainClient.getTokenAccountMint(RAYDIUM_NFT_ACCOUNT)).thenReturn(Optional.empty());
        LpPositionSnapshot snapshot = reader().read(context(RAYDIUM_CORR)).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo("closed");
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** Builds a Meteora DLMM PositionV2 account: discriminator(8) + lbPair(32) + owner(32). */
    private static byte[] positionV2Account(byte[] lbPairRaw) {
        byte[] data = new byte[72];
        System.arraycopy(lbPairRaw, 0, data, 8, 32);
        return data;
    }

    /** Builds a Raydium PersonalPositionState slice with poolId@41 and tickLower/Upper@73/77. */
    private static byte[] raydiumPersonalPosition(int tickLower, int tickUpper) {
        byte[] data = new byte[81];
        System.arraycopy(POOL_RAW, 0, data, 41, 32);
        writeInt32LE(data, 73, tickLower);
        writeInt32LE(data, 77, tickUpper);
        return data;
    }

    private static void writeInt32LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
