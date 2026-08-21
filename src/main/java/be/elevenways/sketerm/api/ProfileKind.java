package be.elevenways.sketerm.api;

/**
 * The browsing-identity vocabulary, mirroring the profile_kind enum web_open and web_tabs declare.
 */
public enum ProfileKind implements WireValue {

    /** The shared cookie jar every view without a profile uses; context id 0. */
    DEFAULT("default"),
    /** A named persistent identity whose storage survives the view and the server. */
    NAMED("named"),
    /** A throwaway in-memory identity, destroyed with the last view holding it. */
    EPHEMERAL("ephemeral");

    private final String wire;

    ProfileKind(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return the member for a wire token, or null when the answer named none (a GUI backend omits
     *         the field entirely, since the browser's identity containers are the user's own)
     */
    public static ProfileKind fromWire(String wire) {
        return WireValues.parse(ProfileKind.class, wire);
    }

    /**
     * @throws SketermApiException when the server sent a kind this build does not know
     */
    public static ProfileKind require(String wire) {
        return WireValues.require(ProfileKind.class, wire);
    }
}
