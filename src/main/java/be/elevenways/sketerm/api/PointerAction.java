package be.elevenways.sketerm.api;

/**
 * What one web_input pointer event does, mirroring the schema's action enum.
 */
public enum PointerAction implements WireValue {

    /** The pointer moves to the point with no button change. */
    MOVE("move"),
    /** A button goes down at the point and stays HELD until its UP. */
    DOWN("down"),
    /** A held button is released at the point. */
    UP("up"),
    /** The pointer leaves the view (hover styles end). */
    LEAVE("leave");

    private final String wire;

    PointerAction(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }
}
