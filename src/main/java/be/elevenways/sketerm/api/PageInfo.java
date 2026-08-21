package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One entry of web_tabs: a view's handle and its page facts.
 *
 * @param handle the pane id with a GUI, the view id headless; either way what 'pane' takes
 * @param profile the named profile this view is in, null when it is in none
 * @param profileKind which identity the view holds, null with a GUI (the tool reports none there)
 * @param context the engine identity-context id, 0 for the shared default jar
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
                       String profile,
                       ProfileKind profileKind,
                       int context,
                       boolean current) {

    static PageInfo decode(Map<String, Object> entry) {

        String profile = Json.optStr(entry, "profile");
        Long context = Json.optLong(entry, "context");

        return new PageInfo(Handles.of(entry, "a web_tabs entry"),
                Json.optStr(entry, "url"),
                Json.optStr(entry, "title"),
                Json.optBool(entry, "loading", false),
                Json.optBool(entry, "can_back", false),
                Json.optBool(entry, "can_fwd", false),
                Json.optBool(entry, "focused", false),
                Json.optBool(entry, "visible", false),
                profile == null || profile.isEmpty() ? null : profile,
                kindOf(entry),
                context == null ? 0 : context.intValue(),
                Json.optBool(entry, "current", false));
    }

    /**
     * @return the kind, null when the answer named none; an unknown token fails closed
     */
    static ProfileKind kindOf(Map<String, Object> structured) {

        String wire = Json.optStr(structured, "profile_kind");

        return wire == null || wire.isEmpty() ? null : ProfileKind.require(wire);
    }
}
