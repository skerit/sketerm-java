package be.elevenways.sketerm.api;

/**
 * THE per-file download vocabulary, mirroring web_download's state enum.
 *
 * <p>Only {@link #FAILED} is a failure: {@link #TIMED_OUT} and the two not-finished-yet members are
 * DATA, since the call's budget ran out while the transfer was still legitimately running.</p>
 */
public enum DownloadState implements WireValue {

    /** The file is on disk and its bytes and digest are the file's own. */
    DONE("done"),
    /** The transfer will not complete; {@link Download#reason()} says why. */
    FAILED("failed"),
    /** The call's budget ran out first; the download may still be running. */
    TIMED_OUT("timed_out"),
    /** A batch entry the budget never reached; call again with the urls that are left. */
    NOT_STARTED("not_started"),
    /** Listed only: the engine accepted the target and is transferring. */
    RUNNING("running"),
    /** Listed only: the engine has not decided the target yet. */
    PENDING("pending");

    private final String wire;

    DownloadState(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether the file landed complete
     */
    public boolean isComplete() {
        return this == DONE;
    }

    /**
     * @return whether this state is neither a completion nor a failure, so the transfer may still be
     *         in flight and a later {@link Page#downloads()} can say
     */
    public boolean isUnfinished() {
        return this != DONE && this != FAILED;
    }

    public static DownloadState fromWire(String wire) {
        return WireValues.parse(DownloadState.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static DownloadState require(String wire) {
        return WireValues.require(DownloadState.class, wire);
    }
}
