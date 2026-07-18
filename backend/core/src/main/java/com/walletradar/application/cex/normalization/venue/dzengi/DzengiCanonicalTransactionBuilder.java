package com.walletradar.application.cex.normalization.venue.dzengi;

import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.linking.pipeline.clarification.FlowCounterpartySupport;
import com.walletradar.application.normalization.config.FiatExitRule;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds canonical normalized docs from Dzengi extracted staging rows.
 */
@Component
@RequiredArgsConstructor
public class DzengiCanonicalTransactionBuilder {

    private static final String DZENGI_PREFIX = "DZENGI:";

    /**
     * Synthetic counterparty stamped on Dzengi external-transfer rows so stat validation accepts a
     * non-blank counterparty (mirrors Bybit FA-001 P0 {@code BYBIT:HOT_WALLET}). Cross-venue linking
     * runs on {@code txHash} (ADR-049), so this placeholder never participates in FA-001 pairing.
     */
    private static final String DZENGI_EXTERNAL_COUNTERPARTY = "DZENGI:EXTERNAL:CHAIN";

    private final FiatExitRule fiatExitRule;

    public NormalizedTransaction build(DzengiExtractedEvent row, Instant now) {
        if (row == null || Boolean.TRUE.equals(row.getOutOfScope())) {
            return null;
        }
        String canonicalType = row.getCanonicalType();
        if (canonicalType == null) {
            return null;
        }
        NormalizedTransaction transaction = switch (canonicalType) {
            case "BUY", "SELL" -> buildTrade(row, now);
            case "EXTERNAL_TRANSFER_IN" -> buildTransfer(row, NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                    NormalizedLegRole.TRANSFER, now);
            case "EXTERNAL_TRANSFER_OUT" -> buildTransfer(row, resolveTransferOutType(row),
                    NormalizedLegRole.TRANSFER, now);
            case "CEX_DERIVATIVE_SETTLEMENT" -> buildDerivativeSettlement(row, now);
            case "FEE" -> buildFee(row, now);
            default -> null;
        };
        if (transaction != null) {
            applyCounterparty(transaction, row);
        }
        return transaction;
    }

