package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_frame answer: the view's newest painted frame, or the fact that none newer was painted in time.
 *
 * @param serial the paint serial to pass as the next {@code since}; the given one when unchanged
 * @param width the image's pixels, which a downscale or a device scale makes differ from the viewport
 * @param viewportWidth the logical width {@link InputEvent} coordinates are in
 * @param bytes the encoded image, empty when unchanged
 */
public record Frame(long serial, boolean unchanged, FrameFormat format, int width, int height, int viewportWidth,
                    int viewportHeight, byte[] bytes) {

    static Frame decode(Map<String, Object> structured, byte[] image) {

        Long serial = Json.optLong(structured, "frame");
        if (serial == null) {
            throw new ProtocolMismatchException("web_frame answered without a frame serial");
        }

        String format = Json.optStr(structured, "format");

        return new Frame(serial, Json.optBool(structured, "unchanged", false),
                format == null ? FrameFormat.JPEG : WireValues.require(FrameFormat.class, format),
                size(structured, "width"), size(structured, "height"),
                size(structured, "viewport_width"), size(structured, "viewport_height"),
                image == null ? new byte[0] : image);
    }

    private static int size(Map<String, Object> structured, String key) {
        Long value = Json.optLong(structured, key);
        return value == null ? 0 : value.intValue();
    }
}
