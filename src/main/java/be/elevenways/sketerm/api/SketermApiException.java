package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.ToolException;

import java.util.Locale;

/**
 * The base of every api-layer failure and THE one home of the error-code to exception mapping.
 *
 * <p>An unrecognised code fails closed as this base type rather than being folded into a
 * neighbouring meaning.</p>
 */
public class SketermApiException extends RuntimeException {

    /** The page reasons Sketerm reports as a plain 'failed' when a semantic id no longer resolves. */
    private static final String[] STALE_MARKERS = {"stale reader id", "stale", "unknown id"};

    private final String toolName;
    private final ErrorCode code;
    private final String rawCode;
    private final Boolean retryable;

    public SketermApiException(String message) {
        this(message, null, null, null, null, null);
    }

    public SketermApiException(String message, Throwable cause) {
        this(message, null, null, null, null, cause);
    }

    protected SketermApiException(String message,
                                  String toolName,
                                  ErrorCode code,
                                  String rawCode,
                                  Boolean retryable,
                                  Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
        this.code = code;
        this.rawCode = rawCode;
        this.retryable = retryable;
    }

    /**
     * Turn a tool failure into its typed api exception.
     *
     * <p>The switch below is exhaustive over {@link ErrorCode} on purpose: a new server code cannot
     * be added without this mapping refusing to compile.</p>
     */
    public static SketermApiException from(ToolException cause) {

        String tool = cause.getToolName();
        String rawCode = cause.getCode();
        ErrorCode code = ErrorCode.fromWire(rawCode);
        Boolean retryable = cause.getRetryable();
        String message = cause.getMessage();

        if (code == null) {
            return new SketermApiException(message, tool, null, rawCode, retryable, cause);
        }

        // A refused act arrives as the generic 'failed' carrying the page's own reason, so the one
        // recognisable shape of it is lifted here instead of at each call site.
        if (code == ErrorCode.FAILED && namesAStaleId(message)) {
            return new StaleRefException(message, tool, retryable, cause);
        }

        return switch (code) {
            case INVALID_ARGS -> new InvalidArgsException(message, tool, retryable, cause);
            case NOT_FOUND -> new NotFoundException(message, tool, retryable, cause);
            case UNAVAILABLE -> new UnavailableException(message, tool, retryable, cause);
            case TIMEOUT -> new TimeoutException(message, tool, retryable, cause);
            case REFUSED -> new RefusedException(message, tool, retryable, cause);
            case CONFLICT -> new ConflictException(message, tool, retryable, cause);
            case IO_FAILED -> new IoFailedException(message, tool, retryable, cause);
            case UNKNOWN_TOOL -> new UnknownToolException(message, tool, retryable, cause);
            case FAILED -> new FailedException(message, tool, retryable, cause);
        };
    }

    /**
     * @return the tool that failed, or null when the failure was raised client-side
     */
    public String getToolName() {
        return this.toolName;
    }

    /**
     * @return the typed code, or null when the server named one this build does not know
     */
    public ErrorCode getCode() {
        return this.code;
    }

    /**
     * @return the code exactly as the server spelled it, or null when it sent none
     */
    public String getRawCode() {
        return this.rawCode;
    }

    /**
     * @return the server's retryable flag, falling back to the fact the known code carries
     */
    public boolean isRetryable() {

        if (this.retryable != null) {
            return this.retryable;
        }

        return this.code != null && this.code.isRetryable();
    }

    private static boolean namesAStaleId(String message) {

        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase(Locale.ROOT);

        for (String marker : STALE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }

        return false;
    }
}
