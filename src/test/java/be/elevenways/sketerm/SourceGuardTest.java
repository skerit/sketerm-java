package be.elevenways.sketerm;

import be.elevenways.protoblast.guard.BuiltInRules;
import be.elevenways.protoblast.guard.ScanResult;
import be.elevenways.protoblast.guard.ScanRoot;
import be.elevenways.protoblast.guard.SourceRuleScanner;
import be.elevenways.protoblast.guard.Violation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard: every current-time read and every wait after a failed attempt in this repo, tests included, goes
 * through protoblast's offset clock and Backoff.
 *
 * AIDEV-NOTE: sketerm-java is plugin-less (no protoblast Gradle plugin), so a direct-clock-reads.guard or
 * private-retry-delays.guard marker would gate nothing; this test drives the SAME rule definitions
 * (protoblast-source-guard's BuiltInRules) the plugin repos enforce at compile time.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SourceGuardTest {

    /** @return every Java source set of the repo: main and test */
    private static List<ScanRoot> everyRoot() {
        List<ScanRoot> roots = new ArrayList<>();
        for (String set : List.of("main", "test")) {
            Path root = Path.of("src", set, "java");
            assertTrue(Files.isDirectory(root), "expected to run from the sketerm-java repo root");
            roots.add(ScanRoot.of(root));
        }
        return roots;
    }

    @Test
    void sourcesMustNotReadTheWallClockDirectly() throws IOException {
        ScanResult result = SourceRuleScanner.scan(BuiltInRules.directClockRead(everyRoot()).build());
        assertTrue(result.scannedFiles() > 0, "the scan must actually see sources");

        assertTrue(result.violations().isEmpty(),
            "Direct wall-clock reads (use Now.instant() / Now.millis()):\n"
            + result.violations().stream().map(Violation::format).collect(Collectors.joining("\n")));
    }

    @Test
    void sourcesMustNotCarryPrivateRetryDelayMath() throws IOException {
        ScanResult result = SourceRuleScanner.scan(BuiltInRules.privateRetryDelay(everyRoot()).build());
        assertTrue(result.scannedFiles() > 0, "the scan must actually see sources");

        assertTrue(result.violations().isEmpty(),
            BuiltInRules.PRIVATE_RETRY_DELAY_CONSEQUENCE + "\n"
            + result.violations().stream().map(Violation::format).collect(Collectors.joining("\n")));
    }
}
