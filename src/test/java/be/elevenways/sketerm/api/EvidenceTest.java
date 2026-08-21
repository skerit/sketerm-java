package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence capture: one page, one moment, composed out of the reads that already exist.
 */
class EvidenceTest {

    private static final String TREE = """
            doc 4 rev 9 url https://example.test/
            [1] document "Example" {1 children}
              [2] heading "Hello" {0 children}
            """;

    /** A one-pixel PNG, so the bytes that are hashed are real image bytes. */
    private static final String PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlE"
            + "QVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Test
    @DisplayName("A capture composes the reads into one frozen record and writes it to a folder")
    void capturesAndWrites(@TempDir Path directory) throws IOException {

        FakeSketermServer server = new FakeSketermServer();
        scriptPage(server, true);

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(NetworkPolicy.builder()
                        .allowHosts("example.test").maxRequests(20).build()));

        // 1. The capture is one call and gathers every part
        Evidence evidence = Evidence.capture(page);

        assertEquals("https://example.test/", evidence.finalUrl(), "step 1: where the view ended up");
        assertEquals("Example", evidence.title(), "step 1: under the title it ended up with");
        assertNotNull(evidence.capturedAt(), "step 1: stamped with when it was taken");
        assertEquals(4, evidence.document(), "step 1: in the document the tree was taken in");
        assertEquals(9, evidence.revision(), "step 1: at that tree's revision");

        // 2. Each part is the answer of the read it composed, not a re-render of it
        assertTrue(evidence.snapshot().contains("heading \"Hello\""), "step 2: the tree came along");
        assertEquals("# Hello", evidence.articleMarkdown(), "step 2: as did the reader markdown");
        assertEquals((byte) 0x89, evidence.screenshot().bytes()[0], "step 2: and real PNG bytes");

        // 3. The screenshot is fingerprinted with the JDK's own SHA-256
        assertEquals(Evidence.sha256(Base64.getDecoder().decode(PNG_BASE64)),
                evidence.screenshotSha256(), "step 3: the digest is of exactly those bytes");
        assertEquals(64, evidence.screenshotSha256().length(), "step 3: as 64 hex characters");

        // 4. The network summary keeps the typed entries, refusal reasons included
        assertEquals(3, evidence.network().totalRequests(), "step 4: the counters came along");
        assertEquals(1, evidence.network().blocked(), "step 4: including what was blocked");
        assertEquals(DenialReason.SUB_HOST, evidence.network().requests().get(1).reason(),
                "step 4: and each entry says why");

        // 5. The policy is attested only because one is actually installed
        assertTrue(evidence.policy().active(), "step 5: the view runs a policy");
        assertEquals(11, evidence.policy().requests(), "step 5: whose accounting came along");
        assertEquals(DenialReason.NONE, evidence.policy().exhaustedReason(),
                "step 5: with no budget latched");

        // 6. Writing it out lands one folder with the manifest beside the artefacts
        Path folder = evidence.writeTo(directory.resolve("capture"));

        assertTrue(Files.exists(folder.resolve("evidence.json")), "step 6: the manifest exists");
        assertTrue(Files.exists(folder.resolve("screenshot.png")), "step 6: as does the png");
        assertTrue(Files.exists(folder.resolve("snapshot.txt")), "step 6: and the tree");
        assertTrue(Files.exists(folder.resolve("article.md")), "step 6: and the markdown");

        assertEquals("# Hello", Files.readString(folder.resolve("article.md"), StandardCharsets.UTF_8),
                "step 6: the markdown file holds the article verbatim");
        assertEquals(evidence.screenshot().bytes().length,
                Files.readAllBytes(folder.resolve("screenshot.png")).length,
                "step 6: and the png holds every byte");

        // 7. The manifest parses back, referring to the siblings rather than inlining them
        Map<String, Object> manifest = Json.parseObject(
                Files.readString(folder.resolve("evidence.json"), StandardCharsets.UTF_8));

        assertEquals("https://example.test/", manifest.get("final_url"), "step 7: the url is in it");
        assertEquals("snapshot.txt", manifest.get("snapshot"), "step 7: the tree is referred to");
        assertEquals("article.md", manifest.get("article"), "step 7: so is the markdown");

        Map<String, Object> shot = Json.map(manifest, "screenshot");

        assertEquals("screenshot.png", shot.get("file"), "step 7: the image rides as a file name");
        assertEquals(evidence.screenshotSha256(), shot.get("sha256"), "step 7: plus its digest");

        Map<String, Object> policy = Json.map(manifest, "policy");

        assertEquals(11L, ((Number) policy.get("requests")).longValue(),
                "step 7: the accounting is in the manifest");
        assertEquals(false, policy.get("exhausted"), "step 7: saying nothing latched");
        assertEquals(List.of("example.test"),
                Json.map(policy, "policy").get("allow_hosts"),
                "step 7: with the policy that was enforced");

