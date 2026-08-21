package be.elevenways.sketerm.api;

/**
 * The web_act action vocabulary, mirroring that tool's input-schema enum.
 */
public enum Action implements WireValue {

    CLICK("click", false),
    FOCUS("focus", false),
    /** Types into a text field, picks a native option, or opens and clicks an ARIA option. */
    SET_VALUE("set_value", true),
    SCROLL_INTO_VIEW("scroll_into_view", false),
    HOVER("hover", false);

    private final String wire;
    private final boolean needsValue;

    Action(String wire, boolean needsValue) {
        this.wire = wire;
        this.needsValue = needsValue;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether the act is meaningless without a value argument
     */
    public boolean requiresValue() {
        return this.needsValue;
    }

    public static Action fromWire(String wire) {
        return WireValues.parse(Action.class, wire);
    }
}
