package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * One entry of tools/list.
 *
 * @param outputSchema null for every Sketerm tool today; the field exists for when it does not
 */
public record ToolDescriptor(String name,
                             String description,
                             Map<String, Object> inputSchema,
                             Map<String, Object> outputSchema) {

    public static ToolDescriptor decode(Map<String, Object> raw) {
        return new ToolDescriptor(
                Json.str(raw, "name"),
                Json.optStr(raw, "description"),
                Json.optMap(raw, "inputSchema"),
                Json.optMap(raw, "outputSchema"));
    }
}
