package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * The captured exchange a {@link Page#waitForResponse} waited for.
 *
 * @param nextSince the cursor to wait from for the NEXT matching response
 */
public record WaitedResponse(CapturedExchange exchange, long nextSince) {

    static WaitedResponse decode(Map<String, Object> structured) {

        Long next = Json.optLong(structured, "next_since");

        return new WaitedResponse(CapturedExchange.decode(Json.map(structured, "response")),
                next == null ? 0 : next);
    }
}
