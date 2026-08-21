package be.elevenways.sketerm.api;

/**
 * The web_navigate action vocabulary, mirroring that tool's input-schema enum.
 */
public enum NavigateAction implements WireValue {

    BACK("back"),
    FORWARD("forward"),
    RELOAD("reload"),
    STOP("stop");

    private final String wire;

    NavigateAction(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static NavigateAction fromWire(String wire) {
        return WireValues.parse(NavigateAction.class, wire);
    }
}