        assertEquals(3, Json.list(Json.map(manifest, "network"), "requests").size(),
                "step 7: and every logged request");
    }

    @Test
    @DisplayName("A capture takes only the parts it is asked for, and skips a policy nobody installed")
    void capturesSelectedParts(@TempDir Path directory) throws IOException {

        FakeSketermServer server = new FakeSketermServer();
        scriptPage(server, false);

        Page page = server.browser().openPage("https://example.test/");

        Evidence evidence = Evidence.capture(page,
                EnumSet.of(Evidence.Part.ARTICLE, Evidence.Part.POLICY));

        assertNull(evidence.screenshot(), "no screenshot was asked for");
        assertNull(evidence.screenshotSha256(), "so nothing was hashed");
        assertNull(evidence.snapshot(), "no tree was asked for");
        assertNull(evidence.network(), "and no network log");
        assertEquals("# Hello", evidence.articleMarkdown(), "the one part asked for is there");

        // An unpoliced view attests to nothing, so the policy is left out rather than being a row
        // of zeroes that reads like enforcement
        assertNull(evidence.policy(), "an unpoliced view records no policy");
        assertTrue(server.callsTo("web_policy").size() == 1, "even though it did ask");

        Path folder = evidence.writeTo(directory.resolve("partial"));

        assertTrue(Files.exists(folder.resolve("article.md")), "the markdown was written");
        assertFalse(Files.exists(folder.resolve("screenshot.png")), "no png was invented");
        assertFalse(Files.exists(folder.resolve("snapshot.txt")), "nor a tree");

        Map<String, Object> manifest = Json.parseObject(
                Files.readString(folder.resolve("evidence.json"), StandardCharsets.UTF_8));

        assertFalse(manifest.containsKey("policy"), "and the manifest claims no enforcement");
        assertFalse(manifest.containsKey("network"), "nor any traffic it did not read");
    }

    @Test
    @DisplayName("An exhausted view is exactly the one worth capturing, and every part still answers")
    void capturesAnExhaustedView(@TempDir Path directory) throws IOException {

        FakeSketermServer server = new FakeSketermServer();
        scriptPage(server, true);

        server.on("web_policy", facts(map("policy_exhausted", true,
                "policy_exhausted_reason", "byte_cap", "policy_active", true,
                "policy_source", "call", "policy_serial", 1, "policy", echo(),
                "requests", 20, "bytes", 2_000_100, "navigations", 1, "ms_left", 0,
                "exhausted", true, "exhausted_reason", "byte_cap",
                "denied", map("byte_cap", 3), "durable", false)));

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(NetworkPolicy.builder()
                        .allowHosts("example.test").maxBytes(2_000_000L).build()));

        Evidence evidence = Evidence.capture(page);

        assertNotNull(evidence.screenshot(), "the reads all answered past the latch");
        assertNotNull(evidence.articleMarkdown(), "including the article");
        assertTrue(evidence.policy().exhausted(), "and the attested policy says the budget went");
        assertEquals(DenialReason.BYTE_CAP, evidence.policy().exhaustedReason(), "naming which");
        assertTrue(page.isPolicyExhausted(), "which the page itself now knows too");

        evidence.writeTo(directory.resolve("exhausted"));

        Map<String, Object> manifest = Json.parseObject(Files.readString(
                directory.resolve("exhausted").resolve("evidence.json"), StandardCharsets.UTF_8));

        assertEquals("byte_cap", Json.map(manifest, "policy").get("exhausted_reason"),
                "the manifest records the latched budget by its wire name");
    }

    /**
     * @param policied whether the view answers as running an enforced policy
     */
    private static void scriptPage(FakeSketermServer server, boolean policied) {

        Map<String, Object> open = map("view", 3, "document", 4, "revision", 9, "snapshot", TREE,
                "profile", "", "profile_kind", "default", "context", 0);

        if (policied) {
            open.put("policy_active", true);
            open.put("policy_source", "call");
            open.put("policy_serial", 1);
            open.put("policy", echo());
        }

        server.on("web_open", facts(open));

        server.on("web_tabs", map("backend", "headless", "count", 1, "helper", "ready",
                "views", List.of(map("view", 3, "url", "https://example.test/", "title", "Example",
                        "loading", false, "current", true))));

        server.on("web_screenshot", arguments -> {
            Map<String, Object> structured = facts(map("bytes", 70, "width", 1, "height", 1));
            structured.put("__image", FakeSketermServer.imageBlock(PNG_BASE64));
            return structured;
        });

        server.on("web_snapshot", facts(map("kind", "full", "document", 4, "revision", 9,
                "snapshot", TREE)));

        server.on("web_read", facts(map("reader_ids", true, "document", 4, "revision", 9,
                "markdown", "# Hello")));

        server.on("web_network", facts(map("blocking_enabled", true, "blocked", 1,
                "total_requests", 3, "rules_loaded", 12, "requests", List.of(
                        map("seq", 1, "blocked", false, "type", "document", "method", "GET",
                                "url", "https://example.test/", "status", 200, "reason", "none"),
                        map("seq", 2, "blocked", true, "type", "image", "method", "GET",
                                "url", "https://other.test/x.png", "reason", "sub_host"),
                        map("seq", 3, "blocked", false, "type", "script", "method", "GET",
                                "url", "https://example.test/a.js", "status", 200)))));

        if (policied) {
            server.on("web_policy", facts(map("policy_active", true, "policy_source", "call",
                    "policy_serial", 1, "policy", echo(), "requests", 11, "bytes", 40_000,
                    "navigations", 1, "ms_left", 0, "exhausted", false, "exhausted_reason", "none",
                    "denied", map("sub_host", 1), "durable", false)));
        } else {
            server.on("web_policy", facts(map("policy_active", false, "policy_source", "none",
                    "requests", 0, "bytes", 0, "navigations", 0, "ms_left", 0,
                    "exhausted", false, "exhausted_reason", "none", "denied", map(),
                    "durable", false)));
        }
    }

    private static Map<String, Object> echo() {

        return map("allow_hosts", List.of("example.test"),
                "allow_subresource_hosts", List.of(),
                "block_types", List.of(),
                "allow_schemes", List.of("http", "https"),
                "allow_private_addresses", false,
                "max_requests", 20,
                "max_bytes", 0,
                "max_navigations", 0,
                "deadline_ms", 0);
    }

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
