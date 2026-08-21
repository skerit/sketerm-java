package be.elevenways.sketerm.api;

/**
 * THE scheme vocabulary a policy can allow, mirroring web_open's policy.allow_schemes enum.
 *
 * <p>{@code about:} is deliberately not a member: it is always allowed, being a view's own blank
 * document, and refusing it would break view creation itself.</p>
 */
public enum UrlScheme implements WireValue {

    HTTP("http"),
    HTTPS("https"),
    WS("ws"),
    WSS("wss"),
    FILE("file"),
    DATA("data"),
    BLOB("blob");

    private final String wire;

    UrlScheme(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    public static UrlScheme fromWire(String wire) {
        return WireValues.parse(UrlScheme.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static UrlScheme require(String wire) {
        return WireValues.require(UrlScheme.class, wire);
    }
}
