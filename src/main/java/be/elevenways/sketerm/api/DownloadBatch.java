package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One web_download answer over several files, or the view's download listing.
 *
 * <p>A batch is never all-or-nothing: a url that could not start is one FAILED entry beside the
 * files that did land, and the counts are the server's own.</p>
 *
 * @param listing whether this answer lists downloads this view already knows - page-initiated ones
 *                included - rather than reporting fetches this call started
 */
public record DownloadBatch(List<Download> downloads,
                            int completed,
                            int failed,
                            int unfinished,
                            boolean listing) {

    /** The cap web_download puts on one call's url list, since they are fetched one at a time. */
    public static final int MAX_URLS = 64;

    /**
     * @return every entry that is on disk complete
     */
    public List<Download> completedDownloads() {
        return this.downloads.stream().filter(Download::succeeded).toList();
    }

    /**
     * @return whether every url in this call landed
     */
    public boolean allCompleted() {
        return this.failed == 0 && this.unfinished == 0 && !this.downloads.isEmpty();
    }

    static DownloadBatch decode(Map<String, Object> structured) {

        List<Download> downloads = new ArrayList<>();
        List<Object> raw = Json.optList(structured, "downloads");

        if (raw != null) {
            for (Object element : raw) {
                downloads.add(Download.decode(Json.asMap(element, "a web_download entry")));
            }
        }

        Long completed = Json.optLong(structured, "completed");
        Long failed = Json.optLong(structured, "failed");
        Long unfinished = Json.optLong(structured, "unfinished");

        return new DownloadBatch(List.copyOf(downloads),
                completed == null ? 0 : completed.intValue(),
                failed == null ? 0 : failed.intValue(),
                unfinished == null ? 0 : unfinished.intValue(),
                Json.optBool(structured, "listing", false));
    }
}
