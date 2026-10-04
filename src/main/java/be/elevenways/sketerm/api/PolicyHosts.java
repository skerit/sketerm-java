package be.elevenways.sketerm.api;

import java.net.InetAddress;
import java.util.Locale;

/**
 * THE host-entry rule, mirroring netpolicy.validHostEntry: ASCII/punycode names or complete IP
 * literals, optionally with a port (1..65535). IPv6 with a port must be bracketed.
 * Names match their subdomains; IP literals match exactly, never as suffixes. Bare entries allow
 * every port ordinarily, but only the scheme's default port in untrusted mode.
 *
 * <p>It is checked client-side for the same reason {@link ProfileNames} is: the server refuses
 * fail-closed, so a doomed policy would open nothing, and finding that out before the call keeps
 * the refusal local. A {@code *} is refused rather than read as allow-all - write no policy at all
 * instead of one that allows everything.</p>
 */
public final class PolicyHosts {

    /** The server's own list cap; it refuses a longer list loudly rather than truncating it. */
    public static final int MAX_HOSTS = 64;

    /** The DNS name limit the server checks against. */
    public static final int MAX_LENGTH = 253;

    private PolicyHosts() {
    }

    /**
     * @return whether the entry survives the server's own validator unchanged
     */
    public static boolean isValid(String entry) {
        if (entry == null || entry.isEmpty() || entry.length() > MAX_LENGTH) {
            return false;
        }
        String host = entry;
        String port = null;
        if (entry.startsWith("[")) {
            int end = entry.indexOf(']');
            if (end < 0) return false;
            host = entry.substring(1, end);
            if (!host.contains(":")) return false;
            if (end + 1 < entry.length()) {
                if (entry.charAt(end + 1) != ':') return false;
                port = entry.substring(end + 2);
            }
        } else if (count(entry, ':') == 1) {
            int colon = entry.indexOf(':');
            host = entry.substring(0, colon);
            port = entry.substring(colon + 1);
        }
        if (port != null) {
            if (!port.matches("[0-9]+")) return false;
            try {
                int number = Integer.parseInt(port);
                if (number < 1 || number > 65535) return false;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        if (host.contains(":")) {
            // Literal-only parsing never consults DNS, and rejects scoped addresses.
            if (!host.matches("[0-9a-f:.]+")) return false;
            try {
                InetAddress.ofLiteral(host);
                return true;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length == 4 && host.matches("[0-9.]+")) {
            for (String label : labels) {
                try {
                    if (label.isEmpty() || Integer.parseInt(label) > 255) return false;
                } catch (NumberFormatException invalid) {
                    return false;
                }
            }
            return true;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")
                    || !label.matches("[a-z0-9-]+")) return false;
        }
        return !labels[labels.length - 1].matches("[0-9]+");
    }

    /**
     * Fold an entry the way the server does before validating it.
     *
     * @return the lower-cased entry
     * @throws InvalidArgsException naming the rule when the entry cannot be used
     */
    public static String require(String host) {

        // Fold ASCII only: Unicode case folding could turn a non-punycode host into ASCII (Kelvin sign).
        String folded = host == null || !host.chars().allMatch(ch -> ch < 128)
                ? null : host.toLowerCase(Locale.ROOT);

        if (isValid(folded)) {
            return folded;
        }

        throw new InvalidArgsException("'" + host + "' is not a usable host entry: ASCII/punycode names"
                + " or complete IP literals, optionally host:port or [IPv6]:port (1..65535);"
                + " no '*', scheme, path, userinfo, numeric suffix or scoped address",
                "web_open", false, null);
    }

    private static int count(String text, char ch) {

        int found = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                found++;
            }
        }

        return found;
    }
}
