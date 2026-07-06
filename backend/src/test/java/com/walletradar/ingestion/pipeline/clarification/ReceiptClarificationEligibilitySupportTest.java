package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptClarificationEligibilitySupportTest {

    @Test
    void activeOnChainNeedsReviewRowsAreEligibleForFullReceiptRecovery() {
        NormalizedTransaction normalizedTransaction = needsReview(false);
        RawTransaction rawTransaction = raw();

        boolean eligible = ReceiptClarificationEligibilitySupport.isEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        );

        assertThat(eligible).isTrue();
    }

    @Test
    void accountingExcludedNeedsReviewRowsAreNotEligibleForFullReceiptRecovery() {
        NormalizedTransaction normalizedTransaction = needsReview(true);
        RawTransaction rawTransaction = raw();

        boolean eligible = ReceiptClarificationEligibilitySupport.isEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        );

        assertThat(eligible).isFalse();
    }

    private static NormalizedTransaction needsReview(boolean excludedFromAccounting) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId("0xreview:KATANA:0xwallet");
        normalizedTransaction.setTxHash("0xreview");
        normalizedTransaction.setNetworkId(NetworkId.KATANA);
        normalizedTransaction.setWalletAddress("0xwallet");
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setMissingDataReasons(List.of("UNSUPPORTED_REVIEW_REASON"));
        normalizedTransaction.setExcludedFromAccounting(excludedFromAccounting);
        return normalizedTransaction;
    }

    private static RawTransaction raw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xreview:KATANA:0xwallet");
        rawTransaction.setTxHash("0xreview");
        rawTransaction.setNetworkId(NetworkId.KATANA.name());
        rawTransaction.setWalletAddress("0xwallet");
        rawTransaction.setRawData(new Document()
                .append("hash", "0xreview")
                .append("methodId", "0xabcdef12")
                .append("to", "0x3067bdba0e6628497d527bef511c22da8b32ca3f"));
        return rawTransaction;
    }
}
