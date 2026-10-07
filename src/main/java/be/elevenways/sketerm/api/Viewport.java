package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * A headless view's viewport in logical pixels, as the server applied it.
 */
public record Viewport(int width, int height) {

    static Viewport decode(Map<String, Object> structured) {

        Long width = Json.optLong(structured, "width");
        Long height = Json.optLong(structured, "height");

        return new Viewport(width == null ? 0 : width.intValue(), height == null ? 0 : height.intValue());
    }
}
