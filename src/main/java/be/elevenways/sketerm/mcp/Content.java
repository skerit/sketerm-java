package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One block of an MCP tool result's content array.
 */
public sealed interface Content {

    /** The MCP type discriminator this block was decoded from. */
    String type();

    record Text(String text) implements Content {

        @Override
        public String type() {
            return "text";
        }
    }

    record Image(String data, String mimeType) implements Content {

        @Override
        public String type() {
            return "image";
        }
    }

    /**
     * Any block whose type is neither text nor image, kept raw rather than dropped.
     */
    record Other(String type, Map<String, Object> raw) implements Content {
    }

    /**
     * Decode one content block.
     *
     * @throws be.elevenways.sketerm.json.JsonException when the block has no type
     */
    static Content decode(Map<String, Object> block) {

        String type = Json.str(block, "type");

        return switch (type) {
            case "text" -> new Text(Json.optStr(block, "text") == null ? "" : Json.str(block, "text"));
            case "image" -> new Image(Json.str(block, "data"), Json.str(block, "mimeType"));
            default -> new Other(type, block);
        };
    }
}
