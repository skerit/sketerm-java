package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of web_tabs: a view's handle and its page facts.
 *
 * @param handle the pane id with a GUI, the view id headless; either way what 'pane' takes
 * @param profile the named profile this view is in, null when it is in none
 * @param profileKind which identity the view holds, null with a GUI (the tool reports none there)
 * @param context the engine identity-context id, 0 for the shared default jar
 * @param current whether a web_* call that omits 'pane' would address this view
 * @param policyActive whether an enforced network policy is installed on this view
 * @param policyExhausted whether a policy budget has LATCHED for this view; permanent per view
 * @param policyExhaustedReason which budget it was, null while none has latched
 * @param captureActive whether this view records response bodies (headless only)
 */
public record PageInfo(int handle,
                       String url,
                       String title,
                       boolean loading,
                       boolean canGoBack,
                       boolean canGoForward,
                       boolean focused,
                       boolean visible,
                       String profile,
                       ProfileKind profileKind,
                       int context,
                       boolean current,
                       boolean policyActive,
                       boolean policyExhausted,
                       DenialReason policyExhaustedReason,
                       boolean captureActive) {

    static PageInfo decode(Map<String, Object> entry) {

        String profile = Json.optStr(entry, "profile");
        Long context = Json.optLong(entry, "context");
        boolean exhausted = Json.optBool(entry, "policy_exhausted", false);

        return new PageInfo(Handles.of(entry, "a web_tabs entry"),
                Json.optStr(entry, "url"),
                Json.optStr(entry, "title"),
                Json.optBool(entry, "loading", false),
                Json.optBool(entry, "can_back", false),
                Json.optBool(entry, "can_fwd", false),
                Json.optBool(entry, "focused", false),
                Json.optBool(entry, "visible", false),
                profile == null || profile.isEmpty() ? null : profile,
                kindOf(entry),
                context == null ? 0 : context.intValue(),
                Json.optBool(entry, "current", false),
                Json.optBool(entry, "policy_active", false),
                exhausted,
                exhausted ? DenialReason.requireExhaustion(Json.optStr(entry, "policy_exhausted_reason")) : null,
                Json.optBool(entry, "capture_active", false));
    }

    /**
     * Re-wire this entry as the facts shape {@link Page#absorb} expects, so a refresh or an attach
     * updates a page's cache through the exact same seam every other answer does.
     *
     * <p>{@code policy_exhausted} is included only when true, mirroring the wire contract itself
     * (the fact is emitted only on latch) so {@link Page#absorbPolicy} never mistakes an absent key
     * for the budgets having come back.</p>
     */
    Map<String, Object> toFacts() {

        Map<String, Object> facts = new LinkedHashMap<>();
        ToolCalls.put(facts, "url", this.url);
        ToolCalls.put(facts, "title", this.title);
        facts.put("loading", this.loading);

        if (this.profileKind != null) {
            facts.put("profile", this.profile == null ? "" : this.profile);
            facts.put("profile_kind", this.profileKind.wire());
            facts.put("context", this.context);
        }

        facts.put("policy_active", this.policyActive);
        facts.put("capture_active", this.captureActive);

        if (this.policyExhausted) {
            facts.put("policy_exhausted", true);
            ToolCalls.put(facts, "policy_exhausted_reason",
                    this.policyExhaustedReason == null ? null : this.policyExhaustedReason.wire());
        }

        return facts;
    }

    /**
     * @return the kind, null when the answer named none; an unknown token fails closed
     */
    static ProfileKind kindOf(Map<String, Object> structured) {

        String wire = Json.optStr(structured, "profile_kind");

        return wire == null || wire.isEmpty() ? null : ProfileKind.require(wire);
    }
}
