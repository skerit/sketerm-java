package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives response-body capture (web_open capture, web_capture, web_capture_set, web_wait
 * for:"response") against scripted structured results.
 */
class CaptureApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://open.example/
            [1] document "Playlist"
            """;

    /**
     * AIDEV-NOTE: a COPY of the enums the server's src/ipc/mcp_tools.zig declares for these tools -
     * that file is the source of truth, this is the drift check, spelled out as literals so it
     * cannot merely prove the Java enums equal themselves.
     */
    private static final List<String> SCHEMA_CAPTURE_STATES = List.of("active", "disabled", "none",
            "refused", "unknown");

    private static final List<String> SCHEMA_TRUNCATIONS = List.of("none", "body_cap", "total_cap",
            "no_memory", "unknown");

    private static final List<String> SCHEMA_ENCODINGS = List.of("utf8", "base64", "binary");

    private static final List<String> SCHEMA_PARTS = List.of("response", "request");

    private static final List<String> SCHEMA_WAIT_FOR = List.of("load", "title", "text", "idle",
            "response");

    @Test
    @DisplayName("Every capture vocabulary matches the tool schema's enum, member for member")
    void vocabulariesMatchTheSchema() {

        assertEquals(SCHEMA_CAPTURE_STATES, wires(CaptureState.values()), "capture_state");
        assertEquals(SCHEMA_TRUNCATIONS, wires(CaptureTruncation.values()), "the truncated reasons");
        assertEquals(SCHEMA_ENCODINGS, wires(BodyEncoding.values()), "encoding");
        assertEquals(SCHEMA_PARTS, wires(BodyPart.values()), "part");
        assertEquals(SCHEMA_WAIT_FOR, wires(WaitFor.values()), "web_wait's for");
    }

    @Test
    @DisplayName("A captured open sends the whole filter, and the capture reads back as typed data")
    void captureJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 7, "document", 1, "revision", 1, "snapshot", TREE,
                "capture_active", true)));
        server.on("web_capture", arguments -> {

            if (arguments.containsKey("out_file")) {
                return facts(map("seq", 41, "part", "response", "complete", true, "status", 200,
                        "mime", "application/json", "charset", "utf-8", "stored_bytes", 18,
                        "delivered_bytes", 18, "capture_truncated", false,
                        "capture_truncated_reason", "none", "encoding", "utf8",
                        "out_file", arguments.get("out_file"), "bytes", 18, "sha256", "ab12"));
            }

            if (arguments.containsKey("seq") && "request".equals(arguments.get("part"))) {
                return facts(map("seq", 41, "part", "request", "complete", true, "status", 200,
                        "mime", "application/json", "charset", "utf-8", "stored_bytes", 33,
                        "delivered_bytes", 33, "capture_truncated", false,
                        "capture_truncated_reason", "none", "encoding", "utf8", "bytes", 33,
                        "inline_truncated", false, "body", "{\"operationName\":\"fetchPlaylist\"}"));
            }

            if (Integer.valueOf(42).equals(arguments.get("seq"))) {
                return facts(map("seq", 42, "part", "response", "complete", true, "status", 200,
                        "mime", "image/png", "charset", "", "stored_bytes", 4, "delivered_bytes", 4,
                        "capture_truncated", false, "capture_truncated_reason", "none",
                        "encoding", "base64", "bytes", 4, "inline_truncated", false,
                        "body", Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'}),
                        "headers", List.of()));
            }

            if (arguments.containsKey("seq")) {
                return facts(map("seq", 41, "part", "response", "complete", true, "status", 200,
                        "mime", "application/json", "charset", "utf-8", "stored_bytes", 1024,
                        "delivered_bytes", 4096, "capture_truncated", true,
                        "capture_truncated_reason", "body_cap", "encoding", "utf8", "bytes", 1024,
                        "inline_truncated", true, "body", "{\"tracks\":[",
                        "headers", List.of(map("name", "content-type", "value", "application/json"))));
            }

            return facts(map("capture_state", "active", "since", 0, "next_since", 3,
                    "head_cursor", 9, "more", true, "count", 1, "in_flight", 2, "stored_bytes", 5000,
                    "max_body_bytes", 1024, "max_total_bytes", 1048576,
                    "dropped", map("pending_full", 0, "total_full", 4, "entries_full", 0, "no_memory", 0),
                    "dropped_total", 4,
                    "exchanges", List.of(exchange(41, 3, arguments.containsKey("out_dir")))));
        });
        server.on("web_wait", facts(map("waited_for", "response", "settled", true,
                "detail", "a captured response matching the filter finished",
                "response", exchange(43, 10, false), "next_since", 10)));
        server.on("web_capture_set", arguments -> facts(map("action", arguments.get("action"),
                "capture_state", "disable".equals(arguments.get("action")) ? "disabled" : "active",
                "stored_bytes", 100, "in_flight", 0, "head_cursor", 10)));

        CaptureFilter filter = CaptureFilter.builder()
                .hosts("Spotify.COM")
                .urlContains("/pathfinder/")
                .urlRegex("operationName=fetch")
                .types(ResourceType.XHR)
                .methods("post", "GET")
                .mimePrefixes("Application/JSON")
                .maxBodyBytes(1024)
                .maxTotalBytes(1_048_576)
                .build();

        // 1. The open carries the capture as web_open's capture object, folded as the server folds
        Page page = server.browser().openPage("https://open.example/", OpenOptions.withCapture(filter));

        Map<String, Object> open = server.lastArguments("web_open");
        assertEquals(map("hosts", List.of("spotify.com"), "url_contains", "/pathfinder/",
                        "url_regex", "operationName=fetch", "types", List.of("xhr"),
                        "methods", List.of("POST", "GET"), "mime_prefixes", List.of("application/json"),
                        "max_body_bytes", 1024L, "max_total_bytes", 1_048_576L),
                normalize(open.get("capture")), "step 1: the whole filter went out, in schema order");
        assertTrue(page.isCaptureActive(), "step 1: the page knows it records");

        // 2. A listing decodes every fact, truncation and drops included
        CapturedExchanges listed = page.captured();

        assertEquals(CaptureState.ACTIVE, listed.state(), "step 2: the capture is active");
        assertEquals(3, listed.nextSince(), "step 2: with the cursor to page on from");
        assertEquals(9, listed.headCursor(), "step 2: and the newest cursor");
        assertTrue(listed.more(), "step 2: more is held past this page");
        assertEquals(2, listed.inFlight(), "step 2: two exchanges are still in flight");
        assertEquals(4, listed.dropped().totalFull(), "step 2: four were dropped at the total cap");
        assertEquals(4, listed.dropped().total(), "step 2: and that is every drop");
        assertFalse(server.lastArguments("web_capture").containsKey("since"),
                "step 2: the first page sends no cursor");

        CapturedExchange first = listed.exchanges().getFirst();
        assertEquals(41, first.seq(), "step 2: the join key with web_network");
        assertEquals(3, first.cursor(), "step 2: its list position");
        assertTrue(first.finished(), "step 2: a listed exchange with a cursor has finished");
        assertEquals(ResourceType.XHR, first.type(), "step 2: its class");
        assertEquals(CaptureTruncation.BODY_CAP, first.bodyTruncation(), "step 2: cut at the per-body cap");
        assertTrue(first.bodyTruncated(), "step 2: which reads as truncated");
        assertEquals(4096, first.bodyDeliveredBytes(), "step 2: with the size the page received");
        assertEquals(Duration.ofMillis(55), first.duration(), "step 2: and its duration");
        assertNull(first.path(), "step 2: a plain listing writes no file");

        // 3. Paging and the in-flight listing name their arguments
        page.captured(3, 20);
        Map<String, Object> paged = server.lastArguments("web_capture");
        assertEquals(3, ((Number) paged.get("since")).intValue(), "step 3: the cursor went out");
        assertEquals(20, ((Number) paged.get("max")).intValue(), "step 3: with the page size");

        page.capturedWithInFlight(0, null);
        assertEquals(true, server.lastArguments("web_capture").get("include_in_flight"),
                "step 3: in-flight exchanges are asked for explicitly");

        // 4. The mark is the head cursor, read without listing anything
        assertEquals(9, page.captureMark(), "step 4: the mark is head_cursor");
        Map<String, Object> mark = server.lastArguments("web_capture");
        assertEquals(0xFFFF_FFFFL, ((Number) mark.get("since")).longValue(), "step 4: past every cursor");
        assertEquals(1, ((Number) mark.get("max")).intValue(), "step 4: at most one");

        // 5. Bodies: text with headers and both cuts, a request body, a binary as bytes
        CapturedBody body = page.responseBody(41);
        assertEquals(BodyPart.RESPONSE, body.part(), "step 5: the response body");
        assertEquals("{\"tracks\":[", body.text(), "step 5: text as text");
        assertTrue(body.inlineTruncated(), "step 5: the answer carried a prefix and says so");
        assertEquals(CaptureTruncation.BODY_CAP, body.truncation(), "step 5: and the capture's own cut");
        assertEquals(List.of(new CapturedBody.Header("content-type", "application/json")), body.headers(),
                "step 5: with the response headers");

        CapturedBody request = page.requestBody(41);
        assertEquals("request", server.lastArguments("web_capture").get("part"), "step 5: part went out");
        assertEquals("{\"operationName\":\"fetchPlaylist\"}", request.text(), "step 5: what the page sent");

        CapturedBody png = page.responseBody(42);
        assertEquals(BodyEncoding.BASE64, png.encoding(), "step 5: a binary arrives as base64");
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, png.data(), "step 5: data() decodes it");
        assertThrows(IllegalStateException.class, png::text, "step 5: and has no text");

        // 6. A body to a file: identity only, and a relative path never leaves this process
        CapturedBodyFile file = page.responseBodyToFile(41, Path.of("/tmp/first.json"));
        assertEquals("/tmp/first.json", server.lastArguments("web_capture").get("out_file"),
                "step 6: the absolute destination went out");
        assertEquals(Path.of("/tmp/first.json"), file.path(), "step 6: and came back");
        assertEquals("ab12", file.sha256(), "step 6: with its digest");
        assertEquals(BodyEncoding.UTF8, file.encoding(), "step 6: as UTF-8 text");

        int before = server.callsTo("web_capture").size();
        assertThrows(InvalidArgsException.class, () -> page.responseBodyToFile(41, Path.of("first.json")),
                "step 6: a relative destination is refused");
        assertEquals(before, server.callsTo("web_capture").size(), "step 6: before the call goes out");

        // 7. A whole page to a directory
        CapturedExchanges dumped = page.capturedToDirectory(Path.of("/tmp/bodies"), 0, null);
        assertEquals("/tmp/bodies", server.lastArguments("web_capture").get("out_dir"), "step 7: out_dir");
        assertEquals(Path.of("/tmp/bodies/41.json"), dumped.exchanges().getFirst().path(),
                "step 7: each exchange names its file");
        assertEquals(BodyEncoding.UTF8, dumped.exchanges().getFirst().fileEncoding(), "step 7: and its encoding");

        // 8. A wait sends the MATCH clauses only - never the caps - with its cursor
        WaitedResponse waited = page.waitForResponse(filter, 9, Duration.ofSeconds(20));
        Map<String, Object> wait = server.lastArguments("web_wait");
        assertEquals("response", wait.get("for"), "step 8: for:response");
        Map<String, Object> match = normalize(wait.get("response"));
        assertFalse(match.containsKey("max_body_bytes"), "step 8: the caps are a capture setting, not a match");
        assertEquals(List.of("spotify.com"), match.get("hosts"), "step 8: the clauses went out");
        assertEquals(9, ((Number) wait.get("since")).intValue(), "step 8: from the mark");
        assertEquals(20_000L, ((Number) wait.get("timeout_ms")).longValue(), "step 8: with its budget");
        assertEquals(43, waited.exchange().seq(), "step 8: the waited-for exchange");
        assertEquals(10, waited.nextSince(), "step 8: and where the next wait starts");

        page.waitForResponseAfterSeq(null, 40, Duration.ofSeconds(5));
        Map<String, Object> afterSeq = server.lastArguments("web_wait");
        assertEquals(40, ((Number) afterSeq.get("after_seq")).intValue(), "step 8: after a request seq");
        assertFalse(afterSeq.containsKey("since"), "step 8: which needs no cursor");
        assertFalse(afterSeq.containsKey("response"), "step 8: and a null filter matches everything");

        // 9. Narrowing only
        CaptureChange cleared = page.clearCaptured(3);
        assertEquals("clear", server.lastArguments("web_capture_set").get("action"), "step 9: clear");
        assertEquals(3, ((Number) server.lastArguments("web_capture_set").get("upto")).intValue(),
                "step 9: up to a cursor");
        assertEquals(100, cleared.storedBytes(), "step 9: the bytes held afterwards");
        assertTrue(page.isCaptureActive(), "step 9: clearing keeps recording");

        page.clearCaptured();
        assertFalse(server.lastArguments("web_capture_set").containsKey("upto"), "step 9: no cursor frees all");

        CaptureChange disabled = page.disableCapture();
        assertEquals(CaptureState.DISABLED, disabled.state(), "step 9: disabled");
        assertFalse(page.isCaptureActive(), "step 9: and the page knows");
    }

    @Test
    @DisplayName("A captured open fails closed: a refusal opens nothing and is typed")
    void failClosed() {

        FakeSketermServer server = new FakeSketermServer();
        server.onError("web_open", "unavailable", "this browser helper does not advertise the capture"
                + " capability, so response bodies cannot be captured. Nothing was opened", false);

        assertThrows(UnavailableException.class,
                () -> server.browser().openPage("https://open.example/", OpenOptions.withCapture(CaptureFilter.json())),
                "the refusal is an UnavailableException");
        assertEquals(1, server.callsTo("web_open").size(), "one open went out");
        assertTrue(server.callsTo("web_tabs").isEmpty(), "and no page was assembled from it");

        FakeSketermServer conflict = new FakeSketermServer();
        conflict.on("web_open", facts(map("view", 2, "document", 1, "revision", 1, "snapshot", TREE)));
        conflict.onError("web_capture", "conflict", "this view was opened without a capture", false);

        Page plain = conflict.browser().openPage("https://open.example/");
        assertFalse(plain.isCaptureActive(), "an uncaptured view says so");
        assertThrows(ConflictException.class, plain::captured, "and reading its capture is a conflict");
    }

    @Test
    @DisplayName("Doomed capture filters are refused before the call goes out")
    void clientSideRefusals() {

        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().hosts("*.spotify.com").build(),
                "a wildcard host");
        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().hosts("https://x.test").build(),
                "a host with a scheme");
        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().methods("GET /").build(),
                "a method that is not one");
        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().mimePrefixes("text/html; charset").build(),
                "a mime prefix with a parameter");
        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().urlContains("").build(),
                "an empty url_contains, which restricts nothing and says so");
        assertThrows(InvalidArgsException.class, () -> CaptureFilter.builder().maxBodyBytes(0).build(),
                "a zero cap, refused rather than clamped");
        assertThrows(InvalidArgsException.class,
                () -> CaptureFilter.builder().maxTotalBytes(CaptureFilter.MAX_TOTAL_LIMIT + 1).build(),
                "a cap past the server's ceiling");
        assertThrows(InvalidArgsException.class,
                () -> CaptureFilter.builder().maxTotalBytes(1024).build(),
                "a total below the default per-body cap");
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.withCapture(null),
                "a null fail-closed capture");

        CaptureFilter everything = CaptureFilter.everything();
        assertTrue(everything.toWire().isEmpty(), "every clause open sends an empty object: the server's defaults");
        assertEquals(Map.of("mime_prefixes", List.of("application/json")), CaptureFilter.json().toWire(),
                "the json shorthand is one clause");
    }

    @Test
    @DisplayName("supportsCapture reads the capabilities report's web_capture flag")
    void preflight() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("capabilities", map("web_capture", true));
        assertTrue(server.browser().supportsCapture(), "the flag is what is read");

        FakeSketermServer older = new FakeSketermServer();
        older.on("capabilities", map("web_downloads", true));
        assertFalse(older.browser().supportsCapture(), "a server naming no capture supports none");
    }

    private static Map<String, Object> exchange(long seq, long cursor, boolean written) {

        Map<String, Object> entry = map("seq", seq, "cursor", cursor,
                "url", "https://api.example/pathfinder/v1/query", "method", "POST", "status", 200,
                "type", "xhr", "mime", "application/json", "charset", "utf-8",
                "started_ms", 1_790_000_000_000L, "duration_ms", 55, "complete", true, "failed", false,
                "error", 0, "body_bytes", 1024, "body_delivered_bytes", 4096, "body_truncated", true,
                "body_truncated_reason", "body_cap", "request_body_bytes", 33,
                "request_body_total_bytes", 33, "request_body_truncated", false,
                "request_body_truncated_reason", "none", "request_body_nonbytes", false,
                "headers_truncated", false);

        if (written) {
            entry.put("path", "/tmp/bodies/" + seq + ".json");
            entry.put("file_bytes", 1024);
            entry.put("sha256", "cd34");
            entry.put("encoding", "utf8");
        }

        return entry;
    }

    /**
     * Numbers decoded from the wire come back as longs; compare structure, not boxing.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Object value) {

        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
            out.put(entry.getKey(), entry.getValue() instanceof Number number ? (Object) number.longValue() : entry.getValue());
        }

        return out;
    }

    private static <E extends Enum<E> & WireValue> List<String> wires(E[] members) {
        return java.util.Arrays.stream(members).map(WireValue::wire).toList();
    }

    private static Map<String, Object> facts(Map<String, Object> extra) {

        Map<String, Object> structured = map("backend", "headless", "origin", "https://open.example",
                "url", "https://open.example/", "title", "Playlist", "loading", false);
        structured.putAll(extra);

        return structured;
    }

    private static Map<String, Object> map(Object... keysAndValues) {

        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }

        return result;
    }
}
