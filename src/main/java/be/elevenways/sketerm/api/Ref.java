package be.elevenways.sketerm.api;

/**
 * A semantic node id together with the document and revision it was handed out in.
 *
 * @param id the [n] the compact tree prints
 * @param document the per-document counter; a new document invalidates every id from the old one
 * @param revision the tree revision the id was read from, kept for diagnostics
 */
public record Ref(int id, int document, int revision) {

    /**
     * A ref whose provenance is unknown, which therefore skips the client-side staleness check.
     */
    public static Ref of(int id) {
        return new Ref(id, 0, 0);
    }

    /**
     * @return whether this ref knows which document it came from
     */
    public boolean hasProvenance() {
        return this.document > 0;
    }

    @Override
    public String toString() {
        return "[" + this.id + "]@doc" + this.document + "/rev" + this.revision;
    }
}
