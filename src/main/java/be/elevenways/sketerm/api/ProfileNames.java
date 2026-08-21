package be.elevenways.sketerm.api;

import java.util.Set;

/**
 * THE profile-name rule, mirroring webprofiles.validName in the server: 1-64 characters of
 * [a-z0-9_-], and neither reserved word.
 *
 * <p>It is checked client-side as well because the server refuses fail-closed: a rejected name
 * opens nothing, and finding that out before the call keeps the refusal local and cheap.</p>
 */
public final class ProfileNames {

    /** The server's own cap, so a name accepted here survives its sanitizer unchanged. */
    public static final int MAX_LENGTH = 64;

    /** Names that already mean "no profile" in the tools' vocabulary. */
    public static final Set<String> RESERVED = Set.of("default", "none");

    private ProfileNames() {
    }

    public static boolean isValid(String name) {

        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {

            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';

            if (!ok) {
                return false;
            }
        }

        return !RESERVED.contains(name);
    }

    /**
     * @return the name itself when it is usable
     * @throws InvalidArgsException naming the rule when it is not
     */
    public static String require(String name, String tool) {

        if (isValid(name)) {
            return name;
        }

        throw new InvalidArgsException("'" + name + "' is not a usable profile name: use 1-"
                + MAX_LENGTH + " characters of a-z, 0-9, '_' or '-' ('default' and 'none' are"
                + " reserved)", tool, false, null);
    }
}
