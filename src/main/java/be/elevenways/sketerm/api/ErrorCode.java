package be.elevenways.sketerm.api;

/**
 * THE tool-failure vocabulary, mirroring ErrCode in src/ipc/mcp.zig; retryability rides the member.
 */
public enum ErrorCode implements WireValue {

    INVALID_ARGS("invalid_args", false),
    NOT_FOUND("not_found", false),
    UNAVAILABLE("unavailable", true),
    TIMEOUT("timeout", true),
    REFUSED("refused", false),
    CONFLICT("conflict", false),
    IO_FAILED("io_failed", true),
    UNKNOWN_TOOL("unknown_tool", false),
    FAILED("failed", false);

    private final String wire;
    private final boolean retryable;

    ErrorCode(String wire, boolean retryable) {
        this.wire = wire;
        this.retryable = retryable;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether repeating the same call could plausibly succeed
     */
    public boolean isRetryable() {
        return this.retryable;
    }

    /**
     * @return the member for a wire token, or null when the server named a code this build predates
     */
    public static ErrorCode fromWire(String wire) {
        return WireValues.parse(ErrorCode.class, wire);
    }
}
