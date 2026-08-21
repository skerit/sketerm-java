package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_navigate answer; it is deliberately not a snapshot, so ask for content separately.
 *
 * @param settled whether the navigation finished inside its budget
 */
public record NavigationResult(String url,
                               String title,
                               boolean loading,
                               boolean canGoBack,
                               boolean canGoForward,
                               boolean settled) {

    static NavigationResult decode(Map<String, Object> structured) {
        return new NavigationResult(Json.optStr(structured, "url"),
                Json.optStr(structured, "title"),
                Json.optBool(structured, "loading", false),
                Json.optBool(structured, "can_back", false),
                Json.optBool(structured, "can_fwd", false),
                Json.optBool(structured, "settled", false));
    }
}
