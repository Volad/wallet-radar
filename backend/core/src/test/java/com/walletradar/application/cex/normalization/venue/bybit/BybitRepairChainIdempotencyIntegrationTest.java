package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.WalletRadarApplication;
import com.walletradar.application.costbasis.application.CostBasisReplayJob;
import com.walletradar.application.costbasis.application.replay.support.CorridorBasisConservationException;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import org.bson.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-9 D1 (determinism fix) whole-chain regression: runs the full Bybit repair chain — in the
 * SAME order as {@code BybitNormalizationService.processNextBatch}
 * ({@code BybitInternalTransferPairer.repairAll} &rarr;
 * {@code BybitStreamAuthorityCollapser.collapseMirrors} &rarr;
 * {@code BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers} &rarr;
 * {@code BybitPrincipalEventExclusivityService.demoteDuplicatePrincipalEvents} &rarr;
 * {@code BybitStakingConversionPairer.pairConversions} &rarr; {@code CostBasisReplayJob.runReplay})
 * — twice against a real, previously-observed-non-deterministic universe restored from a
 * {@code mongorestore} dump, and asserts every mutable determinism field
 * ({@code correlationId}/{@code excludedFromAccounting}/{@code accountingExclusionReason}/
 * {@code continuityCandidate}) on every Bybit {@link NormalizedTransaction} is byte-identical
 * across both runs.
 *
 * <p>This is deliberately NOT a synthetic-fixture unit test: the per-method unit tests in
 * {@code BybitStreamAuthorityCollapserTest}, {@code BybitInternalTransferPairerTest},
 * {@code BybitEarnPrincipalTransferPairerTest}, and {@code BybitStakingConversionPairerTest} already
 * cover each fix in isolation with controlled scan-order permutations. This test instead proves the
 * WHOLE chain is stable end-to-end against real session data, which is the only way to catch
 * cross-method interaction effects a per-method unit test cannot see.</p>
 *
 * <p><b>Skipped by default.</b> Real session data (a {@code mongorestore} dump directory) is
 * required and is NOT committed to the repository. To run this test locally:</p>
 * <pre>
 * ./gradlew :backend:core:test --tests "*BybitRepairChainIdempotencyIntegrationTest*" \
 *     -Dwr.test.bybit.dumpPath=/absolute/path/to/mongodump/walletradar
 * </pre>
 *
 * <p>Optionally pin the session under replay with {@code -Dwr.test.bybit.sessionId=<id>}; otherwise
 * the first {@link UserSession} found in the restored dump is used.</p>
 *
 * <p>The pre-existing, separately-tracked {@code bybit-earn-carry:33625378:FAMILY:MNT} raw-evidence
 * gap (see {@code docs/tasks/financial-audit-followups.md}, FU-1) is expected to keep surfacing as a
 * {@code CorridorBasisConservationException} breach on this session's real data; it is not a
 * collapser-attributable regression and is explicitly excluded from the pass/fail check below.</p>
 */
@Testcontainers
@SpringBootTest(classes = WalletRadarApplication.class)
@EnabledIfSystemProperty(named = "wr.test.bybit.dumpPath", matches = ".+")
class BybitRepairChainIdempotencyIntegrationTest {

