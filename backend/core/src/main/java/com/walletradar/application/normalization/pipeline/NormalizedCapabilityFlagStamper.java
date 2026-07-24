package com.walletradar.application.normalization.pipeline;

import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

/**
 * WS-8 (ADR-073/074) single ingestion-plane seam that stamps the venue- and network-neutral
 * semantic capability flags onto every normalized on-chain row.
 *
 * <p>This is the <b>one place network specifics are allowed</b>. It is invoked by every builder
 * path that produces or rebuilds a canonical {@link NormalizedTransaction} — EVM
 * {@code OnChainNormalizedTransactionBuilder} (initial {@code build} <em>and</em> the
 * {@code rebuildAfterClarification} / {@code rebuildAfterReclassification} paths that
 * {@code ON_CHAIN_RECLASSIFICATION} runs through), plus the Solana and TON builders — so the flags
 * are derived uniformly and <b>survive reclassification</b>. Previously the Solana flags were
 * stamped only inside {@code SolanaNormalizedTransactionBuilder.build}; the reclassification rebuild
 * bypassed that method and silently dropped them.</p>
 *
 * <p>Both flags are derived purely from fields that are always present / preserved on the final
 * normalized row ({@code networkId}, {@code correlationId}) — never from the raw payload — so a
 * rebuild that only threads the existing row + classification result through (as reclassification
 * does) re-derives them deterministically:</p>
 * <ul>
 *   <li>{@code receiptBearingCollateral} = {@link NetworkAddressFormat#isEvm(com.walletradar.domain.common.NetworkId)}
 *       — EVM lending issues fungible receipt tokens (Aave aTokens / Compound cTokens) that surface a
 *       snapshot-able balance; Solana (Jupiter Lend, Kamino) and TON lending are receipt-less.</li>
 *   <li>{@code lpConcentrated} = the correlation id carries the concentrated-liquidity grammar
 *       (Meteora DLMM / Raydium CLMM position-scoped LP, whose full removal is a terminal
 *       {@code LP_EXIT} with residual-tolerant, snapshot-driven closure). Keyed on the correlation-id
 *       prefix rather than {@code type} so it survives even if a rebuild re-classifies the row.</li>
 * </ul>
 *
 * <p>Read/query services consume the stamped flags and must never re-derive them
 * ({@code ModuleDependencyArchTest}, {@code NetworkBranchGuardTest}). The
 * {@code "lp-position:solana:"} literal lives here legitimately — {@code application/normalization}
 * is the allow-listed ingestion plane.</p>
 */
public final class NormalizedCapabilityFlagStamper {

    /**
     * Correlation-id prefix that {@code SolanaLpPositionResolver} stamps on Meteora DLMM / Raydium
     * CLMM position-scoped LP legs. Its presence is the network-neutral signal for a
     * concentrated-liquidity position on the consumption plane.
     */
    private static final String SOLANA_CONCENTRATED_LP_CORRELATION_PREFIX = "lp-position:solana:";

    private NormalizedCapabilityFlagStamper() {
    }

    /**
     * Derives and stamps every WS-8 capability flag onto {@code tx}. Idempotent: calling it again
     * after a rebuild yields the same result as long as {@code networkId} / {@code correlationId}
     * are unchanged. No-op for a {@code null} argument.
     */
    public static void stamp(NormalizedTransaction tx) {
        if (tx == null) {
            return;
        }
        tx.setReceiptBearingCollateral(NetworkAddressFormat.isEvm(tx.getNetworkId()));
        tx.setLpConcentrated(isConcentratedLpPosition(tx.getCorrelationId()) ? Boolean.TRUE : null);
    }

    private static boolean isConcentratedLpPosition(String correlationId) {
        return correlationId != null && correlationId.startsWith(SOLANA_CONCENTRATED_LP_CORRELATION_PREFIX);
    }
}
