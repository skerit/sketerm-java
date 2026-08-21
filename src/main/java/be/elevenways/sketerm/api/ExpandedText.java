package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One web_expand page of a truncated node, or of the last eval result when the id was 0.
 *
 * @param totalChars the full length, only sent for the eval result; 0 otherwise
 */
public record ExpandedText(int id, int offset, int totalChars, String source, String text) {

    static ExpandedText decode(Map<String, Object> structured) {

        Long id = Json.optLong(structured, "id");
        Long offset = Json.optLong(structured, "offset");
        Long total = Json.optLong(structured, "total_chars");

        return new ExpandedText(id == null ? 0 : id.intValue(),
                offset == null ? 0 : offset.intValue(),
                total == null ? 0 : total.intValue(),
                Json.optStr(structured, "source"),
                Json.optStr(structured, "text"));
    }

    /**
     * @return whether more text follows this page
     */
    public boolean hasMore() {
        return this.totalChars > this.offset + (this.text == null ? 0 : this.text.length());
    }
}
