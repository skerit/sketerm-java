package be.elevenways.sketerm.api;

/**
 * One line of the compact semantic tree, parsed far enough to act on.
 *
 * @param depth the indentation depth, two spaces per level
 * @param marker the delta marker (+, - or ~) when the line came from a delta, otherwise null
 * @param line the original line, the authority for anything this record does not model
 */
public record TreeNode(int id, String role, String name, int depth, String marker, String line) {

    /**
     * @return whether the node's name contains the text, ignoring case
     */
    public boolean nameContains(String text) {
        return this.name != null && TreeText.containsIgnoreCase(this.name, text);
    }
}
