package be.elevenways.sketerm.mcp;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultDecodingTest {

    @Test
    @DisplayName("structuredContent wins when the server sends it")
    void structuredContentShape() {

        ToolResult result = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"opened about:blank\"}],"
                        + "\"structuredContent\":{\"view\":1,\"url\":\"about:blank\",\"loading\":false}}"));

        assertFalse(result.isError(), "a plain result is not an error");
        assertTrue(result.hasStructured(), "the structured payload is present");
        assertEquals("about:blank", Json.str(result.structured(), "url"), "and is readable");
        assertEquals(1L, Json.longVal(result.structured(), "view"), "numbers normalise");
        assertEquals("opened about:blank", result.text(), "the prose is kept alongside");
    }

    @Test
    @DisplayName("A JSON object inside the first text block is the 0.1.3 fallback")
    void jsonInTextFallback() {

        ToolResult result = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"view\\\":1,\\\"url\\\":\\\"about:blank\\\",\\\"settled\\\":true}\"}]}"));

        assertTrue(result.hasStructured(), "the text block was recognised as a payload");
        assertEquals("about:blank", Json.str(result.structured(), "url"), "and parsed");
        assertTrue(Json.boolVal(result.structured(), "settled"), "with its booleans intact");
        assertTrue(result.text().startsWith("{"), "the raw text stays available");
    }

    @Test
    @DisplayName("Prose stays prose, even when it begins with a brace")
    void proseOnly() {

        ToolResult plain = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"3 terminals are open\"}]}"));

        assertNull(plain.structured(), "prose yields no structured payload");
        assertEquals("3 terminals are open", plain.text(), "the prose is intact");

        ToolResult braced = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{ this is not json\"}]}"));

        assertNull(braced.structured(), "a false start is not mistaken for a payload");
        assertFalse(braced.hasStructured(), "and hasStructured agrees");
    }

    @Test
    @DisplayName("Image blocks decode and never trigger the text fallback")
    void imageBlocks() {

        ToolResult result = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"image\",\"data\":\"QUJD\",\"mimeType\":\"image/png\"},"
                        + "{\"type\":\"text\",\"text\":\"{\\\"ignored\\\":true}\"}]}"));

        assertEquals(2, result.content().size(), "both blocks decoded");
        assertTrue(result.content().getFirst() instanceof Content.Image, "the first is an image");
        assertEquals("image/png", ((Content.Image) result.content().getFirst()).mimeType(),
                "with its mime type");
        assertNull(result.structured(),
                "the fallback only reads the FIRST block, so a later JSON text is not adopted");
    }

    @Test
    @DisplayName("An isError result with a structured error carries code, message and retryable")
    void structuredErrorDecode() {

        ToolResult result = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"could not reach the view\"}],"
                        + "\"isError\":true,"
                        + "\"structuredContent\":{\"error\":{\"code\":\"view_gone\","
                        + "\"message\":\"view 4 no longer exists\",\"retryable\":false}}}"));

        assertTrue(result.isError(), "the flag decoded");

        ToolException failure = assertThrows(ToolException.class, () -> result.orThrow("web_read"),
                "orThrow refuses an error result");

        assertEquals("web_read", failure.getToolName(), "the tool is named");
        assertEquals("view_gone", failure.getCode(), "the structured code survives");
        assertEquals(Boolean.FALSE, failure.getRetryable(), "the retryable flag survives");
        assertTrue(failure.getMessage().contains("view 4 no longer exists"),
                "the structured message wins over the prose");
        assertEquals(result, failure.getResult(), "the whole result stays reachable");
    }

    @Test
    @DisplayName("An isError result without a structured error degrades to prose")
    void proseErrorDecode() {

        ToolResult result = ToolResult.decode(Json.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"unknown tool: nope\"}],\"isError\":true}"));

        ToolException failure = assertThrows(ToolException.class, () -> result.orThrow("nope"),
                "orThrow refuses");

        assertNull(failure.getCode(), "there is no code to report");
        assertNull(failure.getRetryable(), "and no retryable flag");
        assertTrue(failure.getMessage().contains("unknown tool: nope"), "the prose is the message");
    }

    @Test
    @DisplayName("A result with no content at all decodes to an empty, non-error result")
    void emptyResult() {

        ToolResult result = ToolResult.decode(Json.parseObject("{}"));

        assertTrue(result.content().isEmpty(), "no blocks");
        assertFalse(result.isError(), "not an error");
        assertNull(result.structured(), "no payload");
        assertEquals("", result.text(), "no prose");
    }

    @Test
    @DisplayName("A tool descriptor decodes with a null outputSchema, which Sketerm never sends")
    void toolDescriptorDecode() {

        ToolDescriptor tool = ToolDescriptor.decode(Json.parseObject(
                "{\"name\":\"web_open\",\"description\":\"Open a URL\","
                        + "\"inputSchema\":{\"type\":\"object\"}}"));

        assertEquals("web_open", tool.name(), "the name");
        assertEquals("Open a URL", tool.description(), "the description");
        assertEquals("object", Json.str(tool.inputSchema(), "type"), "the input schema");
        assertNull(tool.outputSchema(), "no output schema");
    }
}
