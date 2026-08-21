package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.mcp.ToolException;
import be.elevenways.sketerm.mcp.ToolResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE api-layer call seam: one place that argues with the session, translates tool failures into
 * {@link SketermApiException}s and insists on a structured payload.
 */
final class ToolCalls {

    private final McpSession session;
    private final Duration defaultTimeout;

    ToolCalls(McpSession session, Duration defaultTimeout) {
        this.session = session;
        this.defaultTimeout = defaultTimeout;
    }

    McpSession session() {
        return this.session;
    }

    Duration defaultTimeout() {
        return this.defaultTimeout;
    }

    /**
     * Call a tool and return its structured payload.
     *
     * @throws SketermApiException when the tool failed
     * @throws ProtocolMismatchException when the answer carried no machine-readable payload
     */
    Map<String, Object> structured(String tool, Map<String, Object> arguments) {
        return this.structured(tool, arguments, this.call(tool, arguments));
    }

    /**
     * @throws SketermApiException when the tool failed
     */
    ToolResult call(String tool, Map<String, Object> arguments) {

        try {
            return this.session.callToolOrThrow(tool, arguments);
        } catch (ToolException e) {
            throw SketermApiException.from(e);
        }
    }

    /**
     * @throws ProtocolMismatchException when the result carried no machine-readable payload
     */
    Map<String, Object> structured(String tool, Map<String, Object> arguments, ToolResult result) {

        if (!result.hasStructured()) {
            throw new ProtocolMismatchException("The tool '" + tool + "' answered with prose only;"
                    + " this client needs structuredContent. The server predates the result-shape"
                    + " migration, or the tool declares no outputSchema. Arguments were "
                    + arguments + ", the prose was: " + result.text());
        }

        return result.structured();
    }

    /**
     * A fresh argument map, so no caller shares one.
     */
    static Map<String, Object> args() {
        return new LinkedHashMap<>();
    }

    /**
     * Put a value only when it is present, keeping server defaults intact.
     */
    static void put(Map<String, Object> arguments, String key, Object value) {

        if (value != null) {
            arguments.put(key, value);
        }
    }

    /**
     * Put a duration as the milliseconds the schemas take.
     */
    static void putTimeout(Map<String, Object> arguments, Duration timeout) {

        if (timeout != null) {
            arguments.put("timeout_ms", timeout.toMillis());
        }
    }
}
