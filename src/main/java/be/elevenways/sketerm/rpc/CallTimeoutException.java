package be.elevenways.sketerm.rpc;

/**
 * A sent JSON-RPC request did not answer before its client-side deadline.
 *
 * <p>The peer may still complete the operation, so callers must not blindly retry a mutation.</p>
 */
public final class CallTimeoutException extends TransportException {

    private final String method;
    private final long timeoutMs;

    CallTimeoutException(String method, long timeoutMs, String context, Throwable cause) {
        super("Timed out after " + timeoutMs + "ms waiting for '" + method
                + "'; the request was sent, so its outcome is unknown; " + context, cause);
        this.method = method;
        this.timeoutMs = timeoutMs;
    }

    public String method() {
        return this.method;
    }

    public long timeoutMs() {
        return this.timeoutMs;
    }

    /**
     * @return true because the timeout starts only after the transport accepted the request
     */
    public boolean outcomeUnknown() {
        return true;
    }
}
