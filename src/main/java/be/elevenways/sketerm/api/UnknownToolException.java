package be.elevenways.sketerm.api;

/**
 * The tool is not published by this server or is withheld by its tool policy.
 */
public class UnknownToolException extends SketermApiException {

    UnknownToolException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.UNKNOWN_TOOL, ErrorCode.UNKNOWN_TOOL.wire(), retryable, cause);
    }
}
