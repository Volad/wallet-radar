package com.walletradar.costbasis.engine;

import com.walletradar.domain.accounting.AssetPosition;
import com.walletradar.domain.accounting.AssetPositionRepository;
import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-wallet AVCO engine on canonical CONFIRMED normalized transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvcoEngine {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AssetPositionRepository assetPositionRepository;
    private final CostBasisOverrideRepository costBasisOverrideRepository;

    /**
     * Replay from beginning for (wallet, network, asset) from CONFIRMED normalized legs.
     */
    public void replayFromBeginning(String walletAddress, NetworkId networkId, String assetContract) {
        replayFromConfirmedNormalized(walletAddress, networkId, assetContract);
    }

    private void replayFromConfirmedNormalized(String walletAddress, NetworkId networkId, String assetContract) {
        List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                .findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                        walletAddress, networkId, NormalizedTransactionStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            removePositionIfPresent(walletAddress, networkId.name(), assetContract);
            return;
        }

        record FlowCursor(NormalizedTransaction tx, NormalizedTransaction.Flow leg, int legIndex) {}

        List<FlowCursor> timeline = new ArrayList<>();
        for (NormalizedTransaction tx : confirmed) {
            if (isNonEconomicLpType(tx.getType())) {
                continue;
            }
            if (tx.getFlows() == null) {
                continue;
            }
            for (int i = 0; i < tx.getFlows().size(); i++) {
                NormalizedTransaction.Flow leg = tx.getFlows().get(i);
                if (leg.getAssetContract() == null || !leg.getAssetContract().equalsIgnoreCase(assetContract)) {
                    continue;
                }
                timeline.add(new FlowCursor(tx, leg, i));
            }
        }
        timeline.sort(Comparator
                .comparing((FlowCursor c) -> c.tx().getBlockTimestamp(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(c -> c.leg().getLogIndex(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FlowCursor::legIndex));

        if (timeline.isEmpty()) {
            removePositionIfPresent(walletAddress, networkId.name(), assetContract);
            return;
        }

        List<String> legIds = timeline.stream()
                .map(cursor -> legId(cursor.tx(), cursor.legIndex()))
                .toList();
        Map<String, BigDecimal> overridePrices = Map.of();
        if (!legIds.isEmpty()) {
            List<CostBasisOverride> overrides = costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(legIds);
            overridePrices = overrides.stream().collect(Collectors.toMap(
                    CostBasisOverride::getNormalizedLegId,
                    CostBasisOverride::getPriceUsd,
                    (a, b) -> a
            ));
        }

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal avco = BigDecimal.ZERO;
        BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        BigDecimal totalGasPaidUsd = BigDecimal.ZERO;
        boolean hasIncompleteHistory = false;
        Instant lastEventTimestamp = null;
        String assetSymbol = timeline.stream()
                .map(c -> c.leg().getAssetSymbol())
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
        boolean first = true;
        Set<NormalizedTransaction> mutated = new HashSet<>();

        for (FlowCursor cursor : timeline) {
            NormalizedTransaction tx = cursor.tx();
            NormalizedTransaction.Flow leg = cursor.leg();
            BigDecimal qtyDelta = leg.getQuantityDelta() != null ? leg.getQuantityDelta() : BigDecimal.ZERO;
            String legId = legId(tx, cursor.legIndex());
            BigDecimal effectivePrice = effectivePrice(tx, leg, legId, overridePrices);

            if (first) {
                hasIncompleteHistory = isFirstNormalizedLegIncomplete(tx.getType(), qtyDelta);
                first = false;
            }
            lastEventTimestamp = tx.getBlockTimestamp();

            if (isNormalizedSellLeg(tx.getType(), qtyDelta)) {
                BigDecimal sellQty = qtyDelta.abs();
                BigDecimal avcoAtSale = avco;
                BigDecimal realisedPnl = (effectivePrice.subtract(avcoAtSale)).multiply(sellQty).setScale(SCALE, ROUNDING);
                leg.setAvcoAtTimeOfSale(avcoAtSale);
                leg.setRealisedPnlUsd(realisedPnl);
                mutated.add(tx);
                totalRealisedPnlUsd = totalRealisedPnlUsd.add(realisedPnl);
                quantity = quantity.add(qtyDelta);
            } else if (isNormalizedInflow(tx.getType(), qtyDelta)) {
                BigDecimal addQty = qtyDelta;
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    BigDecimal newQty = quantity.add(addQty);
                    if (newQty.compareTo(BigDecimal.ZERO) > 0) {
                        avco = effectivePrice;
                    }
                    quantity = newQty;
                } else {
                    BigDecimal newQty = quantity.add(addQty);
                    avco = (avco.multiply(quantity).add(effectivePrice.multiply(addQty))).divide(newQty, SCALE, ROUNDING);
                    quantity = newQty;
                }
            } else if (isNormalizedOutflow(qtyDelta)) {
                quantity = quantity.add(qtyDelta);
            }
        }

        if (!mutated.isEmpty()) {
            normalizedTransactionRepository.saveAll(mutated);
        }

        Instant now = Instant.now();
        AssetPosition position = assetPositionRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(walletAddress, networkId.name(), assetContract)
                .orElse(new AssetPosition());
        position.setWalletAddress(walletAddress);
        position.setNetworkId(networkId.name());
        position.setAssetSymbol(assetSymbol);
        position.setAssetContract(assetContract);
        position.setQuantity(quantity.max(BigDecimal.ZERO));
        position.setPerWalletAvco(avco);
        position.setTotalCostBasisUsd(quantity.max(BigDecimal.ZERO).multiply(avco).setScale(SCALE, ROUNDING));
        position.setTotalGasPaidUsd(totalGasPaidUsd);
        position.setTotalRealisedPnlUsd(totalRealisedPnlUsd);
        position.setHasIncompleteHistory(hasIncompleteHistory);
        position.setHasUnresolvedFlags(false);
        position.setUnresolvedFlagCount(0);
        position.setLastEventTimestamp(lastEventTimestamp);
        position.setLastCalculatedAt(now);
        assetPositionRepository.save(position);
    }

    /**
     * Replay from beginning for all (network, asset) pairs that have CONFIRMED normalized legs for the wallet.
     */
    public void recalculateForWallet(String walletAddress) {
        List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                .findByWalletAddressAndStatusOrderByBlockTimestampAsc(walletAddress, NormalizedTransactionStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            return;
        }
        Set<String> pairs = new HashSet<>();
        for (NormalizedTransaction tx : confirmed) {
            if (isNonEconomicLpType(tx.getType())) {
                continue;
            }
            if (tx.getNetworkId() == null || tx.getFlows() == null) {
                continue;
            }
            for (NormalizedTransaction.Flow leg : tx.getFlows()) {
                if (leg.getAssetContract() == null || leg.getAssetContract().isBlank()) {
                    continue;
                }
                pairs.add(tx.getNetworkId().name() + "\0" + leg.getAssetContract());
            }
        }
        for (String key : pairs) {
            int i = key.indexOf('\0');
            replayFromBeginning(walletAddress, NetworkId.valueOf(key.substring(0, i)), key.substring(i + 1));
        }
    }

    private static BigDecimal effectivePrice(
            NormalizedTransaction tx,
            NormalizedTransaction.Flow leg,
            String legId,
            Map<String, BigDecimal> overridePrices
    ) {
        if (tx.getType() != NormalizedTransactionType.MANUAL_COMPENSATING && overridePrices.containsKey(legId)) {
            return overridePrices.get(legId);
        }
        return leg.getUnitPriceUsd() != null ? leg.getUnitPriceUsd() : BigDecimal.ZERO;
    }

    private static String legId(NormalizedTransaction tx, int legIndex) {
        return tx.getId() + ":" + legIndex;
    }

    private void removePositionIfPresent(String walletAddress, String networkId, String assetContract) {
        assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(walletAddress, networkId, assetContract)
                .ifPresent(assetPositionRepository::delete);
    }

    private static boolean isNormalizedSellLeg(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static boolean isNormalizedInflow(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.WRAP
                || type == NormalizedTransactionType.UNWRAP
                || type == NormalizedTransactionType.BORROW
                || type == NormalizedTransactionType.STAKE_WITHDRAWAL
                || type == NormalizedTransactionType.LEND_WITHDRAWAL
                || type == NormalizedTransactionType.LP_FEE_CLAIM
                || type == NormalizedTransactionType.EXTERNAL_INBOUND
                || type == NormalizedTransactionType.MANUAL_COMPENSATING;
    }

    private static boolean isNormalizedOutflow(BigDecimal qtyDelta) {
        return qtyDelta != null && qtyDelta.compareTo(BigDecimal.ZERO) < 0;
    }

    private static boolean isFirstNormalizedLegIncomplete(NormalizedTransactionType type, BigDecimal qtyDelta) {
        return isNormalizedSellLeg(type, qtyDelta)
                || (qtyDelta != null && qtyDelta.compareTo(BigDecimal.ZERO) < 0
                && type != NormalizedTransactionType.SWAP
                && type != NormalizedTransactionType.LP_EXIT
                && type != NormalizedTransactionType.LP_EXIT_PARTIAL
                && type != NormalizedTransactionType.LP_EXIT_FINAL);
    }

    private static boolean isNonEconomicLpType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE;
    }
}
