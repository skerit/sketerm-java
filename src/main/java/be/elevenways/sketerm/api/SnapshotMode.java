package be.elevenways.sketerm.api;

/**
 * The web_snapshot mode vocabulary, mirroring that tool's input-schema enum.
 */
public enum SnapshotMode implements WireValue {

    /** One coalesced delta since the last snapshot, the server's default. */
    AUTO("auto"),
    FULL("full"),
    /** The per-revision replay, for debugging a page that changes on its own. */
    HISTORY("history");

    private final String wire;

    SnapshotMode(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static SnapshotMode fromWire(String wire) {
        return WireValues.parse(SnapshotMode.class, wire);
    }
}
