package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_capture_set answer: the capture as it stands after a narrowing.
 *
 * @param state whether the capture still records
 * @param storedBytes the bytes held after the change
 * @param headCursor the newest cursor assigned so far
 */
public record CaptureChange(CaptureState state, long storedBytes, long inFlight, long headCursor) {

    static CaptureChange decode(Map<String, Object> structured) {

        Long inFlight = Json.optLong(structured, "in_flight");
        Long head = Json.optLong(structured, "head_cursor");

        return new CaptureChange(CaptureState.require(Json.str(structured, "capture_state")),
                Json.longVal(structured, "stored_bytes"),
                inFlight == null ? 0 : inFlight,
                head == null ? 0 : head);
    }
}
