package com.walletradar.ingestion.pipeline.classification.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manual baseline export/diff tooling for ADR-001 migration safety.
 */
@Slf4j
public class ClassificationBaselineToolService {

    private static final String ROW_SNAPSHOT_NDJSON = "normalized_row_snapshot.ndjson";
    private static final String ROW_SNAPSHOT_CSV = "normalized_row_snapshot.csv";

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final ExternalLedgerRawRepository externalLedgerRawRepository;
    private final ObjectMapper objectMapper;
    private final MongoOperations mongoOperations;

    public ClassificationBaselineToolService(
            NormalizedTransactionRepository normalizedTransactionRepository,
            RawTransactionRepository rawTransactionRepository,
            ExternalLedgerRawRepository externalLedgerRawRepository,
            ObjectMapper objectMapper
    ) {
        this(
                normalizedTransactionRepository,
                rawTransactionRepository,
                externalLedgerRawRepository,
                objectMapper,
                null
        );
    }

    public ClassificationBaselineToolService(
            NormalizedTransactionRepository normalizedTransactionRepository,
            RawTransactionRepository rawTransactionRepository,
            ExternalLedgerRawRepository externalLedgerRawRepository,
            ObjectMapper objectMapper,
            MongoOperations mongoOperations
    ) {
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.rawTransactionRepository = rawTransactionRepository;
        this.externalLedgerRawRepository = externalLedgerRawRepository;
        this.objectMapper = objectMapper;
        this.mongoOperations = mongoOperations;
    }

    public void export(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            List<NormalizedRowSnapshot> rows = loadSnapshots();
            writeExportArtifacts(outputDir, rows);
            log.info("classification-baseline export finished, rows={}, outputDir={}", rows.size(), outputDir.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to export classification baseline", e);
        }
    }

