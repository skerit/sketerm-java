package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.mcp.ToolException;
import be.elevenways.sketerm.mcp.ToolResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE api-layer call seam: one place that argues with the session, translates tool failures into
 * {@link SketermApiException}s and insists on a structured payload.
 */
final class ToolCalls {

    /** Time for a bounded server timeout result to cross MCP after the operation's own deadline. */
    private static final long RESPONSE_GRACE_MS = 5_000;

    private final McpSession session;
    private final Duration defaultTimeout;
    private final List<PageStream> streams = new ArrayList<>();
    private boolean streamsClosed;

    ToolCalls(McpSession session, Duration defaultTimeout) {
        this.session = session;
        this.defaultTimeout = defaultTimeout;
    }

    McpSession session() {
        return this.session;
    }

    Duration defaultTimeout() {
        return this.defaultTimeout;
    }

    synchronized void ownStream(PageStream stream) {
        if (this.streamsClosed) throw new IllegalStateException("Sketerm is closed");
        if (stream.isClosed()) throw new IllegalStateException("Page stream is closed");
        this.streams.add(stream);
        // Start under the same admission lock that shutdown uses; never wait for the reader here.
        try {
            stream.start();
        } catch (RuntimeException | Error failure) {
            this.streams.remove(stream);
            throw failure;
        }
    }

    synchronized void releaseStream(PageStream stream) {
        this.streams.remove(stream);
    }

    void closeStreams() {
        List<PageStream> owned;
        synchronized (this) {
            this.streamsClosed = true;
            owned = List.copyOf(this.streams);
            this.streams.clear();
        }
        for (PageStream stream : owned) stream.close();
    }

    /**
     * Call a tool and return its structured payload.
     *
     * @throws SketermApiException when the tool failed
     * @throws ProtocolMismatchException when the answer carried no machine-readable payload
     */
    Map<String, Object> structured(String tool, Map<String, Object> arguments) {
        return this.structured(tool, arguments, this.call(tool, arguments));
    }

    /**
     * @throws SketermApiException when the tool failed
     */
    ToolResult call(String tool, Map<String, Object> arguments) {

        try {
            return this.session.callToolOrThrow(tool, arguments, this.rpcTimeoutMs(arguments));
        } catch (ToolException e) {
            throw SketermApiException.from(e);
        }
    }

    /**
     * The wire operation owns {@code timeout_ms}; the enclosing RPC must outlive it long enough to
     * receive Sketerm's timeout result instead of abandoning a request whose outcome is unknown.
     */
    long rpcTimeoutMs(Map<String, Object> arguments) {

        long operationMs = this.defaultTimeout == null
                ? this.session.getConnection().getTimeoutMs()
                : this.defaultTimeout.toMillis();
        Object requested = arguments == null ? null : arguments.get("timeout_ms");

        if (requested instanceof Number number) {
            operationMs = Math.max(operationMs, number.longValue());
        }

        if (operationMs > Long.MAX_VALUE - RESPONSE_GRACE_MS) {
            return Long.MAX_VALUE;
        }

        return operationMs + RESPONSE_GRACE_MS;
    }

    /**
     * @throws ProtocolMismatchException when the result carried no machine-readable payload
     */
    Map<String, Object> structured(String tool, Map<String, Object> arguments, ToolResult result) {

        if (!result.hasStructured()) {
            throw new ProtocolMismatchException("The tool '" + tool + "' answered with prose only;"
                    + " this client needs structuredContent. The server predates the result-shape"
                    + " migration, or the tool declares no outputSchema. Arguments were "
                    + arguments + ", the prose was: " + result.text());
        }

        return result.structured();
    }

    /**
     * A fresh argument map, so no caller shares one.
     */
    static Map<String, Object> args() {
        return new LinkedHashMap<>();
    }

    /**
     * Put a value only when it is present, keeping server defaults intact.
     */
    static void put(Map<String, Object> arguments, String key, Object value) {

        if (value != null) {
            arguments.put(key, value);
        }
    }

    /**
     * Put a duration as the milliseconds the schemas take.
     */
    static void putTimeout(Map<String, Object> arguments, Duration timeout) {

        if (timeout != null) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("A tool timeout must be positive: " + timeout);
            }

            long timeoutMs = timeout.toMillis();

            if (timeoutMs == 0) {
                throw new IllegalArgumentException("A tool timeout must be at least one millisecond: "
                        + timeout);
            }

            arguments.put("timeout_ms", timeoutMs);
        }
    }
}
