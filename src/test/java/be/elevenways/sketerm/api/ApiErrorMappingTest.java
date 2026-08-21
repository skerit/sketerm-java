package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a structured failure, a stale id and a pre-migration answer each land in the api layer.
 */
class ApiErrorMappingTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Example" {1 children}
              [2] button "Press Me"
            """;

    @Test
    @DisplayName("Every error code maps to its own exception type, retryability included")
    void everyCodeMaps() {

        Map<ErrorCode, Class<? extends SketermApiException>> expected = new LinkedHashMap<>();
        expected.put(ErrorCode.INVALID_ARGS, InvalidArgsException.class);
        expected.put(ErrorCode.NOT_FOUND, NotFoundException.class);
        expected.put(ErrorCode.UNAVAILABLE, UnavailableException.class);
        expected.put(ErrorCode.TIMEOUT, TimeoutException.class);
        expected.put(ErrorCode.REFUSED, RefusedException.class);
        expected.put(ErrorCode.CONFLICT, ConflictException.class);
        expected.put(ErrorCode.IO_FAILED, IoFailedException.class);
        expected.put(ErrorCode.UNKNOWN_TOOL, UnknownToolException.class);
        expected.put(ErrorCode.FAILED, FailedException.class);

        // 1. The table above covers the whole vocabulary; a new member fails this first
        assertEquals(ErrorCode.values().length, expected.size(),
                "step 1: every ErrorCode member has an expected exception type");

        for (Map.Entry<ErrorCode, Class<? extends SketermApiException>> entry : expected.entrySet()) {

            ErrorCode code = entry.getKey();
            FakeSketermServer server = new FakeSketermServer();
            server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
            server.onError("web_wait", code.wire(), "the tool said no", code.isRetryable());

            Page page = server.browser().openPage("https://example.test/");

            // 2. The failure arrives as the typed subtype
            SketermApiException failure = assertThrows(SketermApiException.class,
                    () -> page.waitFor(WaitFor.LOAD), "step 2: " + code + " throws");

            assertInstanceOf(entry.getValue(), failure, "step 2: " + code + " maps to its own type");

            // 3. Carrying the code, the tool name and the retryable fact
            assertEquals(code, failure.getCode(), "step 3: " + code + " is readable off the exception");
            assertEquals("web_wait", failure.getToolName(), "step 3: it names the tool");
            assertEquals(code.isRetryable(), failure.isRetryable(),
                    "step 3: " + code + " reports the server's retryable flag");
            assertTrue(failure.getMessage().contains("the tool said no"),
                    "step 3: and the server's own sentence");
        }
    }

    @Test
    @DisplayName("An unknown code fails closed as the base type rather than a neighbouring meaning")
    void unknownCodeFailsClosed() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.onError("web_read", "quantum_entangled", "a code from the future", true);

        Page page = server.browser().openPage("https://example.test/");
        SketermApiException failure = assertThrows(SketermApiException.class, page::read);

        assertEquals(SketermApiException.class, failure.getClass(), "no subtype was guessed");
        assertNull(failure.getCode(), "the typed code is absent");
        assertEquals("quantum_entangled", failure.getRawCode(), "but the raw spelling survives");
        assertTrue(failure.isRetryable(), "and the server's retryable flag is still honoured");
    }

    @Test
    @DisplayName("A ref from an older document is refused before it ever reaches the server")
    void staleRefIsRefusedClientSide() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_snapshot", facts(map("kind", "full", "document", 2, "revision", 9, "snapshot", TREE)));

        Page page = server.browser().openPage("https://example.test/");

        // 1. A ref read from the first document
        Ref old = page.lastSnapshot().find("Press").orElseThrow();
        assertEquals(1, old.document(), "step 1: the ref knows its document");

        // 2. The page moves to a new document
        page.snapshot(SnapshotMode.FULL);
        assertEquals(2, page.document(), "step 2: the page is on document 2 now");

        // 3. Acting on the old ref is refused, and nothing was sent
        StaleRefException failure = assertThrows(StaleRefException.class, () -> page.click(old));
        assertEquals(old, failure.getRef(), "step 3: the exception names the offending ref");
        assertTrue(failure.getMessage().contains("fresh snapshot"), "step 3: and says what to do");
        assertTrue(server.callsTo("web_act").isEmpty(), "step 3: web_act was never called");
    }

    @Test
    @DisplayName("The page's own refusal of an id becomes the same stale exception")
    void staleRefIsDecodedFromTheServer() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.onError("web_act", ErrorCode.FAILED.wire(),
                "stale reader id: the page changed since web_read; read the page again", false);

        Page page = server.browser().openPage("https://example.test/");
        Ref ref = page.lastSnapshot().find("Press").orElseThrow();

        StaleRefException failure = assertThrows(StaleRefException.class, () -> page.click(ref));

        assertEquals(ErrorCode.FAILED, failure.getCode(), "the wire code was the generic failure");
        assertFalse(failure.isRetryable(), "which is not retryable");
        assertTrue(failure.getMessage().contains("stale reader id"), "the page's reason is kept");
    }

    @Test
    @DisplayName("A prose-only answer is refused by name, since it means an unmigrated server")
    void proseOnlyAnswerIsRefused() {

        FakeSketermServer server = new FakeSketermServer();
        server.prose("web_open", "opened view 1: Example - https://example.test/");

        Browser browser = server.browser();

        ProtocolMismatchException failure = assertThrows(ProtocolMismatchException.class,
                () -> browser.openPage("https://example.test/"));

        assertTrue(failure.getMessage().contains("web_open"), "the failing tool is named");
        assertTrue(failure.getMessage().contains("structuredContent"), "and the missing shape");
    }

    @Test
    @DisplayName("A structured answer without a view handle is refused by name too")
    void missingHandleIsRefused() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("settled", true, "document", 1, "revision", 1, "snapshot", TREE)));

        Browser browser = server.browser();

        ProtocolMismatchException failure = assertThrows(ProtocolMismatchException.class,
                () -> browser.openPage("https://example.test/"));

        assertTrue(failure.getMessage().contains("'pane' or 'view'"), "it names both spellings");
    }

    @Test
    @DisplayName("A view handle is read from either spelling the backends use")
    void bothHandleSpellings() {

        assertEquals(4, Handles.of(map("pane", 4), "gui"), "a GUI answer carries 'pane'");
        assertEquals(9, Handles.of(map("view", 9), "headless"), "a headless answer carries 'view'");
        assertEquals(4, Handles.of(map("pane", 4, "view", 9), "both"), "'pane' wins when both are sent");
    }

    @Test
    @DisplayName("Every vocabulary member round-trips through its wire token")
    void vocabulariesRoundTrip() {

        assertRoundTrip(Action.class, Action.values());
        assertRoundTrip(NavigateAction.class, NavigateAction.values());
        assertRoundTrip(WaitFor.class, WaitFor.values());
        assertRoundTrip(ScrollTo.class, ScrollTo.values());
        assertRoundTrip(SnapshotMode.class, SnapshotMode.values());
        assertRoundTrip(QueryKind.class, QueryKind.values());
        assertRoundTrip(NetworkAction.class, NetworkAction.values());
        assertRoundTrip(ErrorCode.class, ErrorCode.values());

        assertThrows(SketermApiException.class, () -> WireValues.require(Action.class, "levitate"),
                "an unknown token fails closed");
    }

    private static <E extends Enum<E> & WireValue> void assertRoundTrip(Class<E> type, E[] members) {

        for (E member : members) {
            assertEquals(member, WireValues.parse(type, member.wire()),
                    type.getSimpleName() + "." + member + " round-trips through '" + member.wire() + "'");
        }
    }

    private static Map<String, Object> facts(Map<String, Object> extra) {

        Map<String, Object> structured = map("backend", "headless", "url", "https://example.test/",
                "title", "Example", "loading", false);
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
