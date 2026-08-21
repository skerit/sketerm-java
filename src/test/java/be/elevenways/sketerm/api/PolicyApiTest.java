package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enforced-network-policy half of the api layer: what goes out, what comes back, and what a
 * latched budget does to every later call.
 */
class PolicyApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Example" {0 children}
            """;

    /**
     * AIDEV-NOTE: these four lists are a COPY of the enums the tool schemas declare in the server's
     * src/ipc/mcp_tools.zig - that file is the source of truth, this is the drift check. They are
     * spelled out as literals on purpose: deriving them from the Java enums would only prove the
     * enums equal themselves. When the server grows a member, this test fails and names it.
     */
    private static final List<String> SCHEMA_BLOCK_TYPES = List.of("other", "document",
            "subdocument", "stylesheet", "script", "image", "font", "xhr", "media", "websocket",
            "ping");

    private static final List<String> SCHEMA_ALLOW_SCHEMES = List.of("http", "https", "ws", "wss",
            "file", "data", "blob");

    private static final List<String> SCHEMA_REASONS = List.of("none", "filter_list", "top_host",
            "sub_host", "resource_type", "private_address", "scheme", "redirect_host",
            "request_cap", "byte_cap", "nav_cap", "deadline");

    private static final List<String> SCHEMA_EXHAUSTED_REASONS = List.of("none", "request_cap",
            "byte_cap", "nav_cap", "deadline");

    private static final List<String> SCHEMA_POLICY_SOURCES = List.of("call", "profile_default",
            "none");

    @Test
    @DisplayName("Every policy vocabulary matches the tool schema's enum, member for member")
    void vocabulariesMatchTheSchema() {

        assertEquals(SCHEMA_BLOCK_TYPES, wires(ResourceType.values()),
                "policy.block_types is an 11-name enum and ResourceType mirrors it in order");
        assertEquals(SCHEMA_ALLOW_SCHEMES, wires(UrlScheme.values()),
                "policy.allow_schemes is a 7-name enum and UrlScheme mirrors it in order");
        assertEquals(SCHEMA_REASONS, wires(DenialReason.values()),
                "web_network's per-request reason is a 12-name enum and DenialReason mirrors it");
        assertEquals(SCHEMA_POLICY_SOURCES, wires(PolicySource.values()),
                "policy_source is a 3-name enum and PolicySource mirrors it");

        // The exhausted_reason enum is the SUBSET that can latch, derived from the members
        // themselves rather than listed a second time.
        List<String> latching = DenialReason.EXHAUSTION_REASONS.stream()
                .map(DenialReason::wire)
                .sorted()
                .toList();

        assertEquals(SCHEMA_EXHAUSTED_REASONS.stream().sorted().toList(), latching,
                "exhausted_reason is exactly the latching subset of DenialReason");

        assertFalse(DenialReason.FILTER_LIST.isPolicy(),
                "filter_list is the adblock engine, not the enforced policy");
        assertTrue(DenialReason.TOP_HOST.isPolicy(), "every other reason is the policy's");
    }

    @Test
    @DisplayName("A policy serializes to the schema's object and its echo re-types back to it")
    void policyRoundTrip() {

        NetworkPolicy policy = NetworkPolicy.builder()
                .allowHosts("Example.Test", "cdn.example.test")
                .allowSubresourceHosts("static.example.test")
                .blockTypes(ResourceType.IMAGE, ResourceType.MEDIA, ResourceType.FONT)
                .blockAds(true)
                .allowSchemes(UrlScheme.HTTPS, UrlScheme.WSS)
                .allowPrivateAddresses(false)
                .maxRequests(40)
                .maxBytes(5_000_000L)
                .maxNavigations(3)
                .deadline(Duration.ofSeconds(20))
                .build();

        Map<String, Object> wire = policy.toWire();

        // 1. Host entries are folded exactly the way the server folds them before validating
        assertEquals(List.of("example.test", "cdn.example.test"), wire.get("allow_hosts"),
                "step 1: the hosts went out lower-cased");
        assertEquals(List.of("static.example.test"), wire.get("allow_subresource_hosts"),
                "step 1: and so did the subresource hosts");

        // 2. Vocabularies go out as their wire names, in the schema's own declaration order
        assertEquals(List.of("image", "font", "media"), wire.get("block_types"),
                "step 2: the blocked classes are named, in enum order");
        assertEquals(List.of("https", "wss"), wire.get("allow_schemes"),
                "step 2: as are the schemes");

        // 3. Budgets ride under the schema's key names, the deadline as milliseconds
        assertEquals(40, wire.get("max_requests"), "step 3: the request cap");
        assertEquals(5_000_000L, wire.get("max_bytes"), "step 3: the byte cap");
        assertEquals(3, wire.get("max_navigations"), "step 3: the navigation cap");
        assertEquals(20_000L, wire.get("deadline_ms"), "step 3: the deadline in ms");
        assertEquals(Boolean.TRUE, wire.get("block_ads"), "step 3: and the ad filter switch");
        assertEquals(Boolean.FALSE, wire.get("allow_private_addresses"),
                "step 3: private literals stay refused");

        // 4. An unset field is simply absent, so the server's own default survives
        Map<String, Object> bare = NetworkPolicy.allowingHosts("example.test").toWire();

        assertEquals(List.of("allow_hosts"), List.copyOf(bare.keySet()),
                "step 4: a policy that constrains one thing sends one key");

        // 5. The echo re-types into the same value
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("policy", wire);

        assertEquals(policy, NetworkPolicy.decode(answer),
                "step 5: the echoed object decodes back to the policy that was sent");

        // 6. A budget the caller never set echoes as 0 and decodes back to "unbounded"
        Map<String, Object> zeroed = new LinkedHashMap<>(bare);
        zeroed.put("max_requests", 0);
        zeroed.put("max_bytes", 0);
        zeroed.put("max_navigations", 0);
        zeroed.put("deadline_ms", 0);
        zeroed.put("allow_private_addresses", false);

        NetworkPolicy decoded = NetworkPolicy.decode(Map.of("policy", zeroed));

        assertNull(decoded.maxRequests(), "step 6: 0 means unbounded, not a cap of zero");
        assertNull(decoded.deadline(), "step 6: and so does a 0 deadline");
        assertEquals(Boolean.FALSE, decoded.allowPrivateAddresses(),
                "step 6: while the private-address answer is a real one");
    }

    @Test
    @DisplayName("A host entry the server would refuse is refused here, before anything is sent")
    void validatesHostEntries() {

        for (String bad : List.of("*", "site.example:8080", "https://site.example",
                "site.example/path", "", "x".repeat(254))) {

            assertThrows(InvalidArgsException.class,
                    () -> NetworkPolicy.builder().allowHosts(bad).build(),
                    "'" + bad + "' is not a usable host entry");
            assertFalse(PolicyHosts.isValid(bad), "'" + bad + "' fails the rule");
        }

        assertTrue(PolicyHosts.isValid("site.example"), "a bare name is fine");
        assertTrue(PolicyHosts.isValid("127.0.0.1"), "so is an IPv4 literal");
        assertTrue(PolicyHosts.isValid("[::1]"), "and an IPv6 one, colons and all");
        assertEquals("site.example", PolicyHosts.require("Site.Example"),
                "an upper-case entry is folded rather than refused, as the server folds it");

        assertThrows(InvalidArgsException.class, () -> NetworkPolicy.builder()
                        .allowHosts(Collections.nCopies(65, "h.example")).build(),
                "the 64-host cap is never silently truncated");
    }

    @Test
    @DisplayName("A policied open sends the policy object and the page adopts its provenance")
    void policiedOpen() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", arguments -> facts(map("view", 3, "document", 1, "revision", 1,
                "snapshot", TREE, "profile", "", "profile_kind", "default", "context", 0,
                "policy_active", true, "policy_source", "call", "policy_serial", 7,
                "policy", echo(List.of("example.test"), 40))));

        NetworkPolicy policy = NetworkPolicy.builder()
                .allowHosts("example.test")
                .maxRequests(40)
                .build();

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(policy));

        Map<String, Object> sent = server.lastArguments("web_open");

        assertEquals(policy.toWire(), sent.get("policy"), "the policy object went out under 'policy'");
        assertTrue(page.isPolicyActive(), "the view reports it is policied");
        assertEquals(PolicySource.CALL, page.policySource(), "the call itself supplied it");
        assertEquals(7, page.policySerial(), "under the engine's policy generation");
        assertFalse(page.isPolicyExhausted(), "and nothing has latched yet");
        assertNull(page.policyExhaustedReason(), "so no budget is named");
    }

    @Test
    @DisplayName("A policied open on an incapable helper opens NOTHING rather than opening unpoliced")
    void policiedOpenFailsClosed() {

        FakeSketermServer server = new FakeSketermServer();

        server.onError("web_open", "unavailable", "this browser helper does not advertise the"
                + " net-policy capability, so the requested policy cannot be ENFORCED. Nothing was"
                + " opened: there is deliberately no unpoliced fallback.", true);

        UnavailableException refused = assertThrows(UnavailableException.class,
                () -> server.browser().openPage("https://example.test/",
                        OpenOptions.withNetworkPolicy(NetworkPolicy.allowingHosts("example.test"))));

        assertTrue(refused.getMessage().contains("Nothing was opened"),
                "the fail-closed sentence survives");

        // Past the helper's policied-view cap it is a conflict, not an unavailability
        FakeSketermServer full = new FakeSketermServer();
        full.onError("web_open", "conflict", "too many concurrent web views for another POLICIED"
                + " one (the helper can enforce 4); close one with web_close first", false);

        assertThrows(ConflictException.class, () -> full.browser().openPage("https://example.test/",
                        OpenOptions.withNetworkPolicy(NetworkPolicy.allowingHosts("example.test"))),
                "the cap is a conflict");
    }

    @Test
    @DisplayName("web_policy decodes the accounting, the reasons and the latch")
    void decodesPolicyStatus() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE,
                "policy_active", true, "policy_source", "call", "policy_serial", 1,
                "policy", echo(List.of("example.test"), 40))));

        server.on("web_policy", facts(map("policy_active", true, "policy_source", "call",
                "policy_serial", 1, "policy", echo(List.of("example.test"), 40),
                "requests", 12, "bytes", 480_233, "navigations", 2, "ms_left", 0,
                "exhausted", false, "exhausted_reason", "none",
                "denied", map("sub_host", 4, "filter_list", 9), "durable", false)));

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(NetworkPolicy.builder()
                        .allowHosts("example.test").maxRequests(40).build()));

        PolicyStatus status = page.policy();

        assertTrue(status.active(), "a policy is installed");
        assertEquals(PolicySource.CALL, status.source(), "supplied by the open call");
        assertEquals(1, status.serial(), "at generation 1");
        assertEquals(12, status.requests(), "the allowed requests are counted");
        assertEquals(480_233, status.bytes(), "as are the received bytes");
        assertEquals(2, status.navigations(), "and the main-frame loads");
        assertFalse(status.exhausted(), "no budget has latched");
        assertEquals(DenialReason.NONE, status.exhaustedReason(), "so none is named");
        assertEquals(4, status.denied(DenialReason.SUB_HOST), "the refusals are keyed by reason");
        assertEquals(9, status.denied(DenialReason.FILTER_LIST), "the ad filter's included");
        assertEquals(0, status.denied(DenialReason.DEADLINE), "an absent reason is simply zero");
        assertFalse(status.durable(), "and a policy never survives the server, by design");

        assertNotNull(status.policy(), "the echo re-typed");
        assertEquals(List.of("example.test"), status.policy().allowHosts(),
                "into the hosts that were asked for");
        assertEquals(40, status.policy().maxRequests(), "and the cap that was asked for");
    }

    @Test
    @DisplayName("An exhausted budget latches: every read carries it, and traffic tools are refused")
    void exhaustionLatchesAndRefuses() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE,
                "policy_active", true, "policy_source", "call", "policy_serial", 1,
                "policy", echo(List.of("example.test"), 2))));

        // A read tool keeps answering, and says out loud that the budgets went
        server.on("web_read", facts(map("policy_exhausted", true,
                "policy_exhausted_reason", "request_cap", "reader_ids", true, "document", 1,
                "revision", 1, "markdown", "# Example")));

        server.onError("web_navigate", "refused", "network policy exhausted for view 3"
                + " (request_cap) after 2 requests / 900 bytes / 1 navigations; no new traffic will"
                + " be allowed. Read tools still answer; open a new view with a fresh policy, or"
                + " call web_policy for the full accounting", false);

        server.on("web_policy", facts(map("policy_exhausted", true,
                "policy_exhausted_reason", "request_cap", "policy_active", true,
                "policy_source", "call", "policy_serial", 1,
                "policy", echo(List.of("example.test"), 2),
                "requests", 2, "bytes", 900, "navigations", 1, "ms_left", 0,
                "exhausted", true, "exhausted_reason", "request_cap",
                "denied", map("request_cap", 6), "durable", false)));

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(NetworkPolicy.builder()
                        .allowHosts("example.test").maxRequests(2).build()));

        // 1. Nothing has latched at open
        assertFalse(page.isPolicyExhausted(), "step 1: the budgets held while the page loaded");

        // 2. A read still works, and its answer is what tells the page the budgets went
        Article article = page.read();

        assertEquals("# Example", article.markdown(), "step 2: the read answered normally");
        assertTrue(page.isPolicyExhausted(), "step 2: and the page absorbed the exhaustion fact");
        assertEquals(DenialReason.REQUEST_CAP, page.policyExhaustedReason(),
                "step 2: naming which budget went");
        assertTrue(page.policyExhaustedReason().latches(),
                "step 2: which is one of the budget reasons, not a per-request one");

        // 3. A traffic tool is refused, non-retryably
        RefusedException refused = assertThrows(RefusedException.class,
                () -> page.navigate("https://example.test/other"));

        assertEquals(ErrorCode.REFUSED, refused.getCode(), "step 3: as a refusal, not a failure");
        assertFalse(refused.isRetryable(), "step 3: retrying it unchanged cannot help");
        assertTrue(refused.getMessage().contains("web_policy"),
                "step 3: the sentence points at the accounting");

        // 4. Which web_policy then supplies
        PolicyStatus status = page.policy();

        assertTrue(status.exhausted(), "step 4: the accounting agrees the policy latched");
        assertEquals(DenialReason.REQUEST_CAP, status.exhaustedReason(), "step 4: on the same budget");
        assertEquals(6, status.denied(DenialReason.REQUEST_CAP),
                "step 4: and counts every request refused since");

        // 5. The latch is permanent: an answer that omits the fact never clears it
        server.on("web_read", facts(map("reader_ids", true, "document", 1, "revision", 1,
                "markdown", "# Example")));
        page.read();

        assertTrue(page.isPolicyExhausted(),
                "step 5: an omitted fact means 'not reported', never 'the budgets came back'");
    }

    @Test
    @DisplayName("A tighten names what it narrowed and what it refused as a loosening")
    void tightensLivePolicy() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE,
                "policy_active", true, "policy_source", "call", "policy_serial", 1,
                "policy", echo(List.of("example.test"), 40))));

        server.on("web_policy_set", facts(map("policy_serial", 2,
                "policy", echo(List.of("example.test"), 10),
                "tightened", List.of("max_requests", "block_types"),
                "ignored", List.of("allow_hosts"))));

        Page page = server.browser().openPage("https://example.test/",
                OpenOptions.withNetworkPolicy(NetworkPolicy.builder()
                        .allowHosts("example.test").maxRequests(40).build()));

        NetworkPolicy tighter = NetworkPolicy.builder()
                .allowHosts("example.test", "extra.test")
                .blockTypes(ResourceType.IMAGE)
                .maxRequests(10)
                .build();

        PolicyUpdate update = page.tightenPolicy(tighter);

        assertEquals(tighter.toWire(), server.lastArguments("web_policy_set").get("policy"),
                "the requested policy went out unchanged");
        assertEquals(3, ((Number) server.lastArguments("web_policy_set").get("pane")).intValue(),
                "addressed at this view, not the current one");
        assertEquals(List.of("max_requests", "block_types"), update.tightened(),
                "the fields that actually narrowed are named");
        assertEquals(List.of("allow_hosts"), update.ignored(),
                "and the one that would have loosened is refused by name");
        assertTrue(update.changedAnything(), "something did narrow");
        assertEquals(2, update.serial(), "the generation moved");
        assertEquals(10, update.policy().maxRequests(), "and the effective policy came back");
        assertEquals(2, page.policySerial(), "which the page adopted");

        // A request in which EVERY field would loosen is refused outright
        server.onError("web_policy_set", "refused", "every requested change would LOOSEN the live"
                + " policy (max_requests), and a live policy can only tighten; open a new view for"
                + " a wider one", false);

        assertThrows(RefusedException.class,
                () -> page.tightenPolicy(NetworkPolicy.builder().maxRequests(9999).build()),
                "a pure loosening is a refusal");

        // And a view that runs no policy cannot have one added
        server.onError("web_policy_set", "conflict", "this view runs no policy; one can only be"
                + " installed at web_open, never added to a live view", false);

        assertThrows(ConflictException.class,
                () -> page.tightenPolicy(NetworkPolicy.allowingHosts("example.test")),
                "adding a policy to a live view is a conflict");
    }

    @Test
    @DisplayName("A profile's session-default policy registers under its name and is never durable")
    void registersProfileDefault() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_policy_set", map("backend", "headless", "profile", "work",
                "policy_source", "profile_default", "policy", echo(List.of("example.test"), 25),
                "durable", false));

        NetworkPolicy policy = NetworkPolicy.builder()
                .allowHosts("example.test").maxRequests(25).build();

        ProfilePolicy registered = server.browser().setProfilePolicy("work", policy);

        Map<String, Object> sent = server.lastArguments("web_policy_set");

        assertEquals("work", sent.get("profile"), "the profile name went out");
        assertEquals(policy.toWire(), sent.get("policy"), "with the policy object");
        assertFalse(sent.containsKey("pane"), "and no view handle, so it is the profile variant");
        assertEquals("work", registered.profile(), "the registration names the profile");
        assertEquals(PolicySource.PROFILE_DEFAULT, registered.source(), "as a session default");
        assertEquals(25, registered.policy().maxRequests(), "carrying the policy it registered");
        assertFalse(registered.durable(), "which lives only as long as this server does");

        assertThrows(InvalidArgsException.class,
                () -> server.browser().setProfilePolicy("Work", policy),
                "a name the server would refuse never leaves this process");
    }

    @Test
    @DisplayName("Each logged request carries WHY it was blocked, and an old server carries nothing")
    void networkEntriesCarryReasons() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_network", facts(map("blocking_enabled", true, "blocked", 2,
                "total_requests", 4, "rules_loaded", 100, "next_seq", 5, "requests", List.of(
                        map("seq", 1, "blocked", false, "type", "document", "method", "GET",
                                "url", "https://example.test/", "status", 200, "reason", "none"),
                        map("seq", 2, "blocked", true, "type", "script", "method", "GET",
                                "url", "https://ads.test/a.js", "reason", "filter_list"),
                        map("seq", 3, "blocked", true, "type", "image", "method", "GET",
                                "url", "https://other.test/x.png", "reason", "sub_host"),
                        map("seq", 4, "blocked", false, "type", "xhr", "method", "GET",
                                "url", "https://example.test/api")))));

        Page page = server.browser().openPage("https://example.test/");
        List<NetworkLog.NetworkRequest> requests = page.networkRequests();

        assertEquals(DenialReason.NONE, requests.get(0).reason(), "an allowed request says 'none'");
        assertFalse(requests.get(0).refusedByPolicy(), "and was refused by nothing");

        assertEquals(DenialReason.FILTER_LIST, requests.get(1).reason(), "the adblock engine blocked one");
        assertFalse(requests.get(1).refusedByPolicy(),
                "which is the filter list, deliberately NOT the enforced policy");

        assertEquals(DenialReason.SUB_HOST, requests.get(2).reason(),
                "and the policy refused a subresource host");
        assertTrue(requests.get(2).refusedByPolicy(), "that one IS the policy's decision");

        assertNull(requests.get(3).reason(),
                "an entry with no reason at all decodes to null, as a pre-policy server sends");
    }

    @Test
    @DisplayName("A reason the schema does not declare fails closed rather than being folded away")
    void unknownVocabularyFailsClosed() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 3, "document", 1, "revision", 1, "snapshot", TREE)));
        server.on("web_network", facts(map("blocking_enabled", true, "blocked", 1,
                "total_requests", 1, "rules_loaded", 0, "requests", List.of(
                        map("seq", 1, "blocked", true, "type", "script", "method", "GET",
                                "url", "https://x.test/", "reason", "vibes")))));

        Page page = server.browser().openPage("https://example.test/");

        assertTrue(assertThrows(SketermApiException.class, page::networkRequests)
                .getMessage().contains("vibes"), "the unknown reason is named");

        // A per-request reason can never be a whole view's exhaustion reason
        assertThrows(SketermApiException.class, () -> DenialReason.requireExhaustion("sub_host"),
                "sub_host refuses one request; it cannot latch a policy");
        assertEquals(DenialReason.DEADLINE, DenialReason.requireExhaustion("deadline"),
                "a budget reason can");
    }

    private static <E extends Enum<E> & WireValue> List<String> wires(E[] members) {

        return Arrays.stream(members).map(WireValue::wire).toList();
    }

    /**
     * The policy echo the server sends back: names, not masks, and every budget always present.
     */
    private static Map<String, Object> echo(List<String> hosts, int maxRequests) {

        return map("allow_hosts", hosts,
                "allow_subresource_hosts", List.of(),
                "block_types", List.of(),
                "allow_schemes", List.of("http", "https"),
                "allow_private_addresses", false,
                "max_requests", maxRequests,
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
