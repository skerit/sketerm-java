package be.elevenways.sketerm.api;

/**
 * What a web_eval out_file holds, mirroring the schema's format enum.
 */
public enum EvalFormat implements WireValue {

    /** The value was a string and was written as ITSELF, so a CSV stays a CSV. */
    TEXT("text"),
    /** Anything else, written as its JSON. */
    JSON("json");

    private final String wire;

    EvalFormat(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static EvalFormat fromWire(String wire) {
        return WireValues.parse(EvalFormat.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static EvalFormat require(String wire) {
        return WireValues.require(EvalFormat.class, wire);
    }
}
