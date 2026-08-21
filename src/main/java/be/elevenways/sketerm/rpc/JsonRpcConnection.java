package be.elevenways.sketerm.rpc;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.sketerm.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request/response correlation over a {@link SketermTransport}, with one reader thread.
 *
 * <p>The server echoes ids as JSON integers, so every incoming id is normalised to a long before
 * it is looked up. A message whose id matches nothing pending is dropped: that covers both
 * notifications and late answers to timed-out calls.</p>
 */
public final class JsonRpcConnection implements AutoCloseable {

    /** The default per-call deadline. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final SketermTransport transport;
    private final JobRunner jobRunner = JobRunner.create("sketerm-rpc");
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    private volatile boolean closed;
    private volatile TransportException terminalFailure;
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;

    public JsonRpcConnection(SketermTransport transport) {
        this.transport = transport;
        this.jobRunner.startThread(this::readLoop);
    }

    public SketermTransport getTransport() {
        return this.transport;
    }

    public long getTimeoutMs() {
        return this.timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Send a request and block for its result member.
     *
     * @param params the params member, omitted when null
     * @return the result member, always a map (an empty result becomes an empty map)
     * @throws JsonRpcException when the peer answered with an error object
     * @throws TransportException on timeout, end of stream, or a dead channel
     */
    public Map<String, Object> call(String method, Map<String, Object> params) {
        return this.call(method, params, this.timeoutMs);
    }

    /**
     * @param timeoutMs the deadline for this single call
     */
    public Map<String, Object> call(String method, Map<String, Object> params, long timeoutMs) {

        long id = this.nextId.getAndIncrement();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);

        if (params != null) {
            request.put("params", params);
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        synchronized (this.lifecycleLock) {
            this.requireOpen(method);
            this.pending.put(id, future);

            try {
                this.transport.send(Json.write(request));
            } catch (RuntimeException e) {
                this.pending.remove(id);
                throw e;
            }
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            this.pending.remove(id);
            throw new CallTimeoutException(method, timeoutMs,
                    this.transport.describeFailureContext(), e);
        } catch (InterruptedException e) {
            this.pending.remove(id);
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted while waiting for '" + method + "'", e);
        } catch (ExecutionException e) {
            this.pending.remove(id);
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            throw new TransportException("Call '" + method + "' failed", cause);
        }
    }

    /**
     * Send a notification, which carries no id and expects no answer.
     */
    public void notify(String method, Map<String, Object> params) {

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);

        if (params != null) {
            message.put("params", params);
        }

        synchronized (this.lifecycleLock) {
            this.requireOpen(method);
            this.transport.send(Json.write(message));
        }
    }

    /**
     * Close the transport and fail every call still waiting.
     */
    @Override
    public void close() {

        List<CompletableFuture<Map<String, Object>>> waiting;

        synchronized (this.lifecycleLock) {
            if (this.closed) {
                return;
            }

            this.closed = true;
            waiting = this.drainPending();
        }

        completeAll(waiting, new TransportException("Connection closed"));
        this.transport.close();
        this.jobRunner.shutdownNow();
    }

    private void readLoop() {

        try {
            while (!this.closed) {
                String line = this.transport.receive();

                if (line == null) {
                    break;
                }

                if (line.isBlank()) {
                    continue;
                }

                this.dispatch(line);
            }
        } catch (RuntimeException e) {
            this.failConnection("Reader failed: " + e.getMessage(), e);
            return;
        }

        this.failConnection("The server closed its output stream", null);
    }

    private void dispatch(String line) {

        Map<String, Object> message;

        message = Json.parseObject(line);

        Long id = Json.optLong(message, "id");

        if (id == null) {
            return;
        }

        CompletableFuture<Map<String, Object>> future = this.pending.remove(id);

        if (future == null) {
            return;
        }

        Map<String, Object> error = Json.optMap(message, "error");

        if (error != null) {
            int code = error.get("code") instanceof Number n ? n.intValue() : 0;
            String text = Json.optStr(error, "message");
            future.completeExceptionally(new JsonRpcException(code,
                    text == null ? "(no message)" : text, error.get("data")));
            return;
        }

        Object result = message.get("result");

        if (result == null) {
            future.complete(new LinkedHashMap<>());
            return;
        }

        future.complete(Json.asMap(result, "the 'result' member"));
    }

    private void requireOpen(String method) {

        if (this.closed) {
            throw new TransportException("Connection is closed; cannot send '" + method + "'");
        }

        if (this.terminalFailure != null) {
            throw new TransportException("Connection has failed; cannot send '" + method + "': "
                    + this.terminalFailure.getMessage(), this.terminalFailure);
        }
    }

    private void failConnection(String reason, Throwable cause) {

        TransportException failure;
        List<CompletableFuture<Map<String, Object>>> waiting;

        synchronized (this.lifecycleLock) {
            if (this.closed || this.terminalFailure != null) {
                return;
            }

            String context = reason + "; " + this.transport.describeFailureContext();
            failure = cause == null
                    ? new TransportException(context)
                    : new TransportException(context, cause);
            this.terminalFailure = failure;
            waiting = this.drainPending();
        }

        completeAll(waiting, failure);
    }

    private List<CompletableFuture<Map<String, Object>>> drainPending() {
        List<CompletableFuture<Map<String, Object>>> waiting = List.copyOf(this.pending.values());
        this.pending.clear();
        return waiting;
    }

    private static void completeAll(List<CompletableFuture<Map<String, Object>>> waiting,
                                    TransportException failure) {

        for (CompletableFuture<Map<String, Object>> future : waiting) {
            future.completeExceptionally(failure);
        }
    }
}
