package be.elevenways.sketerm.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the api layer against a real headless Sketerm, skipped when the binaries are absent.
 *
 * <p>Both the server and the browser helper are COPIED to build/it-bin once at setup: the sibling
 * checkout they come from is rebuilt while this suite runs, and a binary swapped underneath a
 * running child is not a test failure worth reading.</p>
 */
class PageApiIT {

    /** Kept short on purpose: the helper's unix socket path has roughly 107 bytes to live in. */
    private static final String RUNTIME_DIR = "/tmp/claude-1000/skjava-it";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private static Path server;
    private static Path helper;

    @BeforeAll
    static void copyBinaries() throws IOException {

        Path source = Path.of(System.getProperty("user.dir")).getParent().resolve("sketerm/zig-out/bin");
        Path target = Path.of(System.getProperty("user.dir"), "build", "it-bin");

        server = copy(source.resolve("sketerm"), target.resolve("sketerm"));
        helper = copy(source.resolve("sketerm-webengine"), target.resolve("sketerm-webengine"));
    }

    @Test
    @DisplayName("A real headless view opens, evaluates, snapshots, is acted on and photographed")
    void realBrowserJourney() throws IOException {

        assumeTrue(server != null, "the sketerm binary is not built in the sibling checkout");
        assumeTrue(helper != null, "sketerm-webengine is not built; run `zig build fetch-cef && zig build web`");

        try (Sketerm sketerm = Sketerm.launch(options("journey").build())) {

            // 1. The handshake identifies the server
            assertNotNull(sketerm.serverName(), "step 1: the server named itself");

            Browser browser = sketerm.browser();

            // 2. A blank view opens and carries a handle
            Page blank = browser.openPage("about:blank");
            assertTrue(blank.handle() > 0, "step 2: the view has a handle");
            assertEquals("about:blank", blank.url(), "step 2: on the requested url");
            assertNotNull(blank.lastSnapshot(), "step 2: web_open already sent the first tree");

            // 3. JavaScript evaluates in that page
            Object sum = blank.evaluate("1+1");
            assertEquals(2L, ((Number) sum).longValue(), "step 3: 1+1 came back as a number");

            // 4. Navigating to a real document settles and updates the facts
            blank.navigate(BUTTON_PAGE);
            assertEquals("Sketerm Java IT", blank.title(), "step 4: the new title was adopted");

            // 5. A full snapshot names the button
            Snapshot snapshot = blank.snapshot(SnapshotMode.FULL);
            assertTrue(snapshot.document() > 0, "step 5: the tree is guarded to a document");
            assertFalse(snapshot.nodes().isEmpty(), "step 5: it holds nodes");

            Optional<Ref> button = snapshot.find("Press Me");
            assertTrue(button.isPresent(), "step 5: including the button, tree was:\n" + snapshot.tree());

            // 6. Clicking it runs the page's own handler
            ActResult act = blank.click(button.get());
            assertNotNull(act.detail(), "step 6: the act reported what it did");
            assertEquals("clicked", blank.evaluate("document.title"), "step 6: the page reacted to a real click");

            // 7. The page reads as an article
            Article article = blank.read();
            assertTrue(article.markdown().contains("Hello"), "step 7: the heading is in the markdown");

            // 8. A screenshot comes back as real PNG bytes
            Screenshot shot = blank.screenshot();
            assertTrue(shot.bytes().length > 0, "step 8: the image block decoded to bytes");
            assertTrue(shot.isComplete(), "step 8: as many bytes as the server declared");
            assertEquals((byte) 0x89, shot.bytes()[0], "step 8: starting with the PNG signature");

            // 9. The network log knows about the document request
            NetworkLog log = blank.network();
            assertTrue(log.totalRequests() >= 1, "step 9: at least the document was logged");
            assertFalse(blank.networkRequests().isEmpty(), "step 9: and it is in the log page");

            // 10. Waiting on a settled load returns rather than timing out
            assertNotNull(blank.waitFor(WaitFor.LOAD).detail(), "step 10: the load condition held");

            // 11. Scrolling reports a settled position
            assertNotNull(blank.scrollTo(ScrollTo.BOTTOM).how(), "step 11: the scroll named how it moved");

            // 12. web_tabs sees the view this session opened
            List<PageInfo> pages = browser.pages();
            assertTrue(pages.stream().anyMatch(info -> info.handle() == blank.handle()),
                    "step 12: the open view is listed");
        }
    }

