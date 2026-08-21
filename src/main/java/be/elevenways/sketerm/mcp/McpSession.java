package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.rpc.JsonRpcConnection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An initialized MCP session over one {@link JsonRpcConnection}, covering the four methods
 * Sketerm implements: initialize, tools/list, tools/call and ping.
 */
public final class McpSession implements AutoCloseable {

    /** The revision this client advertises; Sketerm echoes whatever it is sent. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String CLIENT_NAME = "sketerm-java";
    private static final String CLIENT_VERSION = "0.1.0";

    private final JsonRpcConnection connection;
    private final Map<String, Object> serverInfo;
    private final Map<String, Object> capabilities;
    private final String protocolVersion;

    private McpSession(JsonRpcConnection connection,
                       Map<String, Object> serverInfo,
                       Map<String, Object> capabilities,
                       String protocolVersion) {
        this.connection = connection;
        this.serverInfo = serverInfo;
        this.capabilities = capabilities;
        this.protocolVersion = protocolVersion;
    }

    /**
     * Perform the handshake: initialize, assert the tools capability, then notify initialized.
     *
     * @throws McpException when the server advertises no tools capability
     */
    public static McpSession initialize(JsonRpcConnection connection) {

        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", new LinkedHashMap<String, Object>());
        params.put("clientInfo", clientInfo);

        Map<String, Object> result = connection.call("initialize", params);
        Map<String, Object> capabilities = Json.optMap(result, "capabilities");

        if (capabilities == null || !capabilities.containsKey("tools")) {
            throw new McpException("The server advertises no 'tools' capability: " + result);
        }

        connection.notify("notifications/initialized", null);

        return new McpSession(connection,
                Json.optMap(result, "serverInfo"),
                capabilities,
                Json.optStr(result, "protocolVersion"));
    }

    public JsonRpcConnection getConnection() {
        return this.connection;
    }

    /**
     * @return the serverInfo member, or null when the server sent none
     */
    public Map<String, Object> getServerInfo() {
        return this.serverInfo;
    }

    public Map<String, Object> getCapabilities() {
        return this.capabilities;
    }

    /**
     * @return the protocolVersion the server echoed
     */
    public String getProtocolVersion() {
        return this.protocolVersion;
    }

    /**
     * @return the server name, or null when serverInfo is absent
     */
    public String getServerName() {
        return this.serverInfo == null ? null : Json.optStr(this.serverInfo, "name");
    }

    /**
     * @return the server version, or null when serverInfo is absent
     */
    public String getServerVersion() {
        return this.serverInfo == null ? null : Json.optStr(this.serverInfo, "version");
    }

    /**
     * Sketerm sends no nextCursor, so this is the complete list in one call.
     */
    public List<ToolDescriptor> listTools() {

        Map<String, Object> result = this.connection.call("tools/list", new LinkedHashMap<>());
        List<Object> raw = Json.list(result, "tools");
        List<ToolDescriptor> tools = new ArrayList<>(raw.size());

        for (Object entry : raw) {
            tools.add(ToolDescriptor.decode(Json.asMap(entry, "a tools/list entry")));
        }

        return List.copyOf(tools);
    }

    /**
     * Call a tool and decode its result; an isError result is returned, not thrown.
     *
     * @param arguments the tool arguments, an empty object when null
     */
    public ToolResult callTool(String name, Map<String, Object> arguments) {
        return this.callTool(name, arguments, this.connection.getTimeoutMs());
    }

    /**
     * @param timeoutMs the deadline for this single call
     */
    public ToolResult callTool(String name, Map<String, Object> arguments, long timeoutMs) {

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? new LinkedHashMap<String, Object>() : arguments);

        return ToolResult.decode(this.connection.call("tools/call", params, timeoutMs));
    }

    /**
     * Call a tool and turn an isError result into a {@link ToolException}.
     */
    public ToolResult callToolOrThrow(String name, Map<String, Object> arguments) {
        return this.callTool(name, arguments).orThrow(name);
    }

    /**
     * Call a tool with a deadline for this single JSON-RPC round trip.
     */
    public ToolResult callToolOrThrow(String name, Map<String, Object> arguments, long timeoutMs) {
        return this.callTool(name, arguments, timeoutMs).orThrow(name);
    }

    /**
     * Round-trip the server; Sketerm answers with an empty result.
     */
    public void ping() {
        this.connection.call("ping", new LinkedHashMap<>());
    }

    @Override
    public void close() {
        this.connection.close();
    }
}
