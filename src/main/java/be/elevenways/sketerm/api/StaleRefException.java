package be.elevenways.sketerm.api;

/**
 * A semantic id that no longer addresses anything on the page: take a fresh snapshot and act again.
 *
 * <p>Raised both client-side, when a {@link Ref} comes from a document the page has since left, and
 * from the server's own refusal, which arrives as a generic 'failed' naming the page's reason.</p>
 */
public class StaleRefException extends SketermApiException {

    private final Ref ref;

    StaleRefException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.FAILED, ErrorCode.FAILED.wire(), retryable, cause);
        this.ref = null;
    }

    StaleRefException(String message, Ref ref) {
        super(message, null, null, null, null, null);
        this.ref = ref;
    }

    /**
     * @return the offending ref, or null when only the server knew which id it was
     */
    public Ref getRef() {
        return this.ref;
    }
}
