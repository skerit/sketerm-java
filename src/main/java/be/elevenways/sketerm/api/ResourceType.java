package be.elevenways.sketerm.api;

/**
 * THE resource-class vocabulary a policy can block, mirroring web_open's policy.block_types enum
 * (and the engine's own filter.RType, which that enum is generated from).
 */
public enum ResourceType implements WireValue {

    /** Anything the engine could not classify; blocking it is the catch-all. */
    OTHER("other"),
    /** The top-level document itself. */
    DOCUMENT("document"),
    /** An iframe's document. */
    SUBDOCUMENT("subdocument"),
    STYLESHEET("stylesheet"),
    SCRIPT("script"),
    IMAGE("image"),
    FONT("font"),
    /** Every fetch/XHR the page makes. */
    XHR("xhr"),
    MEDIA("media"),
    WEBSOCKET("websocket"),
    /** Beacons and ping attributes. */
    PING("ping");

    private final String wire;

    ResourceType(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static ResourceType fromWire(String wire) {
        return WireValues.parse(ResourceType.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static ResourceType require(String wire) {
        return WireValues.require(ResourceType.class, wire);
    }
}
