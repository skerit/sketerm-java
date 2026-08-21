package be.elevenways.sketerm.api;

/**
 * A vocabulary member that names its own wire spelling, so no second list of strings exists.
 */
public interface WireValue {

    /**
     * @return the exact token the tool schema declares for this member
     */
    String wire();
}
