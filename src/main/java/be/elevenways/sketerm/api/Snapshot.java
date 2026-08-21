package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One web_snapshot answer: the compact semantic tree plus the document/revision its ids are good in.
 *
 * <p>A snapshot taken in {@link SnapshotMode#AUTO} is a DELTA, so its tree holds only what changed;
 * search it for something the page always had and you will miss it. Ask for
 * {@link SnapshotMode#FULL} when you want the whole page.</p>
 */
public final class Snapshot {

    private final int handle;
    private final String backend;
    private final String url;
    private final String title;
    private final boolean loading;
    private final String kind;
    private final int document;
    private final int revision;
    private final boolean unchanged;
    private final String tree;

    private List<TreeNode> nodes;

    private Snapshot(int handle,
                     String backend,
                     String url,
                     String title,
                     boolean loading,
                     String kind,
                     int document,
                     int revision,
                     boolean unchanged,
                     String tree) {
        this.handle = handle;
        this.backend = backend;
        this.url = url;
        this.title = title;
        this.loading = loading;
        this.kind = kind;
        this.document = document;
        this.revision = revision;
        this.unchanged = unchanged;
        this.tree = tree;
    }

    /**
     * Decode a web_snapshot or web_open structured payload.
     *
     * @throws ProtocolMismatchException when the payload carries no tree
     */
    static Snapshot decode(int handle, Map<String, Object> structured, String toolName) {

        String tree = Json.optStr(structured, "snapshot");

        if (tree == null) {
            String failure = Json.optStr(structured, "snapshot_error");

            throw new ProtocolMismatchException(toolName + " answered without a 'snapshot' field"
                    + (failure == null ? "" : " (" + failure + ")")
                    + "; this client needs the structured tree, not prose");
        }

        Long document = Json.optLong(structured, "document");
        Long revision = Json.optLong(structured, "revision");

        return new Snapshot(handle,
                Json.optStr(structured, "backend"),
                Json.optStr(structured, "url"),
                Json.optStr(structured, "title"),
                Json.optBool(structured, "loading", false),
                Json.optStr(structured, "kind"),
                document == null ? 0 : document.intValue(),
                revision == null ? 0 : revision.intValue(),
                Json.optBool(structured, "unchanged", false),
                tree);
    }

    /**
     * @return the view handle this snapshot was taken from
     */
    public int handle() {
        return this.handle;
    }

    /**
     * @return "gui" or "headless"
     */
    public String backend() {
        return this.backend;
    }

    public String url() {
        return this.url;
    }

    public String title() {
        return this.title;
    }

    public boolean isLoading() {
        return this.loading;
    }

    /**
     * @return "full" or "delta" as the server labelled it
     */
    public String kind() {
        return this.kind;
    }

    public int document() {
        return this.document;
    }

    public int revision() {
        return this.revision;
    }

    /**
     * @return true when a delta came back empty, meaning the page did not change
     */
    public boolean isUnchanged() {
        return this.unchanged;
    }

    /**
     * @return the tree exactly as the server rendered it
     */
    public String tree() {
        return this.tree;
    }

    /**
     * @return every line of the tree that carries an [id]
     */
    public List<TreeNode> nodes() {

        if (this.nodes == null) {
            this.nodes = TreeText.parse(this.tree);
        }

        return this.nodes;
    }

    /**
     * @return a ref for this node, guarded to the document and revision of this snapshot
     */
    public Ref refTo(TreeNode node) {
        return new Ref(node.id(), this.document, this.revision);
    }

    /**
     * @return a ref for a raw id read from this snapshot's tree
     */
    public Ref ref(int id) {
        return new Ref(id, this.document, this.revision);
    }

    /**
     * @return the first node whose name contains the text, ignoring case
     */
    public Optional<Ref> find(String text) {

        for (TreeNode node : this.nodes()) {
            if (node.nameContains(text)) {
                return Optional.of(this.refTo(node));
            }
        }

        return Optional.empty();
    }

    /**
     * @return the first node with this role whose name contains the text, ignoring case
     */
    public Optional<Ref> find(String role, String text) {

        for (TreeNode node : this.nodes()) {
            if (role.equals(node.role()) && node.nameContains(text)) {
                return Optional.of(this.refTo(node));
            }
        }

        return Optional.empty();
    }

    /**
     * @return every node whose name contains the text, ignoring case
     */
    public List<TreeNode> findAll(String text) {

        List<TreeNode> matches = new ArrayList<>();

        for (TreeNode node : this.nodes()) {
            if (node.nameContains(text)) {
                matches.add(node);
            }
        }

        return List.copyOf(matches);
    }

    @Override
    public String toString() {
        return "Snapshot[" + this.kind + " doc " + this.document + " rev " + this.revision
                + ", " + this.nodes().size() + " nodes]";
    }
}
