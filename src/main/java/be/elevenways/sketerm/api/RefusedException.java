package be.elevenways.sketerm.api;

/**
 * The server declined on policy grounds, not because of a fault.
 */
public class RefusedException extends SketermApiException {

    RefusedException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.REFUSED, ErrorCode.REFUSED.wire(), retryable, cause);
    }
}
