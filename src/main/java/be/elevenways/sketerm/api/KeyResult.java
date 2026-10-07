package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_key answer: the chords sent as trusted key events and the tree change they caused.
 *
 * @param keys the chords as the server read them
 * @param count how many chords were sent
 * @param loadingAfter whether a navigation was in flight when the keys settled
 * @param deltaKind "delta", "full" or null when the server sent no tree
 * @param delta the follow-up tree text (a {@link Page#snapshot()} line format), or null
 */
public record KeyResult(String keys, int count, boolean loadingAfter, String deltaKind, String delta) {

    static KeyResult decode(Map<String, Object> structured) {

        Long count = Json.optLong(structured, "count");

        return new KeyResult(Json.optStr(structured, "keys"),
                count == null ? 0 : count.intValue(),
                Json.optBool(structured, "loading_after", false),
                Json.optStr(structured, "delta_kind"),
                Json.optStr(structured, "delta"));
    }
}
