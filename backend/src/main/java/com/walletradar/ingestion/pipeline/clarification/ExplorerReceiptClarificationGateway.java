package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Fetches clarification-safe receipt evidence from explorer providers.
 */
@Service
@RequiredArgsConstructor
public class ExplorerReceiptClarificationGateway {

    private final ExplorerProvider explorerProvider;

    public Optional<ClarificationReceiptEnrichment> fetch(String txHash, NetworkId networkId) {
        if (txHash == null || txHash.isBlank() || networkId == null || !explorerProvider.supports(networkId)) {
            return Optional.empty();
        }
        ExplorerReceipt receipt = explorerProvider.getReceipt(txHash, networkId);
        return ClarificationReceiptEnrichment.fromReceipt(receipt);
    }
}
