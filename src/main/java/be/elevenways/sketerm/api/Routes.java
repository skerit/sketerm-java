package be.elevenways.sketerm.api;

import java.net.InetAddress;

/**
 * THE browser-route rule, mirroring sketerm's {@code web/route.zig}: {@code direct}, {@code tor},
 * {@code via:HOST}, {@code on:HOST}, or a forward proxy the caller runs,
 * {@code proxy:socks5h://HOST:PORT} or {@code proxy:http://HOST:PORT}.
 *
 * <p>A proxy route sends every request of the view through that proxy, which resolves every host
 * and decides what is reachable: a loopback or private origin is reached through it too, there is
 * no bypass list. {@code socks5://} is refused because it would resolve the page host locally;
 * credentials, paths, PAC files and a missing or zero port are refused as well. It is checked
 * client-side for the same reason {@link PolicyHosts} is: the server refuses fail-closed, and a
 * refusal found before the call stays local.</p>
 */
public final class Routes {

    /** The longest host a route may name, as the server checks it. */
    public static final int MAX_HOST = 255;

    /** The longest proxy url: the longest scheme, {@code ://}, a host and {@code :65535}. */
    public static final int MAX_PROXY_URL = "socks5h://".length() + MAX_HOST + ":65535".length();

    /** Every route text, in the server's own spelling of its grammar. */
    public static final String GRAMMAR = "direct | tor | via:<host> | on:<host>"
            + " | proxy:socks5h://<host>:<port> | proxy:http://<host>:<port>";

    private static final String PROXY_PREFIX = "proxy:";

    private Routes() {
    }

    /**
     * @return the route, unchanged
     * @throws InvalidArgsException naming the rule the route breaks
     */
    public static String require(String route) {

        if (route == null) {
            throw refusal("A route must not be null; use direct");
        }

        if (route.startsWith(PROXY_PREFIX)) {
            String why = proxyRefusal(route.substring(PROXY_PREFIX.length()));
            if (why != null) {
                throw refusal("Unusable proxy route '" + route + "': " + why);
            }
            return route;
        }

        if (route.equals("direct") || route.equals("tor") || route.matches("(?:via|on):[^\\s:]+")) {
            return route;
        }

        throw refusal("Unknown browser route: " + route + " (expected " + GRAMMAR + ")");
    }

    /**
     * @return whether the route is a caller-given forward proxy
     */
    public static boolean isProxy(String route) {
        return route != null && route.startsWith(PROXY_PREFIX);
    }

    /**
     * An untrusted view's loader dials one proxy or none, so it takes the direct, tor and proxy
     * routes; {@code via:} and {@code on:} are refused.
     *
     * @return whether an untrusted open may take this (valid) route
     */
    public static boolean servesUntrusted(String route) {
        return route.equals("direct") || route.equals("tor") || isProxy(route);
    }

    /**
     * @param url the text after {@code proxy:}
     * @return why the url is outside the grammar, or null when it is a usable proxy url
     */
    static String proxyRefusal(String url) {

        if (url.length() > MAX_PROXY_URL) {
            return "the proxy url is too long";
        }

        int separator = url.indexOf("://");
        if (separator < 0) {
            return "a proxy route is proxy:socks5h://HOST:PORT or proxy:http://HOST:PORT";
        }
        String scheme = url.substring(0, separator);
        if (scheme.equals("socks5")) {
            return "socks5:// would resolve the page's hostname on this machine; use socks5h://"
                    + " (the proxy resolves every host)";
        }
        if (!scheme.equals("socks5h") && !scheme.equals("http")) {
            return "a proxy route is proxy:socks5h://HOST:PORT or proxy:http://HOST:PORT";
        }

        String authority = url.substring(separator + 3);
        if (authority.indexOf('@') >= 0) {
            return "a proxy route carries no credentials (proxy authentication is not supported)";
        }
        if (authority.indexOf('/') >= 0 || authority.indexOf('?') >= 0 || authority.indexOf('#') >= 0) {
            return "a proxy route is scheme://HOST:PORT with no path, query or trailing slash";
        }

        int colon;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return badHost();
            }
            if (close + 1 >= authority.length() || authority.charAt(close + 1) != ':') {
                return badPort();
            }
            colon = close + 1;
        } else {
            colon = authority.lastIndexOf(':');
            if (colon < 0) {
                return badPort();
            }
        }

        if (!validHost(authority.substring(0, colon))) {
            return badHost();
        }

        String port = authority.substring(colon + 1);
        if (port.isEmpty() || port.length() > 5 || port.charAt(0) == '0' || !port.matches("[0-9]+")
                || Integer.parseInt(port) > 65535) {
            return badPort();
        }

        return null;
    }

    /**
     * A DNS name, a canonical dotted IPv4 literal or a bracketed IPv6 literal; never an address
     * spelled any other way a resolver accepts.
     */
    private static boolean validHost(String host) {

        if (host.isEmpty() || host.length() > MAX_HOST) {
            return false;
        }

        if (host.startsWith("[")) {
            if (host.length() < 4 || !host.endsWith("]")) return false;
            String literal = host.substring(1, host.length() - 1);
            // Literal-only parsing never consults DNS, and rejects scoped addresses.
            if (!literal.contains(":") || !literal.matches("[0-9a-fA-F:.]+")) return false;
            try {
                InetAddress.ofLiteral(literal);
                return true;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")
                    || !label.matches("[A-Za-z0-9_-]+")) return false;
        }

        // A numeric last label is how every URL parser recognises IPv4; only the canonical
        // dotted quad is one (127.1 and 0x7f.0.0.1 are not).
        if (!labels[labels.length - 1].matches("[0-9]+")) {
            return true;
        }
        if (labels.length != 4) {
            return false;
        }
        for (String label : labels) {
            if (!label.matches("[0-9]+") || (label.length() > 1 && label.charAt(0) == '0')
                    || label.length() > 3 || Integer.parseInt(label) > 255) return false;
        }
        return true;
    }

    private static String badHost() {
        return "the proxy host must be a DNS name, a dotted IPv4 literal or a bracketed IPv6 literal";
    }

    private static String badPort() {
        return "the proxy needs an explicit port, 1-65535, without leading zeros";
    }

    private static InvalidArgsException refusal(String message) {
        return new InvalidArgsException(message, "web_open", false, null);
    }
}
