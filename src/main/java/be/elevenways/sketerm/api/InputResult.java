package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_input answer: how many events went out, per kind. A key PRESS counts as its two edges.
 */
public record InputResult(int sent, int pointer, int wheel, int key, int text) {

    static InputResult decode(Map<String, Object> structured) {
        return new InputResult(count(structured, "sent"), count(structured, "pointer"), count(structured, "wheel"),
                count(structured, "key"), count(structured, "text"));
    }

    private static int count(Map<String, Object> structured, String key) {
        Long value = Json.optLong(structured, key);
        return value == null ? 0 : value.intValue();
    }
}
