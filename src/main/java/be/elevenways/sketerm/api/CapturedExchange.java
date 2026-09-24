package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * One exchange a view's capture recorded, as metadata; its bodies are read with
 * {@link Page#responseBody(long)} and {@link Page#requestBody(long)}.
 *
 * @param seq the request's web_network seq, the join key with {@link Page#network()}
 * @param cursor the list position, in the order exchanges FINISHED; 0 while still in flight
 * @param type the resource class, null when the server named one this build does not know
 * @param startedAt when the response began (wall clock)
 * @param complete the load completed; false while in flight or when it {@link #failed()}
 * @param failed the load failed or was aborted, {@link #error()} being the engine's net error
 * @param bodyBytes the response bytes held
 * @param bodyDeliveredBytes the response bytes the page received, held or not
 * @param requestBodyNonBytes the request had parts that are not bytes (a file upload), of which only
 *                            the byte parts are held
 * @param path where {@link Page#capturedToDirectory} wrote the response body, null otherwise
 * @param fileBytes that file's size
 * @param sha256 that file's digest
 * @param fileEncoding how that file holds the body, null when none was written
 */
public record CapturedExchange(long seq,
                               long cursor,
                               String url,
                               String method,
                               int status,
                               ResourceType type,
                               String mime,
                               String charset,
                               Instant startedAt,
                               Duration duration,
                               boolean complete,
                               boolean failed,
                               int error,
                               long bodyBytes,
                               long bodyDeliveredBytes,
                               CaptureTruncation bodyTruncation,
                               long requestBodyBytes,
                               long requestBodyTotalBytes,
                               CaptureTruncation requestTruncation,
                               boolean requestBodyNonBytes,
                               boolean headersTruncated,
                               Path path,
                               long fileBytes,
                               String sha256,
                               BodyEncoding fileEncoding) {

    /**
     * @return whether the exchange has finished (successfully or not); an in-flight one is listed
     *         only when asked for, with cursor 0
     */
    public boolean finished() {
        return this.cursor != 0;
    }

    /**
     * @return whether the held response body is shorter than what the page received
     */
    public boolean bodyTruncated() {
        return this.bodyTruncation.truncated();
    }

    static CapturedExchange decode(Map<String, Object> entry) {

        String path = Json.optStr(entry, "path");
        String encoding = Json.optStr(entry, "encoding");
        Long started = Json.optLong(entry, "started_ms");
        Long duration = Json.optLong(entry, "duration_ms");

        return new CapturedExchange(Json.longVal(entry, "seq"),
                Json.longVal(entry, "cursor"),
                Json.str(entry, "url"),
                Json.str(entry, "method"),
                (int) Json.longVal(entry, "status"),
                ResourceType.fromWire(Json.optStr(entry, "type")),
                orEmpty(Json.optStr(entry, "mime")),
                orEmpty(Json.optStr(entry, "charset")),
                started == null ? null : Instant.ofEpochMilli(started),
                Duration.ofMillis(duration == null ? 0 : duration),
                Json.boolVal(entry, "complete"),
                Json.optBool(entry, "failed", false),
                (int) orZero(Json.optLong(entry, "error")),
                Json.longVal(entry, "body_bytes"),
                orZero(Json.optLong(entry, "body_delivered_bytes")),
                truncation(entry, "body_truncated_reason"),
                orZero(Json.optLong(entry, "request_body_bytes")),
                orZero(Json.optLong(entry, "request_body_total_bytes")),
                truncation(entry, "request_body_truncated_reason"),
                Json.optBool(entry, "request_body_nonbytes", false),
                Json.optBool(entry, "headers_truncated", false),
                path == null || path.isEmpty() ? null : Path.of(path),
                orZero(Json.optLong(entry, "file_bytes")),
                Json.optStr(entry, "sha256"),
                encoding == null ? null : BodyEncoding.require(encoding));
    }

    static CaptureTruncation truncation(Map<String, Object> map, String key) {

        String reason = Json.optStr(map, key);

        return reason == null ? CaptureTruncation.NONE : CaptureTruncation.require(reason);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
