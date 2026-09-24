package be.elevenways.sketerm.api;

/**
 * Which body of a captured exchange to read, mirroring web_capture's part enum.
 */
public enum BodyPart implements WireValue {

    /** What the page received, with the response headers. */
    RESPONSE("response"),
    /** What the page sent, e.g. a POST's JSON. */
    REQUEST("request");

    private final String wire;

    BodyPart(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static BodyPart require(String wire) {
        return WireValues.require(BodyPart.class, wire);
    }
}
