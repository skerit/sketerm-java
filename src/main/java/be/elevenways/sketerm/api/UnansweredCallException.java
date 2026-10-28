package be.elevenways.sketerm.api;

import be.elevenways.sketerm.rpc.CallTimeoutException;

/**
 * A tool call Sketerm did not answer before its client-side deadline, the operation's own budget plus the grace for
 * Sketerm's timeout result to arrive: a {@link TimeoutException} whose outcome is unknown.
 *
 * AIDEV-NOTE: the server itself did not answer, so this says more than a slow page: a caller that pools the browser
 * should believe nothing more of it. The request was sent, so a mutating call must not be retried blindly.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class UnansweredCallException extends TimeoutException {

    private final long timeoutMs;

    UnansweredCallException(String toolName, CallTimeoutException cause) {
        super(cause.getMessage(), toolName, false, cause);
        this.timeoutMs = cause.timeoutMs();
    }

    /** @return the client-side deadline that passed, in milliseconds */
    public long timeoutMs() {
        return this.timeoutMs;
    }

    /** @return true: the request was sent before its deadline started */
    public boolean outcomeUnknown() {
        return true;
    }
}
