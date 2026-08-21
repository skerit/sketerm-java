package be.elevenways.sketerm.api;

/**
 * The server answered in a shape this client cannot use, naming the field that was missing.
 *
 * <p>The usual cause is a Sketerm predating the structuredContent migration: it answers a browser
 * tool with prose only, so there is no view handle to hold on to.</p>
 */
public class ProtocolMismatchException extends SketermApiException {

    public ProtocolMismatchException(String message) {
        super(message);
    }
}
