package be.elevenways.sketerm.api;

/**
 * The operation did not settle inside its budget.
 */
public class TimeoutException extends SketermApiException {

    TimeoutException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.TIMEOUT, ErrorCode.TIMEOUT.wire(), retryable, cause);
    }
}
