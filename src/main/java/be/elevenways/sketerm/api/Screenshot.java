package be.elevenways.sketerm.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A PNG of a web view: the decoded bytes from the image content block plus the reported size.
 *
 * @param declaredBytes the byte count the structured payload named, for cross-checking the decode
 */
public record Screenshot(byte[] bytes, String mimeType, int width, int height, int declaredBytes) {

    /**
     * @throws IOException when the file cannot be written
     */
    public void writeTo(Path path) throws IOException {
        Files.write(path, this.bytes);
    }

    /**
     * @return whether the decoded byte count matches the one the server declared
     */
    public boolean isComplete() {
        return this.declaredBytes == 0 || this.declaredBytes == this.bytes.length;
    }
}
