package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static be.elevenways.sketerm.api.FakeSketermServer.facts;
import static be.elevenways.sketerm.api.FakeSketermServer.map;

/**
 * Drives a page through a whole session against scripted structured results.
 */
class PageApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Example" {3 children}
              [2] heading "Hello"
              [3] button "Press Me"
              [4] textbox "Name"
            """;

    @Test
    @DisplayName("A page opens, is read, acted on and photographed, always naming its own handle")
    void pageJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 7, "settled", true, "document", 1, "revision", 1,
                "snapshot", TREE)));
        server.on("web_eval", facts(map("evaluated", true, "value", map("value", 2))));
        server.on("web_act", facts(map("id", 3, "action", "click", "acted", true,
                "detail", "click at 25,19", "delta_kind", "delta", "delta", "delta rev 1->2\n")));
        server.on("web_snapshot", facts(map("kind", "full", "document", 1, "revision", 2,
                "snapshot", TREE)));
        server.on("web_read", facts(map("reader_ids", true, "document", 1, "revision", 2,
                "markdown", "# Hello\n",
                "entities", List.of(map("id", 2, "kind", "heading", "text", "Hello", "url", "")))));
        server.on("web_wait", facts(map("waited_for", "text", "arg", "Press",
                "settled", true, "detail", "found in the tree")));
        server.on("web_scroll", facts(map("how", "bottom",
                "before", map("x", 0, "y", 0, "max_y", 900, "viewport", 800),
                "after", map("x", 0, "y", 900, "max_y", 900, "viewport", 800),
                "moved", true)));
        server.on("web_network", facts(map("blocking_enabled", true, "blocked", 1,
                "total_requests", 2, "rules_loaded", 13, "next_seq", 3,
                "requests", List.of(
                        map("seq", 1, "blocked", false, "type", "document", "method", "GET",
                                "url", "https://example.test/", "status", 200, "duration_ms", 30, "size", 166),
                        map("seq", 2, "blocked", true, "type", "script", "method", "GET",
                                "url", "https://ads.test/x.js")))));

        Map<String, Object> screenshot = facts(map("bytes", 3, "width", 1280, "height", 800));
        screenshot.put("__image", FakeSketermServer.imageBlock(
                Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        server.on("web_screenshot", screenshot);

        Browser browser = server.browser();

        // 1. Opening a page adopts its handle and the first snapshot web_open already sent
        Page page = browser.openPage("https://example.test/");

        assertEquals(7, page.handle(), "step 1: the headless 'view' field is the handle");
        assertEquals("https://example.test/", page.url(), "step 1: the url was adopted");
        assertEquals("Example", page.title(), "step 1: the title was adopted");
        assertEquals(1, page.document(), "step 1: the document counter was adopted");
        assertEquals("https://example.test/", server.lastArguments("web_open").get("url"),
                "step 1: the url went out as an argument");

        Snapshot opening = page.lastSnapshot();
        assertNotNull(opening, "step 1: web_open's snapshot is kept, since an auto snapshot now would be empty");
        assertEquals(4, opening.nodes().size(), "step 1: four nodes parsed out of the tree");
        assertEquals("button", opening.nodes().get(2).role(), "step 1: with their roles");

        // 2. Evaluation removes the authenticated bridge's one result envelope
        assertEquals(2L, ((Number) page.evaluate("1+1")).longValue(), "step 2: the bridge envelope was decoded");
        assertEquals(7, argInt(server, "web_eval", "pane"), "step 2: the handle was named explicitly");

        // 3. Acting on a ref found in the tree
        Ref button = opening.find("Press").orElseThrow();
        assertEquals(3, button.id(), "step 3: the button is node 3");
        assertEquals(1, button.document(), "step 3: guarded to the document it was read from");

        ActResult act = page.click(button);
        assertEquals(Action.CLICK, act.action(), "step 3: the act echoes what it did");
        assertEquals("click at 25,19", act.detail(), "step 3: with the page's own detail");
        assertFalse(act.navigated(), "step 3: the click stayed on the page");
        assertEquals(3, argInt(server, "web_act", "id"), "step 3: the id went out");
        assertEquals("click", server.lastArguments("web_act").get("action"), "step 3: and the wire action");

        // 4. A fresh snapshot moves the revision but keeps the same document
        Snapshot second = page.snapshot(SnapshotMode.FULL);
        assertEquals(2, second.revision(), "step 4: the revision moved");
        assertEquals(2, page.revision(), "step 4: and the page absorbed it");
        assertEquals("full", server.lastArguments("web_snapshot").get("mode"), "step 4: the mode went out");

        // 5. A ref from the older revision is still good: only a new document invalidates ids
        page.fill(second.find("Name").orElseThrow(), "Jelle");
        assertEquals("Jelle", server.lastArguments("web_act").get("value"), "step 5: set_value carries the text");
        page.click(button);
        assertEquals(3, argInt(server, "web_act", "id"),
                "step 5: the older ref was accepted, as the server accepts it");

        // 6. Reading gives markdown and addressable entities
        Article article = page.read();
        assertEquals("# Hello\n", article.markdown(), "step 6: the article text");
        assertTrue(article.readerIds(), "step 6: this helper hands out reader ids");
        assertEquals(2, article.refTo(article.findEntity("Hello").orElseThrow()).id(),
                "step 6: the heading entity is addressable");

        // 7. Waiting reports what settled
        WaitResult waited = page.waitFor(WaitFor.TEXT, "Press");
        assertEquals(WaitFor.TEXT, waited.waitedFor(), "step 7: the condition round-tripped");
        assertEquals("text", server.lastArguments("web_wait").get("for"), "step 7: spelled 'for' on the wire");
        assertEquals("Press", server.lastArguments("web_wait").get("arg"), "step 7: with its argument");

        // 8. Scrolling reports before and after
        ScrollResult scrolled = page.scrollTo(ScrollTo.BOTTOM);
        assertTrue(scrolled.moved(), "step 8: the page moved");
        assertEquals(900, scrolled.after().y(), "step 8: to the bottom");
        assertEquals("bottom", server.lastArguments("web_scroll").get("to"), "step 8: the keyword went out");

        // 9. The network log decodes into typed entries
        List<NetworkLog.NetworkRequest> requests = page.networkRequests();
        assertEquals(2, requests.size(), "step 9: both entries");
        assertEquals(200, requests.get(0).status(), "step 9: a completed request carries its status");
        assertTrue(requests.get(1).blocked(), "step 9: the ad was blocked");
        assertNull(requests.get(1).status(), "step 9: a pending request has no status, not a zero");

        // 10. The screenshot decodes the image block, not the prose
        Screenshot shot = page.screenshot();
        assertArrayEquals(new byte[]{1, 2, 3}, shot.bytes(), "step 10: the image block decoded");
        assertEquals(1280, shot.width(), "step 10: the width came from the facts");
        assertTrue(shot.isComplete(), "step 10: the decode matches the declared byte count");

        // 11. Every single web_* call named the handle explicitly
        for (FakeSketermServer.Call call : server.calls()) {
            if (!call.tool().equals("web_open")) {
                assertEquals(7, ((Number) call.arguments().get("pane")).intValue(),
                        "step 11: " + call.tool() + " addressed the view by handle");
            }
        }
    }

    @Test
    @DisplayName("A slow open still returns its live view when the first snapshot is unavailable")
    void openWithoutAnOpeningSnapshot() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 9, "settled", false,
                "snapshot_error", "semantic snapshot timed out")));
        server.on("web_tabs", map("backend", "headless", "count", 1, "helper", "ready",
                "views", List.of(map("view", 9, "url", "https://example.test/",
                        "title", "Example", "loading", true, "current", true))));
        server.on("web_snapshot", facts(map("kind", "full", "document", 2, "revision", 1,
                "snapshot", TREE)));

        Page page = server.browser().openPage("https://example.test/");

        assertEquals(9, page.handle(), "the minted view remains addressable");
        assertFalse(page.wasOpeningSettled(), "the unsettled first navigation is stated");
        assertEquals("semantic snapshot timed out", page.openingSnapshotError(),
                "the snapshot failure is preserved for diagnostics");
        assertNull(page.lastSnapshot(), "there was no opening tree to pretend existed");
        assertEquals("https://example.test/", page.url(),
                "the later web_tabs facts are adopted by the verified page");
        assertTrue(page.isLoading(), "including the view's current loading state");
        assertNotNull(page.snapshot(SnapshotMode.FULL), "a later snapshot can recover normally");
    }

    @Test
    @DisplayName("An open that never produced a real view is refused instead of leaking a phantom page")
    void openWithoutAView() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 9, "settled", false,
                "snapshot_error", "no web view is open")));
        server.on("web_tabs", map("backend", "headless", "count", 0,
                "helper", "ready", "views", List.of()));
        server.onError("web_close", "not_found", "no web view with that id", false);

        UnavailableException failure = assertThrows(UnavailableException.class,
                () -> server.browser().openPage("https://example.test/"));

        assertTrue(failure.getMessage().contains("not open"),
                "the failure states that the minted handle was a phantom");
        assertEquals(1, server.callsTo("web_close").size(),
                "cleanup is still attempted in case the listing raced creation");
    }

    @Test
    @DisplayName("Evaluation preserves an object whose only property is named value")
    void evaluationPreservesAValueObject() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 1, "snapshot", TREE)));
        server.on("web_eval", facts(map("evaluated", true,
                "value", map("value", map("value", 2)))));

        Object value = server.browser().openPage("https://example.test/")
                .evaluate("({value: 2})");

        assertTrue(value instanceof Map, "the JavaScript object remains an object");
        assertEquals(2L, ((Number) ((Map<?, ?>) value).get("value")).longValue(),
                "with its property intact");
    }

    @Test
    @DisplayName("The RPC deadline outlives the operation timeout by a response grace")
    void rpcDeadlineOutlivesTheToolDeadline() {

        FakeSketermServer server = new FakeSketermServer();
        ToolCalls calls = server.toolCalls();

        assertEquals(10_000, calls.rpcTimeoutMs(Map.of()),
                "the five-second default gets response grace");
        assertEquals(25_000, calls.rpcTimeoutMs(Map.of("timeout_ms", 20_000)),
                "an explicit longer operation deadline controls the RPC deadline");

        assertThrows(IllegalArgumentException.class,
                () -> ToolCalls.putTimeout(ToolCalls.args(), java.time.Duration.ofNanos(1)),
                "a positive duration that rounds to zero milliseconds is still unusable");
    }

    @Test
    @DisplayName("clickText finds a node by its text and acts on it")
    void clickTextUsesTheQueryMatch() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_query", facts(map("kind", "find_text", "arg", "Press",
                "matches", "query find \"Press\" 1 matches\n[3] button \"Press Me\"\n")));
        server.on("web_act", facts(map("id", 3, "action", "click", "acted", true, "detail", "click")));

        Page page = server.browser().openPage("https://example.test/");
        ActResult result = page.clickText("Press");

        assertEquals(3, result.id(), "the query's match is what got clicked");
        assertEquals("find_text", server.lastArguments("web_query").get("kind"), "the query kind went out");
        assertEquals(3, argInt(server, "web_act", "id"), "and the id it produced");
    }

    @Test
    @DisplayName("clickText refuses loudly when nothing matches")
    void clickTextWithoutAMatch() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_query", facts(map("kind", "find_text", "arg", "Nope",
                "matches", "query find \"Nope\" 0 matches\n")));

        Page page = server.browser().openPage("https://example.test/");

        NotFoundException failure = assertThrows(NotFoundException.class, () -> page.clickText("Nope"));
        assertTrue(failure.getMessage().contains("Nope"), "the message names what was looked for");
        assertTrue(server.callsTo("web_act").isEmpty(), "and nothing was acted on");
    }

    @Test
    @DisplayName("web_tabs lists the open views and attaches to one")
    void listsAndAttaches() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_tabs", map("backend", "headless", "count", 2, "helper", "ready",
                "views", List.of(
                        map("view", 1, "url", "https://a.test/", "title", "A", "loading", false, "current", false),
                        map("view", 2, "url", "https://b.test/", "title", "B", "loading", false, "current", true))));

        Browser browser = server.browser();
        List<PageInfo> pages = browser.pages();

        assertEquals(2, pages.size(), "both views are listed");
        assertEquals("A", pages.get(0).title(), "with their titles");
        assertTrue(pages.get(1).current(), "and the current one flagged");

        Page attached = browser.page(2);
        assertEquals("https://b.test/", attached.url(), "attaching adopts the listed facts");

        assertThrows(NotFoundException.class, () -> browser.page(99), "an unknown handle is refused");
    }

    @Test
    @DisplayName("refresh() absorbs a web_tabs answer's policy facts through the same seam as any other")
    void refreshAbsorbsPolicyFacts() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_tabs", map("backend", "headless", "count", 1, "helper", "ready",
                "views", List.of(map("view", 1, "url", "https://example.test/", "title", "Example",
                        "loading", false, "current", true,
                        "policy_active", true, "policy_exhausted", true,
                        "policy_exhausted_reason", "request_cap"))));

        Page page = server.browser().openPage("https://example.test/");
        assertFalse(page.isPolicyActive(), "before refresh: web_open carried no policy facts");

        PageInfo info = page.refresh();

        assertTrue(info.policyActive(), "the returned PageInfo decoded policy_active");
        assertTrue(info.policyExhausted(), "and policy_exhausted");
        assertEquals(DenialReason.REQUEST_CAP, info.policyExhaustedReason(), "and the reason");

        assertTrue(page.isPolicyActive(), "refresh() fed policy_active into the page's cache");
        assertTrue(page.isPolicyExhausted(), "and the latched exhaustion");
        assertEquals(DenialReason.REQUEST_CAP, page.policyExhaustedReason(), "and its reason");
    }

    @Test
    @DisplayName("refresh() never clears a cached exhaustion when the answer omits the keys")
    void refreshNeverClearsLatchedExhaustion() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_read", facts(map("policy_exhausted", true, "policy_exhausted_reason", "byte_cap",
                "reader_ids", true, "document", 1, "revision", 1, "markdown", "# Hello\n")));
        server.on("web_tabs", map("backend", "headless", "count", 1, "helper", "ready",
                "views", List.of(map("view", 1, "url", "https://example.test/", "title", "Example",
                        "loading", false, "current", true, "policy_active", true))));

        Page page = server.browser().openPage("https://example.test/");
        page.read();

        assertTrue(page.isPolicyExhausted(), "the read latched the budget in the page's cache");
        assertEquals(DenialReason.BYTE_CAP, page.policyExhaustedReason());

        PageInfo info = page.refresh();

        assertFalse(info.policyExhausted(), "this web_tabs entry carried no exhaustion fact");
        assertNull(info.policyExhaustedReason());

        assertTrue(page.isPolicyExhausted(), "refresh() must not clear a latched exhaustion");
        assertEquals(DenialReason.BYTE_CAP, page.policyExhaustedReason(),
                "the reason from the earlier answer is kept, since latching is permanent per view");
    }

    @Test
    @DisplayName("Keys, a resize, the console and a review each name the view and decode their answer")
    void reviewToolsJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 4, "settled", true, "document", 1, "revision", 1,
                "snapshot", TREE)));
        server.on("web_key", facts(map("keys", "Tab Tab", "count", 2, "loading_after", false,
                "delta_kind", "delta", "delta", "delta rev 1->2\n  [3] button \"Press Me\" (focused)\n")));
        server.on("web_resize", facts(map("width", 375, "height", 700)));
        server.on("web_console", facts(map("count", 3, "dropped", 1, "last_id", 4,
                "lines", "[2] log: hello\n[3] error: Uncaught Error: boom\n    at app.js:1\n[4] warn: careful\n")));
        server.on("web_inspect", map("captured_at_ms", 1700, "inspection", map(
                "document_id", "abc", "url", "https://example.test/", "viewport", map("width", 375, "height", 700),
                "elements", List.of(
                        map("ref", 1, "tag", "button", "role", "button", "name", "Press Me",
                                "attributes", map("aria-expanded", "false"), "focusable", true),
                        map("ref", 2, "tag", "div", "role", "", "name", "", "dom_id", "menu",
                                "attributes", map(), "focusable", false)),
                "focus", 1, "landmarks", List.of(), "controls", List.of(1),
                "issues", List.of(map("kind", "disclosure_mismatch", "element", 1, "related", 2)),
                "document_overflow", 12, "truncated", false,
                "page_errors", List.of(map("id", 1, "kind", "uncaught_exception", "message", "boom")),
                "errors_dropped", 0, "coverage", "main document"),
                "console_errors", List.of(map("id", 3, "kind", "error", "message", "Uncaught Error: boom"))));

        Page page = server.browser().openPage("https://example.test/");

        // 1. Keys travel as one chord string and the delta comes back as the server wrote it
        KeyResult keys = page.key("Tab Tab");
        assertEquals("Tab Tab", server.lastArguments("web_key").get("keys"), "step 1: the chords as given");
        assertEquals(4, argInt(server, "web_key", "pane"), "step 1: the view's own handle");
        assertEquals(2, keys.count(), "step 1: both chords counted");
        assertTrue(keys.delta().contains("(focused)"), "step 1: the follow-up delta");
        assertThrows(IllegalArgumentException.class, () -> page.key(" "), "step 1: no chord is refused locally");

        // 2. A resize names both sides and answers with the applied viewport
        assertEquals(new Viewport(375, 700), page.resize(375, 700), "step 2: the applied size");
        assertEquals(375, argInt(server, "web_resize", "width"), "step 2: the width went out");

        // 3. Console lines split on their heads; a continuation stays with its message
        ConsoleLog console = page.console(1, 50);
        assertEquals(1L, ((Number) server.lastArguments("web_console").get("since")).longValue(), "step 3: cursor");
        assertEquals(50, argInt(server, "web_console", "max"), "step 3: cap");
        assertEquals(3, console.lines().size(), "step 3: three messages");
        assertEquals(new ConsoleLog.Line(3, "error", "Uncaught Error: boom\n    at app.js:1"),
                console.lines().get(1), "step 3: the stack line belongs to its error");
        assertEquals(4L, console.lastId(), "step 3: the next cursor");
        assertEquals(1L, console.dropped(), "step 3: what the mirror lost");

        // 4. The review decodes its element table, the finding and both error lanes
        Inspection inspection = page.inspect("main");
        assertEquals("main", server.lastArguments("web_inspect").get("selector"), "step 4: narrowed");
        assertEquals("Press Me", inspection.element(inspection.focus()).name(), "step 4: the focused element");
        assertEquals("menu", inspection.element(2).domId(), "step 4: the dom id");
        assertEquals("false", inspection.element(1).attributes().get("aria-expanded"), "step 4: attributes");
        assertEquals(new Inspection.Issue("disclosure_mismatch", 1, 2, null), inspection.issues().get(0),
                "step 4: the finding names both elements");
        assertEquals(12, inspection.documentOverflow(), "step 4: horizontal overflow");
        assertEquals("boom", inspection.pageErrors().get(0).message(), "step 4: page errors");
        assertEquals("error", inspection.consoleErrors().get(0).kind(), "step 4: console errors");
    }

    @Test
    void headlessFrameRateOptionsValidatePreserveAndFailClosed() {
        FakeSketermServer server = new FakeSketermServer();
        server.on("capabilities", map("web_max_fps", true));
        server.on("web_close", arguments -> map("backend", "headless", "closed", arguments.get("pane"),
                "remaining", 0, "current", 0));
        OpenOptions options = OpenOptions.viewport(640, 480).withMaxFps(15)
                .withTimeout(Duration.ofSeconds(5)).withEphemeral().withRoute("direct")
                .withColorScheme(ColorScheme.LIGHT).withViewport(800, 600);
        assertEquals(15, options.maxFps(), "step 1: fluent options preserve the cap");
        assertNull(options.withMaxFps(null).maxFps(), "step 1: null restores the server default");
        for (int invalid : List.of(0, -1, OpenOptions.MAX_FPS + 1))
            assertThrows(InvalidArgsException.class, () -> options.withMaxFps(invalid));
        server.on("web_open", facts(map("view", 7, "settled", true, "snapshot", TREE,
                "document", 1, "revision", 1, "max_fps", 15, "color_scheme", "light")));
        Browser browser = server.browser();
        Page page = browser.openPage("https://example.test/", options);
        assertEquals(15, argInt(server, "web_open", "max_fps"), "step 2: the option reaches MCP");
        page.close();
        server.on("web_open", facts(map("view", 8, "settled", true, "snapshot", TREE,
                "document", 1, "revision", 1, "color_scheme", "light")));
        assertThrows(ProtocolMismatchException.class, () -> browser.openPage("https://example.test/", options),
                "step 3: an ignored cap fails closed and closes the returned handle");
        assertEquals(8, argInt(server, "web_close", "pane"));
        server.on("capabilities", map("web_max_fps", false));
        int sent = server.callsTo("web_open").size();
        assertThrows(UnavailableException.class, () -> browser.openPage("https://example.test/", options));
        assertEquals(sent, server.callsTo("web_open").size(), "step 4: an unsupported server opens nothing");
    }

    private static int argInt(FakeSketermServer server, String tool, String key) {
        return ((Number) server.lastArguments(tool).get(key)).intValue();
    }
}
