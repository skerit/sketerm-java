package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browser face of a session: it opens views and lists the ones that are open.
 *
 * With a GUI attached these are the user's own tabs; headless they are views this server's own
 * browser engine hosts. Either way a view is a {@link Page} addressed by an integer handle.
 */
public final class Browser {

    private final ToolCalls calls;

    Browser(ToolCalls calls) {
        this.calls = calls;
    }

    /**
     * Open a new view on a url and wait for that first navigation to settle.
     *
     * A successful but slow open can return a live page without its first semantic tree;
     * {@link Page#lastSnapshot()} is then null and {@link Page#openingSnapshotError()} says why.
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
     * @throws UnavailableException when a captured open meets a helper without the capture
     *         capability, or a GUI - NOTHING is opened, there is deliberately no uncaptured fallback
     * @throws InvalidArgsException when the server refuses the capture filter (a bad url_regex)
     * @throws UnavailableException when the server returned a handle that is no longer an open view
     */
    public Page openPage(String url, OpenOptions options) {
        if (options != null && options.maxFps() != null && !this.supportsMaxFps()) {
            throw new UnavailableException("Sketerm does not advertise web_max_fps; nothing was opened",
                    "web_open", false, null);
        }

        boolean untrusted = options != null && options.policy() != null && options.policy().requiresUntrusted();
        if (untrusted && !this.supportsUntrusted()) {
            throw new UnavailableException("Sketerm does not advertise web_untrusted; nothing was opened",
                    "web_open", false, null);
        }
        Map<String, Object> arguments = ToolCalls.args();
        ToolCalls.put(arguments, "url", url);

        if (options != null) {
            ToolCalls.put(arguments, "width", options.width());
            ToolCalls.put(arguments, "height", options.height());
            ToolCalls.putTimeout(arguments, options.timeout());
            ToolCalls.put(arguments, "route", options.route());
            ToolCalls.put(arguments, "color_scheme", options.colorScheme() == null ? null : options.colorScheme().wire());
            ToolCalls.put(arguments, "max_fps", options.maxFps());
            if (untrusted && options.route() == null) arguments.put("route", "direct");

            if (options.profile() != null) {
                arguments.put("profile", ProfileNames.require(options.profile(), "web_open"));
            }

            if (options.ephemeral()) {
                arguments.put("ephemeral", true);
            }

            if (options.policy() != null) {
                arguments.put("policy", options.policy().toWire());
            }

            if (options.capture() != null) {
                arguments.put("capture", options.capture().toWire());
            }
        }

        Map<String, Object> structured = this.calls.structured("web_open", arguments);
        int handle = Handles.of(structured, "web_open");

        try {
            if (options != null && options.maxFps() != null
                    && !Long.valueOf(options.maxFps().longValue()).equals(Json.optLong(structured, "max_fps"))) {
                throw new ProtocolMismatchException("web_open did not acknowledge max_fps: " + options.maxFps());
            }
            if (options != null && options.colorScheme() != null
                    && !options.colorScheme().wire().equals(Json.optStr(structured, "color_scheme"))) {
                throw new ProtocolMismatchException("web_open did not acknowledge color_scheme: "
                        + options.colorScheme().wire());
            }
            // A view opened on another route than asked is browsing somewhere the caller did
            // not choose; a proxy route must be confirmed outright.
            String asked = options == null ? null : options.route();
            String echoed = Json.optStr(structured, "route");
            if (asked != null && (echoed != null ? !echoed.equals(asked) : Routes.isProxy(asked))) {
                throw new ProtocolMismatchException("web_open opened the view on route " + echoed
                        + ", not the requested " + asked);
            }
            if (untrusted) {
                NetworkPolicy installed = NetworkPolicy.decode(structured);
                if (!Boolean.TRUE.equals(structured.get("untrusted"))
                        || !Boolean.TRUE.equals(structured.get("policy_active"))
                        || installed == null || !installed.requiresUntrusted()) {
                    throw new ProtocolMismatchException("web_open did not acknowledge an active untrusted install");
                }
            }
            Snapshot opening = structured.containsKey("snapshot")
                    ? Snapshot.decode(handle, structured, "web_open") : null;
            Map<String, Object> pageFacts = structured;

            if (opening == null) {
                PageInfo live = listPages(this.calls).stream()
                        .filter(info -> info.handle() == handle)
                        .findFirst()
                        .orElseThrow(() -> new UnavailableException("web_open returned handle "
                                + handle + " but web_tabs says that view is not open; the browser"
                                + " helper did not finish creating it", "web_open", true, null));

                // web_tabs was read after web_open, so its changing page facts are fresher. Keep
                // web_open's backend, document and policy detail for keys PageInfo does not carry.
                pageFacts = new LinkedHashMap<>(structured);
                pageFacts.putAll(live.toFacts());
            }

            Page page = new Page(this.calls, handle, pageFacts, opening);
            if (untrusted && !page.policy().untrusted()) {
                throw new ProtocolMismatchException("web_policy did not confirm the acknowledged untrusted policy");
            }
            return page;
        } catch (RuntimeException failure) {
            // A successful web_open already minted this handle. Never turn a decode disagreement
            // into a view the caller cannot address or close.
            Map<String, Object> close = ToolCalls.args();
            close.put("pane", handle);

            try {
                this.calls.structured("web_close", close);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }

            throw failure;
        }
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
                return new Page(this.calls, handle, info.toFacts(), null);
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
                return new Page(this.calls, info.handle(), info.toFacts(), null);
            }
        }

        return null;
    }

    /**
     * The named persistent browsing identities this server can open views in.
     *
     * A profile is created by opening a view in it, so this lists what has been used, not what
     * could be. Headless only: with a GUI attached the browser's identity containers are the
     * user's own and the tool refuses.
     *
     * @throws UnavailableException with a GUI attached, or when the profile store cannot be opened
     */
    public ProfileList profiles() {
        return ProfileList.decode(this.calls.structured("web_profiles", ToolCalls.args()));
    }

    /**
     * Erase a profile's cookies, logins and cache. Irreversible.
     *
     * The name stays usable: the next open with it starts from an empty, freshly allocated jar
     * behind a new context id.
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
     * The registration is in memory and gone when the server exits - deliberately, since a
     * durable copy could be silently lost by a store rebuild - which is what the answer's
     * {@link ProfilePolicy#durable()} keeps saying out loud.
     *
     * @throws UnavailableException with a GUI attached: policies are a headless-only feature
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}
     */
    public ProfilePolicy setProfilePolicy(String name, NetworkPolicy policy) {

        if (policy.requiresUntrusted()) {
            throw new InvalidArgsException("Untrusted policies cannot be named-profile defaults",
                    "web_policy_set", false, null);
        }
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
     * Preflight: whether this server can run the web_* tools at all (a helper it can start, or a GUI).
     *
     * @return the capabilities report's web flag, false when the server names none
     */
    public boolean isAvailable() {
        return this.capability("web");
    }

    /**
     * Preflight: whether the web tools drive this server's own headless helper rather than a GUI the
     * user owns (the {@code web_gui} grant), where policies, profiles and capture are refused.
     *
     * Before the first web call a real server reports web_backend "not_yet_determined", so the
     * answer reads the grant: headless unless web_gui is true or the backend is already "gui".
     */
    public boolean isHeadless() {
        Map<String, Object> capabilities = this.calls.structured("capabilities", ToolCalls.args());
        return !Boolean.TRUE.equals(capabilities.get("web_gui")) && !"gui".equals(capabilities.get("web_backend"));
    }

    /**
     * Preflight: whether this server advertises named browsing profiles at all.
     *
     * Worth asking before offering the feature, since a refusal is fail-closed and opens
     * nothing rather than falling back to the shared jar.
     *
     * @return the capabilities report's web_profiles flag, false when the server names none
     */
    public boolean supportsProfiles() {
        return this.capability("web_profiles");
    }

    /**
     * Preflight: whether this server can download a url through a view's own browser.
     *
     * False also means {@link Page#downloads()} has nothing to list, since a helper without the
     * capability answers no download at all.
     *
     * @return the capabilities report's web_downloads flag, false when the server names none
     */
    public boolean supportsDownloads() {
        return this.capability("web_downloads");
    }

    /**
     * Preflight: whether this server can capture the response bodies a view's page receives.
     *
     * Worth asking before offering the feature, since a captured open is fail-closed: without the
     * capability NOTHING is opened.
     *
     * @return the capabilities report's web_capture flag, false when the server names none (or a
     *         GUI is attached: capture is headless only)
     */
    public boolean supportsCapture() {
        return this.capability("web_capture");
    }

    /**
     * Preflight: whether {@link Page#input} can drive this server's views by hand (headless only).
     *
     * @return the capabilities report's web_input flag, false when the server names none
     */
    public boolean supportsInput() {
        return this.capability("web_input");
    }

    /**
     * Preflight: whether {@link Page#frame} can stream this server's painted frames (headless only).
     *
     * @return the capabilities report's web_frames flag, false when the server names none
     */
    public boolean supportsFrames() {
        return this.capability("web_frames");
    }

    /**
     * Missing or null web_stream means the server cannot offer binary page streams.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public boolean supportsStreams() {
        return this.capability("web_stream");
    }

    /** Missing or false web_max_fps means explicit headless caps are unsupported. */
    public boolean supportsMaxFps() {
        return this.capability("web_max_fps");
    }

    /** Build/platform support for Linux headless untrusted loading; absent means unsupported. */
    public boolean supportsUntrusted() {
        return this.capability("web_untrusted");
    }

    /**
     * Current helper's verified installation-acknowledgement support. False also means unknown:
     * web_policy_ack is null before the first helper handshake. An untrusted open verifies it itself.
     */
    public boolean supportsPolicyAcknowledgement() {
        return this.capability("web_policy_ack");
    }

    private boolean capability(String fact) {
        return capability(this.calls, fact);
    }

    /** @return the capabilities report's boolean fact, false when missing or null */
    static boolean capability(ToolCalls calls, String fact) {
        Object value = calls.structured("capabilities", ToolCalls.args()).get(fact);
        if (value == null) return false;
        if (!(value instanceof Boolean supported)) {
            throw new ProtocolMismatchException("capabilities." + fact + " must be boolean or null");
        }
        return supported;
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
}
