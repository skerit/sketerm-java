package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.file.Path;
import java.util.Map;

/**
 * One captured body written to disk instead of into the answer, reported by identity only.
 *
 * @param encoding {@link BodyEncoding#UTF8} for text (transcoded where the server said so), or
 *                 {@link BodyEncoding#BINARY} for the raw bytes of anything else
 * @param bytes the file's size
 * @param truncation whether the CAPTURE held less than the page received, which is the one case the
 *                   file is short without an error
 * @param complete false while the exchange is still streaming
 */
public record CapturedBodyFile(long seq,
                               BodyPart part,
                               Path path,
                               long bytes,
                               String sha256,
                               BodyEncoding encoding,
                               CaptureTruncation truncation,
                               long deliveredBytes,
                               boolean complete) {

    /**
     * @throws ProtocolMismatchException when the answer named no file
     */
    static CapturedBodyFile decode(Map<String, Object> structured) {

        String path = Json.optStr(structured, "out_file");

        if (path == null || path.isEmpty()) {
            throw new ProtocolMismatchException("web_capture answered without 'out_file'; the body"
                    + " was inlined instead of written");
        }

        String sha256 = Json.optStr(structured, "sha256");

        return new CapturedBodyFile(Json.longVal(structured, "seq"),
                BodyPart.require(Json.str(structured, "part")),
                Path.of(path),
                Json.longVal(structured, "bytes"),
                sha256 == null || sha256.isEmpty() ? null : sha256,
                BodyEncoding.require(Json.str(structured, "encoding")),
                CapturedExchange.truncation(structured, "capture_truncated_reason"),
                Json.longVal(structured, "delivered_bytes"),
                Json.boolVal(structured, "complete"));
    }
}
