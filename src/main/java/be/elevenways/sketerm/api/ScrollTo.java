package be.elevenways.sketerm.api;

/**
 * The keyword half of web_scroll's 'to' argument; the other half is a node id, which
 * {@link Page#scrollTo(Ref)} sends instead.
 */
public enum ScrollTo implements WireValue {

    TOP("top"),
    BOTTOM("bottom"),
    PAGE_UP("page_up"),
    PAGE_DOWN("page_down");

    private final String wire;

    ScrollTo(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static ScrollTo fromWire(String wire) {
        return WireValues.parse(ScrollTo.class, wire);
    }
}
