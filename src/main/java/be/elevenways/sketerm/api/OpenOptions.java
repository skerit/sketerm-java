package be.elevenways.sketerm.api;

import java.time.Duration;

/**
 * How to open a view; every field is optional and null leaves the server's default in place.
 *
 * <p>A profile and an ephemeral identity are opposite answers to the same question, so asking for
 * both is refused here rather than at the server.</p>
 *
 * @param width the headless viewport width, ignored with a GUI (server default 1280)
 * @param height the headless viewport height, ignored with a GUI (server default 800)
 * @param timeout the budget for the first load to settle (server default 20s)
 * @param profile a named persistent identity, headless only; null for the shared default jar
 * @param ephemeral open in a fresh throwaway identity, destroyed with the view
 * @param policy an ENFORCED network policy, installed before the view's first request; headless
 *               only, and fail-closed - a helper that cannot enforce it opens nothing
 */
public record OpenOptions(Integer width,
                          Integer height,
                          Duration timeout,
                          String profile,
                          boolean ephemeral,
                          NetworkPolicy policy) {

    public OpenOptions {

        if (profile != null) {

            if (ephemeral) {
                throw new InvalidArgsException("An open takes either a profile (a named persistent"
                        + " identity) or an ephemeral one, not both", "web_open", false, null);
            }

            ProfileNames.require(profile, "web_open");
        }
    }

    public static OpenOptions defaults() {
        return new OpenOptions(null, null, null, null, false, null);
    }

    public static OpenOptions viewport(int width, int height) {
        return new OpenOptions(width, height, null, null, false, null);
    }

    /**
     * Open under an enforced network policy.
     *
     * <p>Fail closed, exactly as the server is: a browser helper without the net-policy capability
     * refuses the open and NOTHING is opened, never a view running unpoliced.</p>
     */
    public static OpenOptions withNetworkPolicy(NetworkPolicy policy) {
        return defaults().withPolicy(policy);
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
        return new OpenOptions(width, height, this.timeout, this.profile, this.ephemeral, this.policy);
    }

    public OpenOptions withTimeout(Duration timeout) {
        return new OpenOptions(this.width, this.height, timeout, this.profile, this.ephemeral, this.policy);
    }

    /**
     * @param policy the enforced policy, or null to open unpoliced
     */
    public OpenOptions withPolicy(NetworkPolicy policy) {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, this.ephemeral, policy);
    }

    /**
     * @throws InvalidArgsException when the name breaks {@link ProfileNames}, or when these options
     *         already ask for an ephemeral identity (say {@link #withDefaultIdentity()} first)
     */
    public OpenOptions withProfile(String profile) {
        return new OpenOptions(this.width, this.height, this.timeout, profile, this.ephemeral, this.policy);
    }

    /**
     * @throws InvalidArgsException when these options already name a profile
     */
    public OpenOptions withEphemeral() {
        return new OpenOptions(this.width, this.height, this.timeout, this.profile, true, this.policy);
    }

    /**
     * Drop the identity choice, back to the shared default cookie jar.
     */
    public OpenOptions withDefaultIdentity() {
        return new OpenOptions(this.width, this.height, this.timeout, null, false, this.policy);
    }
}
