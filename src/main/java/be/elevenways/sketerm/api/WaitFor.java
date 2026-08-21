package be.elevenways.sketerm.api;

/**
 * The web_wait condition vocabulary, mirroring the 'for' enum of that tool's input schema.
 */
public enum WaitFor implements WireValue {

    /** No load is in flight. */
    LOAD("load", false),
    /** The title contains the argument, or any title when it is omitted. */
    TITLE("title", true),
    /** The argument appears in the page's semantic tree. */
    TEXT("text", true),
    /** The DOM stopped changing for 600ms. */
    IDLE("idle", false);

    private final String wire;
    private final boolean acceptsArgument;

    WaitFor(String wire, boolean acceptsArgument) {
        this.wire = wire;
        this.acceptsArgument = acceptsArgument;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether an 'arg' means anything for this condition
     */
    public boolean acceptsArgument() {
        return this.acceptsArgument;
    }

    public static WaitFor fromWire(String wire) {
        return WireValues.parse(WaitFor.class, wire);
    }
}
