package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoutesTest {
    private static final String TREE = "doc 1 rev 1 url about:blank\n[1] document \"Blank\" {0 children}\n";

    @Test
    void proxyRoutesMatchSketermsGrammar() {
        for (String route : List.of("proxy:socks5h://127.0.0.1:1080", "proxy:http://127.0.0.1:8080",
                "proxy:http://proxy.internal.example:3128", "proxy:socks5h://[::1]:9050",
                "proxy:http://[2001:db8::7]:65535", "proxy:http://localhost:1", "proxy:http://Proxy_1.Example:80",
                "proxy:socks5h://" + "h".repeat(63) + "." + "h".repeat(63) + ":1")) {
            assertEquals(route, Routes.require(route), route);
            assertTrue(Routes.isProxy(route), route);
            assertEquals(route, OpenOptions.defaults().withRoute(route).route(), route);
        }
        for (String route : List.of("direct", "tor", "via:me@box", "on:box")) {
            assertEquals(route, Routes.require(route), route);
            assertFalse(Routes.isProxy(route), route);
        }
    }

    @Test
    void proxyRoutesOutsideTheGrammarAreRefusedWithTheirReason() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("proxy:socks5://127.0.0.1:1080", "socks5h");
        refused.put("proxy:https://127.0.0.1:8443", "proxy:socks5h://HOST:PORT");
        refused.put("proxy:SOCKS5H://127.0.0.1:1080", "proxy:socks5h://HOST:PORT");
        refused.put("proxy:127.0.0.1:1080", "proxy:socks5h://HOST:PORT");
        refused.put("proxy:http://user:pw@127.0.0.1:8080", "credentials");
        refused.put("proxy:http://user@127.0.0.1:8080", "credentials");
        refused.put("proxy:http://127.0.0.1:8080/", "no path");
        refused.put("proxy:http://127.0.0.1:8080/pac", "no path");
        refused.put("proxy:http://127.0.0.1:8080?x", "no path");
        refused.put("proxy:http://127.0.0.1", "explicit port");
        refused.put("proxy:http://127.0.0.1:", "explicit port");
        refused.put("proxy:http://127.0.0.1:0", "explicit port");
        refused.put("proxy:http://127.0.0.1:65536", "explicit port");
        refused.put("proxy:http://127.0.0.1:080", "explicit port");
        refused.put("proxy:http://127.0.0.1:+80", "explicit port");
        refused.put("proxy:http://[::1]", "explicit port");
        refused.put("proxy:http://::1:8080", "proxy host");
        refused.put("proxy:http://[::1:8080", "proxy host");
        refused.put("proxy:http://[127.0.0.1]:8080", "proxy host");
        refused.put("proxy:http://[fe80::1%eth0]:8080", "proxy host");
        refused.put("proxy:http://:8080", "proxy host");
        refused.put("proxy:http://127.1:8080", "proxy host");
        refused.put("proxy:http://0x7f.0.0.1:8080", "proxy host");
        refused.put("proxy:http://127.0.0.01:8080", "proxy host");
        refused.put("proxy:http://bad host:8080", "proxy host");
        refused.put("proxy:http://-lead.example:8080", "proxy host");
        refused.put("proxy:http://" + "a".repeat(Routes.MAX_PROXY_URL) + ":1", "too long");
        refused.put("proxy:", "proxy:socks5h://HOST:PORT");
        refused.put("PROXY:http://127.0.0.1:1", "Unknown browser route");
        refused.put("bogus", "Unknown browser route");
        refused.forEach((route, reason) -> {
            InvalidArgsException failure = assertThrows(InvalidArgsException.class, () -> Routes.require(route), route);
            assertTrue(failure.getMessage().contains(reason), route + ": " + failure.getMessage());
            assertThrows(InvalidArgsException.class, () -> OpenOptions.defaults().withRoute(route), route);
        });
        assertThrows(InvalidArgsException.class, () -> Routes.require(null));
    }

    @Test
    void untrustedViewsTakeDirectTorAndProxyRoutesOnly() {
        NetworkPolicy policy = NetworkPolicy.builder().untrusted().build();
        for (String route : List.of("direct", "tor", "proxy:http://127.0.0.1:8080",
                "proxy:socks5h://folio.localhost:1080")) {
            assertTrue(Routes.servesUntrusted(route), route);
            OpenOptions options = OpenOptions.ephemeralIdentity().withRoute(route).withPolicy(policy);
            assertEquals(route, options.route());
            assertEquals(route, OpenOptions.ephemeralIdentity().withPolicy(policy).withRoute(route).route(),
                    "validation works in either fluent order");
        }
        for (String route : List.of("via:box", "on:box")) {
            assertFalse(Routes.servesUntrusted(route), route);
            assertThrows(InvalidArgsException.class,
                    () -> OpenOptions.ephemeralIdentity().withRoute(route).withPolicy(policy), route);
        }
    }

    @Test
    void anUntrustedProxyOpenSendsItsRouteAndRequiresTheServerToConfirmIt() {
        String route = "proxy:socks5h://127.0.0.1:1080";
        FakeSketermServer server = capableServer();
        server.on("web_open", opening(route)).on("web_policy", attestation(route));
        Page page = server.browser().openPage("http://folio.localhost:4420/",
                OpenOptions.ephemeralIdentity().withRoute(route).withPolicy(NetworkPolicy.builder().untrusted()
                        .allowHosts("folio.localhost:4420").build()));
        assertTrue(page.isPolicyActive());
        assertEquals(route, server.lastArguments("web_open").get("route"), "never rewritten to direct");
        assertEquals(true, Json.map(server.lastArguments("web_open"), "policy").get("untrusted"));

        // The attestation must be the routed loader's: a direct-shaped one for a proxy view is not.
        FakeSketermServer mismatched = capableServer();
        Map<String, Object> direct = attestation(route);
        Json.map(direct, "enforced").put("http_broker", "actual-address-validated");
        mismatched.on("web_open", opening(route)).on("web_policy", direct).on("web_close", Map.of("closed", true));
        assertThrows(ProtocolMismatchException.class, () -> mismatched.browser().openPage("about:blank",
                OpenOptions.ephemeralIdentity().withRoute(route).withPolicy(NetworkPolicy.builder().untrusted()
                .build())));
        assertEquals(1, mismatched.callsTo("web_close").size());

        // Without a route the untrusted open still names direct explicitly.
        FakeSketermServer plain = capableServer();
        plain.on("web_open", opening("direct")).on("web_policy", attestation("direct"));
        plain.browser().openPage("about:blank",
                OpenOptions.ephemeralIdentity().withPolicy(NetworkPolicy.builder().untrusted().build()));
        assertEquals("direct", plain.lastArguments("web_open").get("route"));
    }

    @Test
    void aViewOnAnotherRouteThanAskedIsClosedNeverUsed() {
        String route = "proxy:http://127.0.0.1:8080";
        for (Object echoed : java.util.Arrays.asList("direct", "proxy:http://127.0.0.1:9", null)) {
            FakeSketermServer server = new FakeSketermServer();
            Map<String, Object> open = new LinkedHashMap<>(Map.of("view", 1, "snapshot", TREE));
            if (echoed != null) open.put("route", echoed);
            server.on("web_open", open).on("web_close", Map.of("closed", true));
            assertThrows(ProtocolMismatchException.class,
                    () -> server.browser().openPage("about:blank", OpenOptions.defaults().withRoute(route)),
                    String.valueOf(echoed));
            assertEquals(1, server.callsTo("web_close").size(), "the wrongly routed view is closed: " + echoed);
        }
        // An old server that does not echo a non-proxy route is still usable.
        FakeSketermServer old = new FakeSketermServer();
        old.on("web_open", Map.of("view", 1, "snapshot", TREE));
        old.browser().openPage("about:blank", OpenOptions.defaults().withRoute("tor"));
        assertTrue(old.callsTo("web_close").isEmpty());
        // A refusal opens nothing: never a retry on another route.
        FakeSketermServer refusing = new FakeSketermServer();
        refusing.onError("web_open", "invalid_args", "'proxy:http://127.0.0.1:8080' is not a route", false);
        assertThrows(InvalidArgsException.class,
                () -> refusing.browser().openPage("about:blank", OpenOptions.defaults().withRoute(route)));
        assertEquals(1, refusing.callsTo("web_open").size());
    }

    private static FakeSketermServer capableServer() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("web_untrusted", true);
        facts.put("web_policy_ack", null);
        return new FakeSketermServer().on("capabilities", facts);
    }

    private static Map<String, Object> opening(String route) {
        return new LinkedHashMap<>(Map.of("view", 1, "snapshot", TREE, "untrusted", true, "route", route,
                "policy_active", true, "policy_source", "call", "policy", Map.of("untrusted", true)));
    }

    private static Map<String, Object> attestation(String route) {
        Map<String, Object> facts = opening(route);
        facts.remove("untrusted");
        facts.put("enforced", new LinkedHashMap<>(Map.of("internet_sockets", "denied",
                "http_broker", route.equals("direct") ? "actual-address-validated" : "route-proxy-only",
                "websockets", false, "webrtc", false)));
        return facts;
    }
}
