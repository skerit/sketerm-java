package be.elevenways.sketerm.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
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

        Files.createDirectories(Path.of(RUNTIME_DIR));

        SketermOptions.Builder options = SketermOptions.builder()
                .binaryPath(server.toString())
                .defaultTimeout(CALL_TIMEOUT)
                .env(clearedSketermEnvironment())
                .env("SKETERM_WEB_BIN", helper.toString())
                .env("XDG_RUNTIME_DIR", RUNTIME_DIR);

        try (Sketerm sketerm = Sketerm.launch(options.build())) {

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
