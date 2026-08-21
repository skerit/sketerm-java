package be.elevenways.sketerm.rpc;

/**
 * Thrown when the underlying message channel fails.
 */
public class TransportException extends RuntimeException {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
