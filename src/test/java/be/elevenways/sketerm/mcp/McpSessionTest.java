package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.rpc.FakeTransport;
import be.elevenways.sketerm.rpc.JsonRpcConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionTest {

    @Test
    @DisplayName("The handshake initializes, notifies, lists and calls")
    void handshakeJourney() {

        FakeTransport transport = new FakeTransport(request -> {

            String method = Json.str(request, "method");

            if (method.startsWith("notifications/")) {
                return null;
            }

            long id = Json.longVal(request, "id");

            return switch (method) {
                case "initialize" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
                        + "\"protocolVersion\":\"" + Json.str(Json.map(request, "params"), "protocolVersion")
                        + "\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"sketerm\",\"version\":\"0.1.3\"}}}";
                case "tools/list" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                        + "{\"name\":\"web_open\",\"description\":\"Open\",\"inputSchema\":{\"type\":\"object\"}}"
                        + "]}}";
                case "tools/call" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"{\\\"view\\\":1}\"}]}}";
                case "ping" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
                default -> null;
            };
        });

        try (McpSession session = McpSession.initialize(new JsonRpcConnection(transport))) {

            // 1. The handshake read serverInfo and echoed the protocol version
            assertEquals("sketerm", session.getServerName(), "step 1: the server name");
            assertEquals("0.1.3", session.getServerVersion(), "step 1: the server version");
            assertEquals(McpSession.PROTOCOL_VERSION, session.getProtocolVersion(),
                    "step 1: the echoed protocol version");
            assertTrue(session.getCapabilities().containsKey("tools"), "step 1: the tools capability");

            // 2. notifications/initialized went out right after initialize
            assertEquals("notifications/initialized",
                    Json.str(Json.parseObject(transport.getSent().get(1)), "method"),
                    "step 2: the initialized notification is the second frame");

            // 3. tools/list decodes into descriptors
            List<ToolDescriptor> tools = session.listTools();
            assertEquals(1, tools.size(), "step 3: one tool");
            assertEquals("web_open", tools.getFirst().name(), "step 3: its name");

            // 4. tools/call rides the compatibility decoder
            ToolResult result = session.callTool("web_open", Map.of("url", "about:blank"));
            assertTrue(result.hasStructured(), "step 4: the JSON-in-text payload was adopted");
            assertEquals(1L, Json.longVal(result.structured(), "view"), "step 4: its contents");

            // 5. ping round-trips
            session.ping();
        }
    }

    @Test
    @DisplayName("A server without a tools capability is refused loudly")
    void refusesAToollessServer() {

        FakeTransport transport = new FakeTransport(request ->
                "{\"jsonrpc\":\"2.0\",\"id\":" + Json.longVal(request, "id")
                        + ",\"result\":{\"capabilities\":{\"prompts\":{}}}}");

        JsonRpcConnection connection = new JsonRpcConnection(transport);

        try {
            McpException failure = assertThrows(McpException.class,
                    () -> McpSession.initialize(connection), "the handshake refuses");
            assertTrue(failure.getMessage().contains("tools"), "and names the missing capability");
        } finally {
            connection.close();
        }
    }
}
