package com.walletradar.ingestion.pipeline.onchain.repair;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.ingestion.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Bounded tx-level metadata repair for missing canonical ordering fields.
 */
@Service
@RequiredArgsConstructor
public class ExplorerRawOrderingRepairGateway {

    private static final String[] TRANSACTION_INDEX_KEYS = {
            "transactionIndex",
            "transaction_index",
            "index",
            "position"
    };

    private static final String[] TIMESTAMP_KEYS = {
            "timeStamp",
            "timestamp",
            "block_timestamp",
            "timestampDate"
    };

    private final ExplorerProvider explorerProvider;

    public Optional<ResolvedRawOrderingMetadata> fetch(String txHash, NetworkId networkId) {
        if (txHash == null || txHash.isBlank() || networkId == null || !explorerProvider.supports(networkId)) {
            return Optional.empty();
        }

        ResolvedRawOrderingMetadata fromDetails = extract(explorerProvider.getTransactionDetails(txHash, networkId));
        if (fromDetails.transactionIndex() != null || fromDetails.epochSeconds() != null) {
            return Optional.of(fromDetails);
        }

        ResolvedRawOrderingMetadata fromTransaction = extract(explorerProvider.getTransaction(txHash, networkId));
        if (fromTransaction.transactionIndex() != null || fromTransaction.epochSeconds() != null) {
            return Optional.of(fromTransaction);
        }

        return Optional.empty();
    }

    private ResolvedRawOrderingMetadata extract(ExplorerTransactionDetails details) {
        return extract(details == null ? null : details.data());
    }

    private ResolvedRawOrderingMetadata extract(ExplorerTransaction transaction) {
        return extract(transaction == null ? null : transaction.data());
    }

    private ResolvedRawOrderingMetadata extract(Document data) {
        if (data == null) {
            return new ResolvedRawOrderingMetadata(null, null);
        }
        Integer transactionIndex = firstTransactionIndex(data);
        Long epochSeconds = firstEpochSeconds(data);
        return new ResolvedRawOrderingMetadata(epochSeconds, transactionIndex);
    }

    private Integer firstTransactionIndex(Document data) {
        for (String key : TRANSACTION_INDEX_KEYS) {
            Integer parsed = RawOrderingMetadataResolver.parseFlexibleInteger(data.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Long firstEpochSeconds(Document data) {
        for (String key : TIMESTAMP_KEYS) {
            Long parsed = parseEpochSeconds(data.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Long parseEpochSeconds(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Document document) {
            for (String nestedKey : TIMESTAMP_KEYS) {
                Long parsed = parseEpochSeconds(document.get(nestedKey));
                if (parsed != null) {
                    return parsed;
                }
            }
            Long valueField = parseEpochSeconds(document.get("value"));
            if (valueField != null) {
                return valueField;
            }
            return parseEpochSeconds(document.get("date"));
        }

        Long numeric = RawOrderingMetadataResolver.parseFlexibleLong(value);
        if (numeric != null) {
            return numeric;
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
