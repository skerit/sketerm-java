package be.elevenways.sketerm.api;

import java.time.Duration;

/**
 * How to open a view; every field is optional and null leaves the server's default in place.
 *
 * A profile and an ephemeral identity are opposite answers to the same question, so asking for
 * both is refused here rather than at the server.
 *
 * @param width the headless viewport width, ignored with a GUI (server default 1280)
 * @param height the headless viewport height, ignored with a GUI (server default 800)
 * @param timeout the budget for the first load to settle (server default 20s)
 * @param profile a named persistent identity, headless only; null for the shared default jar
 * @param ephemeral open in a fresh throwaway identity, destroyed with the view
 * @param policy an ENFORCED network policy, installed before the view's first request; headless
 *               only, and fail-closed - a helper that cannot enforce it opens nothing
 * @param capture a response-body CAPTURE, recording from the view's first request on; headless
 *                only, and fail-closed - a helper that cannot capture opens nothing
 * @param route null uses direct; otherwise direct, tor, via:host or on:host (backend permitting)
 * @param colorScheme headless preferred colour scheme, installed before the first document; null keeps defaults
 * @param maxFps headless CEF paint cap from 1 to 240; null keeps the server's default
 */
public record OpenOptions(Integer width,
                          Integer height,
                          Duration timeout,
                          String profile,
                          boolean ephemeral,
                          NetworkPolicy policy,
                          CaptureFilter capture,
                          String route,
                          ColorScheme colorScheme,
                          Integer maxFps) {

    public static final int MAX_FPS = 240;

    /** Existing callers leave the headless frame-rate cap at the server's default. */
    public OpenOptions(Integer width, Integer height, Duration timeout, String profile, boolean ephemeral,
                       NetworkPolicy policy, CaptureFilter capture, String route, ColorScheme colorScheme) {
        this(width, height, timeout, profile, ephemeral, policy, capture, route, colorScheme, null);
    }

    /** Backward-compatible constructor with direct routing and default colours. */
    public OpenOptions(Integer width, Integer height, Duration timeout, String profile,
                       boolean ephemeral, NetworkPolicy policy) {
        this(width, height, timeout, profile, ephemeral, policy, null, null, null);
    }

    /** Backward-compatible constructor for response-body capture. */
    public OpenOptions(Integer width, Integer height, Duration timeout, String profile,
                       boolean ephemeral, NetworkPolicy policy, CaptureFilter capture) {
        this(width, height, timeout, profile, ephemeral, policy, capture, null, null);
    }

    public OpenOptions {
        requireMaxFps(maxFps, "web_open");

        if (profile != null) {

            if (ephemeral) {
                throw new InvalidArgsException("An open takes either a profile (a named persistent"
                        + " identity) or an ephemeral one, not both", "web_open", false, null);
            }

            ProfileNames.require(profile, "web_open");
        }

        if (route != null && !route.equals("direct") && !route.equals("tor")
                && !route.matches("(?:via|on):[^\\s:]+")) {
            throw new InvalidArgsException("Unknown browser route: " + route, "web_open", false, null);
        }
        if (policy != null && policy.requiresUntrusted()) {
            if (!ephemeral || profile != null) {
                throw new InvalidArgsException("Untrusted policy requires ephemeral:true and no named profile",
                        "web_open", false, null);
            }
            if (route != null && !route.equals("direct")) {
                throw new InvalidArgsException("Untrusted policy requires the direct route",
                        "web_open", false, null);
            }
        }
    }

    public static OpenOptions defaults() {
        return new OpenOptions(null, null, null, null, false, null, null);
    }

    public static OpenOptions viewport(int width, int height) {
        return new OpenOptions(width, height, null, null, false, null, null);
    }

    /**
     * Open under an enforced network policy.
     *
     * Fail closed, exactly as the server is: a browser helper without the net-policy capability
     * refuses the open and NOTHING is opened, never a view running unpoliced.
     */
    public static OpenOptions withNetworkPolicy(NetworkPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("A fail-closed network policy must not be null");
        }

        return defaults().withPolicy(policy);
    }

    /**
     * Open with a response-body capture: every exchange the filter names is recorded, request body
     * and response headers included, from the view's very first request on.
     *
     * Fail closed, exactly as the server is: a browser helper without the capture capability
     * refuses the open and NOTHING is opened, never a view that silently records nothing.
     */
    public static OpenOptions withCapture(CaptureFilter capture) {
        if (capture == null) {
            throw new IllegalArgumentException("A fail-closed capture must not be null");
        }

        return defaults().capturing(capture);
    }

    /**
     * Open in a named persistent identity: its own cookie jar and cache, surviving the view and
     * server restarts (session cookies never do - they die with the browser process).
     *
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}
     */
    public static OpenOptions inProfile(String profile) {
        return defaults().withProfile(profile);
    }

    /**
     * Open in a fresh throwaway identity, incognito-shaped and destroyed with the view.
     */
    public static OpenOptions ephemeralIdentity() {
        return defaults().withEphemeral();
    }

    public OpenOptions withViewport(int width, int height) {
        return new OpenOptions(width, height, this.timeout, this.profile, this.ephemeral, this.policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    public OpenOptions withTimeout(Duration timeout) {
        return new OpenOptions(this.width, this.height, timeout, this.profile, this.ephemeral, this.policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    /**
     * @param policy the enforced policy, or null to open unpoliced
     */
    public OpenOptions withPolicy(NetworkPolicy policy) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    /**
     * @param capture the capture to install at open, or null to open uncaptured
     */
    public OpenOptions capturing(CaptureFilter capture) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, this.policy,
                capture, this.route, this.colorScheme, this.maxFps);
    }

    /**
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}, or when these options
     *         already ask for an ephemeral identity (say {@link #withDefaultIdentity()} first)
     */
    public OpenOptions withProfile(String profile) {
        return new OpenOptions(this.width, this.height, this.timeout, profile, this.ephemeral, this.policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    /**
     * @throws InvalidArgsException when these options already name a profile
     */
    public OpenOptions withEphemeral() {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, true, this.policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    /**
     * Drop the identity choice, back to the shared default cookie jar.
     */
    public OpenOptions withDefaultIdentity() {
        return new OpenOptions(this.width, this.height, this.timeout, null, false, this.policy,
                this.capture, this.route, this.colorScheme, this.maxFps);
    }

    /** Select a network route; untrusted policies accept direct only. */
    public OpenOptions withRoute(String route) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, this.policy,
                this.capture, route, this.colorScheme, this.maxFps);
    }

    /**
     * Headless colour preference, applied before initial loading. Unsupported helpers refuse the open.
     * Null clears the preference and keeps Sketerm's default.
     */
    public OpenOptions withColorScheme(ColorScheme colorScheme) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, this.policy,
                this.capture, this.route, colorScheme, this.maxFps);
    }

    /** Null preserves the server's headless default; explicit caps require capability support. */
    public OpenOptions withMaxFps(Integer maxFps) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, this.policy,
                this.capture, this.route, this.colorScheme, maxFps);
    }

    static void requireMaxFps(Integer maxFps, String tool) {
        if (maxFps != null && (maxFps < 1 || maxFps > MAX_FPS)) {
            throw new InvalidArgsException("max_fps must be from 1 to " + MAX_FPS, tool, false, null);
        }
    }
}
