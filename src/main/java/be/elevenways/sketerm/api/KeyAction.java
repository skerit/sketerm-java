package be.elevenways.sketerm.api;

/**
 * What one web_input key event does.
 */
public enum KeyAction implements WireValue {

    /** The key goes down and stays HELD until its UP. */
    DOWN("down"),
    /** A held key is released. */
    UP("up"),
    /** Down and up at once: two edges on the wire. */
    PRESS("press");

    private final String wire;

    KeyAction(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }
}
