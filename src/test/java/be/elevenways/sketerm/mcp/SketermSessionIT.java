package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.process.SketermProcess;
import be.elevenways.sketerm.rpc.JsonRpcConnection;
import be.elevenways.sketerm.rpc.StdioTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the installed `sketerm mcp` binary end to end, skipped when it is not on PATH.
 *
 * <p>The server is mid-migration between result shapes, so every assertion here is shape-agnostic:
 * the decode rule is what turns either shape into the same {@link ToolResult}.</p>
 */
class SketermSessionIT {

    private static final long CALL_TIMEOUT_MS = 30_000;

    @Test
    @DisplayName("A real Sketerm server initializes, lists, pings, calls and closes")
    void realServerJourney() {

        String binary = findSketerm();
        assumeTrue(binary != null, "sketerm is not installed on this machine");

        SketermProcess process = SketermProcess.start(
                List.of(binary, "mcp", "--tools", "browser,core"), null, null);

        JsonRpcConnection connection = new JsonRpcConnection(new StdioTransport(process));
        connection.setTimeoutMs(CALL_TIMEOUT_MS);

        try (McpSession session = McpSession.initialize(connection)) {

            // 1. The handshake succeeded and the server identified itself
            assertNotNull(session.getServerName(), "step 1: the server has a name");
            assertTrue(session.getCapabilities().containsKey("tools"),
                    "step 1: it advertises the tools capability");
            assertEquals(McpSession.PROTOCOL_VERSION, session.getProtocolVersion(),
                    "step 1: it echoes the requested protocol version");

            // 2. The browser and core groups together publish a substantial tool set
            List<ToolDescriptor> tools = session.listTools();
            assertTrue(tools.size() >= 10, "step 2: at least ten tools, got " + tools.size());

            for (ToolDescriptor tool : tools) {
                assertFalse(tool.name().isBlank(), "step 2: every tool is named");
                assertNotNull(tool.inputSchema(), "step 2: every tool has an input schema");
            }

            // 3. ping round-trips
            session.ping();

            // 4. A benign tool call decodes through whichever shape this build emits
            ToolResult capabilities = session.callToolOrThrow("capabilities", Map.of());
            assertFalse(capabilities.content().isEmpty(), "step 4: the call produced content");
            assertTrue(capabilities.hasStructured(),
                    "step 4: capabilities is machine-readable under either result shape");

            // 5. An unknown tool is the isError channel, not a JSON-RPC error
            ToolResult unknown = session.callTool("definitely_not_a_tool_" + System.nanoTime(), Map.of());
            assertTrue(unknown.isError(), "step 5: the unknown tool reports an error result");

            ToolException failure = assertThrows(
                    ToolException.class,
                    () -> unknown.orThrow("definitely_not_a_tool"),
                    "step 5: orThrow turns it into an exception");
            assertFalse(failure.getMessage().isBlank(), "step 5: with a message");

            // 6. The session still works after a tool failure
            session.ping();
        }

        // 7. Closing the session reaped the child
        assertFalse(process.isAlive(), "step 7: the sketerm process is gone");
    }

    private static String findSketerm() {

        String path = System.getenv("PATH");

        if (path == null) {
            return null;
        }

        for (String entry : path.split(File.pathSeparator)) {
            File candidate = new File(entry, "sketerm");

            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }

        return null;
    }
}
