package be.elevenways.sketerm.api;

/**
 * The catch-all failure: the tool ran and did not succeed.
 */
public class FailedException extends SketermApiException {

    FailedException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.FAILED, ErrorCode.FAILED.wire(), retryable, cause);
    }
}
