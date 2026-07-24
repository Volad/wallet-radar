package com.walletradar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * WS-8 (ADR-073) source scan: post-normalization read/query code must not branch on a network name
 * baked into a correlation-id string. ArchUnit sees type dependencies but not string constants, so a
 * literal scan is needed to enforce that {@code "lp-position:solana:"} — the concentrated-liquidity
 * network discriminator — is consumed via the stamped {@code lpConcentrated} capability instead of a
 * prefix test on the consumption plane.
 *
 * <p>Ingestion-plane packages are explicitly allowed to contain these literals — they are the single
 * place network specifics belong (mirroring {@link VenuePrefixGuardTest}):</p>
 * <ul>
 *   <li>{@code application/normalization} — {@code SolanaLpPositionResolver} <em>defines</em> the
 *       correlation-id grammar.</li>
 *   <li>{@code application/liquiditypools/enrichment} — {@code SolanaLpPositionReader} is a
 *       single-network on-chain adapter, network-aware by design.</li>
 * </ul>
 *
 * <p>Comment lines (starting with {@code //} or {@code *}) are excluded, so documentation may still
 * reference the historical prefix.</p>
 */
class NetworkBranchGuardTest {

    private static final Set<String> FORBIDDEN_LITERALS = Set.of(
            "\"lp-position:solana:"
    );

    private static final Set<String> ALLOWED_PATH_SEGMENTS = Set.of(
            "application/normalization",
            "application/liquiditypools/enrichment"
    );

    @Test
    void no_network_named_correlation_prefix_literals_in_post_normalization_code() throws IOException {
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
                            violations.add(path + ":" + (i + 1) + "  \u2192  " + line.strip());
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("Network-named correlation-id prefixes found outside the ingestion plane. "
                    + "Consume the stamped lpConcentrated capability instead of branching on the "
                    + "network name.\n"
                    + String.join("\n", violations))
                .isEmpty();
    }

    private static Path resolveBackendMain() {
        Path candidate = Paths.get("src/main/java/com/walletradar");
        if (candidate.toFile().isDirectory()) {
            return candidate;
        }
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