    @Test
    @DisplayName("A view closes for real, and a named profile keeps its cookies across close and reopen")
    void closeAndProfileLifecycle() throws IOException {

        assumeTrue(server != null, "the sketerm binary is not built in the sibling checkout");
        assumeTrue(helper != null, "sketerm-webengine is not built; run `zig build fetch-cef && zig build web`");

        try (Sketerm sketerm = Sketerm.launch(options("profiles").build())) {

            List<String> tools = sketerm.session().listTools().stream()
                    .map(descriptor -> descriptor.name())
                    .toList();

            assumeTrue(tools.contains("web_close"),
                    "the copied sketerm predates web_close (rebuild the sibling checkout: zig build)");
            assumeTrue(tools.contains("web_profiles") && tools.contains("web_profile_reset"),
                    "the copied sketerm predates the profile tools (rebuild the sibling checkout: zig build)");

            Browser browser = sketerm.browser();

            assumeTrue(browser.supportsProfiles(),
                    "this server reports no web_profiles capability, so profiles cannot be exercised");

            CookieFixture fixture = CookieFixture.start();

            try {
                // 13. A throwaway identity opens, says so, and is destroyed with its view
                Page throwaway = browser.openPage(fixture.url("/set"), OpenOptions.ephemeralIdentity());

                assertEquals(ProfileKind.EPHEMERAL, throwaway.profileKind(),
                        "step 13: the view holds a throwaway identity");
                assertNull(throwaway.profile(), "step 13: which has no name");

                int throwawayHandle = throwaway.handle();
                CloseResult released = throwaway.close();

                assertEquals(throwawayHandle, released.closed(), "step 13: the handle that was closed");
                assertTrue(released.profileReleased(),
                        "step 13: its identity went with it, being the last view holding one");
                assertFalse(browser.pages().stream().anyMatch(info -> info.handle() == throwawayHandle),
                        "step 13: and web_tabs no longer lists the view");
                assertThrows(PageClosedException.class, () -> throwaway.evaluate("1"),
                        "step 13: the closed page refuses locally rather than driving a reused handle");

                // 14. A named profile takes a persistent cookie
                Page named = browser.openPage(fixture.url("/set"), OpenOptions.inProfile(PROFILE));

                assertEquals(ProfileKind.NAMED, named.profileKind(), "step 14: it is a named identity");
                assertEquals(PROFILE, named.profile(), "step 14: under the name that was asked for");
                assertTrue(named.context() > 0, "step 14: with its own engine identity context");
                assertEquals("ok", named.evaluate("document.body.textContent"),
                        "step 14: the fixture answered and set its cookie");

                // 15. Closing keeps a named profile's storage
                CloseResult kept = named.close();

                assertFalse(kept.profileReleased(), "step 15: a named profile is not thrown away");
                assertEquals(PROFILE, kept.profile(), "step 15: the close names it");

                // 16. Reopening the same name finds the cookie again
                Page reopened = browser.openPage(fixture.url("/show"), OpenOptions.inProfile(PROFILE));

                assertEquals(ProfileKind.NAMED, reopened.profileKind(), "step 16: still a named identity");
                assertEquals("cookie: it=1", reopened.evaluate("document.body.textContent"),
                        "step 16: the cookie survived the close and came back with the reopen");

                // 17. A view in the default jar never sees it
                Page anonymous = browser.openPage(fixture.url("/show"));

                assertEquals("cookie: none", anonymous.evaluate("document.body.textContent"),
                        "step 17: the profile's jar is isolated from the shared one");
                assertEquals(ProfileKind.DEFAULT, anonymous.profileKind(), "step 17: which is the default identity");
                anonymous.close();

                // 18. web_profiles lists the profile, its store and its open views
                ProfileList profiles = browser.profiles();

                assertTrue(profiles.contextsSupported(), "step 18: the helper can isolate identities");
                assertNotNull(profiles.store(), "step 18: and names where their storage lives");

                BrowserProfile listed = profiles.find(PROFILE).orElseThrow(
                        () -> new AssertionError("step 18: " + PROFILE + " is not listed in " + profiles));

                assertTrue(listed.inUse(), "step 18: the reopened view still holds it");
                assertEquals(reopened.context(), listed.context(), "step 18: under the same context id");

                // 19. A reset is refused while a view still holds the profile
                assertThrows(ConflictException.class, () -> browser.resetProfile(PROFILE),
                        "step 19: an in-use profile cannot be erased");

                // 20. After the close it succeeds, and the cookie is gone
                reopened.close();

                ProfileResetResult reset = browser.resetProfile(PROFILE);

                assertTrue(reset.deleted(), "step 20: the storage was erased");
                assertEquals(listed.context(), reset.retiredContext(),
                        "step 20: the context that was retired is the one it had");

                Page afterReset = browser.openPage(fixture.url("/show"), OpenOptions.inProfile(PROFILE));

                assertEquals("cookie: none", afterReset.evaluate("document.body.textContent"),
                        "step 20: the name is usable again and starts from an empty jar");
                assertTrue(afterReset.context() != listed.context(),
                        "step 20: behind a freshly allocated context id");

                afterReset.close();
                browser.resetProfile(PROFILE);
            } finally {
                fixture.stop();
            }
        }
    }