    public ClassificationBaselineDiffSummary diff(Path baselineDir, Path outputDir) {
        try {
            if (baselineDir == null) {
                throw new IllegalArgumentException("baselineDir is required for diff mode");
            }
            Files.createDirectories(outputDir);
            Path currentDir = outputDir.resolve("current");
            Path diffDir = outputDir.resolve("diff");
            Files.createDirectories(currentDir);
            Files.createDirectories(diffDir);

            List<NormalizedRowSnapshot> currentRows = loadSnapshots();
            writeExportArtifacts(currentDir, currentRows);

            Map<String, NormalizedRowSnapshot> baselineById = readNdjsonRows(baselineDir.resolve(ROW_SNAPSHOT_NDJSON));
            Map<String, NormalizedRowSnapshot> currentById = currentRows.stream()
                    .collect(Collectors.toMap(NormalizedRowSnapshot::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));

            List<NormalizedRowSnapshot> added = currentById.entrySet().stream()
                    .filter(entry -> !baselineById.containsKey(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(NormalizedRowSnapshot::id))
                    .toList();
            List<NormalizedRowSnapshot> removed = baselineById.entrySet().stream()
                    .filter(entry -> !currentById.containsKey(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(NormalizedRowSnapshot::id))
                    .toList();
            List<Map<String, Object>> changed = baselineById.entrySet().stream()
                    .filter(entry -> currentById.containsKey(entry.getKey()))
                    .map(entry -> changedRow(entry.getValue(), currentById.get(entry.getKey())))
                    .filter(Objects::nonNull)
                    .toList();

            ClassificationBaselineDiffSummary summary = new ClassificationBaselineDiffSummary(
                    baselineById.size(),
                    currentById.size(),
                    added.size(),
                    removed.size(),
                    changed.size()
            );

            writeJson(diffDir.resolve("row_diff_summary.json"), Map.of(
                    "generatedAt", Instant.now().toString(),
                    "baselineRowCount", summary.baselineRowCount(),
                    "currentRowCount", summary.currentRowCount(),
                    "addedCount", summary.addedCount(),
                    "removedCount", summary.removedCount(),
                    "changedCount", summary.changedCount()
            ));
            writeNdjson(diffDir.resolve("row_diff_added.ndjson"), added);
            writeNdjson(diffDir.resolve("row_diff_removed.ndjson"), removed);
            writeNdjson(diffDir.resolve("row_diff_changed.ndjson"), changed);
            writeJson(diffDir.resolve("row_diff_changed_by_field.json"), changedFieldSummary(changed));
            log.info(
                    "classification-baseline diff finished, baselineRows={}, currentRows={}, added={}, removed={}, changed={}, outputDir={}",
                    summary.baselineRowCount(),
                    summary.currentRowCount(),
                    summary.addedCount(),
                    summary.removedCount(),
                    summary.changedCount(),
                    outputDir.toAbsolutePath()
            );
            return summary;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to diff classification baseline", e);
        }
    }

    public void verify(Path baselineDir, Path outputDir) {
        ClassificationBaselineDiffSummary summary = diff(baselineDir, outputDir);
        if (summary.hasDrift()) {
            throw new IllegalStateException(
                    "Classification baseline drift detected: added=%d, removed=%d, changed=%d"
                            .formatted(summary.addedCount(), summary.removedCount(), summary.changedCount())
            );
        }
    }

    private List<NormalizedRowSnapshot> loadSnapshots() {
        List<NormalizedTransaction> normalized = normalizedTransactionRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Map<String, RawTransaction> rawById = rawTransactionRepository.findAll().stream()
                .collect(Collectors.toMap(RawTransaction::getId, Function.identity(), (left, right) -> left));
        Map<String, ExternalLedgerRaw> externalById = loadExternalById();

        return normalized.stream()
                .map(tx -> snapshotOf(tx, rawById.get(tx.getId()), externalById.get(tx.getId())))
                .sorted(Comparator.comparing(NormalizedRowSnapshot::id))
                .toList();
    }

    private Map<String, ExternalLedgerRaw> loadExternalById() {
        if (mongoOperations != null) {
            return mongoOperations.findAll(Document.class, "external_ledger_raw").stream()
                    .map(document -> {
                        String id = document.getString("_id");
                        if (id == null || id.isBlank()) {
                            return null;
                        }
                        ExternalLedgerRaw row = new ExternalLedgerRaw();
                        row.setId(id);
                        return row;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(ExternalLedgerRaw::getId, Function.identity(), (left, right) -> left));
        }

        return externalLedgerRawRepository.findAll().stream()
                .collect(Collectors.toMap(ExternalLedgerRaw::getId, Function.identity(), (left, right) -> left));
    }

    private NormalizedRowSnapshot snapshotOf(
            NormalizedTransaction transaction,
            RawTransaction rawTransaction,
            ExternalLedgerRaw externalLedgerRaw
    ) {
        boolean clarificationEvidencePresent = false;
        boolean fullReceiptPresent = false;
        if (rawTransaction != null) {
            OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
            clarificationEvidencePresent = view.hasClarificationEvidence();
            fullReceiptPresent = view.hasFullReceiptClarificationEvidence();
        }
        if (externalLedgerRaw != null) {
            clarificationEvidencePresent = false;
            fullReceiptPresent = false;
        }

        List<String> missingReasons = ClassificationBaselineSupport.canonicalMissingReasons(transaction.getMissingDataReasons());
        List<CanonicalFlowSnapshot> flows = ClassificationBaselineSupport.canonicalFlows(transaction);
        boolean relatedLifecycleEvidencePresent = transaction.getMatchedCounterparty() != null
                && !transaction.getMatchedCounterparty().isBlank();
        String type = transaction.getType() == null ? null : transaction.getType().name();
        String status = transaction.getStatus() == null ? null : transaction.getStatus().name();
        String source = transaction.getSource() == null ? null : transaction.getSource().name();
        String classificationFingerprint = ClassificationBaselineSupport.classificationFingerprint(
                type,
                status,
                transaction.getProtocolName(),
                transaction.getProtocolVersion(),
                Boolean.TRUE.equals(transaction.getExcludedFromAccounting()),
                clarificationEvidencePresent,
                fullReceiptPresent,
                relatedLifecycleEvidencePresent,
                missingReasons,
                transaction.getCorrelationId(),
                transaction.getMatchedCounterparty()
        );

        return new NormalizedRowSnapshot(
                transaction.getId(),
                transaction.getTxHash(),
                source,
                transaction.getNetworkId() == null ? null : transaction.getNetworkId().name(),
                transaction.getWalletAddress(),
                type,
                status,
                transaction.getProtocolName(),
                transaction.getProtocolVersion(),
                Boolean.TRUE.equals(transaction.getExcludedFromAccounting()),
                clarificationEvidencePresent,
                fullReceiptPresent,
                relatedLifecycleEvidencePresent,
                missingReasons,
                transaction.getCorrelationId(),
                transaction.getMatchedCounterparty(),
                classificationFingerprint,
                ClassificationBaselineSupport.flowFingerprint(flows),
                ClassificationBaselineSupport.priceableFingerprint(flows),
                flows
        );
    }

    private void writeExportArtifacts(Path outputDir, List<NormalizedRowSnapshot> rows) throws IOException {
        writeNdjson(outputDir.resolve(ROW_SNAPSHOT_NDJSON), rows);
        writeCsv(outputDir.resolve(ROW_SNAPSHOT_CSV), rows);
        writeJson(outputDir.resolve("type_status_matrix.json"), typeStatusMatrix(rows));
        writeJson(outputDir.resolve("correlation_shape_matrix.json"), correlationShapeMatrix(rows));
        writeJson(outputDir.resolve("clarification_evidence_counts.json"), clarificationEvidenceCounts(rows));
        writeJson(outputDir.resolve("manifest.json"), manifest(outputDir, rows));
    }

    private List<Map<String, Object>> typeStatusMatrix(List<NormalizedRowSnapshot> rows) {
        Map<List<Object>, Long> counts = rows.stream().collect(Collectors.groupingBy(
                row -> List.of(row.source(), row.type(), row.status(), row.excludedFromAccounting()),
                LinkedHashMap::new,
                Collectors.counting()
        ));
        return counts.entrySet().stream()
                .map(entry -> {
                    List<Object> key = entry.getKey();
                    return ClassificationBaselineSupport.aggregateKey(
                            "source", key.get(0),
                            "type", key.get(1),
                            "status", key.get(2),
                            "excludedFromAccounting", key.get(3),
                            "count", entry.getValue()
                    );
                })
                .sorted(Comparator.<Map<String, Object>, String>comparing(map -> String.valueOf(map.get("source")))
                        .thenComparing(map -> String.valueOf(map.get("type")))
                        .thenComparing(map -> String.valueOf(map.get("status"))))
                .toList();
    }

    private List<Map<String, Object>> correlationShapeMatrix(List<NormalizedRowSnapshot> rows) {
        Map<List<Object>, Long> counts = rows.stream().collect(Collectors.groupingBy(
                row -> List.of(
                        row.type(),
                        row.correlationId() != null,
                        row.matchedCounterparty() != null
                ),
                LinkedHashMap::new,
                Collectors.counting()
        ));
        return counts.entrySet().stream()
                .map(entry -> {
                    List<Object> key = entry.getKey();
                    return ClassificationBaselineSupport.aggregateKey(
                            "type", key.get(0),
                            "hasCorrelationId", key.get(1),
                            "hasMatchedCounterparty", key.get(2),
                            "count", entry.getValue()
                    );
                })
                .sorted(Comparator.<Map<String, Object>, String>comparing(map -> String.valueOf(map.get("type"))))
                .toList();
    }

    private Map<String, Object> clarificationEvidenceCounts(List<NormalizedRowSnapshot> rows) {
        long clarificationRows = rows.stream().filter(NormalizedRowSnapshot::clarificationEvidencePresent).count();
        long fullReceiptRows = rows.stream().filter(NormalizedRowSnapshot::fullReceiptPresent).count();
        long relatedLifecycleRows = rows.stream().filter(NormalizedRowSnapshot::relatedLifecycleEvidencePresent).count();
        return new LinkedHashMap<>(Map.of(
                "rowCount", rows.size(),
                "clarificationEvidenceRows", clarificationRows,
                "fullReceiptRows", fullReceiptRows,
                "relatedLifecycleRows", relatedLifecycleRows
        ));
    }

    private Map<String, Object> manifest(Path outputDir, List<NormalizedRowSnapshot> rows) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("rowCount", rows.size());
        manifest.put("outputDir", outputDir.toAbsolutePath().toString());
        manifest.put("files", List.of(
                fileEntry(outputDir.resolve(ROW_SNAPSHOT_NDJSON)),
                fileEntry(outputDir.resolve(ROW_SNAPSHOT_CSV)),
                fileEntry(outputDir.resolve("type_status_matrix.json")),
                fileEntry(outputDir.resolve("correlation_shape_matrix.json")),
                fileEntry(outputDir.resolve("clarification_evidence_counts.json"))
        ));
        return manifest;
    }

    private Map<String, Object> fileEntry(Path path) {
        try {
            String payload = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
            return new LinkedHashMap<>(Map.of(
                    "name", path.getFileName().toString(),
                    "sha256", ClassificationBaselineSupport.sha256Hex(payload)
            ));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash baseline artifact " + path, e);
        }
    }

    private Map<String, NormalizedRowSnapshot> readNdjsonRows(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Baseline row snapshot does not exist: " + path.toAbsolutePath());
        }
        Map<String, NormalizedRowSnapshot> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            NormalizedRowSnapshot row = objectMapper.readValue(line, NormalizedRowSnapshot.class);
            rows.put(row.id(), row);
        }
        return rows;
    }

