package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv this client launches with, and its one fallback for a server that predates --structured.
 */
class LaunchTest {

    /** What an older `sketerm mcp` does with --structured: refuse it before speaking MCP. */
    private static final String REFUSE_FLAG = "for a; do [ \"$a\" = --structured ] && "
            + "{ echo 'sketerm mcp: unknown flag (see --help)' >&2; exit 2; }; done\n";

    /** Answer initialize with the request's own id, then wait for end-of-file. */
    private static final String ANSWER_INITIALIZE = "read -r line\n"
            + "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]*\\).*/\\1/p')\n"
            + "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{\"tools\":{}},"
            + "\"serverInfo\":{\"name\":\"sketerm\",\"version\":\"0.2.2\"}}}\\n' \"$id\"\n"
            + "cat >/dev/null\n";

    @Test
    @DisplayName("Every launch asks for structured results; the legacy argv is the same without the flag")
    void structuredFlagInArgv() {

        SketermOptions options = SketermOptions.builder().binaryPath("/opt/sketerm").instanceName("x").build();

        assertEquals(List.of("/opt/sketerm", "mcp", "--structured", "--tools", "browser,core", "--name", "x"),
                options.command(), "the flag rides every launch");
        assertEquals(List.of("/opt/sketerm", "mcp", "--tools", "browser,core", "--name", "x"),
                options.legacyCommand(), "and only it is dropped for an older server");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("A server that refuses --structured as an unknown flag is relaunched without it")
    void olderServerIsRelaunchedWithoutTheFlag(@TempDir Path dir) throws IOException {

        Path binary = script(dir, REFUSE_FLAG + ANSWER_INITIALIZE);

        try (Sketerm sketerm = Sketerm.launch(options(binary))) {
            assertEquals("0.2.2", sketerm.serverVersion(), "the relaunched server finished the handshake");
            assertFalse(sketerm.process().getCommand().contains(SketermOptions.STRUCTURED_FLAG),
                    "the relaunch dropped the flag");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("A current server keeps the flag, and any other failure is not retried")
    void currentServerAndOtherFailures(@TempDir Path dir) throws IOException {

        try (Sketerm sketerm = Sketerm.launch(options(script(dir, ANSWER_INITIALIZE)))) {
            assertTrue(sketerm.process().getCommand().contains(SketermOptions.STRUCTURED_FLAG),
                    "a server that takes the flag runs with it");
        }

        // An unknown flag that is not ours fails the legacy launch too: one retry, never a loop.
        Path refusesAll = script(dir, "echo 'sketerm mcp: unknown flag (see --help)' >&2; exit 2\n");
        RuntimeException refused = assertThrows(RuntimeException.class, () -> Sketerm.launch(options(refusesAll)));
        assertTrue(refused.getMessage().contains("unknown flag"), "the refusal is surfaced: " + refused.getMessage());

        Path crashes = script(dir, "echo 'something else broke' >&2; exit 2\n");
        RuntimeException crashed = assertThrows(RuntimeException.class, () -> Sketerm.launch(options(crashes)));
        assertTrue(crashed.getMessage().contains(SketermOptions.STRUCTURED_FLAG),
                "a different failure is reported for the structured launch, not retried: " + crashed.getMessage());
    }

    private static SketermOptions options(Path binary) {
        return SketermOptions.builder().binaryPath(binary.toString()).defaultTimeout(Duration.ofSeconds(10)).build();
    }

    private static Path script(Path dir, String body) throws IOException {

        Path file = Files.createTempFile(dir, "sketerm", ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));

        return file;
    }
}
