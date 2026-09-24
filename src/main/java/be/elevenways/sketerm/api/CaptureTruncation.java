package be.elevenways.sketerm.api;

/**
 * Why a captured body is shorter than what the page received, mirroring the truncated-reason enum
 * of web_capture's output schema. Nothing is ever cut silently: a cut body always names one of the
 * non-{@link #NONE} members.
 */
public enum CaptureTruncation implements WireValue {

    /** The whole body is held. */
    NONE("none"),
    /** The capture's per-body cap (max_body_bytes). */
    BODY_CAP("body_cap"),
    /** The capture's total cap (max_total_bytes) ran out mid-body. */
    TOTAL_CAP("total_cap"),
    /** The browser helper could not allocate the rest. */
    NO_MEMORY("no_memory"),
    /** The helper reported a reason this server build does not name. */
    UNKNOWN("unknown");

    private final String wire;

    CaptureTruncation(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether the held body is shorter than what the page received
     */
    public boolean truncated() {
        return this != NONE;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static CaptureTruncation require(String wire) {
        return WireValues.require(CaptureTruncation.class, wire);
    }
}
