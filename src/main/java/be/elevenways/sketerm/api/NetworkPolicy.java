package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An ENFORCED per-view network policy: what a view may load, and how much of it.
 *
 * <p>A policy is installed at {@link Browser#openPage(String, OpenOptions)} and never later - a
 * view's earlier requests would predate it - and can afterwards only be TIGHTENED
 * ({@link Page#tightenPolicy}). Every field left unset keeps the server's own default, so a policy
 * says only what it means to constrain.</p>
 *
 * <p>Budgets LATCH: the first one that is hit exhausts the view permanently. Read tools keep
 * answering (carrying the exhaustion fact, see {@link Page#isPolicyExhausted()}) while every tool
 * that would cause traffic is refused with a {@link RefusedException}.</p>
 *
 * @param allowHosts hosts (and their subdomains) the TOP-LEVEL document may load from; empty means
 *                   the host of the opened url only
 * @param allowSubresourceHosts extra hosts subresources may use, always unioned with allowHosts
 * @param blockTypes resource classes to refuse outright
 * @param blockAds the built-in EasyList-subset filter, which defaults on; null leaves it alone
 * @param allowSchemes null or empty means the server's http+https default; about: is always allowed
 * @param allowPrivateAddresses whether loopback/private/link-local LITERALS are reachable, which
 *                              defaults to false; a hostname that merely resolves to one is not
 *                              detected, so the host list is the real defence
 * @param maxRequests every allowed request counts, the document included; null means unbounded
 * @param maxBytes received-body budget, accounted at response completion, so the response that
 *                 CROSSES the cap completes and the NEXT request is refused
 * @param maxNavigations main-frame loads, redirect hops included
 * @param deadline wall-clock budget from the open
 */
public record NetworkPolicy(List<String> allowHosts,
                            List<String> allowSubresourceHosts,
                            Set<ResourceType> blockTypes,
                            Boolean blockAds,
                            Set<UrlScheme> allowSchemes,
                            Boolean allowPrivateAddresses,
                            Integer maxRequests,
                            Long maxBytes,
                            Integer maxNavigations,
                            Duration deadline) {

    public NetworkPolicy {
        allowHosts = hosts(allowHosts, "allow_hosts");
        allowSubresourceHosts = hosts(allowSubresourceHosts, "allow_subresource_hosts");
        blockTypes = frozen(blockTypes);
        allowSchemes = frozen(allowSchemes);

        requireNonNegative(maxRequests, "max_requests");
        requireNonNegative(maxNavigations, "max_navigations");
        requireNonNegative(maxBytes, "max_bytes");

        if (deadline != null && deadline.isNegative()) {
            throw new InvalidArgsException("policy.deadline_ms must not be negative",
                    "web_open", false, null);
        }
    }

    /**
     * A policy that constrains nothing yet; every {@code with}/builder call narrows it.
     */
    public static NetworkPolicy none() {
        return new NetworkPolicy(null, null, null, null, null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The commonest shape: one site and nothing else.
     *
     * @throws InvalidArgsException when an entry breaks {@link PolicyHosts}
     */
    public static NetworkPolicy allowingHosts(String... hosts) {
        return builder().allowHosts(hosts).build();
    }

    /**
     * @return this policy as web_open's {@code policy} object, keys in schema order and every unset
     *         field simply absent
     */
    public Map<String, Object> toWire() {

        Map<String, Object> wire = new LinkedHashMap<>();

        if (!this.allowHosts.isEmpty()) {
            wire.put("allow_hosts", List.copyOf(this.allowHosts));
        }

        if (!this.allowSubresourceHosts.isEmpty()) {
            wire.put("allow_subresource_hosts", List.copyOf(this.allowSubresourceHosts));
        }

        if (!this.blockTypes.isEmpty()) {
            wire.put("block_types", wireNames(ResourceType.values(), this.blockTypes));
        }

        if (this.blockAds != null) {
            wire.put("block_ads", this.blockAds);
        }

        if (!this.allowSchemes.isEmpty()) {
            wire.put("allow_schemes", wireNames(UrlScheme.values(), this.allowSchemes));
        }

        if (this.allowPrivateAddresses != null) {
            wire.put("allow_private_addresses", this.allowPrivateAddresses);
        }

        if (this.maxRequests != null) {
            wire.put("max_requests", this.maxRequests);
        }

        if (this.maxBytes != null) {
            wire.put("max_bytes", this.maxBytes);
        }

        if (this.maxNavigations != null) {
            wire.put("max_navigations", this.maxNavigations);
        }

        if (this.deadline != null) {
            wire.put("deadline_ms", this.deadline.toMillis());
        }

        return wire;
    }

    /**
     * Re-type the policy echo a policied answer carries.
     *
     * <p>AIDEV-NOTE: the echo is the EFFECTIVE policy, not the one that was sent, so it differs
     * from its own input in two documented ways: allow_schemes always lists the effective set (the
     * http+https default included) and a budget the caller never set echoes as 0. A 0 budget is
     * decoded back to null - "unbounded" - so a decoded echo compares equal to the policy that
     * asked for no budget at all.</p>
     *
     * @return the decoded policy, or null when the answer carried none
     */
    public static NetworkPolicy decode(Map<String, Object> structured) {

        Map<String, Object> policy = Json.optMap(structured, "policy");

        if (policy == null) {
            return null;
        }

        Builder builder = builder()
                .allowHosts(strings(policy, "allow_hosts"))
                .allowSubresourceHosts(strings(policy, "allow_subresource_hosts"));

        for (String type : strings(policy, "block_types")) {
            builder.blockType(ResourceType.require(type));
        }

        for (String scheme : strings(policy, "allow_schemes")) {
            builder.allowScheme(UrlScheme.require(scheme));
        }

        if (policy.containsKey("block_ads")) {
            builder.blockAds(Json.optBool(policy, "block_ads", true));
        }

        if (policy.containsKey("allow_private_addresses")) {
            builder.allowPrivateAddresses(Json.optBool(policy, "allow_private_addresses", false));
        }

        Long requests = positive(policy, "max_requests");
        Long bytes = positive(policy, "max_bytes");
        Long navigations = positive(policy, "max_navigations");
        Long deadlineMs = positive(policy, "deadline_ms");

        if (requests != null) {
            builder.maxRequests(requests.intValue());
        }

        if (bytes != null) {
            builder.maxBytes(bytes);
        }

        if (navigations != null) {
            builder.maxNavigations(navigations.intValue());
        }

        if (deadlineMs != null) {
            builder.deadline(Duration.ofMillis(deadlineMs));
        }

        return builder.build();
    }

    /**
     * Mutable only until {@link #build()}; every host entry is validated as it goes in.
     */
    public static final class Builder {

        private final List<String> allowHosts = new ArrayList<>();
        private final List<String> allowSubresourceHosts = new ArrayList<>();
        private final EnumSet<ResourceType> blockTypes = EnumSet.noneOf(ResourceType.class);
        private final EnumSet<UrlScheme> allowSchemes = EnumSet.noneOf(UrlScheme.class);

        private Boolean blockAds;
        private Boolean allowPrivateAddresses;
        private Integer maxRequests;
        private Long maxBytes;
        private Integer maxNavigations;
        private Duration deadline;

        private Builder() {
        }

        /**
         * @throws InvalidArgsException when an entry breaks {@link PolicyHosts}
         */
        public Builder allowHosts(String... hosts) {
            return this.allowHosts(List.of(hosts));
        }

        public Builder allowHosts(Collection<String> hosts) {
            add(this.allowHosts, hosts);
            return this;
        }

        /**
         * Hosts subresources may additionally use; the top-level document is not widened by them.
         */
        public Builder allowSubresourceHosts(String... hosts) {
            return this.allowSubresourceHosts(List.of(hosts));
        }

        public Builder allowSubresourceHosts(Collection<String> hosts) {
            add(this.allowSubresourceHosts, hosts);
            return this;
        }

        public Builder blockTypes(ResourceType... types) {
            this.blockTypes.addAll(List.of(types));
            return this;
        }

        public Builder blockType(ResourceType type) {
            this.blockTypes.add(type);
            return this;
        }

        /**
         * @param blockAds the built-in ad filter, which is on by default
         */
        public Builder blockAds(boolean blockAds) {
            this.blockAds = blockAds;
            return this;
        }

        /**
         * Naming any scheme replaces the server's http+https default, so name them all.
         */
        public Builder allowSchemes(UrlScheme... schemes) {
            this.allowSchemes.addAll(List.of(schemes));
            return this;
        }

        public Builder allowScheme(UrlScheme scheme) {
            this.allowSchemes.add(scheme);
            return this;
        }

        /**
         * @param allowed whether loopback/private/link-local literals may be reached at all
         */
        public Builder allowPrivateAddresses(boolean allowed) {
            this.allowPrivateAddresses = allowed;
            return this;
        }

        public Builder maxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
            return this;
        }

        public Builder maxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        public Builder maxNavigations(int maxNavigations) {
            this.maxNavigations = maxNavigations;
            return this;
        }

        public Builder deadline(Duration deadline) {
            this.deadline = deadline;
            return this;
        }

        public NetworkPolicy build() {
            return new NetworkPolicy(this.allowHosts,
                    this.allowSubresourceHosts,
                    this.blockTypes,
                    this.blockAds,
                    this.allowSchemes,
                    this.allowPrivateAddresses,
                    this.maxRequests,
                    this.maxBytes,
                    this.maxNavigations,
                    this.deadline);
        }

        private static void add(List<String> target, Collection<String> hosts) {

            if (hosts == null) {
                return;
            }

            for (String host : hosts) {
                target.add(PolicyHosts.require(host));
            }
        }
    }

    private static List<String> hosts(List<String> hosts, String key) {

        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }

        if (hosts.size() > PolicyHosts.MAX_HOSTS) {
            throw new InvalidArgsException("policy." + key + " lists " + hosts.size() + " hosts;"
                    + " the cap is " + PolicyHosts.MAX_HOSTS + " (never silently truncated)",
                    "web_open", false, null);
        }

        List<String> folded = new ArrayList<>(hosts.size());

        for (String host : hosts) {
            folded.add(PolicyHosts.require(host));
        }

        return List.copyOf(folded);
    }

    private static <E extends Enum<E>> Set<E> frozen(Set<E> members) {

        if (members == null || members.isEmpty()) {
            return Set.of();
        }

        return Set.copyOf(members);
    }

    private static <E extends Enum<E> & WireValue> List<String> wireNames(E[] all, Set<E> members) {

        List<String> names = new ArrayList<>(members.size());

        for (E member : all) {
            if (members.contains(member)) {
                names.add(member.wire());
            }
        }

        return List.copyOf(names);
    }

    private static List<String> strings(Map<String, Object> policy, String key) {

        List<Object> raw = Json.optList(policy, key);

        if (raw == null) {
            return List.of();
        }

        List<String> values = new ArrayList<>(raw.size());

        for (Object element : raw) {

            if (!(element instanceof String text)) {
                throw new ProtocolMismatchException("policy." + key + " holds a non-string entry: "
                        + element);
            }

            values.add(text);
        }

        return values;
    }

    private static Long positive(Map<String, Object> policy, String key) {

        Long value = Json.optLong(policy, key);

        return value == null || value == 0 ? null : value;
    }

    private static void requireNonNegative(Number value, String key) {

        if (value != null && value.longValue() < 0) {
            throw new InvalidArgsException("policy." + key + " must not be negative",
                    "web_open", false, null);
        }
    }
}
