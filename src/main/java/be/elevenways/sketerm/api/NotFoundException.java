package be.elevenways.sketerm.api;

/**
 * The addressed thing (a view, a node, a file) does not exist.
 */
public class NotFoundException extends SketermApiException {

    NotFoundException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.wire(), retryable, cause);
    }
}