    /** The profile this suite owns; its store is under the per-run XDG_STATE_HOME. */
    private static final String PROFILE = "it-test";

    /**
     * A loopback origin, because file:// and data: documents carry no cookies at all in Chromium.
     */
    private record CookieFixture(HttpServer http) {

        static CookieFixture start() throws IOException {

            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

            http.createContext("/set", exchange -> {
                exchange.getResponseHeaders().add("Set-Cookie", "it=1; Max-Age=3600; Path=/");
                respond(exchange, "ok");
            });

            http.createContext("/show", exchange -> {
                List<String> cookies = exchange.getRequestHeaders().get("Cookie");
                respond(exchange, "cookie: " + (cookies == null || cookies.isEmpty() ? "none" : cookies.get(0)));
            });

            http.start();

            return new CookieFixture(http);
        }

        String url(String path) {
            return "http://127.0.0.1:" + this.http.getAddress().getPort() + path;
        }

        void stop() {
            this.http.stop(0);
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {

            byte[] bytes = ("<html><head><title>fixture</title></head><body>" + body + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);

            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        }
    }

    /**
     * @param instance the server instance name, which also keys its profile store
     */
    private static SketermOptions.Builder options(String instance) throws IOException {

        Files.createDirectories(Path.of(RUNTIME_DIR));

        Path state = Path.of(System.getProperty("user.dir"), "build", "it-state", instance);
        Files.createDirectories(state);

        return SketermOptions.builder()
                .binaryPath(server.toString())
                .instanceName("skjava-it-" + instance)
                .defaultTimeout(CALL_TIMEOUT)
                .env(clearedSketermEnvironment())
                .env("SKETERM_WEB_BIN", helper.toString())
                .env("XDG_RUNTIME_DIR", RUNTIME_DIR)
                .env("XDG_STATE_HOME", state.toString());
    }

    private static final String BUTTON_PAGE = "data:text/html,"
            + "<html><head><title>Sketerm Java IT</title></head><body>"
            + "<h1>Hello</h1>"
            + "<button onclick=\"document.title='clicked'\">Press Me</button>"
            + "<p>An article paragraph long enough to be read.</p>"
            + "</body></html>";

    /**
     * @return every inherited SKETERM_* variable mapped to null, so the child starts from a clean slate
     */
    private static Map<String, String> clearedSketermEnvironment() {

        Map<String, String> environment = new LinkedHashMap<>();

        for (String name : System.getenv().keySet()) {
            if (name.startsWith("SKETERM_")) {
                environment.put(name, null);
            }
        }

        return environment;
    }

    /**
     * @return the copy, or null when the source is missing
     */
    private static Path copy(Path source, Path target) throws IOException {

        if (!Files.isExecutable(source)) {
            return null;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

        File file = target.toFile();

        if (!file.setExecutable(true)) {
            return null;
        }

        return target;
    }
}
