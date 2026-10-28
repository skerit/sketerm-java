package be.elevenways.sketerm.api;

/**
 * What a navigation produced, mirroring the outcome enum of the navigation fact web_open, web_navigate and
 * web_fetch report.
 */
public enum NavigationOutcome implements WireValue {

    /** The document arrived with an HTTP status below 400. */
    PAGE("page"),
    /** The server answered 4xx or 5xx; the page shows its body, or the engine's error page. */
    HTTP_ERROR("http_error"),
    /** No HTTP answer at all: DNS, TLS, refused, reset or timed out. */
    NETWORK_ERROR("network_error"),
    /** Stopped or cancelled, and nothing replaced it. */
    ABORTED("aborted"),
    /** Another top-level navigation superseded the requested one (a script's redirect, a meta refresh). */
    REPLACED("replaced"),
    /** The response became a download, not a page. */
    DOWNLOAD("download"),
    /** Refused inside the engine, by the filter list or the network policy, before it left the process. */
    BLOCKED("blocked"),
    /** A data:, about:, file: or blob: url, which has no HTTP status. */
    NON_HTTP("non_http"),
    /** Not finished inside the budget. */
    PENDING("pending"),
    /** Nothing was logged: a back/forward-cache or same-document navigation. */
    NO_REQUEST("no_request"),
    /** web_fetch without following redirects: the server redirected and it was not followed. */
    REDIRECT("redirect");

    private final String wire;

    NavigationOutcome(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static NavigationOutcome require(String wire) {
        return WireValues.require(NavigationOutcome.class, wire);
    }
}
