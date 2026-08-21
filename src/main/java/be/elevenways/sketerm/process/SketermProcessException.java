package be.elevenways.sketerm.process;

/**
 * Thrown when a Sketerm child process cannot be started, written to, or shut down.
 */
public class SketermProcessException extends RuntimeException {

    public SketermProcessException(String message) {
        super(message);
    }

    public SketermProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
