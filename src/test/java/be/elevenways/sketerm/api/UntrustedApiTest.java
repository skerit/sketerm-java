package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UntrustedApiTest {
    private static final String TREE = "doc 1 rev 1 url about:blank\n[1] document \"Blank\" {0 children}\n";

    @Test
    void policyRoundTripAndCompatibility() {
        NetworkPolicy policy = NetworkPolicy.builder().untrusted()
                .allowHosts("ABC.Folio.Localhost:4420", "[2001:db8::1]:443", "xn--bcher-kva.example")
                .allowSubresourceHosts("cdn.example:8443").allowSchemes(UrlScheme.HTTP, UrlScheme.HTTPS)
                .allowPrivateAddresses(true).build();
        Map<String, Object> wire = Json.parseObject(Json.write(policy.toWire()));
        assertEquals(Map.of("untrusted", true,
                "allow_hosts", List.of("abc.folio.localhost:4420", "[2001:db8::1]:443", "xn--bcher-kva.example"),
                "allow_subresource_hosts", List.of("cdn.example:8443"),
                "allow_schemes", List.of("http", "https"), "allow_private_addresses", true), wire);
        assertEquals(policy, NetworkPolicy.decode(Map.of("policy", wire)));
        assertFalse(NetworkPolicy.none().toWire().containsKey("untrusted"), "ordinary wire shape is unchanged");
        assertEquals(Map.of("untrusted", false), NetworkPolicy.builder().untrusted(false).build().toWire());
        assertEquals(NetworkPolicy.none(), new NetworkPolicy(null, null, null, null, null, null,
                null, null, null, null), "old constructor remains usable");
        assertEquals(OpenOptions.defaults(), new OpenOptions(null, null, null, null, false, null));
        assertEquals(OpenOptions.withCapture(CaptureFilter.json()),
                new OpenOptions(null, null, null, null, false, null, CaptureFilter.json()));
        assertThrows(ProtocolMismatchException.class,
                () -> NetworkPolicy.decode(Map.of("policy", Map.of("untrusted", "true"))));
    }

    @Test
    void invalidOptionsNeverCallTheServer() {
        FakeSketermServer server = new FakeSketermServer();
        Browser browser = server.browser();
        NetworkPolicy policy = NetworkPolicy.builder().untrusted().build();
        assertThrows(InvalidArgsException.class, () -> browser.openPage("about:blank",
                OpenOptions.defaults().withPolicy(policy)), "shared identity is forbidden");
        assertThrows(InvalidArgsException.class, () -> browser.openPage("about:blank",
                OpenOptions.inProfile("work").withPolicy(policy)), "named profile is forbidden");
        for (String route : List.of("tor", "via:box", "on:box")) {
            assertThrows(InvalidArgsException.class, () -> browser.openPage("about:blank",
                    OpenOptions.ephemeralIdentity().withRoute(route).withPolicy(policy)), route);
            assertThrows(InvalidArgsException.class, () -> OpenOptions.ephemeralIdentity()
                    .withPolicy(policy).withRoute(route), "validation works in either fluent order");
        }
        for (String route : List.of("unknown", "DIRECT", "via:", "via:bad host")) {
            assertThrows(InvalidArgsException.class, () -> OpenOptions.defaults().withRoute(route), route);
        }
        for (UrlScheme scheme : List.of(UrlScheme.WS, UrlScheme.WSS, UrlScheme.FILE, UrlScheme.DATA, UrlScheme.BLOB)) {
            assertThrows(InvalidArgsException.class,
                    () -> NetworkPolicy.builder().untrusted().allowSchemes(scheme).build(), scheme.wire());
        }
        assertThrows(InvalidArgsException.class, () -> OpenOptions.ephemeralIdentity()
                .withPolicy(policy).withDefaultIdentity());
        assertThrows(InvalidArgsException.class, () -> browser.setProfilePolicy("work", policy));
        Page page = new Page(server.toolCalls(), 1, Map.of(), null);
        for (boolean mode : List.of(true, false)) {
            assertThrows(InvalidArgsException.class,
                    () -> page.tightenPolicy(NetworkPolicy.builder().untrusted(mode).build()));
        }
        assertTrue(server.calls().isEmpty(), "every validation failure precedes any tool call");
    }

    @Test
    void hostRulesMatchSketerm() {
        for (String host : List.of("example.com:1", "example.com:65535", "abc.folio.localhost:4420",
                "127.0.0.1:4420", "[::1]:4420", "[2001:db8::1]", "2001:db8::1", "::ffff:127.0.0.1",
                "xn--bcher-kva.example")) {
            assertEquals(host, PolicyHosts.require(host), host);
        }
        for (String host : List.of("*.folio.localhost:4420", "bücher.example", "K.example", "0.1", "123", "2.3.4",
                "999.0.0.1", "example.com:0", "example.com:65536", "example.com:-1", "example.com:",
                "example.com:abc", "example.com:999999999999999999999", "[::1]:0", "[::1]:65536",
                "[::1", "[::1]extra", "[127.0.0.1]:80", "[bad::ip]:80", "fe80::1%lo",
                "a..example", "-a.example", "a-.example", "a".repeat(64) + ".example",
                "user@example.com", "https://example.com", "example.com/path", "example.com?x=1")) {
            assertThrows(InvalidArgsException.class, () -> PolicyHosts.require(host), host);
            assertThrows(InvalidArgsException.class,
                    () -> NetworkPolicy.builder().allowSubresourceHosts(host).build(), host);
        }
        assertFalse(PolicyHosts.isValid(null));
    }

    @Test
    void capabilityParsingFailsClosed() {
        FakeSketermServer server = new FakeSketermServer();
        Browser browser = server.browser();
        for (Map<String, Object> facts : List.<Map<String, Object>>of(Map.of(),
                Map.of("web_untrusted", false, "web_policy_ack", false),
                nullableCapabilities())) {
            server.on("capabilities", facts);
            assertFalse(browser.supportsUntrusted());
            assertFalse(browser.supportsPolicyAcknowledgement());
            assertThrows(UnavailableException.class, () -> browser.openPage("about:blank", untrustedOptions()));
        }
        assertTrue(server.callsTo("web_open").isEmpty(), "incapable server never receives an open");
        server.on("capabilities", Map.of("web_untrusted", true, "web_policy_ack", true));
        assertTrue(browser.supportsUntrusted());
        assertTrue(browser.supportsPolicyAcknowledgement());
        for (String fact : List.of("web_untrusted", "web_policy_ack")) {
            server.on("capabilities", Map.of(fact, "true"));
            assertThrows(ProtocolMismatchException.class, () -> {
                if (fact.equals("web_untrusted")) browser.supportsUntrusted();
                else browser.supportsPolicyAcknowledgement();
            });
        }
    }

    @Test
    void acknowledgedOpenAndTypedRefusals() {
        FakeSketermServer server = capableServer();
        server.on("web_open", opening());
        Map<String, Object> status = attestation();
        status.put("denied", Map.of("resolved_private_address", 2, "untrusted_http", 1,
                "untrusted_transport", 3, "untrusted_broker", 4, "untrusted_timeout", 5,
                "untrusted_queue_full", 6, "url_too_long", 7, "malformed_url", 8, "policy_refused", 0));
        server.on("web_policy", status);
        Page page = server.browser().openPage("about:blank", untrustedOptions());
        assertTrue(page.isPolicyActive());
        PolicyStatus decoded = page.policy();
        assertTrue(decoded.untrusted());
        assertTrue(decoded.active());
        assertEquals(2, decoded.denied(DenialReason.RESOLVED_PRIVATE_ADDRESS));
        assertEquals(6, decoded.denied(DenialReason.UNTRUSTED_QUEUE_FULL));
        assertEquals(8, decoded.denied(DenialReason.MALFORMED_URL));
        assertEquals(true, server.lastArguments("web_open").get("ephemeral"));
        assertEquals("direct", server.lastArguments("web_open").get("route"));
        assertEquals(true, Json.map(server.lastArguments("web_open"), "policy").get("untrusted"));
        assertEquals(1L, ((Number) server.lastArguments("web_policy").get("pane")).longValue());
        status.remove("enforced");
        assertFalse(page.policy().untrusted(), "missing fresh attestation cannot reuse an old one");
        assertFalse(page.isPolicyActive(), "missing attestation cannot report active untrusted loading");
        status.put("denied", Map.of("future_reason", 0));
        assertThrows(SketermApiException.class, () -> PolicyStatus.decode(status));
        assertEquals(DenialReason.POLICY_REFUSED, DenialReason.requireExhaustion("policy_refused"));
        assertThrows(SketermApiException.class, () -> DenialReason.requireExhaustion("untrusted_timeout"));
    }

    @Test
    void missingAcknowledgementClosesTheViewAndNeverFallsBack() {
        for (String missing : List.of("untrusted", "policy_active", "policy", "enforced", "websockets", "webrtc")) {
            FakeSketermServer server = capableServer();
            Map<String, Object> open = opening();
            Map<String, Object> status = attestation();
            if (List.of("untrusted", "policy_active", "policy").contains(missing)) open.remove(missing);
            else if (missing.equals("enforced")) status.remove(missing);
            else Json.map(status, "enforced").put(missing, true);
            server.on("web_open", open).on("web_policy", status).on("web_close", Map.of("closed", true));
            assertThrows(ProtocolMismatchException.class,
                    () -> server.browser().openPage("about:blank", untrustedOptions()), missing);
            assertEquals(1, server.callsTo("web_open").size(), "no fallback for " + missing);
            assertEquals(1, server.callsTo("web_close").size(), "failed handle is closed for " + missing);
        }
        FakeSketermServer refused = capableServer();
        refused.onError("web_open", "refused", "untrusted install refused", false);
        assertThrows(RefusedException.class,
                () -> refused.browser().openPage("about:blank", untrustedOptions()));
        assertEquals(1, refused.callsTo("web_open").size());

        FakeSketermServer server = new FakeSketermServer();
        Page unacknowledged = new Page(server.toolCalls(), 1, opening(), null);
        assertFalse(unacknowledged.isPolicyActive(), "a mode echo alone does not attest enforcement");
        assertFalse(PolicyStatus.decode(opening()).active());
        assertFalse(PolicyStatus.decode(opening()).untrusted());
    }

    @Test
    void coloursArePreservedSentAndAcknowledged() {
        FakeSketermServer server = new FakeSketermServer();
        for (ColorScheme colour : ColorScheme.values()) {
            OpenOptions options = OpenOptions.withCapture(CaptureFilter.json())
                    .withColorScheme(colour).withViewport(900, 600)
                    .withTimeout(Duration.ofSeconds(5)).withProfile("work").withDefaultIdentity()
                    .withEphemeral().withRoute("direct").withPolicy(NetworkPolicy.none());
            server.on("web_open", Map.of("view", 1, "snapshot", TREE, "color_scheme", colour.wire()));
            server.browser().openPage("about:blank", options);
            assertEquals(colour.wire(), server.lastArguments("web_open").get("color_scheme"));
            assertEquals(CaptureFilter.json().toWire(), server.lastArguments("web_open").get("capture"));
            assertEquals(colour, options.capturing(CaptureFilter.everything()).colorScheme());
            assertEquals(colour, options.colorScheme());
            assertNull(options.withColorScheme(null).colorScheme());
        }
        server.on("web_open", Map.of("view", 1, "snapshot", TREE)).on("web_close", Map.of("closed", true));
        assertThrows(ProtocolMismatchException.class, () -> server.browser().openPage("about:blank",
                OpenOptions.defaults().withColorScheme(ColorScheme.DARK)));
        assertThrows(SketermApiException.class, () -> ColorScheme.require("automatic"));
        server.onError("web_open", "unavailable", "web-emulation is unsupported", false);
        assertThrows(UnavailableException.class, () -> server.browser().openPage("about:blank",
                OpenOptions.defaults().withColorScheme(ColorScheme.LIGHT)));
    }

    private static Map<String, Object> nullableCapabilities() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("web_untrusted", null);
        facts.put("web_policy_ack", null);
        return facts;
    }

    private static FakeSketermServer capableServer() {
        // Unknown pre-handshake acknowledgement is normal; open must still proceed and verify its view.
        Map<String, Object> facts = nullableCapabilities();
        facts.put("web_untrusted", true);
        return new FakeSketermServer().on("capabilities", facts);
    }

    private static OpenOptions untrustedOptions() {
        return OpenOptions.ephemeralIdentity().withPolicy(NetworkPolicy.builder().untrusted().build());
    }

    private static Map<String, Object> opening() {
        return new LinkedHashMap<>(Map.of("view", 1, "snapshot", TREE, "untrusted", true,
                "policy_active", true, "policy_source", "call", "policy", Map.of("untrusted", true)));
    }

    private static Map<String, Object> attestation() {
        Map<String, Object> facts = opening();
        // web_policy carries mode in its effective policy echo, not a top-level untrusted field.
        facts.remove("untrusted");
        facts.put("enforced", new LinkedHashMap<>(Map.of("internet_sockets", "denied",
                "http_broker", "actual-address-validated", "websockets", false, "webrtc", false)));
        return facts;
    }
}
