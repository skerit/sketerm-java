package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One page of a view's captured exchanges, oldest cursor first.
 *
 * <p>Nothing is lost silently: a body past a cap is flagged on its exchange, an exchange the caps
 * could not hold at all is counted in {@link #dropped()}, and more finished exchanges past this page
 * set {@link #more()}. Page on with {@code since = nextSince()}.</p>
 *
 * @param state whether the capture is still recording
 * @param nextSince the cursor to pass as 'since' for the next page
 * @param headCursor the newest cursor assigned so far: read it BEFORE an action and wait from it
 * @param more further finished exchanges are held past this page
 * @param inFlight exchanges whose response began and has not finished
 * @param storedBytes every byte held for the view, request bodies included
 * @param exchanges finished ones first; in-flight ones after them only when they were asked for
 */
public record CapturedExchanges(CaptureState state,
                                long since,
                                long nextSince,
                                long headCursor,
                                boolean more,
                                long inFlight,
                                long storedBytes,
                                long maxBodyBytes,
                                long maxTotalBytes,
                                Drops dropped,
                                List<CapturedExchange> exchanges) {

    public CapturedExchanges {
        exchanges = List.copyOf(exchanges);
    }

    /**
     * Matching exchanges that were never recorded, by reason.
     *
     * @param pendingFull too many matching requests awaited their response at once
     * @param totalFull max_total_bytes was already spent when the response began
     * @param entriesFull the per-view table (4096 exchanges) was full
     * @param noMemory the browser helper could not allocate the record
     */
    public record Drops(long pendingFull, long totalFull, long entriesFull, long noMemory) {

        public long total() {
            return this.pendingFull + this.totalFull + this.entriesFull + this.noMemory;
        }
    }

    static CapturedExchanges decode(Map<String, Object> structured) {

        List<Object> raw = Json.optList(structured, "exchanges");
        List<CapturedExchange> exchanges = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                exchanges.add(CapturedExchange.decode(Json.asMap(element, "a web_capture exchange")));
            }
        }

        Map<String, Object> dropped = Json.optMap(structured, "dropped");

        return new CapturedExchanges(CaptureState.require(Json.str(structured, "capture_state")),
                orZero(Json.optLong(structured, "since")),
                orZero(Json.optLong(structured, "next_since")),
                orZero(Json.optLong(structured, "head_cursor")),
                Json.optBool(structured, "more", false),
                orZero(Json.optLong(structured, "in_flight")),
                orZero(Json.optLong(structured, "stored_bytes")),
                orZero(Json.optLong(structured, "max_body_bytes")),
                orZero(Json.optLong(structured, "max_total_bytes")),
                dropped == null ? new Drops(0, 0, 0, 0) : new Drops(
                        orZero(Json.optLong(dropped, "pending_full")),
                        orZero(Json.optLong(dropped, "total_full")),
                        orZero(Json.optLong(dropped, "entries_full")),
                        orZero(Json.optLong(dropped, "no_memory"))),
                exchanges);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
