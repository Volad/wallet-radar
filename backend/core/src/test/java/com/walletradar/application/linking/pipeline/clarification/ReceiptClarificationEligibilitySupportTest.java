package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
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

    @Test
    void lpExitPendingClarificationWithFeeSplitReasonIsEligibleWhenNoFullReceipt() {
        NormalizedTransaction normalizedTransaction = lpExitFeeSplitPending();
        RawTransaction rawTransaction = lpExitRaw();

        boolean eligible = ReceiptClarificationEligibilitySupport.isEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        );

        assertThat(eligible).isTrue();
    }

    @Test
    void lpExitFeeSplitClarificationIsIdempotentOnceFullReceiptPersisted() {
        NormalizedTransaction normalizedTransaction = lpExitFeeSplitPending();
        RawTransaction rawTransaction = lpExitRaw();
        // Persisted full receipt (with logs) => split evidence already present => not eligible again.
        rawTransaction.getRawData().append("clarificationEvidence", new Document(
                "fullReceipt",
                new Document("logs", List.of(new Document("topics", List.of(
                        "0x26f6a048ee9138f2c0ce266f322cb99228e8d619ae2bff30c67f8dcf9d2377b4"
                ))))
        ));

        boolean eligible = ReceiptClarificationEligibilitySupport.isEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        );

        assertThat(eligible).isFalse();
    }

    private static NormalizedTransaction lpExitFeeSplitPending() {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId("0xlpexit:BASE:0xwallet");
        normalizedTransaction.setTxHash("0xlpexit");
        normalizedTransaction.setNetworkId(NetworkId.BASE);
        normalizedTransaction.setWalletAddress("0xwallet");
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setType(NormalizedTransactionType.LP_EXIT);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        normalizedTransaction.setMissingDataReasons(List.of("LP_FEE_SPLIT_EVIDENCE_REQUIRED"));
        normalizedTransaction.setExcludedFromAccounting(false);
        return normalizedTransaction;
    }

    private static RawTransaction lpExitRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xlpexit:BASE:0xwallet");
        rawTransaction.setTxHash("0xlpexit");
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress("0xwallet");
        rawTransaction.setRawData(new Document()
                .append("hash", "0xlpexit")
                .append("methodId", "0xac9650d8"));
        return rawTransaction;
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
