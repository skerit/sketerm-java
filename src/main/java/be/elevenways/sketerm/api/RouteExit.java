package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;
import java.util.Objects;

/**
 * Where connections a caller makes itself (outside the browser) go to leave through a route, as web_route_exit
 * answers: a {@code via:} host's loopback SOCKS5 bridge, the proxy a {@code tor} or {@code proxy:} route names, or
 * no proxy at all for {@code direct}.
 *
 * A {@code via:} bridge is kept per host for the life of the server and connects to the host in the background, so
 * an unreachable host refuses every CONNECT; asking again then answers a new bridge ({@link #reused()} false).
 *
 * @param route  the route asked for
 * @param kind   its kind word: direct, tor, via or proxy
 * @param proxy  {@code socks5h://HOST:PORT} or {@code http://HOST:PORT}, null for direct
 * @param reused whether a bridge an earlier ask made and that still serves was handed out again
 */
public record RouteExit(String route, String kind, String proxy, boolean reused) {

    static final String TOOL = "web_route_exit";

    public RouteExit {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * @return the exit of the route
     * @throws InvalidArgsException when the route is outside {@link Routes#GRAMMAR}
     * @throws UnavailableException when the server has no route exits, or none for this route (on:, an
     *         unconfigured tor)
     * @throws ProtocolMismatchException when the answer is not an exit
     */
    static RouteExit ask(ToolCalls calls, String route) {
        Routes.require(route);
        if (!supported(calls)) {
            throw new UnavailableException("Sketerm does not advertise web_route_exit; no route exit for " + route,
                    TOOL, false, null);
        }
        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("route", route);
        Map<String, Object> structured = calls.structured(TOOL, arguments);
        String kind = Json.optStr(structured, "kind");
        String proxy = Json.optStr(structured, "proxy");
        if (kind == null || !(structured.get("reused") instanceof Boolean reused)
                || (proxy == null) != "direct".equals(kind)) {
            throw new ProtocolMismatchException("web_route_exit answered no exit for " + route + ": " + structured);
        }
        return new RouteExit(route, kind, proxy, reused);
    }

    /** @return whether the server advertises web_route_exit (capabilities.web_route_exit.available) */
    static boolean supported(ToolCalls calls) {
        return Browser.available(calls, TOOL);
    }
}
