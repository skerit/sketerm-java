package be.elevenways.sketerm.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which exchanges a view's response-body CAPTURE records, and how much of them it may hold.
 *
 * <p>A capture is installed at {@link Browser#openPage(String, OpenOptions)} and never later: the
 * requests that already ran would predate it. From there it records, for the view's whole
 * lifetime, every request whose clauses ALL hold - an omitted clause does not restrict - and
 * nothing that fails one is ever buffered. A live capture can only be narrowed
 * ({@link Page#clearCaptured()}, {@link Page#disableCapture()}).</p>
 *
 * <p>The same clauses select a response to wait for in {@link Page#waitForResponse}; there the byte
 * caps mean nothing and are not sent, and an empty {@link #types()} means ANY class rather than
 * the capture's xhr default.</p>
 *
 * @param hosts hosts (and their subdomains) whose requests count; empty means any host
 * @param urlContains a url substring, case-sensitive; null means no restriction
 * @param urlRegex a url pattern in the server's regex SUBSET (literal text, '.', [a-z] / [^x]
 *                 classes, * + ? quantifiers, ^ $ anchors and top-level |; no groups, so ( ) are
 *                 literal), case-sensitive; the server compiles and refuses a bad one
 * @param types resource classes; empty means the server's default, {@link ResourceType#XHR} for a
 *              capture, which covers fetch() AND XMLHttpRequest (the engine reports both as one)
 * @param methods HTTP methods, upper-cased; empty means any
 * @param mimePrefixes response content types by case-insensitive prefix, lower-cased; empty means
 *                     any
 * @param maxBodyBytes the per-body cap; null means the server's 16 MiB default
 * @param maxTotalBytes the cap on everything held for the view; null means the server's 128 MiB
 *                      default
 */
public record CaptureFilter(List<String> hosts,
                            String urlContains,
                            String urlRegex,
                            Set<ResourceType> types,
                            List<String> methods,
                            List<String> mimePrefixes,
                            Long maxBodyBytes,
                            Long maxTotalBytes) {

    /** The server's per-body default and ceiling (web/capture.zig). */
    public static final long DEFAULT_MAX_BODY_BYTES = 16L * 1024 * 1024;
    public static final long MAX_BODY_LIMIT = 256L * 1024 * 1024;

    /** The server's per-view default and ceiling (web/capture.zig). */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    public static final long MAX_TOTAL_LIMIT = 1024L * 1024 * 1024;

    /** The server's list caps; a longer list is refused, never truncated. */
    public static final int MAX_METHODS = 16;
    public static final int MAX_MIME_PREFIXES = 16;

    /** The server's cap on url_contains, url_regex and each list entry, in UTF-8 bytes. */
    public static final int MAX_STRING_BYTES = 1024;

    private static final String TOOL = "web_open";

    public CaptureFilter {
        hosts = hostList(hosts);
        methods = methodList(methods);
        mimePrefixes = mimeList(mimePrefixes);
        types = types == null || types.isEmpty() ? Set.of() : Set.copyOf(types);
        urlContains = optionalString(urlContains, "url_contains");
        urlRegex = optionalString(urlRegex, "url_regex");

        requireRange(maxBodyBytes, MAX_BODY_LIMIT, "max_body_bytes");
        requireRange(maxTotalBytes, MAX_TOTAL_LIMIT, "max_total_bytes");

        long body = maxBodyBytes == null ? DEFAULT_MAX_BODY_BYTES : maxBodyBytes;
        long total = maxTotalBytes == null ? DEFAULT_MAX_TOTAL_BYTES : maxTotalBytes;

        if (body > total) {
            throw refusal("capture.max_body_bytes (" + body + ") cannot exceed max_total_bytes ("
                    + total + ")");
        }
    }

    /**
     * Every clause open: record every xhr/fetch exchange, up to the server's default caps.
     */
    public static CaptureFilter everything() {
        return builder().build();
    }

    /**
     * The commonest shape: the JSON a page's own API returns.
     */
    public static CaptureFilter json() {
        return builder().mimePrefixes("application/json").build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return this filter as web_open's {@code capture} object, every unset field simply absent
     */
    public Map<String, Object> toWire() {

        Map<String, Object> wire = this.toMatchWire();

        if (this.maxBodyBytes != null) {
            wire.put("max_body_bytes", this.maxBodyBytes);
        }

        if (this.maxTotalBytes != null) {
            wire.put("max_total_bytes", this.maxTotalBytes);
        }

        return wire;
    }

    /**
     * @return the matching clauses only, as web_wait's {@code response} object; the byte caps are
     *         a capture setting the wait filter refuses
     */
    public Map<String, Object> toMatchWire() {

        Map<String, Object> wire = new LinkedHashMap<>();

        if (!this.hosts.isEmpty()) {
            wire.put("hosts", List.copyOf(this.hosts));
        }

        if (this.urlContains != null) {
            wire.put("url_contains", this.urlContains);
        }

        if (this.urlRegex != null) {
            wire.put("url_regex", this.urlRegex);
        }

        if (!this.types.isEmpty()) {

            List<String> names = new ArrayList<>();

            for (ResourceType type : ResourceType.values()) {
                if (this.types.contains(type)) {
                    names.add(type.wire());
                }
            }

            wire.put("types", names);
        }

        if (!this.methods.isEmpty()) {
            wire.put("methods", List.copyOf(this.methods));
        }

        if (!this.mimePrefixes.isEmpty()) {
            wire.put("mime_prefixes", List.copyOf(this.mimePrefixes));
        }

        return wire;
    }

    /**
     * Mutable only until {@link #build()}; every entry is validated as it goes in.
     */
    public static final class Builder {

        private final List<String> hosts = new ArrayList<>();
        private final EnumSet<ResourceType> types = EnumSet.noneOf(ResourceType.class);
        private final List<String> methods = new ArrayList<>();
        private final List<String> mimePrefixes = new ArrayList<>();

        private String urlContains;
        private String urlRegex;
        private Long maxBodyBytes;
        private Long maxTotalBytes;

        private Builder() {
        }

        /**
         * @throws InvalidArgsException when an entry is not a bare host name or IP literal
         */
        public Builder hosts(String... hosts) {
            return this.hosts(List.of(hosts));
        }

        public Builder hosts(Collection<String> hosts) {
            this.hosts.addAll(hosts);
            return this;
        }

        public Builder urlContains(String text) {
            this.urlContains = text;
            return this;
        }

        public Builder urlRegex(String pattern) {
            this.urlRegex = pattern;
            return this;
        }

        /**
         * {@link ResourceType#XHR} covers fetch() and XMLHttpRequest alike.
         */
        public Builder types(ResourceType... types) {
            this.types.addAll(List.of(types));
            return this;
        }

        public Builder methods(String... methods) {
            this.methods.addAll(List.of(methods));
            return this;
        }

        public Builder mimePrefixes(String... prefixes) {
            this.mimePrefixes.addAll(List.of(prefixes));
            return this;
        }

        public Builder maxBodyBytes(long bytes) {
            this.maxBodyBytes = bytes;
            return this;
        }

        public Builder maxTotalBytes(long bytes) {
            this.maxTotalBytes = bytes;
            return this;
        }

        public CaptureFilter build() {
            return new CaptureFilter(this.hosts,
                    this.urlContains,
                    this.urlRegex,
                    this.types,
                    this.methods,
                    this.mimePrefixes,
                    this.maxBodyBytes,
                    this.maxTotalBytes);
        }
    }

    private static List<String> hostList(List<String> hosts) {

        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }

        requireCount(hosts.size(), PolicyHosts.MAX_HOSTS, "hosts");
        List<String> folded = new ArrayList<>(hosts.size());

        for (String host : hosts) {

            String lower = host == null ? null : host.toLowerCase(Locale.ROOT);

            if (!PolicyHosts.isValid(lower)) {
                throw refusal("'" + host + "' is not a usable host: bare host names or IP literals"
                        + " only (subdomains are included), no '*', scheme, port or path");
            }

            folded.add(lower);
        }

        return List.copyOf(folded);
    }

    private static List<String> methodList(List<String> methods) {

        if (methods == null || methods.isEmpty()) {
            return List.of();
        }

        requireCount(methods.size(), MAX_METHODS, "methods");
        List<String> folded = new ArrayList<>(methods.size());

        for (String method : methods) {

            boolean ok = method != null && !method.isEmpty() && method.length() <= 16
                    && method.chars().allMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'));

            if (!ok) {
                throw refusal("'" + method + "' is not an HTTP method");
            }

            folded.add(method.toUpperCase(Locale.ROOT));
        }

        return List.copyOf(folded);
    }

    private static List<String> mimeList(List<String> prefixes) {

        if (prefixes == null || prefixes.isEmpty()) {
            return List.of();
        }

        requireCount(prefixes.size(), MAX_MIME_PREFIXES, "mime_prefixes");
        List<String> folded = new ArrayList<>(prefixes.size());

        for (String prefix : prefixes) {

            boolean ok = prefix != null && !prefix.isEmpty() && prefix.length() <= 127
                    && prefix.chars().noneMatch(ch -> ch == ' ' || ch == '\t' || ch == ';' || ch == ',');

            if (!ok) {
                throw refusal("'" + prefix + "' is not a mime prefix (e.g. application/json, text/)");
            }

            folded.add(prefix.toLowerCase(Locale.ROOT));
        }

        return List.copyOf(folded);
    }

    private static String optionalString(String value, String key) {

        if (value == null) {
            return null;
        }

        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw refusal("capture." + key + " must be a non-empty string of at most "
                    + MAX_STRING_BYTES + " bytes; leave it null for no restriction");
        }

        return value;
    }

    private static void requireCount(int count, int max, String key) {

        if (count > max) {
            throw refusal("capture." + key + " lists " + count + " entries; the cap is " + max
                    + " (never silently truncated)");
        }
    }

    private static void requireRange(Long value, long max, String key) {

        if (value != null && (value < 1 || value > max)) {
            throw refusal("capture." + key + " must be from 1 to " + max + ", not " + value
                    + " (refused, never clamped)");
        }
    }

    private static InvalidArgsException refusal(String message) {
        return new InvalidArgsException(message, TOOL, false, null);
    }
}
