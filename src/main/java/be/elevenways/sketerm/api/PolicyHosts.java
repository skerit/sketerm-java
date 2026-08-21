package be.elevenways.sketerm.api;

import java.util.Locale;

/**
 * THE host-entry rule, mirroring netpolicy.validHostEntry in the server: a bare host name or IP
 * literal, lower-cased, and nothing else.
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
    public static boolean isValid(String host) {

        if (host == null || host.isEmpty() || host.length() > MAX_LENGTH || host.equals("*")) {
            return false;
        }

        // An IPv6 literal keeps its colons, of which it always has at least two; ONE colon is a
        // :port, which an entry must never carry.
        boolean v6 = host.charAt(0) == '[' || count(host, ':') >= 2;

        for (int i = 0; i < host.length(); i++) {

            char ch = host.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '.'
                    || (v6 && (ch == ':' || ch == '[' || ch == ']'));

            if (!ok) {
                return false;
            }
        }

        return true;
    }

    /**
     * Fold an entry the way the server does before validating it.
     *
     * @return the lower-cased entry
     * @throws InvalidArgsException naming the rule when the entry cannot be used
     */
    public static String require(String host) {

        String folded = host == null ? null : host.toLowerCase(Locale.ROOT);

        if (isValid(folded)) {
            return folded;
        }

        throw new InvalidArgsException("'" + host + "' is not a usable host entry: bare host names"
                + " or IP literals only - no '*' (write no policy instead of an allow-all one), no"
                + " scheme, no port, no path", "web_open", false, null);
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
