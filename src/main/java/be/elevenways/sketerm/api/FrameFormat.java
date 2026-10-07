package be.elevenways.sketerm.api;

/**
 * The image encoding web_frame answers with.
 */
public enum FrameFormat implements WireValue {

    /** Lossy and small: what a live viewer polls. */
    JPEG("jpeg"),
    /** Lossless: what a pixel comparison needs. */
    PNG("png");

    private final String wire;

    FrameFormat(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return the media type of an image in this format
     */
    public String mimeType() {
        return switch (this) {
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
        };
    }
}
