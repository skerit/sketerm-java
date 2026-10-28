package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The navigation fact of web_open and web_navigate: what the main document's request produced.
 *
 * @param url          the final url (whole up to the server's web_log_url_max; an older server cut it at 256 bytes)
 * @param requestedUrl the url that was asked for, null when the answer named none
 * @param status       the main document's HTTP status, null when there was no HTTP answer
 * @param redirects    the server redirects before the final url, oldest first; empty from a helper without detail
 * @param contentType  the response's content type, null when unknown
 * @param error        the net::ERR_* name of a failed load, null otherwise
 * @param blockedReason why the engine refused a {@link NavigationOutcome#BLOCKED} load (the filter list or a policy
 *                      rule), null otherwise
 */
public record Navigation(NavigationOutcome outcome,
                         String url,
                         String requestedUrl,
                         Integer status,
                         List<Redirect> redirects,
                         String contentType,
                         String error,
                         String blockedReason) {

    /** One server redirect hop. */
    public record Redirect(String url, int status) {
    }

    public Navigation {
        redirects = List.copyOf(redirects);
    }

    /**
     * @return the answer's navigation fact, or null when it carried none (an older server, or a navigation_error)
     * @throws SketermApiException when the outcome names no {@link NavigationOutcome}
     */
    static Navigation decode(Map<String, Object> structured) {

        Map<String, Object> fact = Json.optMap(structured, "navigation");

        if (fact == null) {
            return null;
        }

        List<Redirect> redirects = new ArrayList<>();
        List<Object> hops = Json.optList(fact, "redirects");

        if (hops != null) {
            for (Object hop : hops) {
                Map<String, Object> entry = Json.asMap(hop, "a navigation redirect");
                redirects.add(new Redirect(Json.str(entry, "url"), Json.intVal(entry, "status")));
            }
        }

        Long status = Json.optLong(fact, "status");

        return new Navigation(NavigationOutcome.require(Json.str(fact, "outcome")),
                Json.optStr(fact, "url"),
                Json.optStr(fact, "requested_url"),
                status == null ? null : status.intValue(),
                redirects,
                Json.optStr(fact, "content_type"),
                Json.optStr(fact, "error"),
                Json.optStr(fact, "blocked_reason"));
    }
}
