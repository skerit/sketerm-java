package be.elevenways.sketerm.api;

/**
 * An underlying read, write or transfer failed.
 */
public class IoFailedException extends SketermApiException {

    IoFailedException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.IO_FAILED, ErrorCode.IO_FAILED.wire(), retryable, cause);
    }
}
