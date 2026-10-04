package be.elevenways.sketerm.api;

/** Sketerm's headless colour preferences, mirroring web_open.color_scheme. */
public enum ColorScheme implements WireValue {
    LIGHT("light"),
    DARK("dark");

    private final String wire;

    ColorScheme(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static ColorScheme require(String wire) {
        return WireValues.require(ColorScheme.class, wire);
    }
}
