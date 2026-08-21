package be.elevenways.sketerm.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE reader of Sketerm's compact semantic tree notation, shared by snapshots and query matches.
 *
 * <p>It is deliberately line-based: the notation is a listing, not a document, and every consumer
 * that wants more detail has the original line on the {@link TreeNode}.</p>
 */
public final class TreeText {

    private TreeText() {
    }

    /**
     * Parse every line that carries an [id]; header and summary lines are skipped.
     */
    public static List<TreeNode> parse(String text) {

        List<TreeNode> nodes = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return List.of();
        }

        for (String line : text.split("\n", -1)) {

            TreeNode node = parseLine(line);

            if (node != null) {
                nodes.add(node);
            }
        }

        return List.copyOf(nodes);
    }

    /**
     * @return the node this line describes, or null when it carries no [id]
     */
    public static TreeNode parseLine(String line) {

        int open = line.indexOf('[');

        if (open < 0) {
            return null;
        }

        int close = line.indexOf(']', open);

        if (close < 0) {
            return null;
        }

        String digits = line.substring(open + 1, close);

        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }

        String head = line.substring(0, open);

        // Only indentation and a single delta marker may precede the id; anything else means the
        // brackets are page text rather than a node line.
        String marker = null;
        int depth = 0;
        int spaces = 0;

        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);

            if (c == ' ') {
                spaces++;
            } else if ((c == '+' || c == '-' || c == '~') && marker == null) {
                marker = String.valueOf(c);
            } else {
                return null;
            }
        }

        depth = spaces / 2;

        String rest = line.substring(close + 1).trim();
        String role = rest;
        int space = rest.indexOf(' ');

        if (space >= 0) {
            role = rest.substring(0, space);
        }

        return new TreeNode(Integer.parseInt(digits), role, quotedName(rest), depth, marker, line);
    }

    /**
     * @return whether the haystack contains the needle, ignoring case
     */
    public static boolean containsIgnoreCase(String haystack, String needle) {

        if (haystack == null || needle == null) {
            return false;
        }

        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String quotedName(String rest) {

        int open = rest.indexOf('"');

        if (open < 0) {
            return null;
        }

        int close = rest.indexOf('"', open + 1);

        if (close < 0) {
            return null;
        }

        return rest.substring(open + 1, close);
    }
}
