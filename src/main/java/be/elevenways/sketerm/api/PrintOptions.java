package be.elevenways.sketerm.api;

import java.time.Duration;

/**
 * How web_print_pdf lays a view out; {@link #defaults()} prints on the engine's own sheet with backgrounds.
 *
 * @param cssPageSize the document's {@code @page} size decides the sheet (a 1920x1080px slide prints as a
 *                    1440x810pt page); needs {@link Browser#supportsPrintPdfCssPage()}
 * @param noMargins no engine margins around the page box (an {@code @page} margin rule is honoured either way);
 *                  needs {@link Browser#supportsPrintPdfCssPage()}
 * @param background print backgrounds and colours
 * @param timeout the budget for the print, the server's default (60 s) when null
 */
public record PrintOptions(boolean cssPageSize, boolean noMargins, boolean background, Duration timeout) {

    /** @return the engine's sheet and margins, backgrounds printed, the server's budget */
    public static PrintOptions defaults() {
        return new PrintOptions(false, false, true, null);
    }

    /** @return the document's {@code @page} rule alone decides the sheet and its margins, backgrounds printed */
    public static PrintOptions cssPage() {
        return new PrintOptions(true, true, true, null);
    }

    public PrintOptions withBackground(boolean background) {
        return new PrintOptions(this.cssPageSize, this.noMargins, background, this.timeout);
    }

    public PrintOptions withTimeout(Duration timeout) {
        return new PrintOptions(this.cssPageSize, this.noMargins, this.background, timeout);
    }
}
