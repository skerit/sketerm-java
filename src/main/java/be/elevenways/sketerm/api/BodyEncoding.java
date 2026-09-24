package be.elevenways.sketerm.api;

/**
 * How a captured body is presented, mirroring the encoding enum of web_capture's output schema.
 */
public enum BodyEncoding implements WireValue {

    /** Text, as UTF-8 (transcoded from a declared single-byte charset where the server says so). */
    UTF8("utf8"),
    /** A binary body, base64-encoded in the answer. */
    BASE64("base64"),
    /** A binary body, written as its raw bytes to a file. */
    BINARY("binary");

    private final String wire;

    BodyEncoding(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static BodyEncoding require(String wire) {
        return WireValues.require(BodyEncoding.class, wire);
    }
}
