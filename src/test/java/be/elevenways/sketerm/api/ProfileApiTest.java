package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle half of the browser face: closing a view, and the named identities one can hold.
 */
class ProfileApiTest {

    private static final String TREE = """
            doc 1 rev 1 url https://example.test/
            [1] document "Example" {0 children}
            """;

    @Test
    @DisplayName("A page opens in a named profile, reports it, and closing refuses every later call")
    void profiledPageJourney() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 4, "settled", true, "document", 1, "revision", 1,
                "snapshot", TREE, "profile", "work", "profile_kind", "named", "context", 3)));
        server.on("web_close", facts(map("closed", 4, "remaining", 0, "current", 0,
                "profile", "work", "profile_released", false)));
        server.on("web_tabs", map("backend", "headless", "count", 0, "helper", "ready",
                "views", List.of()));

        Browser browser = server.browser();

        // 1. The identity goes out as an argument, spelled exactly as the schema declares it
        Page page = browser.openPage("https://example.test/", OpenOptions.inProfile("work"));

        assertEquals("work", server.lastArguments("web_open").get("profile"),
                "step 1: the profile name went out");
        assertFalse(server.lastArguments("web_open").containsKey("ephemeral"),
                "step 1: and nothing claimed a throwaway identity");

        // 2. The open facts say which identity the view actually got
        assertEquals("work", page.profile(), "step 2: the page knows its profile");
        assertEquals(ProfileKind.NAMED, page.profileKind(), "step 2: as a named one");
        assertEquals(3, page.context(), "step 2: with the engine context id it was given");
        assertFalse(page.isClosed(), "step 2: and it is open");

        // 3. Closing names the handle and decodes what the server did with it
        CloseResult closed = page.close();

        assertEquals(4, ((Number) server.lastArguments("web_close").get("pane")).intValue(),
                "step 3: web_close addressed this view by handle");
        assertEquals(4, closed.closed(), "step 3: the closed handle came back");
        assertEquals(0, closed.remaining(), "step 3: no views are left");
        assertEquals(0, closed.current(), "step 3: so a handle-less call addresses nothing");
        assertEquals("work", closed.profile(), "step 3: the profile it was in is named");
        assertFalse(closed.profileReleased(), "step 3: a named profile keeps its storage");
        assertFalse(closed.closedAPane(), "step 3: headless, so no pane of the user's went away");

        // 4. The page refuses locally afterwards: its handle is free to be reused by another view
        assertTrue(page.isClosed(), "step 4: the page knows it is closed");

        int callsBefore = server.calls().size();
        PageClosedException refused = assertThrows(PageClosedException.class, () -> page.evaluate("1+1"));

        assertEquals(4, refused.getHandle(), "step 4: the refusal names the handle");
        assertEquals(callsBefore, server.calls().size(), "step 4: and nothing went out");

        assertThrows(PageClosedException.class, page::refresh, "step 4: refresh is refused too");
        assertThrows(PageClosedException.class, () -> page.snapshot(SnapshotMode.FULL),
                "step 4: as is a snapshot");

        // 5. Closing again is a no-op that repeats the first answer
        assertEquals(closed, page.close(), "step 5: the same result comes back");
        assertEquals(1, server.callsTo("web_close").size(), "step 5: without a second web_close");

        // 6. And the view really is gone from the listing
        assertTrue(browser.pages().isEmpty(), "step 6: web_tabs lists nothing");
    }

    @Test
    @DisplayName("An ephemeral open asks for a throwaway identity and reports its release on close")
    void ephemeralOpenAndRelease() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 9, "document", 1, "revision", 1, "snapshot", TREE,
                "profile", "", "profile_kind", "ephemeral", "context", 1073741824)));
        server.on("web_close", facts(map("closed", 9, "remaining", 1, "current", 2,
                "profile_released", true)));

        Page page = server.browser().openPage("https://example.test/", OpenOptions.ephemeralIdentity());

        assertEquals(Boolean.TRUE, server.lastArguments("web_open").get("ephemeral"),
                "the throwaway identity was asked for");
        assertFalse(server.lastArguments("web_open").containsKey("profile"),
                "and no name went with it");
        assertEquals(ProfileKind.EPHEMERAL, page.profileKind(), "the view holds a throwaway identity");
        assertNull(page.profile(), "which has no name, rather than an empty one");

        CloseResult closed = page.close();

        assertTrue(closed.profileReleased(), "the identity went with the last view holding it");
        assertNull(closed.profile(), "and it never had a name");
        assertEquals(2, closed.current(), "a handle-less call now addresses the view that is left");
    }

    @Test
    @DisplayName("A GUI-attached close says it closed the user's own pane")
    void guiCloseIsDestructive() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", map("backend", "gui", "pane", 12, "origin", "https://example.test",
                "url", "https://example.test/", "title", "Example", "loading", false,
                "document", 1, "revision", 1, "snapshot", TREE));
        server.on("web_close", map("backend", "gui", "closed", 12, "remaining", 2, "current", 0));

        Page page = server.browser().openPage("https://example.test/");

        assertNull(page.profileKind(), "a GUI backend reports no identity: the containers are the user's");

        CloseResult closed = page.close();

        assertTrue(closed.closedAPane(), "the backend says a real pane was destroyed");
        assertEquals(12, closed.closed(), "and names it");
    }

    @Test
    @DisplayName("web_profiles decodes into typed profiles, store path and availability")
    void listsProfiles() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_profiles", map("profiles", List.of(
                        map("name", "work", "context", 3, "views", 1, "last_used_ms", 1755000000000L, "live", true),
                        map("name", "test", "context", 4, "views", 0, "last_used_ms", 0, "live", false)),
                "store", "/home/x/.local/state/sketerm/web-profiles/abc",
                "contexts_supported", true));

        ProfileList profiles = server.browser().profiles();

        assertEquals(2, profiles.profiles().size(), "both profiles are listed");
        assertTrue(profiles.contextsSupported(), "the helper can isolate an identity");
        assertNull(profiles.unavailableReason(), "so nothing explains an absence");
        assertEquals("/home/x/.local/state/sketerm/web-profiles/abc", profiles.store(),
                "the store path came back");

        BrowserProfile work = profiles.find("work").orElseThrow();

        assertTrue(work.inUse(), "one view still holds it, so a reset would be refused");
        assertTrue(work.live(), "and the running helper knows about it");
        assertEquals(3, work.context(), "the context id came back");
        assertEquals(1755000000000L, work.lastUsed().toEpochMilli(), "and when it was last opened");

        assertNull(profiles.find("test").orElseThrow().lastUsed(),
                "a profile the server timestamps with 0 reports no instant, not the epoch");
    }

    @Test
    @DisplayName("A server without isolated contexts says so instead of pretending")
    void profilesUnavailable() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_profiles", map("profiles", List.of(), "contexts_supported", false,
                "unavailable_reason", "the browser helper does not advertise contexts"));
        server.onError("web_open", "unavailable", "this browser helper does not advertise isolated"
                + " identity contexts. Nothing was opened.", true);

        Browser browser = server.browser();
        ProfileList profiles = browser.profiles();

        assertTrue(profiles.isEmpty(), "nothing is listed");
        assertFalse(profiles.contextsSupported(), "because nothing can be isolated");
        assertEquals("the browser helper does not advertise contexts", profiles.unavailableReason(),
                "and the reason is carried, not guessed");

        // Fail closed: the refusal means NOTHING was opened, never a view on the shared jar
        UnavailableException refused = assertThrows(UnavailableException.class,
                () -> browser.openPage("https://example.test/", OpenOptions.inProfile("work")));

        assertTrue(refused.getMessage().contains("Nothing was opened"),
                "the refusal keeps the server's own fail-closed sentence");
        assertTrue(refused.isRetryable(), "and unavailable is retryable");
    }

    @Test
    @DisplayName("A reset erases the storage, and an in-use profile is a conflict")
    void resetsAndRefuses() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_profile_reset", map("profile", "work", "deleted", true, "retired_context", 3));

        Browser browser = server.browser();
        ProfileResetResult reset = browser.resetProfile("work");

        assertEquals("work", server.lastArguments("web_profile_reset").get("profile"),
                "the name went out");
        assertTrue(reset.deleted(), "the storage was erased");
        assertEquals(3, reset.retiredContext(), "and the retired context id came back");

        server.onError("web_profile_reset", "conflict",
                "profile 'work' is in use by 2 open web view(s); close them with web_close first", false);

        ConflictException conflict = assertThrows(ConflictException.class, () -> browser.resetProfile("work"));

        assertEquals(ErrorCode.CONFLICT, conflict.getCode(), "an in-use profile is a conflict");
        assertFalse(conflict.isRetryable(), "retrying it unchanged cannot help");

        server.onError("web_profile_reset", "not_found", "no profile named 'gone'", false);
        assertThrows(NotFoundException.class, () -> browser.resetProfile("gone"),
                "an unknown name is a not_found");
    }

    @Test
    @DisplayName("A name the server would refuse is refused here, before anything is sent")
    void validatesProfileNames() {

        FakeSketermServer server = new FakeSketermServer();
        Browser browser = server.browser();

        for (String bad : List.of("Work", "with space", "a/b", "default", "none", "", "x".repeat(65))) {

            assertThrows(InvalidArgsException.class, () -> OpenOptions.inProfile(bad),
                    "'" + bad + "' is not a usable profile name");
            assertThrows(InvalidArgsException.class, () -> browser.resetProfile(bad),
                    "'" + bad + "' is refused by the reset too");
            assertFalse(ProfileNames.isValid(bad), "'" + bad + "' fails the rule");
        }

        assertTrue(server.calls().isEmpty(), "and not one of them reached the server");

        for (String good : List.of("work", "it-test", "a_b-9", "x".repeat(64))) {
            assertTrue(ProfileNames.isValid(good), "'" + good + "' matches the server's own rule");
        }
    }

    @Test
    @DisplayName("A profile and a throwaway identity cannot be asked for together")
    void identitiesAreExclusive() {

        assertThrows(InvalidArgsException.class,
                () -> OpenOptions.inProfile("work").withEphemeral(),
                "the two are opposite answers to the same question");

        assertThrows(InvalidArgsException.class,
                () -> OpenOptions.ephemeralIdentity().withProfile("work"),
                "in either order");

        assertEquals("work", OpenOptions.ephemeralIdentity().withDefaultIdentity()
                .withProfile("work").profile(), "dropping the identity first makes it legal");
    }

    @Test
    @DisplayName("The capabilities report is the preflight for offering profiles at all")
    void probesCapabilities() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("capabilities", map("mode", "isolated", "web", true, "web_backend", "headless",
                "web_profiles", true, "web_profile_store", "/home/x/.local/state/sketerm/web-profiles/abc"));

        assertTrue(server.browser().supportsProfiles(), "this server advertises them");

        FakeSketermServer without = new FakeSketermServer();
        without.on("capabilities", map("mode", "shared", "web", true, "web_backend", "gui"));

        assertFalse(without.browser().supportsProfiles(),
                "a server that names no flag is treated as not having the feature");
    }

    @Test
    @DisplayName("web_tabs carries the identity of every headless view")
    void tabsCarryIdentity() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_tabs", map("backend", "headless", "count", 2, "helper", "ready",
                "views", List.of(
                        map("view", 1, "url", "https://a.test/", "title", "A", "loading", false,
                                "profile", "work", "profile_kind", "named", "context", 3, "current", false),
                        map("view", 2, "url", "https://b.test/", "title", "B", "loading", false,
                                "profile", "", "profile_kind", "ephemeral", "context", 1073741824,
                                "current", true))));

        Browser browser = server.browser();
        List<PageInfo> pages = browser.pages();

        assertEquals("work", pages.get(0).profile(), "the named view says which profile it holds");
        assertEquals(ProfileKind.NAMED, pages.get(0).profileKind(), "as a named identity");
        assertEquals(ProfileKind.EPHEMERAL, pages.get(1).profileKind(), "the other is a throwaway");
        assertNull(pages.get(1).profile(), "which carries no name");

        Page attached = browser.page(1);

        assertEquals("work", attached.profile(), "attaching adopts the listed identity");
        assertEquals(ProfileKind.NAMED, attached.profileKind(), "and its kind");
    }

    @Test
    @DisplayName("An unknown profile_kind fails closed rather than being folded into a neighbour")
    void unknownKindFailsClosed() {

        FakeSketermServer server = new FakeSketermServer();

        server.on("web_open", facts(map("view", 1, "document", 1, "revision", 1, "snapshot", TREE,
                "profile", "work", "profile_kind", "borrowed", "context", 3)));

        SketermApiException failure = assertThrows(SketermApiException.class,
                () -> server.browser().openPage("https://example.test/"));

        assertTrue(failure.getMessage().contains("borrowed"), "the unknown token is named");
    }

    /**
     * The fields every headless browser answer carries, merged with the ones this call adds.
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
