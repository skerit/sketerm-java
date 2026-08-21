package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One entry of web_tabs: a view's handle and its page facts.
 *
 * @param handle the pane id with a GUI, the view id headless; either way what 'pane' takes
 * @param current whether a web_* call that omits 'pane' would address this view
 */
public record PageInfo(int handle,
                       String url,
                       String title,
                       boolean loading,
                       boolean canGoBack,
                       boolean canGoForward,
                       boolean focused,
                       boolean visible,
                       boolean current) {

    static PageInfo decode(Map<String, Object> entry) {
        return new PageInfo(Handles.of(entry, "a web_tabs entry"),
                Json.optStr(entry, "url"),
                Json.optStr(entry, "title"),
                Json.optBool(entry, "loading", false),
                Json.optBool(entry, "can_back", false),
                Json.optBool(entry, "can_fwd", false),
                Json.optBool(entry, "focused", false),
                Json.optBool(entry, "visible", false),
                Json.optBool(entry, "current", false));
    }
}
