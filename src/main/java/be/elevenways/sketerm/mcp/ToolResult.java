package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The decoded result of tools/call.
 *
 * <p>Sketerm has two structured shapes in the wild, so {@link #decode} applies one compatibility
 * rule: use structuredContent when the server sent it, otherwise try to read the first text block
 * as a JSON object (what 0.1.3 emits), otherwise treat the result as prose only.</p>
 *
 * @param structured the machine-readable payload, or null when the result is prose only
 */
public record ToolResult(List<Content> content, boolean isError, Map<String, Object> structured) {

    public static ToolResult decode(Map<String, Object> raw) {

        List<Content> blocks = new ArrayList<>();
        List<Object> rawBlocks = Json.optList(raw, "content");

        if (rawBlocks != null) {
            for (Object block : rawBlocks) {
                blocks.add(Content.decode(Json.asMap(block, "a content block")));
            }
        }

        boolean isError = Json.optBool(raw, "isError", false);
        Map<String, Object> structured = Json.optMap(raw, "structuredContent");

        if (structured == null) {
            structured = parseFirstTextAsObject(blocks);
        }

        return new ToolResult(List.copyOf(blocks), isError, structured);
    }

    /**
     * @return true when a machine-readable payload was found by either shape
     */
    public boolean hasStructured() {
        return this.structured != null;
    }

    /**
     * @return every text block joined by newlines, empty when the result carries no prose
     */
    public String text() {

        StringBuilder builder = new StringBuilder();

        for (Content block : this.content) {
            if (block instanceof Content.Text text) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }

                builder.append(text.text());
            }
        }

        return builder.toString();
    }

    /**
     * @throws ToolException when this result is an error
     */
    public ToolResult orThrow(String toolName) {

        if (this.isError) {
            throw ToolException.of(toolName, this);
        }

        return this;
    }

    private static Map<String, Object> parseFirstTextAsObject(List<Content> blocks) {

        if (blocks.isEmpty() || !(blocks.getFirst() instanceof Content.Text text)) {
            return null;
        }

        String trimmed = text.text().trim();

        if (!trimmed.startsWith("{")) {
            return null;
        }

        try {
            return Json.parseObject(trimmed);
        } catch (RuntimeException e) {
            // Prose that merely begins with a brace; not a structured payload.
            return null;
        }
    }
}
