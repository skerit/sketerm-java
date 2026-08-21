package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_scroll answer: the settled position before and after, so "nothing moved" is a fact.
 *
 * @param how what the server actually did: wheel, scroll_into_view, or one of the keywords
 */
public record ScrollResult(String how, Position before, Position after, boolean moved) {

    /**
     * @param maxY the largest scrollable y, 0 when the page does not scroll
     * @param viewport the viewport height the engine measured
     */
    public record Position(int x, int y, int maxY, int viewport) {
    }

    static ScrollResult decode(Map<String, Object> structured) {
        return new ScrollResult(Json.optStr(structured, "how"),
                position(Json.optMap(structured, "before")),
                position(Json.optMap(structured, "after")),
                Json.optBool(structured, "moved", false));
    }

    private static Position position(Map<String, Object> raw) {

        if (raw == null) {
            return null;
        }

        return new Position(intOf(raw, "x"), intOf(raw, "y"), intOf(raw, "max_y"), intOf(raw, "viewport"));
    }

    private static int intOf(Map<String, Object> raw, String key) {
        Long value = Json.optLong(raw, key);
        return value == null ? 0 : value.intValue();
    }
}
