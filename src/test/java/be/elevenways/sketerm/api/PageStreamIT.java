package be.elevenways.sketerm.api;

import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.sketerm.api.PageStream.Damage;
import be.elevenways.sketerm.api.PageStream.FrameEnd;
import be.elevenways.sketerm.api.PageStream.Listener;
import be.elevenways.sketerm.api.PageStream.SurfaceSize;
import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.process.SketermProcess;
import be.elevenways.sketerm.rpc.SketermTransport;
import be.elevenways.sketerm.rpc.StdioTransport;
import be.elevenways.sketerm.testing.SketermBuild;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Java client against fresh checkout binaries; helper-internal budgets are Sketerm's own smoke assertions.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class PageStreamIT {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static @Nullable Path binaries;

    @BeforeAll
    static void copyFreshBinaries() throws IOException {
        Path server = SketermBuild.copy(Path.of("build", "stream-it-bin").toAbsolutePath());
        assumeTrue(server != null, SketermBuild.NO_CHECKOUT);
        binaries = server.getParent();
    }

    @Test
    @Timeout(240)
    void streamingJourney() throws Exception {
        JobRunner jobs = JobRunner.create("sketerm-stream-it");
        SketermOptions options = options();
        CompletableFuture<Void> waitSent = new CompletableFuture<>();
        CompletableFuture<WaitResult> waitResult = new CompletableFuture<>();
        Path html = binaries.resolveSibling("stream-it-journey.html");
        Files.writeString(html, JOURNEY_PAGE);
        try (SketermProcess process = SketermProcess.start(options.command(), options.workingDirectory(),
                options.environment()); Sketerm sketerm = Sketerm.connect(options,
                observed(new StdioTransport(process), waitSent))) {
            assertNotNull(sketerm.serverVersion(), "step 1: the server names its version");
            assertTrue(sketerm.browser().supportsStreams(), "step 1: a fresh server advertises web_stream");
            assertTrue(sketerm.browser().supportsMaxFps(), "step 1: and per-view frame-rate caps");
            Page page = sketerm.browser().openPage(html.toUri().toString(),
                    OpenOptions.ephemeralIdentity().withViewport(640, 480));
            Paints paints = new Paints();
            PageStream stream = page.stream(paints);

            // 1. The first frame is the whole surface in row bands, and the settled pixels equal web_screenshot.
            Painted first = next(paints, stream);
            assertEquals(new SurfaceSize(640, 480, 640, 480), first.size(), "step 1: scale-one surface");
            assertBands(first, "step 1");
            assertMatchesScreenshot(page, quiet(paints, stream), "step 1");

            // 2. Socket input resolves an MCP wait that is still outstanding and repaints only the clicked box.
            jobs.startThread(() -> {
                try { waitResult.complete(page.waitFor(WaitFor.TITLE, "clicked", Duration.ofSeconds(10))); }
                catch (Throwable failure) { waitResult.completeExceptionally(failure); }
            });
            waitSent.get(5, TimeUnit.SECONDS);
            assertFalse(waitResult.isDone(), "step 2: the MCP wait is outstanding before input");
            stream.focus();
            stream.input(InputEvent.move(300, 34), InputEvent.down(300, 34, MouseButton.LEFT, InputModifier.SHIFT),
                    InputEvent.up(300, 34, MouseButton.LEFT, InputModifier.SHIFT));
            assertNotNull(waitResult.get(15, TimeUnit.SECONDS), "step 2: socket input resolved the MCP wait");
            assertEquals("clicked shift trusted", page.evaluate("document.title"), "step 2: modifiers and trust");
            Painted clicked = settle(paints, stream);
            assertWithin(clicked.union(), new Rect(258, 18, 84, 32), "step 2: only the hit box repainted");
            assertEquals(0xff00ff00, clicked.argb(300, 34), "step 2: the hit box turned green");
            stream.input(InputEvent.click(60, 32));
            stream.key(KeyAction.PRESS, KeyCode.KEY_A);
            stream.input(InputEvent.text("bc"));
            assertEquals("abc", page.evaluate("new Promise(r=>{const t=()=>inp.value.length>=3?r(inp.value)"
                    + ":setTimeout(t,20);t()})", true, Duration.ofSeconds(5)),
                    "step 2: keys and text reached the field");
            page.evaluate("inp.blur(),1");
            quiet(paints, stream);

            // 3. Two unacknowledged frames fill the window; after the ACK the reader gets the newest pixels only.
            repaint(page, "veil.style.display='block',veil.style.background='rgb(255,0,0)'");
            solid(paints, stream, 255, 0, 0);
            repaint(page, "veil.style.background='rgb(0,0,255)'");
            Painted blue = solid(paints, stream, 0, 0, 255);
            page.evaluate("window.hue=0,window.iv=setInterval(()=>{hue=(hue+7)%360;"
                    + "veil.style.background='hsl('+hue+',80%,50%)'},5),1");
            assertNull(paints.frames.poll(1500, TimeUnit.MILLISECONDS), "step 3: no third frame without an ACK");
            repaint(page, "clearInterval(iv),veil.style.background='rgb(255,255,0)'");
            stream.ack(blue.serial());
            Painted merged = paints.frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(merged, "step 3: the ACK opens the window again");
            assertTrue(merged.solid(255, 255, 0), "step 3: the first frame after the stall is the newest yellow");
            stream.ack(merged.serial());
            assertNull(paints.frames.poll(300, TimeUnit.MILLISECONDS), "step 3: the stalled paints were not queued");
            repaint(page, "veil.style.display='none'");
            quiet(paints, stream);

            // 4. A resize sent on the socket brings a new surface, painted whole, with the right pixels.
            stream.resize(480, 360);
            Painted resized = until(paints, stream, frame -> frame.size().pixelWidth() == 480, "step 4");
            assertEquals(new SurfaceSize(480, 360, 480, 360), resized.size(), "step 4: resized surface");
            assertBands(resized, "step 4");
            assertEquals(480L, ((Number) page.evaluate("innerWidth")).longValue(), "step 4: the page relaid out");
            assertMatchesScreenshot(page, quiet(paints, stream), "step 4");

            // 5. A blur lets go of a key and a button the client still holds, as trusted page events.
            page.evaluate("ev.length=0,1");
            stream.focus();
            stream.input(InputEvent.keyDown("Shift"), InputEvent.move(200, 200),
                    InputEvent.down(200, 200, MouseButton.LEFT));
            stream.blur();
            Object released = page.evaluate("new Promise(r=>{const t=()=>ev.includes('keyup:Shift:true')"
                    + "&&ev.includes('pointerup:0:true')?r(ev.join(' ')):setTimeout(t,20);t()})", true,
                    Duration.ofSeconds(5));
            assertTrue(String.valueOf(released).contains("keydown:Shift:true"), "step 5: the key went down first");
            quiet(paints, stream);

            // 6. One stream per view; a wrong token is hung up on and spends the socket.
            assertThrows(ConflictException.class, () -> page.stream(new Paints()),
                    "step 6: a second stream on a streaming view is a conflict");
            Page other = sketerm.browser().openPage(DOCUMENT, OpenOptions.ephemeralIdentity().withViewport(320, 240));
            Map<String, Object> offer = sketerm.session().callToolOrThrow("web_stream",
                    Map.of("pane", other.handle(), "audio", false)).structured();
            Path socket = Path.of(Json.str(offer, "socket_path"));
            try (SocketChannel wrong = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                wrong.write(authFrame("f".repeat(32)));
                assertEquals(-1, wrong.read(ByteBuffer.allocate(64)), "step 6: a wrong token is hung up on");
            }
            assertThrows(IOException.class, () -> SocketChannel.open(UnixDomainSocketAddress.of(socket)),
                    "step 6: the right token cannot be replayed");
            other.close();

            // 7. A page's WebAudio tone arrives as 48kHz stereo 20ms Opus packets on a steady capture clock.
            assertTrue(stream.hasAudio(), "step 7: the helper started the stream's Opus encoder");
            stream.focus();
            stream.input(InputEvent.click(300, 94));
            List<PageStream.Audio> packets = new ArrayList<>();
            long audioDeadline = Now.millis() + 10_000;
            while (packets.size() < 30 && Now.millis() < audioDeadline) {
                PageStream.Audio packet = paints.audio.poll(500, TimeUnit.MILLISECONDS);
                if (packet != null) packets.add(packet);
            }
            assertTrue(packets.size() >= 30, "step 7: audio packets arrived: " + packets.size());
            for (int index = packets.size() - 10; index < packets.size(); index++) {
                PageStream.Audio packet = packets.get(index);
                assertEquals(List.of(48000, 2, 960), List.of(packet.rate(), packet.channels(), packet.samples()),
                        "step 7: 48kHz stereo 20ms packets");
                assertTrue(packet.opus().remaining() > 0, "step 7: each packet carries Opus bytes");
                assertEquals(20_000L, packet.ptsUs() - packets.get(index - 1).ptsUs(),
                        "step 7: the capture clock advances 20ms per packet");
            }
            page.evaluate("audio.close(),1");

            // 8. Closing the stream keeps the view; closing the Page ends its next stream and leaves no socket file.
            stream.close();
            assertNull(paints.ended.get(5, TimeUnit.SECONDS), "step 8: the reader stopped without a failure");
            assertEquals(2L, ((Number) page.evaluate("1+1")).longValue(), "step 8: the view outlives its stream");
            Paints last = new Paints();
            PageStream reopened = page.stream(last);
            assertNotNull(next(last, reopened), "step 8: a reopened stream paints");
            page.close();
            assertTrue(reopened.isClosed(), "step 8: Page close ended its stream");
            assertNull(last.ended.get(5, TimeUnit.SECONDS), "step 8: no decoder error during teardown");
            try (var files = Files.list(socket.getParent())) {
                assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("ws-"))
                        .toList(), "step 8: no stream socket file is left behind");
            }
        } finally {
            jobs.shutdownNow();
            assertTrue(jobs.awaitTermination(5000), "integration wait job stopped");
        }
    }

    private static @NonNull SketermOptions options() throws IOException {
        // Short on purpose: the helper's socket path has roughly 107 bytes to live in.
        Path runtime = Files.createTempDirectory("sj-stream-");
        Path state = Path.of("build", "stream-it-state", Long.toString(ProcessHandle.current().pid()))
                .toAbsolutePath();
        Files.createDirectories(state);
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("SKETERM_")) env.put(key, null);
        }
        return SketermOptions.builder().binaryPath(binaries.resolve("sketerm").toString()).env(env)
                .extraArgs("--channel-name", "sketerm-java-it")
                .env("SKETERM_WEB_BIN", binaries.resolve("sketerm-webengine").toString())
                .env("SKETERM_MUX_BIN", binaries.resolve("sketerm-mux").toString())
                .env("SKETERM_MCP_WEB_GUI", "0").env("XDG_RUNTIME_DIR", runtime.toString())
                .env("XDG_STATE_HOME", state.toString()).env("XDG_CONFIG_HOME", state.resolve("config").toString())
                .defaultTimeout(Duration.ofSeconds(30)).build();
    }

    /** Completes `waitSent` once a web_wait request has gone out. */
    private static @NonNull SketermTransport observed(@NonNull SketermTransport delegate,
                                                       @NonNull CompletableFuture<Void> waitSent) {
        return new SketermTransport() {
            @Override public void send(@NonNull String message) {
                delegate.send(message);
                Map<String, Object> params = Json.optMap(Json.parseObject(message), "params");
                if (params != null && "web_wait".equals(params.get("name"))) waitSent.complete(null);
            }
            @Override public @Nullable String receive() { return delegate.receive(); }
            @Override public @NonNull String describeFailureContext() { return delegate.describeFailureContext(); }
            @Override public void close() { delegate.close(); }
        };
    }

    private static @NonNull ByteBuffer authFrame(@NonNull String token) {
        ByteBuffer frame = ByteBuffer.allocate(5 + token.length()).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(1 + token.length()).put((byte) 1).put(token.getBytes(StandardCharsets.US_ASCII));
        return frame.flip();
    }

    private static void repaint(@NonNull Page page, @NonNull String script) {
        assertEquals(1L, ((Number) page.evaluate("new Promise(resolve => { " + script + "; requestAnimationFrame("
                + "() => requestAnimationFrame(() => setTimeout(() => resolve(1), 80))) })",
                true, Duration.ofSeconds(5))).longValue(), "renderer completed a distinct paint turn");
    }

    private static void assertMatchesScreenshot(@NonNull Page page, @NonNull Painted frame, @NonNull String step)
            throws IOException {
        BufferedImage shot = ImageIO.read(new ByteArrayInputStream(page.screenshot().bytes()));
        assertEquals(frame.size().pixelWidth() + "x" + frame.size().pixelHeight(),
                shot.getWidth() + "x" + shot.getHeight(), step + ": screenshot and stream surface sizes");
        int mismatched = 0;
        String firstMismatch = "";
        for (int y = 0; y < shot.getHeight(); y++) {
            for (int x = 0; x < shot.getWidth(); x++) {
                if ((shot.getRGB(x, y) | 0xff000000) != frame.argb(x, y) && mismatched++ == 0) {
                    firstMismatch = x + "," + y + " screenshot " + Integer.toHexString(shot.getRGB(x, y))
                            + " stream " + Integer.toHexString(frame.argb(x, y));
                }
            }
        }
        assertEquals(0, mismatched, step + ": streamed pixels equal web_screenshot, first difference " + firstMismatch);
    }

    /** The next frame, acknowledged. */
    private static @NonNull Painted next(@NonNull Paints paints, @NonNull PageStream stream) throws Exception {
        Painted frame = paints.frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(frame, "expected a frame; stream failure=" + stream.failure());
        stream.ack(frame.serial());
        return frame;
    }

    /** Acknowledge frames until none arrives for 300ms; the newest frame with the union of their damage. */
    private static @NonNull Painted quiet(@NonNull Paints paints, @NonNull PageStream stream) throws Exception {
        List<Rect> damage = new ArrayList<>();
        long deadline = Now.millis() + WAIT.toMillis();
        Painted frame;
        while ((frame = paints.frames.poll(300, TimeUnit.MILLISECONDS)) != null) {
            stream.ack(frame.serial());
            damage.addAll(frame.damage());
            assertTrue(Now.millis() < deadline, "the page never stopped painting");
        }
        Painted latest = paints.latest;
        assertNotNull(latest, "no frame was ever streamed; failure=" + stream.failure());
        return new Painted(latest.serial(), latest.size(), damage, latest.pixels());
    }

    /** At least one frame, then {@link #quiet}. */
    private static @NonNull Painted settle(@NonNull Paints paints, @NonNull PageStream stream) throws Exception {
        List<Rect> damage = new ArrayList<>(next(paints, stream).damage());
        Painted rest = quiet(paints, stream);
        damage.addAll(rest.damage());
        return new Painted(rest.serial(), rest.size(), damage, rest.pixels());
    }

    private static @NonNull Painted until(@NonNull Paints paints, @NonNull PageStream stream,
                                          @NonNull Predicate<Painted> wanted, @NonNull String step) throws Exception {
        long deadline = Now.millis() + WAIT.toMillis();
        while (Now.millis() < deadline) {
            Painted frame = paints.frames.poll(Math.max(1, deadline - Now.millis()), TimeUnit.MILLISECONDS);
            if (frame == null) break;
            stream.ack(frame.serial());
            if (wanted.test(frame)) return frame;
        }
        throw new AssertionError(step + ": no such frame arrived; stream failure=" + stream.failure());
    }

    /** The first frame of one exact colour, left unacknowledged. */
    private static @NonNull Painted solid(@NonNull Paints paints, @NonNull PageStream stream, int r, int g, int b)
            throws Exception {
        long deadline = Now.millis() + WAIT.toMillis();
        while (Now.millis() < deadline) {
            Painted frame = paints.frames.poll(Math.max(1, deadline - Now.millis()), TimeUnit.MILLISECONDS);
            assertNotNull(frame, "expected a frame; stream failure=" + stream.failure());
            if (frame.solid(r, g, b)) return frame;
            stream.ack(frame.serial());
        }
        throw new AssertionError("No exact solid BGRA frame arrived; stream failure=" + stream.failure());
    }

    private static void assertBands(@NonNull Painted frame, @NonNull String step) {
        List<Rect> expected = new ArrayList<>();
        int rows = PageStream.MAX_DAMAGE_BYTES / (frame.size().pixelWidth() * 4);
        for (int y = 0; y < frame.size().pixelHeight(); y += rows) {
            expected.add(new Rect(0, y, frame.size().pixelWidth(), Math.min(rows, frame.size().pixelHeight() - y)));
        }
        assertEquals(expected, frame.damage(), step + ": exact full-surface damage bands");
    }

    private static void assertWithin(@NonNull Rect damage, @NonNull Rect bounds, @NonNull String step) {
        assertTrue(damage.width() > 0 && damage.x() >= bounds.x() && damage.y() >= bounds.y()
                && damage.x() + damage.width() <= bounds.x() + bounds.width()
                && damage.y() + damage.height() <= bounds.y() + bounds.height(),
                step + ": damage " + damage + " within " + bounds);
    }

    private record Rect(int x, int y, int width, int height) {
        @NonNull Rect unite(@NonNull Rect other) {
            if (this.width == 0 || this.height == 0) return other;
            int x0 = Math.min(this.x, other.x);
            int y0 = Math.min(this.y, other.y);
            return new Rect(x0, y0, Math.max(this.x + this.width, other.x + other.width) - x0,
                    Math.max(this.y + this.height, other.y + other.height) - y0);
        }
    }

    private record Painted(long serial, @NonNull SurfaceSize size, @NonNull List<Rect> damage,
                           byte @NonNull [] pixels) {
        boolean solid(int r, int g, int b) {
            for (int offset = 0; offset < this.pixels.length; offset += 4) {
                if ((this.pixels[offset] & 255) != b || (this.pixels[offset + 1] & 255) != g
                        || (this.pixels[offset + 2] & 255) != r || (this.pixels[offset + 3] & 255) != 255) return false;
            }
            return true;
        }

        @NonNull Rect union() {
            Rect union = new Rect(0, 0, 0, 0);
            for (Rect rect : this.damage) union = union.unite(rect);
            return union;
        }

        int argb(int x, int y) {
            int offset = (y * this.size.pixelWidth() + x) * 4;
            return (this.pixels[offset + 3] & 255) << 24 | (this.pixels[offset + 2] & 255) << 16
                    | (this.pixels[offset + 1] & 255) << 8 | this.pixels[offset] & 255;
        }
    }

    /** Composes damage into whole frames; audio packets are copied since their bytes are borrowed. */
    private static final class Paints implements Listener {
        final @NonNull LinkedBlockingQueue<Painted> frames = new LinkedBlockingQueue<>();
        final @NonNull LinkedBlockingQueue<PageStream.Audio> audio = new LinkedBlockingQueue<>();
        final @NonNull CompletableFuture<Throwable> ended = new CompletableFuture<>();
        volatile @Nullable Painted latest;
        private @Nullable SurfaceSize size;
        private byte @Nullable [] pixels;
        private final @NonNull List<Rect> damage = new ArrayList<>();

        @Override public void surfaceSize(@NonNull PageStream stream, @NonNull SurfaceSize size) {
            this.size = size;
            this.pixels = new byte[size.pixelWidth() * size.pixelHeight() * 4];
            this.damage.clear();
        }
        @Override public void damage(@NonNull PageStream stream, @NonNull Damage damage) {
            this.damage.add(new Rect(damage.x(), damage.y(), damage.width(), damage.height()));
            ByteBuffer bytes = damage.pixels();
            for (int row = 0; row < damage.height(); row++) {
                bytes.get(this.pixels, ((damage.y() + row) * this.size.pixelWidth() + damage.x()) * 4,
                        damage.width() * 4);
            }
        }
        @Override public void frameEnd(@NonNull PageStream stream, @NonNull FrameEnd frame) {
            Painted painted = new Painted(frame.serial(), this.size, List.copyOf(this.damage), this.pixels.clone());
            this.latest = painted;
            this.frames.add(painted);
            this.damage.clear();
        }
        @Override public void audio(@NonNull PageStream stream, PageStream.@NonNull Audio audio) {
            ByteBuffer opus = ByteBuffer.allocate(audio.opus().remaining()).put(audio.opus().duplicate()).flip();
            this.audio.add(new PageStream.Audio(audio.ptsUs(), audio.rate(), audio.channels(), audio.samples(), opus));
        }
        @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
            this.ended.complete(failure);
        }
    }

    private static final String JOURNEY_PAGE = "<!doctype html><html><head><title>stream</title><style>"
            + "html,body{margin:0;background:rgb(17,34,51)}"
            + "#inp{position:absolute;left:20px;top:20px;width:200px;height:24px;box-sizing:border-box;"
            + "border:1px solid rgb(0,0,0);padding:0;outline:none;font:16px monospace}"
            + "#hit{position:absolute;left:260px;top:20px;width:80px;height:28px;background:rgb(120,0,0)}"
            + "#tone{position:absolute;left:260px;top:80px;width:80px;height:28px}"
            + "#veil{display:none;position:fixed;left:0;top:0;width:100%;height:100%;z-index:10}"
            + "</style></head><body><input id=inp><div id=hit></div><button id=tone>Tone</button><div id=veil></div>"
            + "<script>window.ev=[];"
            + "for(const t of ['keydown','keyup','pointerdown','pointerup'])document.addEventListener(t,"
            + "e=>ev.push(e.type+':'+(e.key??e.button)+':'+e.isTrusted),true);"
            + "hit.addEventListener('pointerup',e=>{hit.style.background='rgb(0,255,0)';document.title='clicked'"
            + "+(e.shiftKey?' shift':'')+(e.isTrusted?' trusted':'')});"
            + "tone.addEventListener('click',e=>{if(!window.audio){window.audio=new AudioContext({sampleRate:48000});"
            + "const o=audio.createOscillator(),g=audio.createGain();g.gain.value=.25;o.frequency.value=440;"
            + "o.connect(g).connect(audio.destination);o.start();audio.resume()}});"
            + "</script></body></html>";

    private static final String DOCUMENT = "data:text/html,<html><head><title>stream</title></head>"
            + "<body style='margin:0;background:rgb(17,34,51)'></body></html>";
}
