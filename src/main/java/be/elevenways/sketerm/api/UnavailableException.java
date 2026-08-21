package be.elevenways.sketerm.api;

/**
 * A dependency the tool needs is not running or not installed.
 */
public class UnavailableException extends SketermApiException {

    UnavailableException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE.wire(), retryable, cause);
    }
}
