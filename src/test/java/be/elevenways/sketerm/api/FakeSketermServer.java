package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.rpc.FakeTransport;
import be.elevenways.sketerm.rpc.JsonRpcConnection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A scripted Sketerm: one handler per tool name, plus the call log every assertion reads.
 *
 * <p>It answers in the migrated shape by default (structuredContent beside a prose block), which is
 * exactly what the api layer requires; {@link #prose} scripts the old shape on purpose.</p>
 */
final class FakeSketermServer {

    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> tools = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();

    /** One recorded tools/call. */
    record Call(String tool, Map<String, Object> arguments) {
    }

    /**
     * Script a tool with a fixed structured payload.
     */
    FakeSketermServer on(String tool, Map<String, Object> structured) {
        return this.on(tool, arguments -> structured);
    }

    /**
     * Script a tool whose answer depends on its arguments.
     */
    FakeSketermServer on(String tool, Function<Map<String, Object>, Map<String, Object>> handler) {
        this.tools.put(tool, handler);
        return this;
    }

    /**
     * Script a tool that fails with a structured error.
     */
    FakeSketermServer onError(String tool, String code, String message, boolean retryable) {

        return this.on(tool, arguments -> {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("message", message);
            error.put("retryable", retryable);

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("error", error);
            structured.put("__isError", true);

            return structured;
        });
    }

    /**
     * Script a tool that answers with prose only, the pre-migration shape.
     */
    FakeSketermServer prose(String tool, String text) {

        return this.on(tool, arguments -> {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("__prose", text);
            return structured;
        });
    }

    List<Call> calls() {
        return this.calls;
    }

    /**
     * @return every recorded call to one tool
     */
    List<Call> callsTo(String tool) {
        return this.calls.stream().filter(call -> call.tool().equals(tool)).toList();
    }

    /**
     * @return the arguments of the last call to a tool
     * @throws IllegalStateException when the tool was never called
     */
    Map<String, Object> lastArguments(String tool) {

        List<Call> matching = this.callsTo(tool);

        if (matching.isEmpty()) {
            throw new IllegalStateException("The test never called " + tool + "; it called " + this.calls);
        }

        return matching.getLast().arguments();
    }

    /**
     * Build the whole client stack over this fake, ready to drive.
     */
    Browser browser() {
        return new Browser(this.toolCalls());
    }

    ToolCalls toolCalls() {

        JsonRpcConnection connection = new JsonRpcConnection(new FakeTransport(this::answer));

        return new ToolCalls(McpSession.initialize(connection), Duration.ofSeconds(5));
    }

    private String answer(Map<String, Object> request) {

        String method = Json.str(request, "method");
        Object id = request.get("id");

        if (id == null) {
            return null;
        }

        Map<String, Object> result = switch (method) {
            case "initialize" -> initializeResult();
            case "ping" -> new LinkedHashMap<>();
            case "tools/call" -> this.callResult(Json.map(request, "params"));
            default -> throw new IllegalStateException("The fake server has no answer for " + method);
        };

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        return Json.write(response);
    }

    private Map<String, Object> callResult(Map<String, Object> params) {

        String tool = Json.str(params, "name");
        Map<String, Object> arguments = Json.optMap(params, "arguments");

        this.calls.add(new Call(tool, arguments == null ? Map.of() : arguments));

        Function<Map<String, Object>, Map<String, Object>> handler = this.tools.get(tool);

        if (handler == null) {
            return errorResult("unknown_tool", "the tool '" + tool + "' is not scripted", false);
        }

        Map<String, Object> structured = new LinkedHashMap<>(handler.apply(arguments));

        if (structured.remove("__prose") instanceof String prose) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(textBlock(prose)));
            return result;
        }

        boolean isError = Boolean.TRUE.equals(structured.remove("__isError"));
        Object image = structured.remove("__image");

        List<Object> content = new ArrayList<>();
        content.add(textBlock("prose lane"));

        if (image instanceof Map) {
            content.add(image);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("structuredContent", structured);

        if (isError) {
            result.put("isError", true);
        }

        return result;
    }

    private static Map<String, Object> errorResult(String code, String message, boolean retryable) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("error", error);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textBlock(message)));
        result.put("structuredContent", structured);
        result.put("isError", true);

        return result;
    }

    private static Map<String, Object> textBlock(String text) {

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);

        return block;
    }

    /**
     * An image content block, added to a structured payload under the __image key.
     */
    static Map<String, Object> imageBlock(String base64) {

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("data", base64);
        block.put("mimeType", "image/png");

        return block;
    }

    private static Map<String, Object> initializeResult() {

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "sketerm-fake");
        serverInfo.put("version", "0.0.0");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", McpSession.PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return result;
    }
}
