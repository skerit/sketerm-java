package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * One captured body returned INLINE: text as UTF-8, a binary as base64.
 *
 * <p>Two different cuts can apply and both are facts, never silent: {@link #truncation()} is the
 * CAPTURE holding less than the page received, and {@link #inlineTruncated()} is this answer
 * carrying only a prefix of what is held ({@link Page#responseBodyToFile} gets it whole).</p>
 *
 * @param complete false while the exchange is still streaming, so the body may still grow
 * @param transcodedFrom the single-byte charset the text was converted from, null when it arrived
 *                       as UTF-8
 * @param storedBytes the bytes held for this body
 * @param deliveredBytes the bytes the page received, held or not
 * @param bytes the size of the body as presented (UTF-8 text or the raw binary)
 * @param body the text, or the base64 of a binary; a prefix when {@link #inlineTruncated()}
 * @param headers the response headers as the engine reported them; empty for a request body
 */
public record CapturedBody(long seq,
                           BodyPart part,
                           boolean complete,
                           int status,
                           String mime,
                           String charset,
                           BodyEncoding encoding,
                           String transcodedFrom,
                           long storedBytes,
                           long deliveredBytes,
                           CaptureTruncation truncation,
                           long bytes,
                           boolean inlineTruncated,
                           String body,
                           List<Header> headers) {

    public CapturedBody {
        headers = List.copyOf(headers);
    }

    /** One response header, repeated names kept as the engine sent them. */
    public record Header(String name, String value) {
    }

    /**
     * @return the body's bytes: the UTF-8 of the text, or the decoded binary
     */
    public byte[] data() {
        return this.encoding == BodyEncoding.BASE64
                ? Base64.getDecoder().decode(this.body)
                : this.body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return the body as text
     * @throws IllegalStateException for a binary body, which has no text to return
     */
    public String text() {

        if (this.encoding != BodyEncoding.UTF8) {
            throw new IllegalStateException("Exchange " + this.seq + "'s " + this.part.wire()
                    + " body is binary (" + this.mime + "); read data() instead");
        }

        return this.body;
    }

    static CapturedBody decode(Map<String, Object> structured) {

        List<Object> raw = Json.optList(structured, "headers");
        List<Header> headers = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                Map<String, Object> header = Json.asMap(element, "a captured response header");
                headers.add(new Header(Json.str(header, "name"), Json.str(header, "value")));
            }
        }

        String body = Json.optStr(structured, "body");

        if (body == null) {
            throw new ProtocolMismatchException("web_capture answered a body read without 'body';"
                    + " was an out_file sent?");
        }

        Long status = Json.optLong(structured, "status");

        return new CapturedBody(Json.longVal(structured, "seq"),
                BodyPart.require(Json.str(structured, "part")),
                Json.boolVal(structured, "complete"),
                status == null ? 0 : status.intValue(),
                Json.optStr(structured, "mime"),
                Json.optStr(structured, "charset"),
                BodyEncoding.require(Json.str(structured, "encoding")),
                Json.optStr(structured, "transcoded_from"),
                Json.longVal(structured, "stored_bytes"),
                Json.longVal(structured, "delivered_bytes"),
                CapturedExchange.truncation(structured, "capture_truncated_reason"),
                Json.longVal(structured, "bytes"),
                Json.optBool(structured, "inline_truncated", false),
                body,
                headers);
    }
}
