package be.elevenways.sketerm.api;

/**
 * THE state of a view's response-body capture, mirroring the capture_state enum of web_capture's
 * and web_capture_set's output schemas.
 */
public enum CaptureState implements WireValue {

    /** Matching exchanges are being recorded. */
    ACTIVE("active"),
    /** Nothing new is recorded; what is held stays readable. */
    DISABLED("disabled"),
    /** The view has no capture. */
    NONE("none"),
    /** The browser helper could not hold the capture; an open that meets this fails closed. */
    REFUSED("refused"),
    /** The helper reported a state this server build does not name. */
    UNKNOWN("unknown");

    private final String wire;

    CaptureState(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static CaptureState require(String wire) {
        return WireValues.require(CaptureState.class, wire);
    }
}