    @Container
    private static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        // getReplicaSetUrl() defaults to database "test"; the restored mongodump uses the
        // production database name ("walletradar", per mongorestore's directory-name convention),
        // so the app must be pointed at that same database or every repository read sees zero docs.
        registry.add("spring.data.mongodb.uri", () -> MONGO_CONTAINER.getReplicaSetUrl("walletradar"));
    }

    @BeforeAll
    static void seedFromDump() throws IOException, InterruptedException {
        String dumpPath = System.getProperty("wr.test.bybit.dumpPath");
        // mongodump's default output is gzip-compressed (--gzip); detect that rather than requiring
        // a second system property, since a plain (uncompressed) dump directory also needs to work.
        boolean gzip;
        try (var files = Files.list(Path.of(dumpPath))) {
            gzip = files.anyMatch(p -> p.toString().endsWith(".gz"));
        }
        List<String> command = new ArrayList<>(List.of(
                "mongorestore",
                "--uri=" + MONGO_CONTAINER.getReplicaSetUrl("walletradar"),
                "--drop"
        ));
        if (gzip) {
            command.add("--gzip");
        }
        command.add(dumpPath);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("mongorestore timed out after 120s. Output so far:\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("mongorestore failed with exit code " + process.exitValue()
                    + ". Output:\n" + output);
        }
    }

    @AfterAll
    static void stopContainer() {
        MONGO_CONTAINER.stop();
    }

    @Autowired
    private BybitInternalTransferPairer bybitInternalTransferPairer;
    @Autowired
    private BybitStreamAuthorityCollapser bybitStreamAuthorityCollapser;
    @Autowired
    private BybitEarnPrincipalTransferPairer bybitEarnPrincipalTransferPairer;
    @Autowired
    private BybitPrincipalEventExclusivityService bybitPrincipalEventExclusivityService;
    @Autowired
    private BybitStakingConversionPairer bybitStakingConversionPairer;
    @Autowired
    private CostBasisReplayJob costBasisReplayJob;
    @Autowired
    private UserSessionRepository userSessionRepository;
    @Autowired
    private MongoTemplate mongoTemplate;

    private record DeterminismSnapshot(
            String correlationId,
            Boolean excludedFromAccounting,
            String accountingExclusionReason,
            Boolean continuityCandidate
    ) {
    }

    @Test
    void repairChainAndReplayAreByteIdenticalAcrossRepeatedRuns() {
        String sessionId = resolveSessionId();

        writeReport("before-pairing-groups.json", pairingGroupsJson());
        writeReport("before-financial-snapshot.json", financialSnapshotJson());

        Map<String, DeterminismSnapshot> afterRun1 = runChainOnceAndSnapshot(sessionId);
        Map<String, DeterminismSnapshot> afterRun2 = runChainOnceAndSnapshot(sessionId);

        writeReport("after-pairing-groups.json", pairingGroupsJson());
        writeReport("after-financial-snapshot.json", financialSnapshotJson());

        assertThat(afterRun2)
                .as("correlationId/excludedFromAccounting/accountingExclusionReason/continuityCandidate "
                        + "on every Bybit NormalizedTransaction must be byte-identical across two full "
                        + "repair-chain + replay runs against the same restored universe")
                .isEqualTo(afterRun1);
    }

    /**
     * Acceptance Criterion 5 support (pairing-set diff sign-off): {@code correlationId -> member leg ids}
     * groupings for every Bybit transaction, written before the first repair-chain run (reflecting
     * whatever pre-fix state the restored dump captured) and after the final run, so a reviewer can diff
     * them and sign off that no grouping's economic membership changed unexpectedly. Not asserted on —
     * this is a manual review artifact, per the plan's requirement for a named human sign-off rather than
     * an automated check (tie-break changes can legitimately change WHICH legs get paired).
     */
    private String pairingGroupsJson() {
        Query query = Query.query(Criteria.where("source").is(NormalizedTransactionSource.BYBIT)
                .and("correlationId").ne(null));
        List<NormalizedTransaction> docs = mongoTemplate.find(query, NormalizedTransaction.class);
        Map<String, List<String>> groups = new TreeMap<>();
        for (NormalizedTransaction tx : docs) {
            groups.computeIfAbsent(tx.getCorrelationId(), ignored -> new ArrayList<>()).add(tx.getId());
        }
        groups.values().forEach(ids -> ids.sort(Comparator.naturalOrder()));
        return new Document("groups", groups).toJson();
    }

    /**
     * Acceptance Criterion 6 support (dollar/AVCO-level financial snapshot): mirrors
     * {@code scripts/avco/capture-financial-snapshot.sh}'s per-(wallet, network, asset) terminal
     * aggregation, run directly against this test's disposable Mongo instance via the same
     * {@code asset_ledger_points} collection. Not asserted on here — compared out-of-band against the
     * before-fix snapshot captured from production, per the plan's expectation of a near-zero diff.
     */
    private String financialSnapshotJson() {
        List<Document> pipeline = List.of(
                new Document("$sort", new Document()
                        .append("accountingUniverseId", 1)
                        .append("walletAddress", 1)
                        .append("networkId", 1)
                        .append("accountingAssetIdentity", 1)
                        .append("blockTimestamp", 1)
                        .append("transactionIndex", 1)
                        .append("replaySequence", 1)),
                new Document("$group", new Document()
                        .append("_id", new Document()
                                .append("universe", "$accountingUniverseId")
                                .append("wallet", "$walletAddress")
                                .append("network", "$networkId")
                                .append("asset", "$accountingAssetIdentity"))
                        .append("quantityAfter", new Document("$last", "$quantityAfter"))
                        .append("avcoAfterUsd", new Document("$last", "$avcoAfterUsd"))
                        .append("totalCostBasisAfterUsd", new Document("$last", "$totalCostBasisAfterUsd"))
                        .append("cumulativeRealisedPnlUsd",
                                new Document("$sum", new Document("$ifNull", List.of("$realisedPnlDeltaUsd", 0))))
                        .append("pointCount", new Document("$sum", 1))),
                new Document("$sort", new Document()
                        .append("_id.universe", 1)
                        .append("_id.wallet", 1)
                        .append("_id.network", 1)
                        .append("_id.asset", 1))
        );
        List<Document> terminals = mongoTemplate.getCollection(mongoTemplate.getCollectionName(AssetLedgerPoint.class))
                .aggregate(pipeline)
                .into(new ArrayList<>());
        return new Document("terminalByAsset", terminals).toJson();
    }

    private void writeReport(String fileName, String contents) {
        String reportDirProperty = System.getProperty("wr.test.bybit.reportDir");
        Path dir = Path.of(reportDirProperty != null && !reportDirProperty.isBlank()
                ? reportDirProperty
                : "build/bybit-repair-chain-verification");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write verification report " + fileName, e);
        }
    }

    // The bybit-earn-carry:33625378:FAMILY:MNT breach is a raw-evidence gap in Bybit Earn extraction
    // (2025-09-26 to 2025-10-05), independently triaged as out of scope for the collapser determinism
    // fix (see docs/tasks/financial-audit-followups.md, FU-1). It is expected to persist on this
    // session's data and must not be misread as a collapser regression.
    private static final String KNOWN_OUT_OF_SCOPE_BREACH_QUEUE_KEY = "bybit-earn-carry:33625378:FAMILY:MNT";

    private Map<String, DeterminismSnapshot> runChainOnceAndSnapshot(String sessionId) {
        // Production order: BybitNormalizationService.processNextBatch — internalTransfer →
        // collapser → earnPrincipal → principalDedupe → stakingConversion → (pricing, out of scope
        // here) → costBasisReplayJob.runReplay.
        bybitInternalTransferPairer.repairAll();
        bybitStreamAuthorityCollapser.collapseMirrors();
        bybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers();
        bybitPrincipalEventExclusivityService.demoteDuplicatePrincipalEvents();
        bybitStakingConversionPairer.pairConversions();

        try {
            costBasisReplayJob.runReplay(sessionId);
        } catch (CorridorBasisConservationException exception) {
            List<String> unexpectedBreaches = exception.getResult().breaches().stream()
                    .filter(breach -> !KNOWN_OUT_OF_SCOPE_BREACH_QUEUE_KEY.equals(breach.queueKey()))
                    .map(breach -> breach.queueKey() + "=" + breach.orphanedBasisUsd() + "USD")
                    .toList();
            assertThat(unexpectedBreaches)
                    .as("costBasisReplayJob.runReplay must raise no CorridorBasisConservationException "
                            + "breach other than the pre-existing, separately-tracked "
                            + KNOWN_OUT_OF_SCOPE_BREACH_QUEUE_KEY + " raw-evidence gap")
                    .isEmpty();
        }

        return snapshotAllBybitTransactions();
    }

    private Map<String, DeterminismSnapshot> snapshotAllBybitTransactions() {
        Query query = Query.query(Criteria.where("source").is(NormalizedTransactionSource.BYBIT));
        List<NormalizedTransaction> docs = mongoTemplate.find(query, NormalizedTransaction.class);
        Map<String, DeterminismSnapshot> snapshot = new LinkedHashMap<>();
        for (NormalizedTransaction tx : docs) {
            snapshot.put(tx.getId(), new DeterminismSnapshot(
                    tx.getCorrelationId(),
                    tx.getExcludedFromAccounting(),
                    tx.getAccountingExclusionReason(),
                    tx.getContinuityCandidate()
            ));
        }
        return snapshot;
    }

    private String resolveSessionId() {
        String pinned = System.getProperty("wr.test.bybit.sessionId");
        if (pinned != null && !pinned.isBlank()) {
            return pinned.trim();
        }
        Optional<UserSession> first = userSessionRepository.findAll().stream().findFirst();
        return first
                .map(UserSession::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No UserSession found in the restored dump at " + System.getProperty("wr.test.bybit.dumpPath")
                                + " — pass -Dwr.test.bybit.sessionId=<id> explicitly if the dump has a "
                                + "differently-shaped session collection."));
    }
}
