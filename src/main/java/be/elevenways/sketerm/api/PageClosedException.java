package be.elevenways.sketerm.api;

/**
 * A call on a {@link Page} this client already closed, refused before it goes out.
 *
 * <p>The handle would be free to be reused by a later view, so sending the call anyway could drive
 * somebody else's page: this is the one refusal that is deliberately local.</p>
 */
public class PageClosedException extends SketermApiException {

    private final int handle;

    PageClosedException(int handle, String what) {
        super("View " + handle + " was closed by this client; " + what
                + " has nothing to address. Open a new page rather than reusing this one.");
        this.handle = handle;
    }

    /**
     * @return the handle the closed page held
     */
    public int getHandle() {
        return this.handle;
    }
}
