package com.walletradar.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc-coverage guards (Track A / A0, Part 7). Module pages and extensibility reference docs
 * required by the layered architecture model.
 */
class DocumentationCoverageTest {

    private static final List<String> REQUIRED_MODULE_PAGES = List.of(
            "canonical.md",
            "platform.md",
            "application-cex.md",
            "application-costbasis.md",
            "application-portfolio.md",
            "api-bff.md"
    );

    private static final List<String> REQUIRED_REFERENCE_DOCS = List.of(
            "reference/protocol-descriptor.md",
            "reference/capability-behavior-spi.md",
            "reference/extensibility/add-a-network.md",
            "reference/extensibility/add-a-protocol.md",
            "reference/extensibility/add-an-integration.md"
    );

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repo root from user.dir");
    }

    @Test
    void adr_index_lists_every_adr_file() throws IOException {
        Path adrDir = repoRoot().resolve("docs/adr");
        Path index = adrDir.resolve("INDEX.md");
        String indexContent = Files.readString(index);
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(adrDir)) {
            files.filter(p -> p.getFileName().toString().matches("ADR-\\d+-.+\\.md"))
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".md", "");
                        if (!indexContent.contains(name)) {
                            missing.add(name);
                        }
                    });
        }
        assertTrue(missing.isEmpty(),
                "ADR files missing from INDEX.md: " + missing);
    }

    @Test
    void module_template_exists() {
        Path template = repoRoot().resolve("docs/overview/modules/_template.md");
        assertTrue(Files.isRegularFile(template), "Missing docs/overview/modules/_template.md");
    }

    @Test
    void required_module_pages_exist() {
        Path modulesDir = repoRoot().resolve("docs/overview/modules");
        List<String> missing = new ArrayList<>();
        for (String page : REQUIRED_MODULE_PAGES) {
            Path path = modulesDir.resolve(page);
            if (!Files.isRegularFile(path)) {
                missing.add(page);
            }
        }
        assertTrue(missing.isEmpty(),
                "Missing module pages under docs/overview/modules/: " + missing);
    }

    @Test
    void required_extensibility_reference_docs_exist() {
        Path docsDir = repoRoot().resolve("docs");
        List<String> missing = new ArrayList<>();
        for (String relative : REQUIRED_REFERENCE_DOCS) {
            if (!Files.isRegularFile(docsDir.resolve(relative))) {
                missing.add(relative);
            }
        }
        assertTrue(missing.isEmpty(),
                "Missing extensibility reference docs: " + missing);
    }

    @Test
    void extensibility_task_plan_exists() {
        Path plan = repoRoot().resolve("docs/tasks/extensibility-refactor-implementation-plan.md");
        assertTrue(Files.isRegularFile(plan));
    }

    @Test
    void financial_snapshot_script_exists() {
        Path script = repoRoot().resolve("scripts/avco/capture-financial-snapshot.sh");
        assertTrue(Files.isRegularFile(script));
    }
}
