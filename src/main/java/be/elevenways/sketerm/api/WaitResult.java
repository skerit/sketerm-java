package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_wait answer; a condition that never held is a {@link TimeoutException}, never this.
 *
 * @param detail what made the condition hold
 */
public record WaitResult(WaitFor waitedFor, String arg, String detail) {

    static WaitResult decode(Map<String, Object> structured) {
        return new WaitResult(WaitFor.fromWire(Json.optStr(structured, "waited_for")),
                Json.optStr(structured, "arg"),
                Json.optStr(structured, "detail"));
    }
}
