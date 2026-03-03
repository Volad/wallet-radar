package com.walletradar.ingestion.classifier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierRawDataAccessGuardTest {

    private static final Path CLASSIFIER_SOURCE_DIR = Path.of("src/main/java/com/walletradar/ingestion/classifier");
    private static final Set<String> EXCLUDED_FILES = Set.of("RawTransactionNormalizationView.java");
    private static final List<ForbiddenPattern> FORBIDDEN_PATTERNS = List.of(
            new ForbiddenPattern(Pattern.compile("\\.rawData\\("), "rawData() access must stay inside RawTransactionNormalizationView"),
            new ForbiddenPattern(Pattern.compile("\\bgetRawData\\("), "getRawData() access must stay inside RawTransactionNormalizationView"),
            new ForbiddenPattern(Pattern.compile("\\blog\\s*\\.\\s*get\\("), "log.get(...) should be wrapped by view helpers"),
            new ForbiddenPattern(Pattern.compile("\\blog\\s*\\.\\s*getString\\("), "log.getString(...) should be wrapped by view helpers"),
            new ForbiddenPattern(Pattern.compile("\\blog\\s*\\.\\s*getList\\("), "log.getList(...) should be wrapped by view helpers"),
            new ForbiddenPattern(Pattern.compile("\\btransfer\\s*\\.\\s*get\\("), "transfer.get(...) should be wrapped by view helpers"),
            new ForbiddenPattern(Pattern.compile("\\binternalTransfer\\s*\\.\\s*get\\("), "internalTransfer.get(...) should be wrapped by view helpers")
    );

    @Test
    void classifiers_should_not_access_raw_document_directly() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(CLASSIFIER_SOURCE_DIR)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> inspectFile(path, violations));
        }

        assertThat(violations)
                .withFailMessage("Direct raw document access found:%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    private void inspectFile(Path path, List<String> violations) {
        String fileName = path.getFileName().toString();
        if (EXCLUDED_FILES.contains(fileName)) {
            return;
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            violations.add(path + ": cannot read source file: " + e.getMessage());
            return;
        }

        for (ForbiddenPattern forbidden : FORBIDDEN_PATTERNS) {
            if (forbidden.pattern().matcher(content).find()) {
                violations.add(path + ": " + forbidden.reason());
            }
        }
    }

    private record ForbiddenPattern(Pattern pattern, String reason) {}
}

