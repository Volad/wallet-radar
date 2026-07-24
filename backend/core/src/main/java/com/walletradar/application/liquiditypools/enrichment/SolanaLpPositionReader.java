package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient;
import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient.MeteoraPool;
import com.walletradar.application.liquiditypools.enrichment.solana.MeteoraDlmmApiClient.MeteoraToken;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient.RaydiumPool;
import com.walletradar.application.liquiditypools.enrichment.solana.RaydiumClmmApiClient.RaydiumToken;
import com.walletradar.application.liquiditypools.enrichment.solana.SolanaBase58;
import com.walletradar.application.liquiditypools.enrichment.solana.SolanaLpChainClient;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.application.normalization.pipeline.solana.JupiterSplTokenMetadataResolver;
import com.walletradar.application.normalization.pipeline.solana.SolanaProgramIds;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * On-chain enrichment for Solana concentrated-liquidity LP positions: Meteora DLMM
 * ({@code lp-position:solana:meteora-dlmm:<positionPda>}) and Raydium CLMM
 * ({@code lp-position:solana:raydium-clmm:<positionNftAccount>}) — the correlation schemes produced
 * by {@code SolanaLpPositionResolver}.
 *
 * <p><b>Meteora DLMM.</b> The {@code meteora-dlmm} correlation pubkey is the on-chain
 * <b>{@code PositionV2} account PDA</b> (produced by {@code SolanaLpPositionResolver}), <b>not</b> the
 * LbPair pool address. Pool metadata (mints, symbols, decimals, current price) is resolved from the
 * free Meteora data API keyed by the LbPair pool address, obtained by preference order:
 * <ol>
 *   <li>the <b>LbPair pool address captured at normalization</b> and carried on
 *       {@link LpPositionContext#lpPoolAddress()} — the shared LbPair pool account persists on-chain
 *       even after the user's position PDA is reclaimed, so it resolves the pair for both open and
 *       <b>closed</b> positions with no read-path RPC; then</li>
 *   <li>(legacy fallback, open positions only) decoding the {@code PositionV2} account on-chain: its
 *       Anchor layout stores the {@code lbPair: Pubkey} at byte offset
 *       {@value #METEORA_POSITION_LBPAIR_OFFSET} (immediately after the 8-byte discriminator); the 32
 *       raw bytes are Base58-encoded to obtain the pool address. A missing account (rent reclaimed)
 *       means the position is closed.</li>
 * </ol>
 * A resolvable pool for an open position yields best-effort {@code in_range} (per-position bins /
 * amounts are not reconstructed from the pool alone); for a closed position it yields a
 * {@code closed} snapshot that still carries the resolved pair (TVL/fees zero). Closed detection is
 * otherwise handled upstream (A4 terminal {@code LP_EXIT} / {@code qtyHeld<=0}).</p>
 *
 * <p><b>Raydium CLMM.</b> The position NFT account yields the position NFT mint via
 * {@code getAccountInfo}; the {@code PersonalPositionState} (which carries pool id and tick range) is
 * resolved with a targeted {@code getProgramAccounts} memcmp on that mint. Pool metadata (mints,
 * symbols, decimals) comes from the free Raydium v3 API.</p>
 *
 * <p>Resilience mirrors the EVM readers: every failure resolves to {@link Optional#empty()} (caller
 * keeps the existing stale/shell snapshot) — this reader never throws. Base58 pubkeys are
 * case-sensitive and never lowercased. Token quantities are left unpriced here; TVL/fees pricing is
 * applied downstream by {@code LpPositionRefreshService.applyMarks}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SolanaLpPositionReader implements LpPositionReader {

    private static final String METEORA_PREFIX = "lp-position:solana:meteora-dlmm:";
    private static final String RAYDIUM_PREFIX = "lp-position:solana:raydium-clmm:";

    private static final String METEORA_PROTOCOL = "Meteora DLMM";
    private static final String RAYDIUM_PROTOCOL = "Raydium CLMM";

    private static final int PUBKEY_LEN = 32;

    // Meteora DLMM PositionV2 Anchor layout: discriminator(8) lbPair(32) owner(32) ...
    // The lbPair (the pool) pointer starts immediately after the 8-byte account discriminator.
    private static final int METEORA_POSITION_LBPAIR_OFFSET = 8;
    private static final int METEORA_POSITION_MIN_LEN = METEORA_POSITION_LBPAIR_OFFSET + PUBKEY_LEN;

    // Raydium CLMM PersonalPositionState layout: discriminator(8) bump(1) nftMint(32) poolId(32)
    // tickLowerIndex(i32) tickUpperIndex(i32) liquidity(u128) ...
    private static final int RAYDIUM_NFT_MINT_OFFSET = 9;
    private static final int RAYDIUM_POOL_ID_OFFSET = 41;
    private static final int RAYDIUM_TICK_LOWER_OFFSET = 73;
    private static final int RAYDIUM_TICK_UPPER_OFFSET = 77;
    private static final int RAYDIUM_PPS_SLICE_LEN = RAYDIUM_TICK_UPPER_OFFSET + Integer.BYTES;

    private final SolanaLpChainClient chainClient;
    private final MeteoraDlmmApiClient meteoraClient;
    private final RaydiumClmmApiClient raydiumClient;
    private final JupiterSplTokenMetadataResolver metadataResolver;

    @Override
    public boolean supports(LpPositionContext context) {
        if (context == null || context.networkId() != NetworkId.SOLANA) {
            return false;
        }
        String correlationId = context.correlationId();
        if (correlationId == null) {
            return false;
        }
        boolean meteora = correlationId.startsWith(METEORA_PREFIX);
        boolean raydium = correlationId.startsWith(RAYDIUM_PREFIX);
        if (context.closed()) {
            // A closed position is normally not re-read (live valuation is pointless). The one
            // exception: a closed Meteora DLMM position whose LbPair pool address was captured at
            // normalization can still resolve its SOL/<SPL> pair from the shared, persistent LbPair
            // pool — the position PDA is deallocated so the pair is otherwise unrecoverable. This lets
            // discovery write a closed snapshot that still carries the correct pair label.
            return meteora && hasCapturedLbPair(context);
        }
        return meteora || raydium;
    }

    private static boolean hasCapturedLbPair(LpPositionContext context) {
        return context.lpPoolAddress() != null && !context.lpPoolAddress().isBlank();
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        String correlationId = context.correlationId();
        if (correlationId.startsWith(METEORA_PREFIX)) {
            return readMeteora(context, correlationId.substring(METEORA_PREFIX.length()));
        }
        if (correlationId.startsWith(RAYDIUM_PREFIX)) {
            return readRaydium(context, correlationId.substring(RAYDIUM_PREFIX.length()));
        }
        return Optional.empty();
    }

    private Optional<LpPositionSnapshot> readMeteora(LpPositionContext context, String positionPda) {
        boolean closed = context.closed();

        // Prefer the LbPair pool address captured at normalization time (accounts[1] of the DLMM
        // liquidity leg). The LbPair pool account is shared and persists on-chain even after the
        // user's position PDA is closed/reclaimed, so it resolves the SOL/<SPL> pair for BOTH open
        // and closed positions and removes a read-path RPC decode. Only when it is absent (legacy
        // rows normalized before capture) do we fall back to decoding the position PDA on-chain —
        // which is impossible for a closed position (PDA deallocated).
        String poolAddress = hasCapturedLbPair(context) ? context.lpPoolAddress().trim() : null;
        if (poolAddress == null) {
            if (closed) {
                // No captured LbPair and the PDA is gone — the pair is unrecoverable; still emit a
                // closed snapshot (no pair) so downstream close detection stays consistent.
                return Optional.of(closedSnapshot(context, METEORA_PROTOCOL));
            }
            // The meteora-dlmm correlation pubkey is the on-chain PositionV2 account PDA, NOT the pool.
            // Decode it to its LbPair pool address before querying the Meteora data API.
            //  - account missing (rent reclaimed) => position closed (durable signal).
            //  - data too short / decode failure => transient; keep the existing shell (empty).
            Optional<SolanaLpChainClient.OnChainAccount> account = chainClient.getAccountInfo(positionPda);
            if (account.isEmpty()) {
                return Optional.of(closedSnapshot(context, METEORA_PROTOCOL));
            }
            byte[] data = account.get().data();
            if (data == null || data.length < METEORA_POSITION_MIN_LEN) {
                return Optional.empty();
            }
            // Base58 is case-sensitive and must never be lowercased.
            poolAddress = SolanaBase58.encode(slice(data, METEORA_POSITION_LBPAIR_OFFSET, PUBKEY_LEN));
        }

        // A resolvable pool means the position is open (or, for a closed position, that we can still
        // recover the pair). A durable HTTP 404 (pool not found) is the reliable closed signal for
        // entry-only positions whose LP_EXIT was never captured on-chain — otherwise these linger
        // forever as "unknown/stale" ghost pools. A transient error keeps the existing shell.
        MeteoraDlmmApiClient.MeteoraPoolResult result = meteoraClient.fetchPoolResult(poolAddress);
        if (result.availability() == MeteoraDlmmApiClient.Availability.NOT_FOUND) {
            return Optional.of(closedSnapshot(context, METEORA_PROTOCOL));
        }
        Optional<MeteoraPool> poolOpt = result.pool();
        if (poolOpt.isEmpty()) {
            // Transient failure. For a closed position keep the closed snapshot (with no pair) rather
            // than an empty shell so it does not resurrect as an open ghost; for an open position keep
            // the existing shell so the next refresh retries.
            return closed ? Optional.of(closedSnapshot(context, METEORA_PROTOCOL)) : Optional.empty();
        }
        MeteoraPool pool = poolOpt.get();

        String symX = resolveSymbol(pool.tokenX());
        String symY = resolveSymbol(pool.tokenY());

        // For a closed position, carry the resolved pair (token0/token1 + symbols) but keep the
        // position economically closed: TVL/fees zero, status closed. This is what lets a closed
        // single-sided SOL deposit finally display the correct SOL/<SPL> pair label.
        LpPositionSnapshot snapshot = closed
                ? closedSnapshot(context, METEORA_PROTOCOL)
                : baseSnapshot(context, METEORA_PROTOCOL);
        snapshot.setToken0(tokenSide(symX, pool.tokenX().mint()));
        snapshot.setToken1(tokenSide(symY, pool.tokenY().mint()));
        snapshot.setPriceCurrent(pool.currentPrice());
        snapshot.setFeeTierPct(pool.baseFeePct());
        if (!closed) {
            // Best-effort: a resolvable pool means the position is open. The pool alone does not
            // expose the user's bin range/amounts, so status defaults to in_range and per-position
            // size stays unset. Closed detection is handled upstream (A4 terminal LP_EXIT /
            // qtyHeld<=0), never here.
            snapshot.setStatus("in_range");
        }
        return Optional.of(snapshot);
    }

    private Optional<LpPositionSnapshot> readRaydium(LpPositionContext context, String positionNftAccount) {
        Optional<String> nftMint = chainClient.getTokenAccountMint(positionNftAccount);
        if (nftMint.isEmpty()) {
            // Position NFT account reclaimed => position closed.
            return Optional.of(closedSnapshot(context, RAYDIUM_PROTOCOL));
        }
        Optional<byte[]> ppsData = chainClient.findProgramAccountData(
                SolanaProgramIds.RAYDIUM_CLMM, RAYDIUM_NFT_MINT_OFFSET, nftMint.get(), RAYDIUM_PPS_SLICE_LEN);
        if (ppsData.isEmpty() || ppsData.get().length < RAYDIUM_PPS_SLICE_LEN) {
            // PersonalPositionState reclaimed (liquidity fully removed and position closed).
            return Optional.of(closedSnapshot(context, RAYDIUM_PROTOCOL));
        }
        byte[] pps = ppsData.get();
        String poolId = SolanaBase58.encode(slice(pps, RAYDIUM_POOL_ID_OFFSET, PUBKEY_LEN));
        int tickLower = readInt32LE(pps, RAYDIUM_TICK_LOWER_OFFSET);
        int tickUpper = readInt32LE(pps, RAYDIUM_TICK_UPPER_OFFSET);

        Optional<RaydiumPool> poolOpt = raydiumClient.fetchPool(poolId);
        if (poolOpt.isEmpty()) {
            return Optional.empty();
        }
        RaydiumPool pool = poolOpt.get();

        String symA = resolveSymbol(pool.mintA());
        String symB = resolveSymbol(pool.mintB());

        LpPositionSnapshot snapshot = baseSnapshot(context, RAYDIUM_PROTOCOL);
        snapshot.setToken0(tokenSide(symA, pool.mintA().mint()));
        snapshot.setToken1(tokenSide(symB, pool.mintB().mint()));
        snapshot.setPriceCurrent(pool.price());
        snapshot.setTickLower(tickLower);
        snapshot.setTickUpper(tickUpper);
        // The v3 pool-info endpoint does not expose the live tick, so in-range cannot be derived
        // from a single REST call; an open position is reported as in_range (best-effort).
        snapshot.setStatus("in_range");
        return Optional.of(snapshot);
    }

    private String resolveSymbol(MeteoraToken token) {
        return resolveSymbol(token.mint(), token.symbol());
    }

    private String resolveSymbol(RaydiumToken token) {
        return resolveSymbol(token.mint(), token.symbol());
    }

    private String resolveSymbol(String mint, String fallbackSymbol) {
        String resolved = metadataResolver.resolveSymbol(mint).orElse(null);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (fallbackSymbol != null && !fallbackSymbol.isBlank()) {
            return fallbackSymbol;
        }
        return mint;
    }

    private LpPositionSnapshot baseSnapshot(LpPositionContext context, String protocol) {
        LpPositionSnapshot snapshot = new LpPositionSnapshot();
        snapshot.setCorrelationId(context.correlationId());
        snapshot.setUniverseId(context.universeId());
        snapshot.setNetworkId(NetworkId.SOLANA.name());
        snapshot.setWalletAddress(context.walletAddress());
        snapshot.setProtocol(context.protocol() != null ? context.protocol() : protocol);
        snapshot.setFamily(context.family() != null ? context.family() : "CL_NFT");
        snapshot.setStaked(context.staked());
        // WS-8: Meteora DLMM / Raydium CLMM are concentrated-liquidity positions. Stamp the capability
        // so the refresh service closes ghost snapshots via the flag, never a network/prefix check.
        snapshot.setLpConcentrated(true);
        snapshot.setSnapshotAt(Instant.now());
        snapshot.setSnapshotStale(false);
        snapshot.setUnavailableReason(null);
        return snapshot;
    }

    private LpPositionSnapshot closedSnapshot(LpPositionContext context, String protocol) {
        LpPositionSnapshot snapshot = baseSnapshot(context, protocol);
        snapshot.setStatus("closed");
        snapshot.setTvlUsd(BigDecimal.ZERO);
        snapshot.setUnclaimedFeesUsd(BigDecimal.ZERO);
        return snapshot;
    }

    private static LpPositionSnapshot.TokenSide tokenSide(String sym, String contract) {
        LpPositionSnapshot.TokenSide side = new LpPositionSnapshot.TokenSide();
        side.setSym(sym);
        side.setContract(contract);
        return side;
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    /** Little-endian signed 32-bit integer at the given offset (Solana/Borsh encoding). */
    private static int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }
}
