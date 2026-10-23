package be.elevenways.sketerm.api;

import be.elevenways.sketerm.testing.ScriptedSketerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static be.elevenways.sketerm.api.FakeSketermServer.facts;
import static be.elevenways.sketerm.api.FakeSketermServer.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives web_print_pdf and its web_print_pdf_css_page preflight against scripted structured results.
 */
class PrintApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Slides" {0 children}
            """;

    @Test
    @DisplayName("A page prints on its @page sheet, with defaults, and doomed or unanswered prints fail")
    void printJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_print_pdf", arguments -> facts(map("view", 3, "path", arguments.get("path"),
                "bytes", 6861, "sha256", "c0ffee", "paper", "default",
                "css_page_size", Boolean.TRUE.equals(arguments.get("css_page_size")),
                "no_margins", Boolean.TRUE.equals(arguments.get("no_margins")))));

        Page page = server.browser().openPage("https://example.test/");

        // 1. The @page layout goes out as both flags, with the budget in milliseconds and backgrounds left default
        PdfFile printed = page.printPdf(Path.of("/tmp/slides.pdf"),
                PrintOptions.cssPage().withTimeout(Duration.ofSeconds(20)));

        Map<String, Object> sent = server.lastArguments("web_print_pdf");
        assertEquals(3, ((Number) sent.get("pane")).intValue(), "step 1: addressed by handle");
        assertEquals("/tmp/slides.pdf", sent.get("path"), "step 1: to the absolute destination");
        assertEquals(true, sent.get("css_page_size"), "step 1: the @page size decides the sheet");
        assertEquals(true, sent.get("no_margins"), "step 1: without engine margins");
        assertFalse(sent.containsKey("background"), "step 1: backgrounds keep the server's default");
        assertEquals(20_000L, ((Number) sent.get("timeout_ms")).longValue(), "step 1: the budget in milliseconds");

        assertEquals(Path.of("/tmp/slides.pdf"), printed.path(), "step 1: the answer names the file");
        assertEquals(6861, printed.bytes(), "step 1: its size");
        assertEquals("c0ffee", printed.sha256(), "step 1: and its digest");
        assertTrue(printed.cssPageSize() && printed.noMargins(), "step 1: and echoes the layout");

        // 2. The defaults send neither layout flag; turning backgrounds off is said explicitly
        PdfFile plain = page.printPdf(Path.of("/tmp/plain.pdf"), PrintOptions.defaults().withBackground(false));

        Map<String, Object> plainSent = server.lastArguments("web_print_pdf");
        assertFalse(plainSent.containsKey("css_page_size"), "step 2: no @page flag");
        assertFalse(plainSent.containsKey("no_margins"), "step 2: no margin flag");
        assertFalse(plainSent.containsKey("timeout_ms"), "step 2: the server's budget");
        assertEquals(false, plainSent.get("background"), "step 2: backgrounds off");
        assertFalse(plain.cssPageSize(), "step 2: the echo says the engine's sheet");

        // 3. A relative destination is refused before anything goes out
        int sentSoFar = server.callsTo("web_print_pdf").size();
        assertThrows(InvalidArgsException.class, () -> page.printPdf(Path.of("relative.pdf")),
                "step 3: a relative path is refused");
        assertEquals(sentSoFar, server.callsTo("web_print_pdf").size(), "step 3: and nothing was sent");

        // 4. An answer naming no file or no size is a protocol mismatch, and a helper's refusal keeps its code
        server.on("web_print_pdf", facts(map("view", 3, "bytes", 10)));
        assertThrows(ProtocolMismatchException.class, () -> page.printPdf(Path.of("/tmp/lost.pdf")),
                "step 4: no path in the answer");
        server.on("web_print_pdf", facts(map("view", 3, "path", "/tmp/sizeless.pdf")));
        assertThrows(ProtocolMismatchException.class, () -> page.printPdf(Path.of("/tmp/sizeless.pdf")),
                "step 4: no byte count in the answer");

        server.onError("web_print_pdf", "unavailable", "this browser helper predates css_page_size/no_margins",
                false);
        assertThrows(UnavailableException.class, () -> page.printPdf(Path.of("/tmp/old.pdf"), PrintOptions.cssPage()),
                "step 4: an older helper refuses the layout rather than print on the wrong paper");
    }

    @Test
    @DisplayName("web_print_pdf_css_page is preflighted, and null before the helper starts means this server's own")
    void cssPageCapability() {

        assertTrue(supports(map("web_print_pdf", true, "web_print_pdf_css_page", true)), "a negotiated helper");
        assertTrue(supports(map("web_print_pdf", true, "web_print_pdf_css_page", null)),
                "a helper that has not started yet: the server names the fact, its own helper honours it");
        assertFalse(supports(map("web_print_pdf", true, "web_print_pdf_css_page", false)), "an older helper");
        assertFalse(supports(map("web_print_pdf", true)), "a server predating the layout flags names no fact");
        assertFalse(supports(map("web_print_pdf", false, "web_print_pdf_css_page", false)), "no printing at all");
        assertFalse(supports(map("web_print_pdf", null, "web_print_pdf_css_page", null)), "a GUI-driven server");
    }

    @Test
    @DisplayName("The scripted browser preset prints a real file where it was told to")
    void scriptedBrowserPrints(@TempDir Path directory) throws IOException {

        try (ScriptedSketerm server = ScriptedSketerm.browser(); Sketerm sketerm = server.connect()) {

            assertTrue(sketerm.browser().supportsPrintPdfCssPage(), "the preset prints @page sheets");

            Page page = sketerm.browser().openPage("https://example.test/");
            PdfFile printed = page.printPdf(directory.resolve("out.pdf"), PrintOptions.cssPage());

            assertArrayEquals(ScriptedSketerm.PRINTED_PDF, Files.readAllBytes(printed.path()), "the file is on disk");
            assertEquals(ScriptedSketerm.PRINTED_PDF.length, printed.bytes(), "with its size");
            assertEquals(64, printed.sha256().length(), "and a SHA-256 digest");
            assertTrue(printed.cssPageSize(), "and the layout echoed");
        }
    }

    private static boolean supports(Map<String, Object> capabilities) {
        FakeSketermServer server = new FakeSketermServer();
        server.on("capabilities", capabilities);
        return server.browser().supportsPrintPdfCssPage();
    }
}
