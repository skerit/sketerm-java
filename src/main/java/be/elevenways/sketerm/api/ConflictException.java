package be.elevenways.sketerm.api;

/**
 * The request collided with the state the server is in.
 */
public class ConflictException extends SketermApiException {

    ConflictException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.CONFLICT, ErrorCode.CONFLICT.wire(), retryable, cause);
    }
}
