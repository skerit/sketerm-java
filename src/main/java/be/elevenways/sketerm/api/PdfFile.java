package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.file.Path;
import java.util.Map;

/**
 * A PDF web_print_pdf wrote to disk, reported by identity only.
 *
 * @param sha256 the digest of the written file, null when the server named none
 * @param cssPageSize whether the document's {@code @page} size decided the sheet, as the server echoed it
 * @param noMargins whether the engine's margins were dropped, as the server echoed it
 */
public record PdfFile(Path path, long bytes, String sha256, boolean cssPageSize, boolean noMargins) {

    /**
     * @throws ProtocolMismatchException when the answer named no file
     */
    static PdfFile decode(Map<String, Object> structured) {

        String path = Json.optStr(structured, "path");
        Long bytes = Json.optLong(structured, "bytes");

        if (path == null || path.isEmpty() || bytes == null) {
            throw new ProtocolMismatchException("web_print_pdf answered without the 'path' it printed to"
                    + " or the 'bytes' it wrote");
        }

        String sha256 = Json.optStr(structured, "sha256");

        return new PdfFile(Path.of(path),
                bytes,
                sha256 == null || sha256.isEmpty() ? null : sha256,
                Json.optBool(structured, "css_page_size", false),
                Json.optBool(structured, "no_margins", false));
    }
}
