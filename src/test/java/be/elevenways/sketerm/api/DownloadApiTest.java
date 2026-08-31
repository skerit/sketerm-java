package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives web_download and web_eval's out_file variant against scripted structured results.
 */
class DownloadApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Example" {1 children}
              [2] link "Report"
            """;

    @Test
    @DisplayName("A page downloads one file, a batch and its listing, and a timeout stays data")
    void downloadJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 4, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_download", arguments -> {

            if (arguments.containsKey("urls")) {
                return facts(map("downloads", List.of(
                                entry("https://example.test/a.zip", "/tmp/out/a.zip", "done", 12, "sha-a", null),
                                entry("https://example.test/b.zip", "/tmp/out/b.zip", "failed", 0, null, "404"),
                                entry("https://example.test/c.zip", "/tmp/out/c.zip", "not_started", 0, null,
                                        "the call's budget ran out before this url was reached")),
                        "completed", 1, "failed", 1, "unfinished", 1));
            }

            if (arguments.containsKey("url")) {
                return facts(map("downloads", List.of(
                                entry("https://example.test/report.pdf", "/tmp/report.pdf", "done", 4096, "abc123", null)),
                        "completed", 1, "failed", 0, "unfinished", 0,
                        "path", "/tmp/report.pdf", "state", "done", "bytes", 4096, "sha256", "abc123"));
            }

            return facts(map("downloads", List.of(
                            entry("", "/home/user/Downloads/page.csv", "running", 900, null, null)),
                    "listing", true));
        });

        Page page = server.browser().openPage("https://example.test/");

        // 1. One url goes out with its absolute destination and decodes to a complete file
        Download one = page.download("https://example.test/report.pdf", Path.of("/tmp/report.pdf"));

        Map<String, Object> single = server.lastArguments("web_download");
        assertEquals("https://example.test/report.pdf", single.get("url"), "step 1: the url went out");
        assertEquals("/tmp/report.pdf", single.get("path"), "step 1: with the destination as 'path'");
        assertEquals(4, ((Number) single.get("pane")).intValue(), "step 1: addressed by handle");
        assertFalse(single.containsKey("dir"), "step 1: a single download names no directory");

        assertTrue(one.succeeded(), "step 1: the file landed");
        assertEquals(DownloadState.DONE, one.state(), "step 1: with the done state");
        assertEquals(Path.of("/tmp/report.pdf"), one.path(), "step 1: at the path the server named");
        assertEquals(4096, one.bytes(), "step 1: and its size");
        assertEquals("abc123", one.sha256(), "step 1: and its digest, so nothing is read back to verify");
        assertNull(one.reason(), "step 1: a completed download names no reason");

        // 2. A batch names its directory and reports every url, including the ones that did not land
        DownloadBatch batch = page.download(List.of("https://example.test/a.zip",
                "https://example.test/b.zip", "https://example.test/c.zip"), Path.of("/tmp/out"));

        Map<String, Object> many = server.lastArguments("web_download");
        assertEquals(3, ((List<?>) many.get("urls")).size(), "step 2: all three urls went out");
        assertEquals("/tmp/out", many.get("dir"), "step 2: into one directory");
        assertFalse(many.containsKey("path"), "step 2: a batch names no single file path");

        assertEquals(3, batch.downloads().size(), "step 2: one entry per url");
        assertEquals(1, batch.completed(), "step 2: the server's own completed count");
        assertEquals(1, batch.failed(), "step 2: its failed count");
        assertEquals(1, batch.unfinished(), "step 2: and its unfinished count");
        assertFalse(batch.allCompleted(), "step 2: so the batch is not all green");
        assertFalse(batch.listing(), "step 2: this answer fetched rather than listed");
        assertEquals(DownloadState.FAILED, batch.downloads().get(1).state(), "step 2: the bad url failed");
        assertEquals("404", batch.downloads().get(1).reason(), "step 2: carrying the server's reason");
        assertEquals(DownloadState.NOT_STARTED, batch.downloads().get(2).state(),
                "step 2: the url the budget never reached is not_started, not failed");
        assertTrue(batch.downloads().get(2).unfinished(), "step 2: which counts as unfinished");
        assertEquals(List.of(batch.downloads().getFirst()), batch.completedDownloads(),
                "step 2: only the finished file is a completed download");

        // 3. With no url the same tool lists what the view knows, page-initiated downloads included
        DownloadBatch listed = page.downloads();

        Map<String, Object> listing = server.lastArguments("web_download");
        assertFalse(listing.containsKey("url"), "step 3: a listing sends no url");
        assertFalse(listing.containsKey("urls"), "step 3: and no url list");
        assertTrue(listed.listing(), "step 3: the answer says it listed");
        assertEquals(DownloadState.RUNNING, listed.downloads().getFirst().state(),
                "step 3: a transfer the page started is still running");
        assertEquals("", listed.downloads().getFirst().url(),
                "step 3: a page-initiated entry names no url this call asked for");
    }

    @Test
    @DisplayName("A timed_out download is data: the budget ran out, not the transfer")
    void timedOutIsNotAFailure() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_download", facts(map("downloads", List.of(
                        entry("https://example.test/big.iso", "/tmp/big.iso", "timed_out", 1024, null,
                                "the download had not finished when the call's budget ran out")),
                "completed", 0, "failed", 0, "unfinished", 1,
                "path", "/tmp/big.iso", "state", "timed_out", "bytes", 1024)));

        Page page = server.browser().openPage("https://example.test/");
        Download slow = page.download("https://example.test/big.iso", Path.of("/tmp/big.iso"),
                java.time.Duration.ofSeconds(30));

        assertEquals(DownloadState.TIMED_OUT, slow.state(), "the state is reported, not thrown");
        assertTrue(slow.unfinished(), "and it counts as unfinished, since it may still be running");
        assertFalse(slow.succeeded(), "but never as complete");
        assertEquals(1024, slow.bytes(), "the bytes seen so far are kept");
        assertNull(slow.sha256(), "an unfinished file has no digest to trust");
        assertEquals(30_000L, ((Number) server.lastArguments("web_download").get("timeout_ms")).longValue(),
                "the budget went out as milliseconds");
    }

    @Test
    @DisplayName("Doomed download arguments are refused before the call goes out")
    void refusesImpossibleArgumentsLocally() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));

        Page page = server.browser().openPage("https://example.test/");

        assertThrows(InvalidArgsException.class,
                () -> page.download("https://example.test/a", Path.of("relative/a")),
                "a relative destination is refused");

        assertThrows(InvalidArgsException.class,
                () -> page.download(List.of(), Path.of("/tmp")),
                "an empty batch is refused");

        List<String> tooMany = IntStream.rangeClosed(0, DownloadBatch.MAX_URLS)
                .mapToObj(index -> "https://example.test/" + index)
                .toList();

        InvalidArgsException capped = assertThrows(InvalidArgsException.class,
                () -> page.download(tooMany, Path.of("/tmp")));
        assertTrue(capped.getMessage().contains(String.valueOf(DownloadBatch.MAX_URLS)),
                "the refusal names the cap");

        assertTrue(server.callsTo("web_download").isEmpty(), "and nothing was ever sent");
    }

    @Test
    @DisplayName("An eval result can be written to disk instead of into the answer")
    void evaluateToFile() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 2, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_eval", facts(map("evaluated", true, "out_file", "/tmp/rows.json",
                "bytes", 51200, "sha256", "deadbeef", "format", "json", "truncated", false)));

        Page page = server.browser().openPage("https://example.test/");
        EvaluatedFile written = page.evaluateToFile("[...document.links].map(a => a.href)",
                Path.of("/tmp/rows.json"));

        assertEquals("/tmp/rows.json", server.lastArguments("web_eval").get("out_file"),
                "the destination went out as out_file");
        assertEquals(Path.of("/tmp/rows.json"), written.path(), "the answer names where it landed");
        assertEquals(51200, written.bytes(), "with the size");
        assertEquals("deadbeef", written.sha256(), "and the digest");
        assertEquals(EvalFormat.JSON, written.format(), "a non-string value was written as JSON");
        assertFalse(written.truncated(), "and the page serialized the whole value");
        assertEquals(0, written.totalChars(), "so no cut length was reported");
    }

    @Test
    @DisplayName("A page-side cut is stated on the written file rather than hidden by its size")
    void evaluateToFileReportsATruncatedValue() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 2, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_eval", facts(map("evaluated", true, "out_file", "/tmp/dump.txt",
                "bytes", 256000, "format", "text", "truncated", true, "total_chars", 410511)));

        EvaluatedFile written = server.browser().openPage("https://example.test/")
                .evaluateToFile("document.body.innerText", Path.of("/tmp/dump.txt"));

        assertTrue(written.truncated(), "the cut is a fact, not a short file to be discovered");
        assertEquals(410511, written.totalChars(), "and the page says how much there was");
        assertEquals(EvalFormat.TEXT, written.format(), "a string value was written as itself");
        assertNull(written.sha256(), "this answer named no digest");
    }

    @Test
    @DisplayName("An unknown download state fails closed instead of being folded into a neighbour")
    void unknownStateFailsClosed() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_download", facts(map("downloads", List.of(
                entry("https://example.test/a", "/tmp/a", "paused", 0, null, null)))));

        Page page = server.browser().openPage("https://example.test/");

        SketermApiException failure = assertThrows(SketermApiException.class, page::downloads);
        assertTrue(failure.getMessage().contains("paused"), "the unknown token is named");
    }

    @Test
    @DisplayName("web_downloads is preflighted beside web_profiles")
    void capabilityProbe() {

        FakeSketermServer server = new FakeSketermServer();
        server.on("capabilities", map("web_profiles", true, "web_downloads", true));

        Browser browser = server.browser();

        assertTrue(browser.supportsDownloads(), "the report's flag is what is read");
        assertTrue(browser.supportsProfiles(), "beside the profile flag");

        FakeSketermServer without = new FakeSketermServer();
        without.on("capabilities", map("web_profiles", false));

        assertFalse(without.browser().supportsDownloads(),
                "a server naming no download capability supports none");
    }

    private static Map<String, Object> entry(String url, String path, String state, long bytes,
                                             String sha256, String reason) {

        Map<String, Object> result = map("url", url, "path", path, "state", state, "bytes", bytes);

        if (sha256 != null) {
            result.put("sha256", sha256);
        }

        if (reason != null) {
            result.put("reason", reason);
        }

        return result;
    }

    /**
     * The fields every browser answer carries, merged with the ones this call adds.
     */
    private static Map<String, Object> facts(Map<String, Object> extra) {

        Map<String, Object> structured = map("backend", "headless", "origin", "https://example.test",
                "url", "https://example.test/", "title", "Example", "loading", false);
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
