package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.file.Path;
import java.util.Map;

/**
 * One file web_download reports on, fetched by the view's own browser or found in its download list.
 *
 * @param url what was fetched, empty for a page-initiated download a GUI listing found
 * @param path where the file landed, as the server named it
 * @param bytes the size the server measured, 0 while a transfer is unfinished
 * @param sha256 the digest of the finished file, null unless the state is
 *               {@link DownloadState#DONE}, so a caller can verify without reading the bytes back
 * @param reason why a non-complete state holds, null when the answer named none
 */
public record Download(String url,
                       Path path,
                       DownloadState state,
                       long bytes,
                       String sha256,
                       String reason) {

    /**
     * @return whether the file is on disk complete
     */
    public boolean succeeded() {
        return this.state.isComplete();
    }

    /**
     * @return whether the transfer may still be running, which a {@link DownloadState#TIMED_OUT}
     *         entry is: the call's budget ran out, not the download
     */
    public boolean unfinished() {
        return this.state.isUnfinished();
    }

    /**
     * @throws ProtocolMismatchException when the entry carries no state, which the schema requires
     */
    static Download decode(Map<String, Object> entry) {

        String state = Json.optStr(entry, "state");

        if (state == null) {
            throw new ProtocolMismatchException("A web_download entry carried no 'state': " + entry);
        }

        String path = Json.optStr(entry, "path");
        String sha256 = Json.optStr(entry, "sha256");
        String reason = Json.optStr(entry, "reason");
        Long bytes = Json.optLong(entry, "bytes");

        return new Download(Json.optStr(entry, "url"),
                path == null || path.isEmpty() ? null : Path.of(path),
                DownloadState.require(state),
                bytes == null ? 0 : bytes,
                sha256 == null || sha256.isEmpty() ? null : sha256,
                reason == null || reason.isEmpty() ? null : reason);
    }
}
