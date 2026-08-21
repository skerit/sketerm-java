package be.elevenways.sketerm.rpc;

import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.process.SketermProcess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a scripted `sh` child, so framing and process supervision are exercised against real pipes.
 */
@DisabledOnOs(OS.WINDOWS)
class StdioSubprocessTest {

    @Test
    @DisplayName("A scripted child answers one call, then its death drains the next")
    void oneAnswerThenDeath() {

        // Answer the first request verbatim, complain on stderr, and exit non-zero.
        String script = "read -r line; "
                + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pong\":true}}'; "
                + "echo 'scripted child is leaving' >&2; "
                + "exit 3";

        SketermProcess process = SketermProcess.start(List.of("sh", "-c", script), null, null);
        StdioTransport transport = new StdioTransport(process);

        try (JsonRpcConnection connection = new JsonRpcConnection(transport)) {

            // 1. The framed request reaches the child and its answer correlates back
            Map<String, Object> result = connection.call("ping", null, 10_000);
            assertTrue(Json.boolVal(result, "pong"), "step 1: the scripted answer arrived");

            // 2. The child is gone, so the next call drains with its exit code and stderr
            TransportException failure = assertThrows(TransportException.class,
                    () -> connection.call("ping", null, 10_000), "step 2: the next call fails");

            assertTrue(failure.getMessage().contains("exited with code 3"),
                    "step 2: the exit code is surfaced");
            assertTrue(failure.getMessage().contains("scripted child is leaving"),
                    "step 2: the stderr tail is surfaced");
        }

        // 3. Closing the connection closed the process
        assertFalse(process.isAlive(), "step 3: the child is reaped");
        assertEquals(3, process.getExitCode(), "step 3: its exit code is readable");
    }

    @Test
    @DisplayName("A never-answering child is closed by force, not waited on forever")
    void closeTerminatesAnIgnoringChild() {

        // Ignore SIGTERM and sleep; only destroyForcibly can end this.
        String script = "trap '' TERM; while true; do sleep 1; done";

        SketermProcess process = SketermProcess.start(List.of("sh", "-c", script), null, null);

        assertTrue(process.isAlive(), "the child started");

        long started = System.currentTimeMillis();
        process.close(300);
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(process.isAlive(), "the child was force-killed");
        assertTrue(elapsed < 5_000, "close did not hang, it took " + elapsed + "ms");
    }

    @Test
    @DisplayName("The stderr ring keeps the most recent lines only")
    void stderrRingKeepsTheTail() throws Exception {

        String script = "i=1; while [ $i -le 250 ]; do echo \"line $i\" >&2; i=$((i+1)); done";

        SketermProcess process = SketermProcess.start(List.of("sh", "-c", script), null, null);

        try {
            // describeFailureContext waits for the pump, which is exactly what we need here.
            String context = process.describeFailureContext();

            assertNotNull(context, "the context renders");
            assertTrue(context.contains("line 250"), "the newest line is kept");
            assertFalse(context.contains("line 1\n"), "the oldest lines were dropped");
            assertEquals(SketermProcess.STDERR_RING_SIZE, process.getStderrTail().size(),
                    "the ring is capped");
        } finally {
            process.close();
        }
    }

    @Test
    @DisplayName("The environment and working directory reach the child")
    void environmentAndCwd() {

        String script = "read -r line; "
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"who\":\"%s\",\"cwd\":\"%s\"}}\\n' "
                + "\"$SKETERM_TEST_MARKER\" \"$PWD\"";

        SketermProcess process = SketermProcess.start(
                List.of("sh", "-c", script),
                new java.io.File("/"),
                SketermProcess.environment("SKETERM_TEST_MARKER", "hello-from-the-test"));

        try (JsonRpcConnection connection = new JsonRpcConnection(new StdioTransport(process))) {

            Map<String, Object> result = connection.call("ping", null, 10_000);

            assertEquals("hello-from-the-test", Json.str(result, "who"), "the env override arrived");
            assertEquals("/", Json.str(result, "cwd"), "the working directory arrived");
        }
    }
}
