package be.elevenways.sketerm.api;

/**
 * A modifier held during one web_input event.
 */
public enum InputModifier implements WireValue {

    SHIFT("shift"),
    CTRL("ctrl"),
    ALT("alt"),
    META("meta");

    private final String wire;

    InputModifier(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }
}
