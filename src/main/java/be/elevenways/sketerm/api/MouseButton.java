package be.elevenways.sketerm.api;

/**
 * The button of a web_input pointer edge.
 */
public enum MouseButton implements WireValue {

    LEFT("left"),
    MIDDLE("middle"),
    RIGHT("right");

    private final String wire;

    MouseButton(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }
}
