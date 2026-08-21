package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A page as it was at one moment: what it said, what it looked like, what it fetched, and what it
 * was allowed to fetch.
 *
 * <p>Nothing here is a new tool call shape - {@link #capture} composes the reads a caller would
 * make by hand and freezes their answers together, so the pieces are known to describe the SAME
 * moment rather than three round trips apart. Every part it uses is a read tool, so a capture still
 * works on a view whose network policy has latched: that is the point, since an exhausted view is
 * exactly the one whose evidence someone wants.</p>
 *
 * @param finalUrl where the view actually ended up, after any redirects
 * @param capturedAt when the capture began, from the client's clock
 * @param document the per-document counter the snapshot was taken in, 0 when no tree was asked for
 * @param screenshot the PNG, null when {@link Part#SCREENSHOT} was not asked for
 * @param snapshot the semantic tree as text, null when {@link Part#SNAPSHOT} was not asked for
 * @param articleMarkdown reader-mode markdown, null when {@link Part#ARTICLE} was not asked for
 * @param network the request log and its counters, null when {@link Part#NETWORK} was not asked for
 * @param policy the enforced policy and its accounting; null when not asked for AND null when the
 *               view runs no policy at all, since there is nothing to attest to
 * @param screenshotSha256 lower-case hex of the PNG bytes, null when there is no screenshot
 */
public record Evidence(String finalUrl,
                       String title,
                       Instant capturedAt,
                       int document,
                       int revision,
                       Screenshot screenshot,
                       String snapshot,
                       String articleMarkdown,
                       NetworkSummary network,
                       PolicyStatus policy,
                       String screenshotSha256) {

    /** The name of the manifest {@link #writeTo} persists. */
    public static final String MANIFEST = "evidence.json";

    /**
     * What a capture may gather; every part costs one tool call.
     */
    public enum Part {
        SCREENSHOT,
        SNAPSHOT,
        ARTICLE,
        NETWORK,
        POLICY
    }

    /**
     * The request log as evidence: the counters plus the entries themselves, reasons included.
     */
    public record NetworkSummary(int totalRequests, int blocked, List<NetworkLog.NetworkRequest> requests) {

        public NetworkSummary {
            requests = List.copyOf(requests);
        }

        static NetworkSummary of(NetworkLog log) {
            return new NetworkSummary(log.totalRequests(), log.blocked(), log.requests());
        }
    }

    /**
     * Capture everything.
     */
    public static Evidence capture(Page page) {
        return capture(page, EnumSet.allOf(Part.class));
    }

    /**
     * @param parts which pieces to gather; an empty set still records the page's identity
     */
    public static Evidence capture(Page page, EnumSet<Part> parts) {

        Instant capturedAt = Instant.now();

        // Re-read the identity first, so the url and title describe the page the parts came from
        // rather than whatever the last call happened to leave behind.
        page.refresh();

        Screenshot screenshot = parts.contains(Part.SCREENSHOT) ? page.screenshot() : null;
        String tree = parts.contains(Part.SNAPSHOT) ? page.snapshot(SnapshotMode.FULL).tree() : null;
        String markdown = parts.contains(Part.ARTICLE) ? page.read().markdown() : null;
        NetworkSummary network = parts.contains(Part.NETWORK)
                ? NetworkSummary.of(page.network()) : null;

        // A policy report on an unpoliced view attests to nothing, so it is left out rather than
        // recorded as a row of zeroes that reads like enforcement.
        PolicyStatus policy = null;

        if (parts.contains(Part.POLICY)) {

            PolicyStatus status = page.policy();
            policy = status.active() ? status : null;
        }

        return new Evidence(page.url(),
                page.title(),
                capturedAt,
                page.document(),
                page.revision(),
                screenshot,
                tree,
                markdown,
                network,
                policy,
                screenshot == null ? null : sha256(screenshot.bytes()));
    }

    /**
     * Persist this capture as a folder: {@value #MANIFEST} always, plus screenshot.png,
     * snapshot.txt and article.md for whichever parts were gathered.
     *
     * @param directory created when it does not exist; existing files of these names are replaced
     * @return the directory itself, for chaining
     * @throws IOException when anything cannot be written
     */
    public Path writeTo(Path directory) throws IOException {

        Files.createDirectories(directory);

        if (this.screenshot != null) {
            this.screenshot.writeTo(directory.resolve("screenshot.png"));
        }

        if (this.snapshot != null) {
            Files.writeString(directory.resolve("snapshot.txt"), this.snapshot, StandardCharsets.UTF_8);
        }

        if (this.articleMarkdown != null) {
            Files.writeString(directory.resolve("article.md"), this.articleMarkdown, StandardCharsets.UTF_8);
        }

        Files.writeString(directory.resolve(MANIFEST), Json.write(this.toMap()), StandardCharsets.UTF_8);

        return directory;
    }

    /**
     * The manifest as a plain map, which is also what {@link #writeTo} serializes.
     *
     * <p>The screenshot rides as its digest and byte count only: the bytes themselves are the
     * sibling png, not a base64 blob inside the json.</p>
     */
    public Map<String, Object> toMap() {

        Map<String, Object> manifest = new LinkedHashMap<>();

        put(manifest, "final_url", this.finalUrl);
        put(manifest, "title", this.title);
        put(manifest, "captured_at", this.capturedAt == null ? null : this.capturedAt.toString());
        manifest.put("document", this.document);
        manifest.put("revision", this.revision);

        if (this.screenshot != null) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("file", "screenshot.png");
            shot.put("bytes", this.screenshot.bytes().length);
            shot.put("width", this.screenshot.width());
            shot.put("height", this.screenshot.height());
            put(shot, "sha256", this.screenshotSha256);
            manifest.put("screenshot", shot);
        }

        if (this.snapshot != null) {
            manifest.put("snapshot", "snapshot.txt");
        }

        if (this.articleMarkdown != null) {
            manifest.put("article", "article.md");
        }

        if (this.network != null) {

            List<Object> requests = new ArrayList<>();

            for (NetworkLog.NetworkRequest request : this.network.requests()) {

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("seq", request.seq());
                entry.put("blocked", request.blocked());
                put(entry, "type", request.type());
                put(entry, "method", request.method());
                put(entry, "url", request.url());
                put(entry, "status", request.status());
                put(entry, "size", request.size());
                put(entry, "reason", request.reason() == null ? null : request.reason().wire());
                requests.add(entry);
            }

            Map<String, Object> network = new LinkedHashMap<>();
            network.put("total_requests", this.network.totalRequests());
            network.put("blocked", this.network.blocked());
            network.put("requests", requests);
            manifest.put("network", network);
        }

        if (this.policy != null) {

            Map<String, Object> denied = new LinkedHashMap<>();

            for (Map.Entry<DenialReason, Long> entry : this.policy.denied().entrySet()) {
                denied.put(entry.getKey().wire(), entry.getValue());
            }

            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("active", this.policy.active());
            put(policy, "source", this.policy.source() == null ? null : this.policy.source().wire());
            policy.put("serial", this.policy.serial());
            policy.put("requests", this.policy.requests());
            policy.put("bytes", this.policy.bytes());
            policy.put("navigations", this.policy.navigations());
            policy.put("ms_left", this.policy.msLeft());
            policy.put("exhausted", this.policy.exhausted());
            put(policy, "exhausted_reason", this.policy.exhaustedReason() == null
                    ? null : this.policy.exhaustedReason().wire());
            policy.put("denied", denied);
            policy.put("durable", this.policy.durable());

            if (this.policy.policy() != null) {
                policy.put("policy", this.policy.policy().toWire());
            }

            manifest.put("policy", policy);
        }

        return manifest;
    }

    /**
     * @return lower-case hex of the SHA-256 of these bytes
     */
    public static String sha256(byte[] bytes) {

        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256; there is no sane fallback and no reason to have one.
            throw new IllegalStateException("This JVM has no SHA-256", e);
        }

        StringBuilder hex = new StringBuilder(64);

        for (byte value : digest.digest(bytes)) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }

        return hex.toString();
    }

    private static void put(Map<String, Object> target, String key, Object value) {

        if (value != null) {
            target.put(key, value);
        }
    }
}
