package com.walletradar.application.normalization.pipeline;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.linking.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.application.normalization.pipeline.metadata.ResolvedTokenMetadata;
import com.walletradar.application.normalization.pipeline.metadata.TokenMetadataResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Shared canonical-metadata enrichment with per-family step opt-in (ADR-066, RC-S1).
 *
 * <p>EVM keeps its own inline enrichment in {@code OnChainNormalizationService} (receipt-shaped
 * steps: registry bridge correction, receipt-identity protocol hints, lending receipt identity).
 * Solana / TON payloads are not EVM-receipt-shaped, so this enricher runs only the
 * network-agnostic steps: {@link ProtocolNameEnrichmentService} (canonicalises the
 * classifier-provided name + stamps resolution state) and {@link CounterpartyEnrichmentService}
 * (per-family {@code CounterpartyResolver}). No EVM receipt-shaped step runs here.</p>
 */
@Component
@RequiredArgsConstructor
public class CanonicalMetadataEnricher {

    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final CounterpartyEnrichmentService counterpartyEnrichmentService;
    private final TokenMetadataResolutionService tokenMetadataResolutionService;

    /**
     * Enriches a Solana (Helius) normalized row in place: protocol-name canonicalisation +
     * counterparty resolution only. Skips {@code PENDING_CLARIFICATION} and {@code UNKNOWN} rows so
     * unresolved shells are not stamped with misleading metadata (mirrors the EVM guard).
     */
    public void enrichSolana(NormalizedTransaction normalizedTransaction, RawTransaction rawTransaction, Instant now) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                || normalizedTransaction.getType() == NormalizedTransactionType.UNKNOWN) {
            return;
        }
        protocolNameEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        counterpartyEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        finalizeTokenIdentity(normalizedTransaction, NetworkId.SOLANA);
    }

    /**
     * Enriches a TON (TON Center v3) normalized row in place: protocol-name canonicalisation +
     * counterparty resolution only (ADR-066, PR3 RC-T1.4). Skips {@code PENDING_CLARIFICATION} and
     * {@code UNKNOWN} rows so unresolved shells (including jetton/DeFi rows whose value could not be
     * booked) are not stamped with misleading metadata and stay visible for review.
     */
    public void enrichTon(NormalizedTransaction normalizedTransaction, RawTransaction rawTransaction, Instant now) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                || normalizedTransaction.getType() == NormalizedTransactionType.UNKNOWN) {
            return;
        }
        protocolNameEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        counterpartyEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        finalizeTokenIdentity(normalizedTransaction, NetworkId.TON);
    }

    /** Native pseudo-contracts booked by the builders; these already carry a real symbol (SOL/TON). */
    private static final String SOLANA_WSOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String TON_NATIVE_CONTRACT = "TONCOIN";
    private static final String SPL_FALLBACK_PREFIX = "SPL:";
    private static final String TON_FALLBACK_PREFIX = "JETTON:";
    private static final int FALLBACK_SUFFIX_LENGTH = 6;

    /**
     * WS-7 metadata seam: resolve each flow's token identity through the unified
     * {@link TokenMetadataResolutionService} (descriptor override → persistent cache → live resolver,
     * write-through). This warms the durable {@code token_metadata_cache} at normalization time — so
     * background replay/renormalization is RPC-free — and upgrades a still-placeholder flow symbol
     * (blank, or the raw contract) to the resolved symbol. Decimals are already applied by the
     * builder (they scale quantity) and are never rewritten here.
     *
     * <p>Last resort: when no tier resolves a symbol, a deterministic, replay-stable non-blank
     * fallback derived from the contract is applied ({@code SPL:xxxxxx} / {@code JETTON:xxxxxx}) so
     * {@code assetSymbol} is never blank and never the full raw address. This never changes the
     * accounting identity/family, which is contract-keyed (see
     * {@code AccountingAssetFamilySupport.continuityIdentity}) and falls through to the contract
     * whether the symbol is blank or the fallback.</p>
     */
    private void finalizeTokenIdentity(NormalizedTransaction normalizedTransaction, NetworkId networkId) {
        List<NormalizedTransaction.Flow> flows = normalizedTransaction.getFlows();
        if (flows == null || flows.isEmpty()) {
            return;
        }
        for (NormalizedTransaction.Flow flow : flows) {
            String contract = flow.getAssetContract();
            if (contract == null || contract.isBlank()) {
                continue;
            }
            // Always resolve to warm the durable token_metadata_cache write-through (RPC-free replay),
            // even when the flow already carries a good symbol.
            ResolvedTokenMetadata resolved = tokenMetadataResolutionService.resolve(networkId, contract);
            if (!isPlaceholderSymbol(flow.getAssetSymbol(), contract)) {
                continue;
            }
            if (resolved.hasSymbol()) {
                flow.setAssetSymbol(resolved.symbol());
            } else if (needsDeterministicFallback(networkId, contract, flow.getAssetSymbol())) {
                flow.setAssetSymbol(deterministicFallbackSymbol(networkId, contract));
            }
        }
    }

    /** A symbol is a placeholder when it is blank or is merely the raw contract/mint/master itself. */
    private static boolean isPlaceholderSymbol(String symbol, String contract) {
        if (symbol == null || symbol.isBlank()) {
            return true;
        }
        String trimmed = symbol.trim();
        return trimmed.equalsIgnoreCase(contract.trim())
                || contract.trim().toLowerCase(Locale.ROOT).startsWith(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * A deterministic fallback is only applied to genuine token contracts (SPL mints / TON jetton
     * addresses) whose symbol is truly absent (blank) or is the raw contract itself. Native
     * pseudo-contracts (wSOL native alias, {@code TONCOIN}) already carry a correct real symbol
     * (SOL/TON) and must never be overwritten with a contract-derived placeholder.
     */
    private static boolean needsDeterministicFallback(NetworkId networkId, String contract, String symbol) {
        if (isNativePseudoContract(networkId, contract)) {
            return false;
        }
        if (symbol == null || symbol.isBlank()) {
            return true;
        }
        return symbol.trim().equalsIgnoreCase(contract.trim());
    }

    private static boolean isNativePseudoContract(NetworkId networkId, String contract) {
        String trimmed = contract.trim();
        return switch (networkId) {
            case SOLANA -> trimmed.equals(SOLANA_WSOL_MINT);
            case TON -> trimmed.equalsIgnoreCase(TON_NATIVE_CONTRACT);
            default -> false;
        };
    }

    /** {@code SPL:} + last 6 of the mint (Solana) / {@code JETTON:} + last 6 of the jetton address (TON). */
    private static String deterministicFallbackSymbol(NetworkId networkId, String contract) {
        String trimmed = contract.trim();
        String suffix = trimmed.length() >= FALLBACK_SUFFIX_LENGTH
                ? trimmed.substring(trimmed.length() - FALLBACK_SUFFIX_LENGTH)
                : trimmed;
        String prefix = networkId == NetworkId.TON ? TON_FALLBACK_PREFIX : SPL_FALLBACK_PREFIX;
        return prefix + suffix;
    }
}
