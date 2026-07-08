package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts new extracted Bybit staging rows into the legacy ExternalLedgerRaw
 * shape expected by the canonical builder.
 */
@Component
public class BybitExtractedEventMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(BybitExtractedEventMapper.class);

    public ExternalLedgerRaw toLegacyRaw(BybitExtractedEvent extractedEvent) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setId(extractedEvent.getId());
        row.setSource(extractedEvent.getSource());
        row.setSourceFile(extractedEvent.getSourceFile());
        row.setSourceFileType(extractedEvent.getSourceFileType());
        row.setUid(extractedEvent.getUid());
        row.setSessionId(extractedEvent.getSessionId());
        if (extractedEvent.getTimeUtc() == null && extractedEvent.getImportedAt() != null) {
            LOGGER.warn("BYBIT_EVENT_TIME_UTC_MISSING_USING_IMPORTED_AT id={}", extractedEvent.getId());
            row.setTimeUtc(extractedEvent.getImportedAt());
        } else {
            row.setTimeUtc(extractedEvent.getTimeUtc());
        }
        row.setAssetSymbol(extractedEvent.getAssetSymbol());
        row.setQuantityRaw(extractedEvent.getQuantityRaw());
        row.setAccountBalance(extractedEvent.getAccountBalance());
        row.setCanonicalType(extractedEvent.getCanonicalType());
        row.setBasisRelevant(extractedEvent.getBasisRelevant());
        row.setBybitType(extractedEvent.getBybitType());
        row.setBybitDescription(extractedEvent.getBybitDescription());
        row.setChain(extractedEvent.getChain());
        row.setUtaContract(extractedEvent.getUtaContract());
        row.setUtaDirection(extractedEvent.getUtaDirection());
        row.setUtaLegRole(extractedEvent.getUtaLegRole());
        row.setFilledPrice(extractedEvent.getFilledPrice());
        row.setFeePaid(extractedEvent.getFeePaid());
        row.setCashFlow(extractedEvent.getCashFlow());
        row.setChange(extractedEvent.getChange());
        row.setFunding(extractedEvent.getFunding());
        row.setWalletBalance(extractedEvent.getWalletBalance());
        row.setTxHash(extractedEvent.getTxHash());
        row.setTradeOrderId(extractedEvent.getTradeOrderId());
        row.setNetworkId(extractedEvent.getNetworkId());
        row.setSenderAddress(extractedEvent.getSenderAddress());
        row.setReceivedAddress(extractedEvent.getReceivedAddress());
        row.setBybitStatus(extractedEvent.getBybitStatus());
        row.setWalletRef(extractedEvent.getWalletRef());
        row.setOutOfScope(extractedEvent.getOutOfScope());
        if (extractedEvent.getOnChainCorrelation() != null) {
            ExternalLedgerRaw.OnChainCorrelation correlation = new ExternalLedgerRaw.OnChainCorrelation();
            correlation.setStatus(extractedEvent.getOnChainCorrelation().getStatus());
            correlation.setCorrelationId(extractedEvent.getOnChainCorrelation().getCorrelationId());
            correlation.setMatchedDocId(extractedEvent.getOnChainCorrelation().getMatchedDocId());
            row.setOnChainCorrelation(correlation);
        }
        row.setStatus(extractedEvent.getStatus() == null
                ? null
                : ExternalLedgerRawStatus.valueOf(extractedEvent.getStatus().name()));
        row.setImportedAt(extractedEvent.getImportedAt());
        return row;
    }
}
