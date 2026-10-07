package be.elevenways.sketerm.api;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Several nodes of a snapshot match a name equally well, so {@link Snapshot#find} refuses to guess one.
 *
 * Raised client-side only; its message lists every candidate's line, so a caller can act on one by its id.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public class AmbiguousMatchException extends SketermApiException {

    private final List<TreeNode> matches;

    AmbiguousMatchException(String role, String text, List<TreeNode> matches) {
        super("Several elements match " + (role == null ? "" : role + " ") + "\"" + text + "\": "
                + matches.stream().map(node -> node.line().strip()).collect(Collectors.joining(", "))
                + "; act on one by its id");
        this.matches = List.copyOf(matches);
    }

    /**
     * @return every node that matched, in tree order
     */
    public List<TreeNode> matches() {
        return this.matches;
    }
}
