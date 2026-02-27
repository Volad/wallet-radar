package com.walletradar.costbasis.engine;

import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.CostBasisOverride;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
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
 * Per-wallet AVCO engine (T-015). Loads economic_events for (wallet, network, asset) ORDER BY blockTimestamp ASC,
 * applies active cost_basis_overrides to on-chain events only, runs AVCO formula, persists asset_positions and
 * updates SELL events with realisedPnlUsd and avcoAtTimeOfSale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvcoEngine {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final EconomicEventRepository economicEventRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AssetPositionRepository assetPositionRepository;
    private final CostBasisOverrideRepository costBasisOverrideRepository;

    /**
     * Replay from beginning: load economic events for (wallet, network, asset) in blockTimestamp ASC,
     * apply cost_basis_overrides to on-chain events, recompute AVCO and persist asset position (03-accounting).
     * Loads events in blockTimestamp ASC (INV-01), applies overrides to on-chain events (INV-08),
     * computes realised P&amp;L on SELL (INV-07), sets hasIncompleteHistory if first event is SELL/transfer-out (INV-09).
     */
    public void replayFromBeginning(String walletAddress, NetworkId networkId, String assetContract) {
        if (replayFromConfirmedNormalized(walletAddress, networkId, assetContract)) {
            return;
        }
        replayFromLegacyEconomicEvents(walletAddress, networkId, assetContract);
    }

    private void replayFromLegacyEconomicEvents(String walletAddress, NetworkId networkId, String assetContract) {
        List<EconomicEvent> events = new ArrayList<>(economicEventRepository
                .findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
                        walletAddress, networkId, assetContract));
        events.sort(Comparator
                .comparing(EconomicEvent::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EconomicEvent::getLogIndex, Comparator.nullsLast(Comparator.naturalOrder())));
        if (events.isEmpty()) {
            removePositionIfPresent(walletAddress, networkId.name(), assetContract);
            return;
        }

        List<String> onChainEventIds = events.stream()
                .filter(e -> e.getTxHash() != null)
                .map(EconomicEvent::getId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<String, BigDecimal> overridePrices = Map.of();
        if (!onChainEventIds.isEmpty()) {
            List<CostBasisOverride> overrides = costBasisOverrideRepository
                    .findByEconomicEventIdInAndActiveTrue(onChainEventIds);
            overridePrices = overrides.stream()
                    .collect(Collectors.toMap(CostBasisOverride::getEconomicEventId, CostBasisOverride::getPriceUsd, (a, b) -> a));
        }

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal avco = BigDecimal.ZERO;
        BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
        BigDecimal totalGasPaidUsd = BigDecimal.ZERO;
        boolean hasIncompleteHistory = false;
        int unresolvedFlagCount = 0;
        Instant lastEventTimestamp = null;
        String assetSymbol = events.get(0).getAssetSymbol();
        boolean first = true;

        for (EconomicEvent event : events) {
            BigDecimal effectivePrice = effectivePrice(event, overridePrices);
            BigDecimal qtyDelta = event.getQuantityDelta() != null ? event.getQuantityDelta() : BigDecimal.ZERO;

            if (first) {
                hasIncompleteHistory = AvcoEventTypeHelper.isFirstEventIncomplete(event.getEventType(), qtyDelta);
                first = false;
            }

            if (!event.isFlagResolved() && event.getFlagCode() != null) {
                unresolvedFlagCount++;
            }
            totalGasPaidUsd = totalGasPaidUsd.add(event.getGasCostUsd() != null ? event.getGasCostUsd() : BigDecimal.ZERO);
            lastEventTimestamp = event.getBlockTimestamp();

            EconomicEventType type = event.getEventType();
            if (AvcoEventTypeHelper.isSellType(type) && qtyDelta.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal sellQty = qtyDelta.abs();
                BigDecimal avcoAtSale = avco;
                BigDecimal realisedPnl = (effectivePrice.subtract(avcoAtSale)).multiply(sellQty).setScale(SCALE, ROUNDING);
                event.setAvcoAtTimeOfSale(avcoAtSale);
                event.setRealisedPnlUsd(realisedPnl);
                totalRealisedPnlUsd = totalRealisedPnlUsd.add(realisedPnl);
                quantity = quantity.add(qtyDelta);
            } else if (AvcoEventTypeHelper.isInflow(type, qtyDelta)) {
                BigDecimal addQty = qtyDelta;
                BigDecimal priceForBasis = effectivePrice;
                if (event.isGasIncludedInBasis() && event.getGasCostUsd() != null && event.getGasCostUsd().compareTo(BigDecimal.ZERO) > 0
                        && addQty.compareTo(BigDecimal.ZERO) > 0) {
                    priceForBasis = effectivePrice.add(event.getGasCostUsd().divide(addQty, SCALE, ROUNDING));
                }
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    BigDecimal newQty = quantity.add(addQty);
                    if (newQty.compareTo(BigDecimal.ZERO) > 0) {
                        // Incomplete history may drive running qty below zero; once it turns positive,
                        // restart AVCO from the first positive lot we can account for.
                        avco = priceForBasis;
                    }
                    quantity = newQty;
                } else {
                    BigDecimal newQty = quantity.add(addQty);
                    avco = (avco.multiply(quantity).add(priceForBasis.multiply(addQty))).divide(newQty, SCALE, ROUNDING);
                    quantity = newQty;
                }
            } else if (AvcoEventTypeHelper.isOutflow(type, qtyDelta)) {
                quantity = quantity.add(qtyDelta);
            }
        }

        Instant now = Instant.now();
        List<EconomicEvent> toSave = events.stream()
                .filter(e -> AvcoEventTypeHelper.isSellType(e.getEventType()) && e.getRealisedPnlUsd() != null)
                .toList();
        economicEventRepository.saveAll(toSave);

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
        position.setHasUnresolvedFlags(unresolvedFlagCount > 0);
        position.setUnresolvedFlagCount(unresolvedFlagCount);
        position.setLastEventTimestamp(lastEventTimestamp);
        position.setLastCalculatedAt(now);
        assetPositionRepository.save(position);
    }

    private boolean replayFromConfirmedNormalized(String walletAddress, NetworkId networkId, String assetContract) {
        List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                .findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
                        walletAddress, networkId, NormalizedTransactionStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            return false;
        }

        record LegCursor(NormalizedTransaction tx, NormalizedTransaction.Leg leg) {}

        List<LegCursor> timeline = new ArrayList<>();
        for (NormalizedTransaction tx : confirmed) {
            if (tx.getLegs() == null) continue;
            for (NormalizedTransaction.Leg leg : tx.getLegs()) {
                if (leg.getAssetContract() == null || !leg.getAssetContract().equalsIgnoreCase(assetContract)) {
                    continue;
                }
                timeline.add(new LegCursor(tx, leg));
            }
        }
        timeline.sort(Comparator
                .comparing((LegCursor c) -> c.tx().getBlockTimestamp(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(c -> c.leg().getLogIndex(), Comparator.nullsLast(Comparator.naturalOrder())));

        if (timeline.isEmpty()) {
            removePositionIfPresent(walletAddress, networkId.name(), assetContract);
            return true;
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

        for (LegCursor cursor : timeline) {
            NormalizedTransaction tx = cursor.tx();
            NormalizedTransaction.Leg leg = cursor.leg();
            BigDecimal qtyDelta = leg.getQuantityDelta() != null ? leg.getQuantityDelta() : BigDecimal.ZERO;
            BigDecimal effectivePrice = leg.getUnitPriceUsd() != null ? leg.getUnitPriceUsd() : BigDecimal.ZERO;

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
        return true;
    }

    /**
     * Replay from beginning for all (network, asset) pairs that have events for the given wallet.
     */
    public void recalculateForWallet(String walletAddress) {
        List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                .findByWalletAddressAndStatusOrderByBlockTimestampAsc(walletAddress, NormalizedTransactionStatus.CONFIRMED);
        if (!confirmed.isEmpty()) {
            Set<String> pairs = new HashSet<>();
            for (NormalizedTransaction tx : confirmed) {
                if (tx.getNetworkId() == null || tx.getLegs() == null) {
                    continue;
                }
                for (NormalizedTransaction.Leg leg : tx.getLegs()) {
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
            return;
        }
        List<EconomicEvent> markers = economicEventRepository.findNetworkIdAndAssetContractByWalletAddress(walletAddress);
        if (markers.isEmpty()) {
            return;
        }
        Set<String> distinct = new HashSet<>();
        for (EconomicEvent e : markers) {
            if (e.getNetworkId() != null && e.getAssetContract() != null) {
                distinct.add(e.getNetworkId().name() + "\0" + e.getAssetContract());
            }
        }
        for (String key : distinct) {
            int i = key.indexOf('\0');
            replayFromBeginning(walletAddress, NetworkId.valueOf(key.substring(0, i)), key.substring(i + 1));
        }
    }

    private BigDecimal effectivePrice(EconomicEvent event, Map<String, BigDecimal> overridePrices) {
        if (event.getEventType() == EconomicEventType.MANUAL_COMPENSATING) {
            return event.getPriceUsd() != null ? event.getPriceUsd() : BigDecimal.ZERO;
        }
        if (event.getId() != null && overridePrices.containsKey(event.getId())) {
            return overridePrices.get(event.getId());
        }
        return event.getPriceUsd() != null ? event.getPriceUsd() : BigDecimal.ZERO;
    }

    private void removePositionIfPresent(String walletAddress, String networkId, String assetContract) {
        assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(walletAddress, networkId, assetContract)
                .ifPresent(assetPositionRepository::delete);
    }

    private static boolean isNormalizedSellLeg(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP || type == NormalizedTransactionType.LP_EXIT;
    }

    private static boolean isNormalizedInflow(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.BORROW
                || type == NormalizedTransactionType.STAKE_WITHDRAWAL
                || type == NormalizedTransactionType.LEND_WITHDRAWAL
                || type == NormalizedTransactionType.EXTERNAL_INBOUND
                || type == NormalizedTransactionType.MANUAL_COMPENSATING
                || type == NormalizedTransactionType.INTERNAL_TRANSFER;
    }

    private static boolean isNormalizedOutflow(BigDecimal qtyDelta) {
        return qtyDelta != null && qtyDelta.compareTo(BigDecimal.ZERO) < 0;
    }

    private static boolean isFirstNormalizedLegIncomplete(NormalizedTransactionType type, BigDecimal qtyDelta) {
        return isNormalizedSellLeg(type, qtyDelta)
                || (qtyDelta != null && qtyDelta.compareTo(BigDecimal.ZERO) < 0
                && type != NormalizedTransactionType.SWAP
                && type != NormalizedTransactionType.LP_EXIT);
    }
}
