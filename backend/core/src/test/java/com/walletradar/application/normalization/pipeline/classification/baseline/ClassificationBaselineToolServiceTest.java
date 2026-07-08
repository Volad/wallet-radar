package com.walletradar.application.normalization.pipeline.classification.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.linking.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.application.linking.pipeline.clarification.RawTransactionClarificationEnricher;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassificationBaselineToolServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exportWritesDeterministicRowSnapshotAndAggregates() throws Exception {
        NormalizedTransactionRepository normalizedRepository = mock(NormalizedTransactionRepository.class);
        RawTransactionRepository rawRepository = mock(RawTransactionRepository.class);
        ExternalLedgerRawRepository externalRepository = mock(ExternalLedgerRawRepository.class);

        NormalizedTransaction transaction = normalizedTransaction();
        RawTransaction raw = rawTransaction();
        new RawTransactionClarificationEnricher().merge(
                raw,
                new ClarificationReceiptEnrichment(
                        "1",
                        null,
                        null,
                        null,
                        null,
                        List.of(new Document("address", "0xabc")),
                        List.of(),
                        List.of(),
                        new Document("logs", List.of(new Document("address", "0xdef"))),
                        RawSyncMethod.BLOCKSCOUT
                )
        );

        when(normalizedRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(transaction));
        when(rawRepository.findAll()).thenReturn(List.of(raw));
        when(externalRepository.findAll()).thenReturn(List.of());

        ClassificationBaselineToolService service = new ClassificationBaselineToolService(
                normalizedRepository,
                rawRepository,
                externalRepository,
                new ObjectMapper()
        );

        service.export(tempDir);

        Path ndjson = tempDir.resolve("normalized_row_snapshot.ndjson");
        Path csv = tempDir.resolve("normalized_row_snapshot.csv");
        Path typeStatus = tempDir.resolve("type_status_matrix.json");
        Path corr = tempDir.resolve("correlation_shape_matrix.json");
        Path manifest = tempDir.resolve("manifest.json");

        assertThat(Files.exists(ndjson)).isTrue();
        assertThat(Files.exists(csv)).isTrue();
        assertThat(Files.exists(typeStatus)).isTrue();
        assertThat(Files.exists(corr)).isTrue();
        assertThat(Files.exists(manifest)).isTrue();

        String row = Files.readString(ndjson);
        assertThat(row).contains("\"_id\":\"0xhash:ARBITRUM:0xwallet\"");
        assertThat(row).contains("\"clarificationEvidencePresent\":true");
        assertThat(row).contains("\"fullReceiptPresent\":true");
        assertThat(row).contains("\"flowFingerprint\":\"sha256:");
        assertThat(row).contains("\"priceableFingerprint\":\"sha256:");
        assertThat(row).contains("\"classificationFingerprint\":\"sha256:");
    }

    @Test
    void diffDetectsChangedRowsByField(@TempDir Path diffDir) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path baselineDir = diffDir.resolve("baseline");
        Files.createDirectories(baselineDir);

        NormalizedRowSnapshot baselineRow = new NormalizedRowSnapshot(
                "0xhash:ARBITRUM:0xwallet",
                "0xhash",
                "ON_CHAIN",
                "ARBITRUM",
                "0xwallet",
                "SWAP",
                "PENDING_PRICE",
                "Uniswap",
                "V3",
                false,
                false,
                false,
                false,
                List.of(),
                null,
                null,
                "sha256:old-classification",
                "sha256:old-flow",
                "sha256:old-priceable",
                List.of()
        );
        Files.writeString(
                baselineDir.resolve("normalized_row_snapshot.ndjson"),
                objectMapper.writeValueAsString(baselineRow) + System.lineSeparator()
        );

        NormalizedTransactionRepository normalizedRepository = mock(NormalizedTransactionRepository.class);
        RawTransactionRepository rawRepository = mock(RawTransactionRepository.class);
        ExternalLedgerRawRepository externalRepository = mock(ExternalLedgerRawRepository.class);
        NormalizedTransaction transaction = normalizedTransaction();
        transaction.setType(NormalizedTransactionType.BRIDGE_OUT);

        when(normalizedRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(transaction));
        when(rawRepository.findAll()).thenReturn(List.of(rawTransaction()));
        when(externalRepository.findAll()).thenReturn(List.of());

        ClassificationBaselineToolService service = new ClassificationBaselineToolService(
                normalizedRepository,
                rawRepository,
                externalRepository,
                objectMapper
        );

        service.diff(baselineDir, diffDir.resolve("out"));

        String summary = Files.readString(diffDir.resolve("out/diff/row_diff_summary.json"));
        String changed = Files.readString(diffDir.resolve("out/diff/row_diff_changed.ndjson"));

        assertThat(summary).contains("\"changedCount\" : 1");
        assertThat(changed).contains("\"_id\":\"0xhash:ARBITRUM:0xwallet\"");
        assertThat(changed).contains("\"type\"");
        assertThat(changed).contains("\"classificationFingerprint\"");
    }

    @Test
    void verifyFailsWhenAnyRowLevelDriftIsDetected(@TempDir Path verifyDir) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path baselineDir = verifyDir.resolve("baseline");
        Files.createDirectories(baselineDir);

        NormalizedRowSnapshot baselineRow = new NormalizedRowSnapshot(
                "0xhash:ARBITRUM:0xwallet",
                "0xhash",
                "ON_CHAIN",
                "ARBITRUM",
                "0xwallet",
                "SWAP",
                "PENDING_PRICE",
                "Uniswap",
                "V3",
                false,
                false,
                false,
                false,
                List.of(),
                null,
                null,
                "sha256:old-classification",
                "sha256:old-flow",
                "sha256:old-priceable",
                List.of()
        );
        Files.writeString(
                baselineDir.resolve("normalized_row_snapshot.ndjson"),
                objectMapper.writeValueAsString(baselineRow) + System.lineSeparator()
        );

        NormalizedTransactionRepository normalizedRepository = mock(NormalizedTransactionRepository.class);
        RawTransactionRepository rawRepository = mock(RawTransactionRepository.class);
        ExternalLedgerRawRepository externalRepository = mock(ExternalLedgerRawRepository.class);
        NormalizedTransaction transaction = normalizedTransaction();
        transaction.setType(NormalizedTransactionType.BRIDGE_OUT);

        when(normalizedRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(transaction));
        when(rawRepository.findAll()).thenReturn(List.of(rawTransaction()));
        when(externalRepository.findAll()).thenReturn(List.of());

        ClassificationBaselineToolService service = new ClassificationBaselineToolService(
                normalizedRepository,
                rawRepository,
                externalRepository,
                objectMapper
        );

        assertThatThrownBy(() -> service.verify(baselineDir, verifyDir.resolve("out")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Classification baseline drift detected");
    }

    private static NormalizedTransaction normalizedTransaction() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("0xhash:ARBITRUM:0xwallet");
        transaction.setTxHash("0xhash");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setType(NormalizedTransactionType.SWAP);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setProtocolName("Uniswap");
        transaction.setProtocolVersion("V3");
        transaction.setExcludedFromAccounting(false);
        transaction.setBlockTimestamp(Instant.parse("2026-03-29T00:00:00Z"));
        transaction.setTransactionIndex(1);
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flow(new BigDecimal("-8.000000"), NormalizedLegRole.SELL),
                flow(new BigDecimal("0.022742145033450122"), NormalizedLegRole.BUY)));
        return transaction;
    }

    private static NormalizedTransaction.Flow flow(BigDecimal qty, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract("0xasset");
        flow.setAssetSymbol(role == NormalizedLegRole.SELL ? "ETH" : "wstETH");
        flow.setQuantityDelta(qty);
        flow.setConfidence(ConfidenceLevel.HIGH);
        return flow;
    }

    private static RawTransaction rawTransaction() {
        RawTransaction raw = new RawTransaction();
        raw.setId("0xhash:ARBITRUM:0xwallet");
        raw.setTxHash("0xhash");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xwallet");
        raw.setRawData(new Document("input", "0x12345678"));
        return raw;
    }
}
