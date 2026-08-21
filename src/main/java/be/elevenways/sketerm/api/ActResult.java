package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_act answer: what was acted on plus the tree delta that followed it.
 *
 * @param detail the server's description of what it did, for example "click at 25,19"
 * @param delta the compact tree of what changed, null when the follow-up snapshot failed
 * @param navigatedTo the new url when the act moved the view, otherwise null
 */
public record ActResult(int id,
                        Action action,
                        String detail,
                        String deltaKind,
                        String delta,
                        String deltaError,
                        String navigatedTo,
                        boolean loadingAfter) {

    static ActResult decode(Map<String, Object> structured) {

        Long id = Json.optLong(structured, "id");

        return new ActResult(id == null ? 0 : id.intValue(),
                Action.fromWire(Json.optStr(structured, "action")),
                Json.optStr(structured, "detail"),
                Json.optStr(structured, "delta_kind"),
                Json.optStr(structured, "delta"),
                Json.optStr(structured, "delta_error"),
                Json.optStr(structured, "navigated_to"),
                Json.optBool(structured, "loading_after", false));
    }

    /**
     * @return whether the act moved the view to another url
     */
    public boolean navigated() {
        return this.navigatedTo != null;
    }
}
