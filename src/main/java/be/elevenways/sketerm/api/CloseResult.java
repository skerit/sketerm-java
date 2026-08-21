package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * What web_close did, and what a later handle-less call now addresses.
 *
 * <p>The backend decides how destructive this was: headless it destroyed a helper view, but with a
 * GUI attached it closed the USER'S pane, exactly as close_pane does.</p>
 *
 * @param backend "gui" or "headless"
 * @param closed the handle that was closed
 * @param remaining how many views are left
 * @param current the view a web_* call with no handle now addresses, 0 when none is left
 * @param profile the named profile the view was in, null when it was in none
 * @param profileReleased whether this was the last view of an ephemeral identity, destroyed with it
 */
public record CloseResult(String backend,
                          int closed,
                          int remaining,
                          int current,
                          String profile,
                          boolean profileReleased) {

    /**
     * @return whether this closed a pane the user could see, rather than a headless view
     */
    public boolean closedAPane() {
        return "gui".equals(this.backend);
    }

    static CloseResult decode(Map<String, Object> structured) {

        return new CloseResult(Json.optStr(structured, "backend"),
                intOf(structured, "closed"),
                intOf(structured, "remaining"),
                intOf(structured, "current"),
                emptyToNull(Json.optStr(structured, "profile")),
                Json.optBool(structured, "profile_released", false));
    }

    private static int intOf(Map<String, Object> structured, String key) {

        Long value = Json.optLong(structured, key);

        return value == null ? 0 : value.intValue();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
