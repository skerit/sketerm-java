package be.elevenways.sketerm.rpc;

import be.elevenways.sketerm.json.Json;

import be.elevenways.protoblast.common.time.Pace;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * An in-process transport whose answers are scripted by a function over the sent request.
 *
 * A null answer means "stay silent", which is how the timeout and notification paths are
 * exercised without a child process.
 */
public final class FakeTransport implements SketermTransport {

    private static final String EOF = "\0EOF";

    private final Function<Map<String, Object>, String> responder;
    private final LinkedBlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final CompletableFuture<String> firstSent = new CompletableFuture<>();

    private volatile String failureContext = "fake transport";
    private volatile boolean closed;

    public FakeTransport(Function<Map<String, Object>, String> responder) {
        this.responder = responder;
    }

    public List<String> getSent() {
        return this.sent;
    }

    /**
     * Waits until the client sent its first frame.
     *
     * @return that frame
     * @throws Exception when nothing was sent within the wait
     */
    public String awaitFirstSent(Duration within) throws Exception {
        return Pace.await(this.firstSent, within);
    }

    public void setFailureContext(String failureContext) {
        this.failureContext = failureContext;
    }

    /** Push a frame the client never asked for. */
    public void push(String line) {
        this.inbound.add(line);
    }

    /** End the stream, as a dying child would. */
    public void endOfStream() {
        this.inbound.add(EOF);
    }

    @Override
    public void send(String message) {

        if (this.closed) {
            throw new TransportException("fake transport is closed");
        }

        this.sent.add(message);
        this.firstSent.complete(message);

        String answer = this.responder.apply(Json.parseObject(message));

        if (answer != null) {
            this.inbound.add(answer);
        }
    }

    @Override
    public String receive() {

        try {
            String line = this.inbound.take();
            return EOF.equals(line) ? null : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public String describeFailureContext() {
        return this.failureContext;
    }

    @Override
    public void close() {
        this.closed = true;
        this.inbound.add(EOF);
    }
}
