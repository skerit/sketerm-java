package be.elevenways.sketerm.api;

import java.time.Duration;

/**
 * How to open a view; every field is optional and null leaves the server's default in place.
 *
 * @param width the headless viewport width, ignored with a GUI (server default 1280)
 * @param height the headless viewport height, ignored with a GUI (server default 800)
 * @param timeout the budget for the first load to settle (server default 20s)
 */
public record OpenOptions(Integer width, Integer height, Duration timeout) {

    public static OpenOptions defaults() {
        return new OpenOptions(null, null, null);
    }

    public static OpenOptions viewport(int width, int height) {
        return new OpenOptions(width, height, null);
    }

    public OpenOptions withViewport(int width, int height) {
        return new OpenOptions(width, height, this.timeout);
    }

    public OpenOptions withTimeout(Duration timeout) {
        return new OpenOptions(this.width, this.height, timeout);
    }
}
