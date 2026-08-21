package be.elevenways.sketerm.api;

/**
 * The web_query kind vocabulary, mirroring that tool's input-schema enum.
 */
public enum QueryKind implements WireValue {

    /** Nodes whose name contains the argument. */
    FIND_TEXT("find_text"),
    /** Children of the node id in the argument. */
    SUBTREE("subtree"),
    FOCUSED("focused");

    private final String wire;

    QueryKind(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static QueryKind fromWire(String wire) {
        return WireValues.parse(QueryKind.class, wire);
    }
}
