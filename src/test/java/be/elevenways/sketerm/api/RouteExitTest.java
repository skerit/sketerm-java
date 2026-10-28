package be.elevenways.sketerm.api;

import be.elevenways.sketerm.testing.ScriptedSketerm;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteExitTest {

    @Test
    void aRoutesExitIsItsProxyAndDirectHasNone() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser().exitingThrough("socks5h://127.0.0.1:41000");
             Sketerm sketerm = server.connect()) {

            // 1. The capability is read, and direct needs no proxy.
            assertTrue(sketerm.supportsRouteExit(), "step 1: the capability");
            RouteExit direct = sketerm.routeExit("direct");
            assertEquals("direct", direct.kind(), "step 1: direct");
            assertNull(direct.proxy(), "step 1: direct has no proxy");

            // 2. A via: route exits through its bridge, the second ask shares it.
            RouteExit via = sketerm.routeExit("via:me@fra-1");
            assertEquals("via", via.kind(), "step 2: the kind");
            assertEquals("socks5h://127.0.0.1:41000", via.proxy(), "step 2: the bridge");
            assertFalse(via.reused(), "step 2: a new bridge");
            assertTrue(sketerm.routeExit("via:me@fra-1").reused(), "step 2: the bridge is shared");
            assertEquals(Map.of("route", "via:me@fra-1"), server.lastArguments("web_route_exit"), "step 2: the call");

            // 3. Refusals: outside the grammar never reaches the server, on: has no exit.
            int calls = server.callsTo("web_route_exit").size();
            assertThrows(InvalidArgsException.class, () -> sketerm.routeExit("via:"), "step 3: the grammar");
            assertEquals(calls, server.callsTo("web_route_exit").size(), "step 3: refused locally");
            assertThrows(UnavailableException.class, () -> sketerm.routeExit("on:box"), "step 3: on: has no exit");
        }
    }

    @Test
    void aServerWithoutRouteExitsIsRefusedBeforeTheCall() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser()
                .on("capabilities", ScriptedSketerm.map("web", true, "web_backend", "headless"));
             Sketerm sketerm = server.connect()) {
            assertFalse(sketerm.supportsRouteExit());
            assertThrows(UnavailableException.class, () -> sketerm.routeExit("via:box"));
            assertTrue(server.callsTo("web_route_exit").isEmpty(), "nothing was asked");
        }
    }

    @Test
    void anAnswerThatNamesNoExitIsAProtocolMismatch() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser()
                .on("web_route_exit", ScriptedSketerm.map("route", "via:box", "kind", "via", "proxy", null,
                        "reused", false));
             Sketerm sketerm = server.connect()) {
            assertThrows(ProtocolMismatchException.class, () -> sketerm.routeExit("via:box"));
        }
    }
}
