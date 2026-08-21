package be.elevenways.sketerm.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    @DisplayName("A JSON-RPC envelope survives a parse/write journey with its types intact")
    void envelopeRoundTrip() {

        // 1. A realistic frame parses into plain java.util types
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"hello\"}],\"isError\":false,\"ratio\":1.5,\"missing\":null}}";

        Map<String, Object> parsed = Json.parseObject(frame);

        assertEquals("2.0", Json.str(parsed, "jsonrpc"), "step 1: the version member is a string");
        assertEquals(7L, Json.longVal(parsed, "id"), "step 1: a small int normalises to long");

        // 2. Nested access finds the content block
        Map<String, Object> result = Json.map(parsed, "result");
        List<Object> content = Json.list(result, "content");

        assertEquals(1, content.size(), "step 2: one content block");
        assertEquals("hello", Json.str(Json.asMap(content.getFirst(), "block"), "text"),
                "step 2: the block text");

        // 3. Doubles stay doubles, explicit nulls read as absent
        assertEquals(1.5d, ((Number) Json.require(result, "ratio")).doubleValue(),
                "step 3: a fractional number stays fractional");
        assertNull(Json.optStr(result, "missing"), "step 3: an explicit null reads as absent");
        assertFalse(Json.boolVal(result, "isError"), "step 3: the error flag");

        // 4. Writing back yields NDJSON-safe text that re-parses
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 12L);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", "web_open"));

        String written = Json.write(request);

        assertFalse(written.contains("\n"), "step 4: no raw newline in the frame");

        Map<String, Object> reparsed = Json.parseObject(written);

        assertEquals("tools/call", Json.str(reparsed, "method"), "step 4: the method survives");
        assertEquals(12L, Json.longVal(reparsed, "id"), "step 4: the id survives");
    }

    @Test
    @DisplayName("A string carrying newlines is escaped, never emitted raw")
    void embeddedNewlinesAreEscaped() {

        String written = Json.write(Map.of("text", "line one\nline two"));

        assertFalse(written.contains("\n"), "the payload newline is escaped");
        assertEquals("line one\nline two", Json.str(Json.parseObject(written), "text"),
                "and decodes back");
    }

    @Test
    @DisplayName("Typed getters fail loudly and name the key")
    void gettersFailLoudly() {

        Map<String, Object> map = Json.parseObject("{\"name\":\"web_open\",\"count\":3}");

        JsonException missing = assertThrows(JsonException.class, () -> Json.str(map, "absent"),
                "an absent key refuses");
        assertTrue(missing.getMessage().contains("absent"), "the message names the key");

        JsonException wrongType = assertThrows(JsonException.class, () -> Json.list(map, "name"),
                "a wrong type refuses");
        assertTrue(wrongType.getMessage().contains("name"), "the message names the key");
        assertTrue(wrongType.getMessage().contains("array"), "the message names the expectation");

        assertThrows(JsonException.class, () -> Json.boolVal(map, "count"),
                "a number is not a boolean");
    }

    @Test
    @DisplayName("Parsing refuses non-objects and garbage")
    void parseRefusesNonObjects() {

        assertThrows(JsonException.class, () -> Json.parseObject("[1,2,3]"), "an array is not an object");
        assertThrows(JsonException.class, () -> Json.parseObject("{oops"), "garbage refuses");
        assertThrows(JsonException.class, () -> Json.parseObject(null), "null refuses");
    }

    @Test
    @DisplayName("optBool falls back only for an absent key")
    void optBoolFallback() {

        Map<String, Object> map = Json.parseObject("{\"flag\":false}");

        assertFalse(Json.optBool(map, "flag", true), "a present false wins over the fallback");
        assertTrue(Json.optBool(map, "other", true), "an absent key takes the fallback");
    }
}
