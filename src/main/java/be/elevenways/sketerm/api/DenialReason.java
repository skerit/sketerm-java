package be.elevenways.sketerm.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * THE refusal vocabulary, mirroring protocol.NetReason: why one request was blocked, and - for the
 * four budget members - why a whole view's policy latched.
 *
 * <p>AIDEV-NOTE: the schemas spell this vocabulary twice, as web_network's 12-name request
 * {@code reason} and as web_policy's 5-name {@code exhausted_reason}, but the server has one
 * declaring home and the second list is the subset that can LATCH. So this enum is that one home
 * and {@link #latches()} carries the subset as a fact on the member; {@link #EXHAUSTION_REASONS}
 * derives the shorter list rather than restating it.</p>
 */
public enum DenialReason implements WireValue {

    /** Not a refusal at all: the request was allowed, or no budget has latched. */
    NONE("none", false),
    /** The built-in EasyList-subset ad filter, which is NOT the enforced policy. */
    FILTER_LIST("filter_list", false),
    /** The top-level document's host is not in allow_hosts. */
    TOP_HOST("top_host", false),
    /** A subresource host is in neither allow list. */
    SUB_HOST("sub_host", false),
    /** The resource class is in block_types. */
    RESOURCE_TYPE("resource_type", false),
    /** A loopback/private/link-local literal with allow_private_addresses off. */
    PRIVATE_ADDRESS("private_address", false),
    /** The scheme is outside allow_schemes, or outside the vocabulary entirely. */
    SCHEME("scheme", false),
    /** A redirect hop landed on a host the allow lists do not carry. */
    REDIRECT_HOST("redirect_host", false),
    /** max_requests is spent; the policy has LATCHED. */
    REQUEST_CAP("request_cap", true),
    /** max_bytes was crossed by the response before this one; the policy has LATCHED. */
    BYTE_CAP("byte_cap", true),
    /** max_navigations is spent, redirect hops included; the policy has LATCHED. */
    NAV_CAP("nav_cap", true),
    /** deadline_ms ran out; the policy has LATCHED. */
    DEADLINE("deadline", true);

    /**
     * The members web_policy's exhausted_reason can name, {@link #NONE} included: derived from the
     * members themselves, so a new budget kind is one edit here and nowhere else.
     */
    public static final Set<DenialReason> EXHAUSTION_REASONS = exhaustionReasons();

    private final String wire;
    private final boolean latches;

    DenialReason(String wire, boolean latches) {
        this.wire = wire;
        this.latches = latches;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether hitting this reason permanently exhausts the view's policy, rather than
     *         refusing just the one request
     */
    public boolean latches() {
        return this.latches;
    }

    /**
     * @return whether the enforced policy (rather than the ad filter) made this decision
     */
    public boolean isPolicy() {
        return this != NONE && this != FILTER_LIST;
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static DenialReason require(String wire) {
        return WireValues.require(DenialReason.class, wire);
    }

    /**
     * Decode a reason that a server predating enforced policy simply does not send.
     *
     * @return the member, or null when the answer carried no reason at all
     * @throws SketermApiException when a reason IS named but this build does not know it
     */
    public static DenialReason optional(String wire) {
        return wire == null || wire.isEmpty() ? null : require(wire);
    }

    /**
     * Decode an exhausted_reason, which may only name a latching member or {@link #NONE}.
     *
     * @return the member, or null when the answer carried no reason
     * @throws SketermApiException when the token is unknown, or names a per-request reason that
     *         cannot latch a whole view
     */
    public static DenialReason requireExhaustion(String wire) {

        if (wire == null || wire.isEmpty()) {
            return null;
        }

        DenialReason reason = require(wire);

        if (!EXHAUSTION_REASONS.contains(reason)) {
            throw new SketermApiException("The server named '" + wire + "' as an exhausted_reason,"
                    + " but that reason refuses one request and cannot latch a view's policy");
        }

        return reason;
    }

    private static Set<DenialReason> exhaustionReasons() {

        EnumSet<DenialReason> reasons = EnumSet.of(NONE);

        for (DenialReason reason : values()) {
            if (reason.latches()) {
                reasons.add(reason);
            }
        }

        return Collections.unmodifiableSet(reasons);
    }
}
