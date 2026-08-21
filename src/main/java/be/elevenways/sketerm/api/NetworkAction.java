package be.elevenways.sketerm.api;

/**
 * The web_network action vocabulary, mirroring that tool's input-schema enum.
 */
public enum NetworkAction implements WireValue {

    ENABLE("enable"),
    DISABLE("disable"),
    TOGGLE("toggle"),
    /** Read the counters without touching the blocking state. */
    STATUS("status");

    private final String wire;

    NetworkAction(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static NetworkAction fromWire(String wire) {
        return WireValues.parse(NetworkAction.class, wire);
    }
}
