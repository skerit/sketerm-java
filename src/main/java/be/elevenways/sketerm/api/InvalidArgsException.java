package be.elevenways.sketerm.api;

/**
 * The tool refused the arguments it was given.
 */
public class InvalidArgsException extends SketermApiException {

    InvalidArgsException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.INVALID_ARGS, ErrorCode.INVALID_ARGS.wire(), retryable, cause);
    }
}
