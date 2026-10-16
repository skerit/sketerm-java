package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tool that answered with isError, the failure channel distinct from a JSON-RPC error.
 *
 * The structured shape puts a machine-readable failure under structuredContent.error. A server
 * answering text only starts the text with "error CODE: " (" (retryable)" after a retryable code),
 * which is read the same way; with neither the code and retryable flag are null.
 */
public class ToolException extends RuntimeException {

    /** A text-only result's error line: code, optional retryable marker, message. */
    private static final Pattern TEXT_ERROR =
            Pattern.compile("^error ([a-z_]+)( \\(retryable\\))?: (.*)$", Pattern.DOTALL);

    private final String toolName;
    private final String code;
    private final Boolean retryable;
    private final ToolResult result;

    private ToolException(String message,
                          String toolName,
                          String code,
                          Boolean retryable,
                          ToolResult result) {
        super(message);
        this.toolName = toolName;
        this.code = code;
        this.retryable = retryable;
        this.result = result;
    }

    /**
     * Build the exception for an isError result, reading structuredContent.error when present.
     */
    public static ToolException of(String toolName, ToolResult result) {

        String code = null;
        Boolean retryable = null;
        String message = result.text();

        Map<String, Object> error = result.structured() == null
                ? null
                : Json.optMap(result.structured(), "error");

        if (error != null) {
            code = Json.optStr(error, "code");
            String structuredMessage = Json.optStr(error, "message");

            if (structuredMessage != null && !structuredMessage.isBlank()) {
                message = structuredMessage;
            }

            Object rawRetryable = error.get("retryable");

            if (rawRetryable instanceof Boolean b) {
                retryable = b;
            }
        }

        if (error == null && message != null) {
            Matcher line = TEXT_ERROR.matcher(message);

            if (line.matches()) {
                code = line.group(1);
                retryable = line.group(2) != null;
                message = line.group(3);
            }
        }

        if (message == null || message.isBlank()) {
            message = "(no message)";
        }

        String rendered = "Tool '" + toolName + "' failed"
                + (code == null ? "" : " [" + code + "]")
                + ": " + message
                + (retryable == null ? "" : " (retryable: " + retryable + ")");

        return new ToolException(rendered, toolName, code, retryable, result);
    }

    public String getToolName() {
        return this.toolName;
    }

    /**
     * @return the structured error code, or null when the server sent none
     */
    public String getCode() {
        return this.code;
    }

    /**
     * @return the structured retryable flag, or null when the server sent none
     */
    public Boolean getRetryable() {
        return this.retryable;
    }

    public ToolResult getResult() {
        return this.result;
    }
}