    /**
     * Stamps a row-local counterparty on every non-fee flow (and the transaction) so the stat
     * validation gate does not park Dzengi rows as NEEDS_REVIEW with
     * {@code STAT_COUNTERPARTY_TYPE_MISSING} / {@code FLOW_COUNTERPARTY_MISSING}.
     *
     * <ul>
     *   <li>External transfers → {@link CounterpartyType#UNKNOWN_EOA} on a synthetic chain
     *       placeholder; real pairing is driven by {@code txHash} (ADR-049).</li>
     *   <li>Spot trades / derivative settlement → {@link CounterpartyType#CEX} on the venue account.</li>
     * </ul>
     * Fee-only rows are exempt (stat validation accepts replay-safe fee flows).
     */
    private void applyCounterparty(NormalizedTransaction transaction, DzengiExtractedEvent row) {
        if (transaction.getType() == NormalizedTransactionType.FEE || transaction.getFlows() == null) {
            return;
        }
        boolean externalTransfer = transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.FIAT_EXIT;
        String counterpartyAddress = externalTransfer
                ? DZENGI_EXTERNAL_COUNTERPARTY
                : DZENGI_PREFIX + "VENUE:" + row.getUserId();
        String counterpartyType = externalTransfer ? CounterpartyType.UNKNOWN_EOA : CounterpartyType.CEX;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getCounterpartyAddress() == null || flow.getCounterpartyAddress().isBlank()) {
                flow.setCounterpartyAddress(counterpartyAddress);
            }
            if (flow.getCounterpartyType() == null || flow.getCounterpartyType().isBlank()) {
                flow.setCounterpartyType(counterpartyType);
            }
        }
        FlowCounterpartySupport.applyTransactionCounterparty(transaction);
    }

    private NormalizedTransaction buildTrade(DzengiExtractedEvent row, Instant now) {
        boolean buy = Boolean.TRUE.equals(row.getIsBuyer()) || "BUY".equalsIgnoreCase(row.getCanonicalType());
        NormalizedTransactionType type = NormalizedTransactionType.SWAP;
        NormalizedTransaction transaction = baseTransaction(row, type, now);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        BigDecimal qty = row.getQuantityRaw();
        BigDecimal price = row.getPrice();
        String base = upper(row.getAssetSymbol());
        String quote = upper(row.getQuoteAsset());
        NormalizedTransaction.Flow baseLeg = null;
        if (base != null && qty != null) {
            // Both BUY and SELL legs use null → SWAP_DERIVED in replay.
            // For BUY: price = USD_paid / qty (or BYN_paid × BYN/USD rate / qty).
            // For SELL: price = USD_received / qty.
            // This is symmetric and avoids Dzengi's spread-biased fill prices for both directions.
            baseLeg = flow(
                    buy ? NormalizedLegRole.BUY : NormalizedLegRole.SELL,
                    base,
                    qty,
                    null,
                    null
            );
            flows.add(baseLeg);
        }
        if (quote != null && qty != null && price != null) {
            BigDecimal quoteQty = qty.abs().multiply(price);
            // Quote leg carries no explicit price — applyStableUsdPeg() will stamp STABLECOIN for
            // USD/USDT/USDC, and SWAP_DERIVED will handle non-stable quotes during replay.
            flows.add(flow(
                    buy ? NormalizedLegRole.SELL : NormalizedLegRole.BUY,
                    quote,
                    buy ? quoteQty.negate() : quoteQty,
                    null,
                    null
            ));
        }
        if (row.getCommission() != null && row.getCommissionAsset() != null) {
            flows.add(flow(
                    NormalizedLegRole.FEE,
                    upper(row.getCommissionAsset()),
                    row.getCommission().negate(),
                    null,
                    null
            ));
            // For BUY trades, record the USD commission on the BUY leg so the replay engine can
            // capitalize it into Net AVCO only (Market AVCO stays the clean fill price).
            // Dzengi always charges commission in USD (commissionAsset = "USD"), so the raw
            // commission value is already in USD — no unit-price conversion needed.
            // The FEE leg continues to reduce the USD position (outflow bookkeeping is preserved).
            if (buy && baseLeg != null && isUsdAsset(upper(row.getCommissionAsset()))) {
                baseLeg.setAcquisitionFeeUsd(Decimal128Support.normalize(row.getCommission().abs()));
            }
        }
        transaction.setFlows(flows);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setConfirmedAt(now);
        applyStableUsdPeg(transaction);
        return transaction;
    }

    private static boolean isUsdAsset(String symbol) {
        return symbol != null && CanonicalAssetCatalog.isUsdStablecoinBySymbol(symbol);
    }

    /**
     * Resolves whether a withdrawal should be classified as FIAT_EXIT or plain EXTERNAL_TRANSFER_OUT.
     * Delegates to the injected {@link FiatExitRule} so the criteria are configurable without
     * changing this builder.
     */
    private NormalizedTransactionType resolveTransferOutType(DzengiExtractedEvent row) {
        if (fiatExitRule.matches(row.getSourceStream(), row.getPaymentMethod(), upper(row.getAssetSymbol()))) {
            return NormalizedTransactionType.FIAT_EXIT;
        }
        return NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
    }

    private NormalizedTransaction buildTransfer(
            DzengiExtractedEvent row,
            NormalizedTransactionType type,
            NormalizedLegRole role,
            Instant now
    ) {
        NormalizedTransaction transaction = baseTransaction(row, type, now);
        BigDecimal qty = row.getQuantityRaw();
        if (qty == null) {
            return null;
        }
        NormalizedTransaction.Flow flow = flow(role, upper(row.getAssetSymbol()), qty, null, null);
        transaction.setFlows(List.of(flow));
        if (row.getTxHash() != null && !row.getTxHash().isBlank()) {
            transaction.setTxHash(row.getTxHash());
        }
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setConfirmedAt(now);
        applyStableUsdPeg(transaction);
        return transaction;
    }

    private NormalizedTransaction buildDerivativeSettlement(DzengiExtractedEvent row, Instant now) {
        NormalizedTransaction transaction = baseTransaction(row, NormalizedTransactionType.CEX_DERIVATIVE_SETTLEMENT, now);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        String cash = upper(row.getAssetSymbol());
        if (row.getRealizedPnl() != null && cash != null) {
            flows.add(flow(NormalizedLegRole.TRANSFER, cash, row.getRealizedPnl(), PriceSource.EXECUTION, BigDecimal.ONE));
        }
        if (row.getFeePaid() != null && cash != null && row.getFeePaid().signum() != 0) {
            flows.add(flow(NormalizedLegRole.FEE, cash, row.getFeePaid(), null, null));
        }
        transaction.setFlows(flows);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setConfirmedAt(now);
        applyStableUsdPeg(transaction);
        return transaction;
    }

    private NormalizedTransaction buildFee(DzengiExtractedEvent row, Instant now) {
        NormalizedTransaction transaction = baseTransaction(row, NormalizedTransactionType.FEE, now);
        transaction.setFlows(List.of(flow(
                NormalizedLegRole.FEE,
                upper(row.getAssetSymbol()),
                row.getQuantityRaw(),
                null,
                null
        )));
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setConfirmedAt(now);
        applyStableUsdPeg(transaction);
        return transaction;
    }

    private NormalizedTransaction baseTransaction(
            DzengiExtractedEvent row,
            NormalizedTransactionType type,
            Instant now
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("dzengi:" + row.getId());
        transaction.setTxHash(row.getTxHash());
        transaction.setWalletAddress(walletRef(row));
        transaction.setSource(NormalizedTransactionSource.DZENGI);
        transaction.setBlockTimestamp(row.getTimeUtc());
        transaction.setTransactionIndex(0);
        transaction.setType(type);
        transaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        transaction.setClarificationAttempts(0);
        transaction.setFullReceiptClarificationAttempts(0);
        transaction.setPricingAttempts(0);
        transaction.setStatAttempts(0);
        transaction.setCreatedAt(now);
        transaction.setUpdatedAt(now);
        transaction.setMissingDataReasons(new ArrayList<>());
        transaction.setExcludedFromAccounting(false);
        return transaction;
    }

    private static String walletRef(DzengiExtractedEvent row) {
        if (row.getWalletRef() != null && !row.getWalletRef().isBlank()) {
            return row.getWalletRef();
        }
        return DZENGI_PREFIX + row.getUserId();
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetSymbol,
            BigDecimal quantityDelta,
            PriceSource priceSource,
            BigDecimal unitPriceUsd
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(Decimal128Support.normalize(quantityDelta));
        if (unitPriceUsd != null) {
            flow.setUnitPriceUsd(Decimal128Support.normalize(unitPriceUsd));
            flow.setValueUsd(Decimal128Support.normalize(quantityDelta.abs().multiply(unitPriceUsd)));
            flow.setPriceSource(priceSource == null ? PriceSource.EXECUTION : priceSource);
        } else if (priceSource != null) {
            flow.setPriceSource(priceSource);
        }
        return flow;
    }

    private void applyStableUsdPeg(NormalizedTransaction transaction) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getAssetSymbol() == null) {
                continue;
            }
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, flow.getAssetSymbol(), NormalizedTransactionSource.DZENGI)) {
                BigDecimal qty = flow.getQuantityDelta() == null ? BigDecimal.ZERO : flow.getQuantityDelta().abs();
                flow.setUnitPriceUsd(BigDecimal.ONE);
                flow.setValueUsd(qty);
                flow.setPriceSource(PriceSource.STABLECOIN);
            }
        }
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
