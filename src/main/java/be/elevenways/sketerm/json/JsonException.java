package be.elevenways.sketerm.json;

/**
 * Thrown when JSON text cannot be parsed, written, or does not carry the expected shape.
 */
public class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
