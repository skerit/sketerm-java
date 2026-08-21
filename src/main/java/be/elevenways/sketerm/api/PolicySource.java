package be.elevenways.sketerm.api;

/**
 * Where the policy a view runs came from, mirroring the policy_source enum web_open, web_policy and
 * web_policy_set declare.
 */
public enum PolicySource implements WireValue {

    /** The web_open call carried the policy object itself. */
    CALL("call"),
    /** The view's profile had a session-default policy registered by web_policy_set. */
    PROFILE_DEFAULT("profile_default"),
    /** No policy is installed; this view's traffic is unenforced. */
    NONE("none");

    private final String wire;

    PolicySource(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return the member, or null when the answer named no source at all
     * @throws SketermApiException when a source IS named but this build does not know it, so an
     *         unknown provenance never passes for {@link #NONE}
     */
    public static PolicySource fromWire(String wire) {
        return wire == null || wire.isEmpty() ? null : require(wire);
    }

    /**
     * @throws SketermApiException when the server named a source this build does not know
     */
    public static PolicySource require(String wire) {
        return WireValues.require(PolicySource.class, wire);
    }
}
