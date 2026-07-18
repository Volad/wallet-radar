package com.walletradar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrow literal scan: post-normalization Java source files must not contain
 * raw venue-specific string prefixes that bypass the WalletRef / neutral-marker contract.
 *
 * Ingestion-plane packages are explicitly allowed to contain these literals:
 *   - application/cex/acquisition/venue/bybit   (Bybit venue implementation)
 *   - application/cex/acquisition/venue/dzengi  (Dzengi venue implementation)
 *   - application/cex/normalization             (canonical builders)
 *   - application/cex/job                       (normalization jobs)
 *   - domain/wallet                             (WalletRef parser — defines the grammar)
 *   - application/cex/port                      (SPI constants)
 *   - canonical/correlation                     (CorrelationContract — allowed to define the constants)
 *
 * Forbidden literals (must not appear outside the allowlist above):
 *   - "BYBIT:"   — raw CEX account-ref prefix check
 *   - "bybit:"   — lower-case variant
 *   - ":FUND"    — sub-account suffix literal
 *   - ":UTA"     — sub-account suffix literal
 *   - ":EARN"    — sub-account suffix literal
 *
 * Comment lines (starting with // or *) are excluded.
 */
class VenuePrefixGuardTest {

    private static final Set<String> FORBIDDEN_LITERALS = Set.of(
            "\"BYBIT:\"",
            "\"bybit:\"",
            "\":FUND\"",
            "\":UTA\"",
            "\":EARN\""
    );

    /**
     * Package path segments (relative to com/walletradar) that are allowed to
     * contain venue-specific prefix literals.
     *
     * Ingestion and bootstrap packages are always venue-aware by design.
     * The session/integration bootstrap creates account refs during CEX onboarding
     * (e.g. "BYBIT:" + uid when a Bybit key is added) — this is expected and
     * will have a symmetric counterpart per new venue.
     */
    private static final Set<String> ALLOWED_PATH_SEGMENTS = Set.of(
            "application/cex/acquisition/venue/bybit",
            "application/cex/acquisition/venue/dzengi",
            "application/cex/normalization",
            "application/cex/job",
            "domain/wallet",
            "application/cex/port",
            "canonical/correlation",
            "application/session",
            "bootstrap"
    );

    @Test
    void no_raw_venue_prefix_literals_in_post_normalization_code() throws IOException {
        Path backendMain = resolveBackendMain();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(backendMain)) {
            List<Path> candidates = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isAllowlisted(backendMain, p))
                    .toList();

            for (Path path : candidates) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                        continue;
                    }
                    for (String literal : FORBIDDEN_LITERALS) {
                        if (line.contains(literal)) {
                            violations.add(path + ":" + (i + 1) + "  →  " + line.strip());
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("Raw venue prefix literals found outside the ingestion plane. "
                    + "Use WalletRef / CorrelationContract constants / neutral markers instead.\n"
                    + String.join("\n", violations))
                .isEmpty();
    }

    private static Path resolveBackendMain() {
        // When running from :backend:core subproject the cwd is the module root
        Path candidate = Paths.get("src/main/java/com/walletradar");
        if (candidate.toFile().isDirectory()) {
            return candidate;
        }
        // Fallback: invoked from repo root
        candidate = Paths.get("backend/core/src/main/java/com/walletradar");
        if (candidate.toFile().isDirectory()) {
            return candidate;
        }
        throw new IllegalStateException("Cannot locate backend main sources. "
                + "Checked src/main/java/com/walletradar and backend/core/src/main/java/com/walletradar");
    }

    private boolean isAllowlisted(Path base, Path file) {
        String relative = base.relativize(file).toString().replace("\\", "/");
        return ALLOWED_PATH_SEGMENTS.stream().anyMatch(relative::startsWith);
    }
}
