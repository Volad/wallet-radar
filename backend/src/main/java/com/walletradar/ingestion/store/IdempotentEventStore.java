package com.walletradar.ingestion.store;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Upserts economic events keyed by (txHash, networkId, walletAddress, assetContract) for on-chain events
 * so one tx can have multiple events (e.g. SWAP_SELL and SWAP_BUY); by clientId for MANUAL_COMPENSATING (INV-11).
 */
@Service
@RequiredArgsConstructor
public class IdempotentEventStore {

    private final EconomicEventRepository repository;

    /**
     * Upsert by (txHash, networkId, walletAddress, assetContract) when on-chain; by clientId for MANUAL_COMPENSATING.
     * Returns the saved or existing event (idempotent).
     */
    public EconomicEvent upsert(EconomicEvent event) {
        if (event.getTxHash() != null && event.getNetworkId() != null
                && event.getWalletAddress() != null && event.getAssetContract() != null) {
            return repository.findByTxHashAndNetworkIdAndWalletAddressAndAssetContract(
                            event.getTxHash(), event.getNetworkId(), event.getWalletAddress(), event.getAssetContract())
                    .map(existing -> copyEventInto(existing, event))
                    .map(repository::save)
                    .orElseGet(() -> repository.save(event));
        }
        if (event.getClientId() != null) {
            return repository.findByClientId(event.getClientId())
                    .orElseGet(() -> repository.save(event));
        }
        return repository.save(event);
    }

    private static EconomicEvent copyEventInto(EconomicEvent target, EconomicEvent source) {
        target.setWalletAddress(source.getWalletAddress());
        target.setBlockTimestamp(source.getBlockTimestamp());
        target.setEventType(source.getEventType());
        target.setAssetSymbol(source.getAssetSymbol());
        target.setAssetContract(source.getAssetContract());
        target.setQuantityDelta(source.getQuantityDelta());
        target.setPriceUsd(source.getPriceUsd());
        target.setPriceSource(source.getPriceSource());
        target.setTotalValueUsd(source.getTotalValueUsd());
        target.setGasCostUsd(source.getGasCostUsd());
        target.setGasIncludedInBasis(source.isGasIncludedInBasis());
        target.setRealisedPnlUsd(source.getRealisedPnlUsd());
        target.setAvcoAtTimeOfSale(source.getAvcoAtTimeOfSale());
        target.setFlagCode(source.getFlagCode());
        target.setFlagResolved(source.isFlagResolved());
        target.setCounterpartyAddress(source.getCounterpartyAddress());
        target.setInternalTransfer(source.isInternalTransfer());
        target.setProtocolName(source.getProtocolName());
        return target;
    }
}