    private Map<String, Object> changedRow(NormalizedRowSnapshot baseline, NormalizedRowSnapshot current) {
        if (baseline.equals(current)) {
            return null;
        }
        Map<String, Object> before = objectMapper.convertValue(baseline, Map.class);
        Map<String, Object> after = objectMapper.convertValue(current, Map.class);
        List<String> changedFields = before.keySet().stream()
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .sorted()
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("_id", baseline.id());
        payload.put("changedFields", changedFields);
        payload.put("before", before);
        payload.put("after", after);
        return payload;
    }

    private Map<String, Long> changedFieldSummary(List<Map<String, Object>> changed) {
        Map<String, Long> counts = changed.stream()
                .flatMap(change -> ((List<String>) change.getOrDefault("changedFields", List.of())).stream())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private void writeNdjson(Path path, List<?> rows) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Object row : rows) {
                writer.write(objectMapper.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private void writeCsv(Path path, List<NormalizedRowSnapshot> rows) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",",
                    "_id",
                    "txHash",
                    "source",
                    "networkId",
                    "walletAddress",
                    "type",
                    "status",
                    "protocolName",
                    "protocolVersion",
                    "excludedFromAccounting",
                    "clarificationEvidencePresent",
                    "fullReceiptPresent",
                    "relatedLifecycleEvidencePresent",
                    "missingDataReasons",
                    "correlationId",
                    "matchedCounterparty",
                    "classificationFingerprint",
                    "flowFingerprint",
                    "priceableFingerprint",
                    "flowCount",
                    "priceableFlowCount"
            ));
            writer.newLine();
            for (NormalizedRowSnapshot row : rows) {
                writer.write(String.join(",",
                        csv(row.id()),
                        csv(row.txHash()),
                        csv(row.source()),
                        csv(row.networkId()),
                        csv(row.walletAddress()),
                        csv(row.type()),
                        csv(row.status()),
                        csv(row.protocolName()),
                        csv(row.protocolVersion()),
                        csv(Boolean.toString(row.excludedFromAccounting())),
                        csv(Boolean.toString(row.clarificationEvidencePresent())),
                        csv(Boolean.toString(row.fullReceiptPresent())),
                        csv(Boolean.toString(row.relatedLifecycleEvidencePresent())),
                        csv(String.join("|", row.missingDataReasons())),
                        csv(row.correlationId()),
                        csv(row.matchedCounterparty()),
                        csv(row.classificationFingerprint()),
                        csv(row.flowFingerprint()),
                        csv(row.priceableFingerprint()),
                        csv(Integer.toString(row.flowCount())),
                        csv(Long.toString(row.priceableFlowCount()))
                ));
                writer.newLine();
            }
        }
    }

    private void writeJson(Path path, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
