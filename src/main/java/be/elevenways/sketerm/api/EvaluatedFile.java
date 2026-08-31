package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.nio.file.Path;
import java.util.Map;

/**
 * A web_eval result written to disk instead of into the answer, reported by identity only.
 *
 * @param sha256 the digest of the written file, null when the server named none
 * @param truncated whether the PAGE cut the value before it was written, which is the one case the
 *                  file is short and no error was raised; return the value in slices to get the rest
 * @param totalChars how many characters the page had, 0 unless it cut the value
 */
public record EvaluatedFile(Path path,
                            long bytes,
                            String sha256,
                            EvalFormat format,
                            boolean truncated,
                            long totalChars) {

    /**
     * @throws ProtocolMismatchException when the answer named no file, which means the server
     *         predates web_eval's out_file and inlined the result instead
     */
    static EvaluatedFile decode(Map<String, Object> structured) {

        String path = Json.optStr(structured, "out_file");

        if (path == null || path.isEmpty()) {
            throw new ProtocolMismatchException("web_eval answered without 'out_file'; this server"
                    + " predates writing an eval result to disk, so the value was inlined instead");
        }

        Long bytes = Json.optLong(structured, "bytes");
        Long totalChars = Json.optLong(structured, "total_chars");
        String sha256 = Json.optStr(structured, "sha256");
        String format = Json.optStr(structured, "format");

        return new EvaluatedFile(Path.of(path),
                bytes == null ? 0 : bytes,
                sha256 == null || sha256.isEmpty() ? null : sha256,
                format == null ? null : EvalFormat.require(format),
                Json.optBool(structured, "truncated", false),
                totalChars == null ? 0 : totalChars);
    }
}
