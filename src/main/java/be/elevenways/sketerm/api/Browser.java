package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The browser face of a session: it opens views and lists the ones that are open.
 *
 * <p>With a GUI attached these are the user's own tabs; headless they are views this server's own
 * browser engine hosts. Either way a view is a {@link Page} addressed by an integer handle.</p>
 */
public final class Browser {

    private final ToolCalls calls;

    Browser(ToolCalls calls) {
        this.calls = calls;
    }

    /**
     * Open a new view on a url and wait for that first navigation to settle.
     *
     * @throws ProtocolMismatchException when the answer carries no view handle
     */
    public Page openPage(String url) {
        return this.openPage(url, OpenOptions.defaults());
    }

    /**
     * @param options the viewport, identity, settle budget and enforced policy; null means the
     *                server's defaults
     * @throws UnavailableException when a policied open meets a helper without the net-policy
     *         capability - NOTHING is opened, there is deliberately no unpoliced fallback
     * @throws ConflictException when the helper cannot hold another policy (its table is full);
     *         the view is closed again un-navigated
     */
    public Page openPage(String url, OpenOptions options) {

        Map<String, Object> arguments = ToolCalls.args();
        ToolCalls.put(arguments, "url", url);

        if (options != null) {
            ToolCalls.put(arguments, "width", options.width());
            ToolCalls.put(arguments, "height", options.height());
            ToolCalls.putTimeout(arguments, options.timeout());

            if (options.profile() != null) {
                arguments.put("profile", ProfileNames.require(options.profile(), "web_open"));
            }

            if (options.ephemeral()) {
                arguments.put("ephemeral", true);
            }

            if (options.policy() != null) {
                arguments.put("policy", options.policy().toWire());
            }
        }

        Map<String, Object> structured = this.calls.structured("web_open", arguments);
        int handle = Handles.of(structured, "web_open");

        return new Page(this.calls, handle, structured, Snapshot.decode(handle, structured, "web_open"));
    }

    /**
     * Open a blank view.
     */
    public Page openPage() {
        return this.openPage(null, OpenOptions.defaults());
    }

    /**
     * @return every open view, the current one flagged
     */
    public List<PageInfo> pages() {
        return listPages(this.calls);
    }

    /**
     * Address an already-open view.
     *
     * @throws NotFoundException when no view has that handle
     */
    public Page page(int handle) {

        for (PageInfo info : this.pages()) {
            if (info.handle() == handle) {
                return new Page(this.calls, handle, factsOf(info), null);
            }
        }

        throw new NotFoundException("No open view has handle " + handle, "web_tabs", false, null);
    }

    /**
     * @return the view a web_* call with no handle would address, or null when none is open
     */
    public Page currentPage() {

        for (PageInfo info : this.pages()) {
            if (info.current()) {
                return new Page(this.calls, info.handle(), factsOf(info), null);
            }
        }

        return null;
    }

    /**
     * The named persistent browsing identities this server can open views in.
     *
     * <p>A profile is created by opening a view in it, so this lists what has been used, not what
     * could be. Headless only: with a GUI attached the browser's identity containers are the
     * user's own and the tool refuses.</p>
     *
     * @throws UnavailableException with a GUI attached, or when the profile store cannot be opened
     */
    public ProfileList profiles() {
        return ProfileList.decode(this.calls.structured("web_profiles", ToolCalls.args()));
    }

    /**
     * Erase a profile's cookies, logins and cache. Irreversible.
     *
     * <p>The name stays usable: the next open with it starts from an empty, freshly allocated jar
     * behind a new context id.</p>
     *
     * @throws ConflictException while any open view still holds the profile; close them first
     * @throws NotFoundException when no profile has that name
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}
     */
    public ProfileResetResult resetProfile(String name) {

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("profile", ProfileNames.require(name, "web_profile_reset"));

        return ProfileResetResult.decode(this.calls.structured("web_profile_reset", arguments));
    }

    /**
     * Register a profile's SESSION-DEFAULT network policy, applied by every later open in that
     * profile whose own call carries no policy.
     *
     * <p>The registration is in memory and gone when the server exits - deliberately, since a
     * durable copy could be silently lost by a store rebuild - which is what the answer's
     * {@link ProfilePolicy#durable()} keeps saying out loud.</p>
     *
     * @throws UnavailableException with a GUI attached: policies are a headless-only feature
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}
     */
    public ProfilePolicy setProfilePolicy(String name, NetworkPolicy policy) {

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("profile", ProfileNames.require(name, "web_policy_set"));
        arguments.put("policy", policy.toWire());

        return ProfilePolicy.decode(name, this.calls.structured("web_policy_set", arguments));
    }

    /**
     * Read back a profile's registered session-default policy.
     *
     * @throws NotFoundException when the profile has no session default registered
     */
    public ProfilePolicy profilePolicy(String name) {

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("profile", ProfileNames.require(name, "web_policy"));

        return ProfilePolicy.decode(name, this.calls.structured("web_policy", arguments));
    }

    /**
     * Preflight: whether this server advertises named browsing profiles at all.
     *
     * <p>Worth asking before offering the feature, since a refusal is fail-closed and opens
     * nothing rather than falling back to the shared jar.</p>
     *
     * @return the capabilities report's web_profiles flag, false when the server names none
     */
    public boolean supportsProfiles() {
        return Json.optBool(this.calls.structured("capabilities", ToolCalls.args()), "web_profiles", false);
    }

    static List<PageInfo> listPages(ToolCalls calls) {

        Map<String, Object> structured = calls.structured("web_tabs", ToolCalls.args());
        List<Object> raw = Json.optList(structured, "views");
        List<PageInfo> pages = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                pages.add(PageInfo.decode(Json.asMap(element, "a web_tabs entry")));
            }
        }

        return List.copyOf(pages);
    }

    private static Map<String, Object> factsOf(PageInfo info) {

        Map<String, Object> facts = ToolCalls.args();
        ToolCalls.put(facts, "url", info.url());
        ToolCalls.put(facts, "title", info.title());
        facts.put("loading", info.loading());

        if (info.profileKind() != null) {
            facts.put("profile", info.profile() == null ? "" : info.profile());
            facts.put("profile_kind", info.profileKind().wire());
            facts.put("context", info.context());
        }

        return facts;
    }
}
