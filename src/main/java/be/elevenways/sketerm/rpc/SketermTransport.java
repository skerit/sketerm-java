package be.elevenways.sketerm.rpc;

/**
 * One framed message channel to a Sketerm server, the seam an HTTP transport can later fill.
 */
public interface SketermTransport extends AutoCloseable {

    /**
     * Send one complete JSON message; the transport owns the framing.
     *
     * @throws TransportException when the message cannot be delivered
     */
    void send(String message);

    /**
     * Block until the next message arrives.
     *
     * @return the next message, or null at end of stream
     * @throws TransportException when the channel fails for a reason other than end of stream
     */
    String receive();

    /**
     * A description of why the channel is in the state it is, appended to failures.
     */
    String describeFailureContext();

    @Override
    void close();
}
