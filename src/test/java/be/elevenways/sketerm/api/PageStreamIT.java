package be.elevenways.sketerm.api;

import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.sketerm.api.PageStream.Damage;
import be.elevenways.sketerm.api.PageStream.FrameEnd;
import be.elevenways.sketerm.api.PageStream.Listener;
import be.elevenways.sketerm.api.PageStream.SurfaceSize;
import be.elevenways.sketerm.api.SketermOptions.Builder;
import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.mcp.Content.Image;
import be.elevenways.sketerm.mcp.ToolResult;
import be.elevenways.sketerm.mcp.ToolException;
import be.elevenways.sketerm.process.SketermProcess;
import be.elevenways.sketerm.rpc.SketermTransport;
import be.elevenways.sketerm.rpc.StdioTransport;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fresh checkout binaries prove binary paints and socket input independently of a blocked MCP operation.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class PageStreamIT {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static @Nullable Path binaries;

    @BeforeAll
    static void copyFreshBinaries() throws IOException {
        Path source = PageApiIT.checkoutBinaries();
        for (String name : List.of("sketerm", "sketerm-webengine", "sketerm-mux")) {
            assumeTrue(Files.isExecutable(source.resolve(name)),
                    "fresh checkout binary is absent: " + source.resolve(name));
        }
        binaries = Path.of("build", "stream-it-bin").toAbsolutePath();
        Files.createDirectories(binaries);
        for (String name : List.of("sketerm", "sketerm-webengine", "sketerm-mux")) {
            copyExecutable(source.resolve(name), binaries.resolve(name));
        }
    }

    private static void copyExecutable(@NonNull Path source, @NonNull Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(target.toFile().setExecutable(true), "copied integration binary is executable: " + target);
        assertEquals(-1L, Files.mismatch(source, target), "the rig copied the exact fresh binary: " + target);
    }

    @Test
    @Timeout(240)
    @DisplayName("A real page streams exact pixels, takes socket input, stays bounded and cleans up")
    void streamingJourney() throws Exception {
        JobRunner jobs = JobRunner.create("sketerm-stream-it");
        SketermOptions options = options("journey").build();
        CompletableFuture<Void> waitSent = new CompletableFuture<>();
        CompletableFuture<WaitResult> waitResult = new CompletableFuture<>();
        Path html = binaries.resolveSibling("stream-it-journey.html");
        Files.writeString(html, JOURNEY_PAGE);
        Map<String, Object> numbers = new LinkedHashMap<>();
        try (SketermProcess process = SketermProcess.start(options.command(), options.workingDirectory(),
                options.environment()); Sketerm sketerm = Sketerm.connect(options,
                observed(new StdioTransport(process), waitSent))) {
            assertTrue(sketerm.browser().supportsStreams(), "step 1: fresh server advertises web_stream");
            Page page = sketerm.browser().openPage(html.toUri().toString(),
                    OpenOptions.ephemeralIdentity().withViewport(640, 480));
            long helper = helperPid(sketerm);
            Paints paints = new Paints();
            PageStream stream = page.stream(paints);

            // 1. The first frame is the whole surface in exact row bands, pixel-identical to web_screenshot.
            Painted first = next(paints, stream);
            assertEquals(new SurfaceSize(640, 480, 640, 480), first.size(), "step 1: scale-one surface");
            assertBands(first, "step 1");
            Painted settled = quiet(paints, stream);
            assertMatchesScreenshot(page, settled, "step 1");

            // 2. Socket input resolves an MCP wait that is still outstanding, and repaints only the clicked box.
            jobs.startThread(() -> {
                try { waitResult.complete(page.waitFor(WaitFor.TITLE, "clicked", Duration.ofSeconds(10))); }
                catch (Throwable failure) { waitResult.completeExceptionally(failure); }
            });
            waitSent.get(5, TimeUnit.SECONDS);
            assertFalse(waitResult.isDone(), "step 2: MCP wait is outstanding before input");
            stream.focus();
            stream.input(InputEvent.move(300, 34), InputEvent.down(300, 34, MouseButton.LEFT, InputModifier.SHIFT),
                    InputEvent.up(300, 34, MouseButton.LEFT, InputModifier.SHIFT));
            assertNotNull(waitResult.get(15, TimeUnit.SECONDS), "step 2: binary input resolved the MCP wait");
            assertEquals("clicked shift trusted", page.evaluate("document.title"),
                    "step 2: CEF flags and trusted edges");
            Painted clicked = settle(paints, stream);
            assertWithin(clicked.union(), new Rect(258, 18, 84, 32), "step 2: only the hit box repainted");
            assertEquals(0xff00ff00, clicked.argb(300, 34), "step 2: the hit box turned green");

            // 3. Typing into a field: each key repaints only the field, quickly, and the page holds the text.
            stream.input(InputEvent.click(60, 32));
            settle(paints, stream);
            List<Long> latencies = new ArrayList<>();
            for (KeyCode code : List.of(KeyCode.KEY_A, KeyCode.KEY_B, KeyCode.KEY_C)) {
                long sent = Now.millis();
                stream.key(KeyAction.PRESS, code);
                Painted typed = next(paints, stream);
                latencies.add(typed.end() - sent);
                assertWithin(typed.union(), new Rect(18, 18, 206, 30), "step 3: a key repaints only the field");
                quiet(paints, stream);
            }
            long sent = Now.millis();
            stream.input(InputEvent.text("de"));
            Painted texted = next(paints, stream);
            latencies.add(texted.end() - sent);
            assertWithin(texted.union(), new Rect(18, 18, 206, 30), "step 3: text repaints only the field");
            quiet(paints, stream);
            assertEquals("abcde", page.evaluate("inp.value"), "step 3: the page holds what the socket typed");
            numbers.put("input_to_damage_ms", latencies);
            assertTrue(median(latencies) < 100, "step 3: input reaches the screen within 100ms: " + latencies);
            page.evaluate("inp.blur(),1");
            settle(paints, stream);

            // 4. A wheel over a tall page scrolls it, and the streamed frame matches the scrolled screenshot.
            page.evaluate("tall.style.display='block',1");
            settle(paints, stream);
            stream.input(InputEvent.wheel(320, 240, 0, 600));
            settled = settle(paints, stream);
            long scrolled = ((Number) page.evaluate("scrollY")).longValue();
            assertTrue(scrolled > 0, "step 4: the wheel scrolled the page, scrollY=" + scrolled);
            assertMatchesScreenshot(page, settled, "step 4");
            page.evaluate("scrollTo(0,0),tall.style.display='none',1");
            settled = settle(paints, stream);

            // 5. A select's native popup is composed into the stream and leaves exactly the page behind on Escape.
            Rect under = new Rect(20, 108, 150, 90);
            byte[] baseline = settled.region(under);
            stream.input(InputEvent.click(60, 90));
            Painted open = until(paints, stream, frame -> differing(frame.region(under), baseline) > 1000,
                    "step 5: the popup appears below the select");
            assertTrue(open.union().height() > 0, "step 5: popup damage was streamed");
            stream.key(KeyAction.PRESS, KeyCode.ESCAPE);
            until(paints, stream, frame -> differing(frame.region(under), baseline) == 0,
                    "step 5: Escape restores the exact underlay");
            quiet(paints, stream);

            // 6. A custom cursor arrives as premultiplied BGRA with its hotspot; leaving it restores a named one.
            paints.cursors.clear();
            stream.input(InputEvent.move(430, 50));
            PageStream.Cursor custom = paints.cursors.poll(5, TimeUnit.SECONDS);
            assertNotNull(custom, "step 6: entering the box sends its cursor");
            assertEquals(List.of(16, 16, 2, 3), List.of(custom.width(), custom.height(), custom.hotspotX(),
                    custom.hotspotY()), "step 6: cursor size and hotspot");
            byte[] pixel = new byte[4];
            custom.image().get(0, pixel);
            assertEquals(List.of(0, 0, 128, 128), List.of(pixel[0] & 255, pixel[1] & 255, pixel[2] & 255,
                    pixel[3] & 255), "step 6: rgba(255,0,0,0.5) arrives premultiplied as BGRA 0,0,128,128");
            stream.input(InputEvent.move(600, 400));
            PageStream.Cursor named = paints.cursors.poll(5, TimeUnit.SECONDS);
            assertNotNull(named, "step 6: leaving the box sends a cursor again");
            assertEquals("default", named.name(), "step 6: back to the default cursor");

            // 7. An idle page streams nothing and costs the helper almost no CPU.
            quiet(paints, stream);
            long ticks = cpuTicks(helper);
            assertNull(paints.frames.poll(1500, TimeUnit.MILLISECONDS), "step 7: a still page sends no frame");
            long idle = cpuTicks(helper) - ticks;
            numbers.put("idle_helper_cpu_ticks_per_1500ms", idle);
            assertTrue(idle < 30, "step 7: the helper does not spin while idle: " + idle + " ticks");

            // 8. A CSS animation streams at a usable rate with damage limited to the moving box.
            paints.damageBytes.set(0);
            page.evaluate("anim.classList.add('spin'),1");
            int animated = 0;
            Rect animUnion = new Rect(0, 0, 0, 0);
            long start = Now.millis();
            while (Now.millis() - start < 3000) {
                Painted frame = paints.frames.poll(1000, TimeUnit.MILLISECONDS);
                assertNotNull(frame, "step 8: the animation keeps painting");
                stream.ack(frame.serial());
                animated++;
                animUnion = animUnion.unite(frame.union());
            }
            double seconds = (Now.millis() - start) / 1000.0;
            page.evaluate("anim.classList.remove('spin'),1");
            quiet(paints, stream);
            numbers.put("animation_fps", Math.round(animated / seconds));
            numbers.put("animation_kib_per_s", Math.round(paints.damageBytes.get() / seconds / 1024));
            assertTrue(animated / seconds > 20, "step 8: at least 20 frames per second: " + numbers);
            assertTrue(animated / seconds >= 45, "step 8: the default CEF cap produces near 60 FPS: " + numbers);
            assertTrue(animated / seconds <= 70, "step 8: the headless default caps rendering near 60 FPS: " + numbers);
            assertWithin(animUnion, new Rect(398, 298, 154, 54), "step 8: damage covers only the moving box");

            // 9. Two unacknowledged frames fill the window; a stalled reader costs bounded memory and then
            // receives the newest pixels, never the backlog.
            repaint(page, "veil.style.display='block',veil.style.background='rgb(255,0,0)'");
            solid(paints, stream, 255, 0, 0);
            repaint(page, "veil.style.background='rgb(0,0,255)'");
            Painted blue = solid(paints, stream, 0, 0, 255);
            long rss = rssKib(helper);
            long peak = rss;
            page.evaluate("window.hue=0,window.iv=setInterval(()=>{hue=(hue+7)%360;"
                    + "veil.style.background='hsl('+hue+',80%,50%)'},5),1");
            for (int sample = 0; sample < 6; sample++) {
                assertNull(paints.frames.poll(500, TimeUnit.MILLISECONDS), "step 9: no third frame without an ACK");
                peak = Math.max(peak, rssKib(helper));
            }
            repaint(page, "clearInterval(iv),veil.style.background='rgb(255,255,0)'");
            numbers.put("stalled_helper_rss_growth_kib", peak - rss);
            assertTrue(peak - rss < 64 * 1024, "step 9: a stalled reader does not grow the helper: "
                    + (peak - rss) + " KiB");
            stream.ack(blue.serial());
            Painted merged = paints.frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(merged, "step 9: the ACK opens the window again");
            assertTrue(Long.compareUnsigned(merged.serial(), blue.serial()) > 0, "step 9: a newer merged frame");
            assertTrue(merged.solid(255, 255, 0), "step 9: the first frame after the stall is the newest yellow");
            stream.ack(merged.serial());
            assertNull(paints.frames.poll(300, TimeUnit.MILLISECONDS), "step 9: the stalled paints were not queued");
            repaint(page, "veil.style.display='none'");
            quiet(paints, stream);

            // 10. Resize goes out on the socket: a new SURFACE, a whole-surface frame, the right pixels.
            stream.resize(480, 360);
            Painted resized = until(paints, stream, frame -> frame.size().pixelWidth() == 480,
                    "step 10: a frame of the new surface");
            assertEquals(new SurfaceSize(480, 360, 480, 360), resized.size(), "step 10: resized logical surface");
            assertBands(resized, "step 10");
            assertEquals(480L, ((Number) page.evaluate("innerWidth")).longValue(), "step 10: the page relaid out");
            assertMatchesScreenshot(page, quiet(paints, stream), "step 10");

            // 11. A blur lets go of a key and a button the client still holds, as trusted page events.
            page.evaluate("ev.length=0,1");
            stream.focus();
            stream.input(InputEvent.keyDown("Shift"), InputEvent.move(400, 300),
                    InputEvent.down(400, 300, MouseButton.LEFT));
            stream.blur();
            Object released = page.evaluate("new Promise(r=>{const t=()=>ev.includes('keyup:Shift:true')"
                    + "&&ev.includes('pointerup:0:true')?r(ev.join(' ')):setTimeout(t,20);t()})", true,
                    Duration.ofSeconds(5));
            assertTrue(String.valueOf(released).contains("keydown:Shift:true"), "step 11: the key went down first");
            quiet(paints, stream);

            // 12. One stream per view; a wrong token spends the socket, so the right one cannot be replayed.
            assertThrows(ConflictException.class, () -> page.stream(new Paints()),
                    "step 12: a second stream on a streaming view is a conflict");
            Page other = sketerm.browser().openPage(DOCUMENT, OpenOptions.ephemeralIdentity().withViewport(320, 240));
            Map<String, Object> offer = sketerm.session().callToolOrThrow("web_stream",
                    Map.of("pane", other.handle())).structured();
            Path socket = Path.of(Json.str(offer, "socket_path"));
            String token = Json.str(offer, "token");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)),
                    "step 12: the socket is private to its owner");
            try (SocketChannel wrong = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                wrong.write(authFrame("f".repeat(32)));
                assertEquals(-1, wrong.read(ByteBuffer.allocate(64)), "step 12: a wrong token is hung up on");
            }
            assertFalse(Files.exists(socket), "step 12: the spent socket is gone");
            assertThrows(IOException.class, () -> SocketChannel.open(UnixDomainSocketAddress.of(socket)),
                    "step 12: the right token cannot be replayed");
            Paints others = new Paints();
            try (PageStream retry = other.stream(others)) {
                retry.ack(solid(others, retry, 17, 34, 51).serial());
            }
            other.close();

            // 13. A page's WebAudio tone arrives as decodable, non-silent 20ms Opus with a steady clock.
            assertTrue(stream.hasAudio(), "step 13: this helper negotiated stream audio");
            stream.focus();
            stream.input(InputEvent.click(300, 94));
            List<Captured> packets = new ArrayList<>();
            long audioDeadline = Now.millis() + 10_000;
            while (packets.size() < 60 && Now.millis() < audioDeadline) {
                Captured packet = paints.audio.poll(500, TimeUnit.MILLISECONDS);
                if (packet != null) packets.add(packet);
            }
            assertTrue(packets.size() >= 60, "step 13: audio packets arrived: " + packets.size());
            Captured last = packets.getLast();
            assertEquals(List.of(48000, 2, 960), List.of(last.rate(), last.channels(), last.samples()),
                    "step 13: 48kHz stereo 20ms packets");
            for (int index = packets.size() - 20; index < packets.size(); index++) {
                assertEquals(20_000L, packets.get(index).ptsUs() - packets.get(index - 1).ptsUs(),
                        "step 13: capture clock advances 20ms per packet");
            }
            double rms = OpusDecoder.rms(packets.subList(packets.size() - 25, packets.size()));
            numbers.put("tone_rms", Math.round(rms));
            assertTrue(rms > 2000, "step 13: the decoded tone is audible, rms=" + rms);
            page.evaluate("audio.close(),1");

            // 14. Closing the stream keeps the view; repeated streams leak no helper descriptors; closing the
            // Page ends its stream and every socket file is gone.
            stream.close();
            assertNull(paints.ended.get(5, TimeUnit.SECONDS), "step 14: the reader stopped without a failure");
            assertEquals(2L, ((Number) page.evaluate("1+1")).longValue(), "step 14: the view outlives its stream");
            long descriptors = descriptors(helper);
            for (int round = 0; round < 8; round++) {
                Paints again = new Paints();
                try (PageStream reopened = page.stream(again)) {
                    assertNotNull(next(again, reopened), "step 14: reopened stream " + round + " paints");
                    reopened.input(InputEvent.move(10, 10));
                }
                assertNull(again.ended.get(5, TimeUnit.SECONDS), "step 14: reopened reader stopped cleanly");
            }
            assertTrue(descriptors(helper) <= descriptors + 2, "step 14: helper descriptors stay flat: "
                    + descriptors + " -> " + descriptors(helper));
            Paints final_ = new Paints();
            PageStream last_ = page.stream(final_);
            assertNotNull(next(final_, last_), "step 14: the final stream paints");
            page.close();
            assertTrue(last_.isClosed(), "step 14: Page closed its owned socket");
            assertNull(final_.ended.get(5, TimeUnit.SECONDS), "step 14: no decoder error during teardown");
            try (var files = Files.walk(Path.of(Json.str(offer, "socket_path")).getParent())) {
                assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("ws-"))
                        .toList(), "step 14: no stream socket file is left behind");
            }
            System.out.println("page-stream-journey-numbers " + Json.write(numbers));
        } finally {
            jobs.shutdownNow();
            assertTrue(jobs.awaitTermination(5000), "integration wait job stopped");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Headless helpers force CPU rendering despite inherited GPU preferences")
    void headlessHelpersForceCpuDespiteInheritedGpuPreferences(boolean broker) throws Exception {
        Builder options = options("cpu").env("SKETERM_WEB_GPU", "1").env("SKETERM_WEB_OZONE", "wayland")
                .env("SKETERM_WEB_SESSION", "0");
        if (broker) options.instanceName("sj-" + ProcessHandle.current().pid() + "-cpu");
        try (Sketerm sketerm = Sketerm.launch(options.build())) {
            Page page = sketerm.browser().openPage(DOCUMENT, OpenOptions.ephemeralIdentity().withViewport(320, 240));

            // 1. Prove ownership and the child's CPU overrides before using either pixel API.
            Map<String, Object> capabilities = sketerm.session().callToolOrThrow("capabilities", Map.of()).structured();
            assertEquals(broker ? "broker" : "self", Json.str(capabilities, "web_engine_owner"),
                    "step 1: the requested launch path");
            long helperPid = helperPid(sketerm);
            List<String> childEnvironment = List.of(new String(Files.readAllBytes(
                    Path.of("/proc", Long.toString(helperPid), "environ")), StandardCharsets.UTF_8).split("\u0000"));
            assertTrue(childEnvironment.contains("SKETERM_WEB_GPU=0"), "step 1: override inherited GPU=1");
            assertTrue(childEnvironment.contains("SKETERM_WEB_OZONE=headless"),
                    "step 1: override inherited OZONE=wayland");

            // 2. Legacy first-frame readiness must work before opening a stream, without a warm-up.
            Frame before = page.frame(0, FrameFormat.PNG, null, null, Duration.ofSeconds(5));
            assertFalse(before.unchanged(), "step 2: the helper delivered a painted software frame");
            assertTrue(before.bytes().length > 0, "step 2: the legacy frame contains pixels");

            // 3. The independent binary source also opens and yields the exact fixture pixels.
            Paints paints = new Paints();
            try (PageStream stream = page.stream(paints)) {
                Painted initial = solid(paints, stream, 17, 34, 51);
                assertEquals(new SurfaceSize(320, 240, 320, 240), initial.size());
                stream.ack(initial.serial());
            }
            page.close();
        }
    }

    @Test
    @DisplayName("Legacy and binary streams observe the same initial paint and subsequent damage")
    void legacyFramesObservePaintBeforeAndAfterBinaryStreaming() throws Exception {
        try (Sketerm sketerm = Sketerm.launch(options("legacy").build())) {
            Page page = sketerm.browser().openPage(DOCUMENT, OpenOptions.ephemeralIdentity().withViewport(320, 240));
            Map<String, Object> arguments = new LinkedHashMap<>(Map.of("pane", page.handle(), "since", 0,
                    "format", "png", "timeout_ms", 5000));

            // 1. Preserve the immediate legacy result, before any stream or paint-inducing action.
            ToolResult before = sketerm.session().callTool("web_frame", arguments);
            assertNotNull(before.structured(), "step 1: legacy frame result carries structured facts");

            // 2. The independent binary source must contain the actual initial pixels on the SAME Page.
            Paints paints = new Paints();
            try (PageStream stream = page.stream(paints)) {
                Painted initial = solid(paints, stream, 17, 34, 51);
                stream.ack(initial.serial());

                // 3. Prove a distinct engine paint by reading every mutated pixel from the binary source.
                paint(page, "rgb(85,102,119)");
                Painted changed = solid(paints, stream, 85, 102, 119);
                stream.ack(changed.serial());
                arguments.put("since", Json.longVal(before.structured(), "frame"));
                ToolResult after = sketerm.session().callTool("web_frame", arguments);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("server_version", sketerm.serverVersion());
                evidence.put("binary_directory", binaries.toString());
                evidence.put("before", before.structured());
                evidence.put("before_error", before.isError());
                evidence.put("binary_initial", Map.of("serial", initial.serial(), "size", initial.size().toString(),
                        "solid_rgb", "17,34,51"));
                evidence.put("binary_mutation", Map.of("serial", changed.serial(), "size", changed.size().toString(),
                        "solid_rgb", "85,102,119", "damage", changed.damage().toString()));
                evidence.put("after", after.structured());
                evidence.put("after_error", after.isError());
                if (after.isError() || Json.optBool(after.structured(), "unchanged", true)) {
                    ToolResult screenshot = sketerm.session().callTool("web_screenshot",
                            Map.of("pane", page.handle(), "timeout_ms", 1000));
                    evidence.put("screenshot_after", screenshot.structured());
                    evidence.put("screenshot_after_error", screenshot.isError());
                    BufferedImage sample = decodePicture(screenshot);
                    if (sample != null) {
                        evidence.put("screenshot_size", sample.getWidth() + "x" + sample.getHeight());
                        evidence.put("screenshot_argb_0_0", Integer.toUnsignedString(sample.getRGB(0, 0), 16));
                    }
                    evidence.put("document_after", page.evaluate("({readyState:document.readyState,"
                            + "visibility:document.visibilityState,width:innerWidth,height:innerHeight,"
                            + "background:document.body.style.background})"));
                }
                String facts = Json.write(evidence);

                // 4. Neither opening a stream nor waiting longer is accepted as a repair of the initial result.
                assertFalse(after.isError(), "step 4: legacy post-damage request succeeds: " + facts);
                assertNotNull(after.structured(), "step 4: post-damage structured facts: " + facts);
                assertFalse(Json.optBool(after.structured(), "unchanged", true),
                        "step 4: legacy source must observe the proven binary damage: " + facts);
                BufferedImage pixels = decodePicture(after);
                assertNotNull(pixels, "step 4: legacy PNG decodes: " + facts);
                assertEquals(changed.size().pixelWidth(), pixels.getWidth());
                assertEquals(changed.size().pixelHeight(), pixels.getHeight());
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        assertEquals(0xff556677, pixels.getRGB(x, y), "step 4: newest legacy pixel at " + x + "," + y);
                    }
                }
                assertFalse(before.isError(), "step 1: initial legacy request succeeds: " + facts);
                assertFalse(Json.optBool(before.structured(), "unchanged", true),
                        "step 1: initial legacy paint was available before streaming: " + facts);
            } catch (SketermApiException failure) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("before", before.structured());
                evidence.put("stream_error", failure.getCause() instanceof ToolException tool
                        ? tool.getResult().structured() : failure.getMessage());
                evidence.put("capabilities", sketerm.session().callTool("capabilities", Map.of()).structured());
                evidence.put("mcp_stderr", sketerm.process().getStderrTail());
                System.out.println("legacy-stream-refusal-diagnostic " + Json.write(evidence));
                throw failure;
            }
            page.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(120)
    void frameRateCapsArePerViewAndConfigDefaultsPropagate(boolean broker) throws Exception {
        Builder builder = options("fps-" + broker).env("SKETERM_WEB_SESSION", "0");
        if (broker) builder.instanceName("sj-" + ProcessHandle.current().pid() + "-fps");
        SketermOptions configured = builder.build();
        Path config = Path.of(configured.environment().get("XDG_CONFIG_HOME"), "sketerm", "config.conf");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[mcp]\nweb_max_fps = 30\n[mcp.slow]\nweb_max_fps = 15\n");
        Path html = binaries.resolveSibling("stream-it-fps.html");
        Files.writeString(html, JOURNEY_PAGE);
        Map<String, Object> rates = new LinkedHashMap<>();
        try (Sketerm sketerm = Sketerm.launch(configured)) {
            Browser browser = sketerm.browser();
            assertTrue(browser.supportsMaxFps(), "step 1: explicit cap support is discoverable before startup");
            for (var tool : sketerm.session().listTools()) {
                if (!List.of("web_open", "web_stream").contains(tool.name())) continue;
                Map<String, Object> properties = Json.optMap(tool.inputSchema(), "properties");
                assertEquals(OpenOptions.MAX_FPS, Json.intVal(Json.optMap(properties, "max_fps"), "maximum"),
                        "step 1: Java's range matches the declaring MCP schema for " + tool.name());
            }
            assertEquals(30, Json.intVal(sketerm.session().callToolOrThrow("capabilities", Map.of()).structured(),
                    "web_default_max_fps"), "step 1: config default is resolved before startup");
            List<Page> pages = new ArrayList<>();
            for (int fps : List.of(15, 30, 60)) {
                Page page = browser.openPage(html.toUri().toString(),
                        OpenOptions.ephemeralIdentity().withViewport(640, 480).withMaxFps(fps));
                page.evaluate("anim.classList.add('spin'),1");
                pages.add(page);
            }
            // 2. Three live animated views retain independent caps on one helper.
            for (int index = 0; index < pages.size(); index++) {
                int fps = List.of(15, 30, 60).get(index);
                rates.put("open_" + fps, measureFps(pages.get(index), null, fps));
            }
            // 3. A stream override persists on the view after the stream closes.
            rates.put("stream_override_15", measureFps(pages.get(2), 15, 15));
            rates.put("stream_reopened_15", measureFps(pages.get(2), null, 15));
            for (Page page : pages) page.close();
            Page defaults = browser.openPage(html.toUri().toString(),
                    OpenOptions.ephemeralIdentity().withViewport(640, 480));
            defaults.evaluate("anim.classList.add('spin'),1");
            rates.put("config_default_30", measureFps(defaults, null, 30));
            defaults.close();
        }
        // 4. A named MCP profile overrides the bare config value, without changing GUI settings.
        try (Sketerm sketerm = Sketerm.launch(builder.extraArgs("--profile", "slow").build())) {
            assertEquals(15, Json.intVal(sketerm.session().callToolOrThrow("capabilities", Map.of()).structured(),
                    "web_default_max_fps"));
            Page page = sketerm.browser().openPage(html.toUri().toString(),
                    OpenOptions.ephemeralIdentity().withViewport(640, 480));
            page.evaluate("anim.classList.add('spin'),1");
            rates.put("profile_default_15", measureFps(page, null, 15));
            page.close();
        }
        System.out.println("page-stream-fps-numbers " + Json.write(Map.of("broker", broker, "rates", rates)));
    }

    private static double measureFps(@NonNull Page page, @Nullable Integer override, int expected) throws Exception {
        Paints paints = new Paints();
        try (PageStream stream = page.stream(paints, override)) {
            next(paints, stream);
            long warm = Now.millis() + 300;
            while (Now.millis() < warm) next(paints, stream);
            int count = 0;
            long start = Now.millis();
            while (Now.millis() - start < 2000) {
                next(paints, stream);
                count++;
            }
            double fps = count * 1000.0 / (Now.millis() - start);
            assertTrue(fps >= expected * 0.75 && fps <= expected * 1.2,
                    "measured " + fps + " FPS for the per-view CEF cap " + expected);
            return fps;
        }
    }

    @Test
    void sketermShutdownOwnsUnclosedPageStreams() throws Exception {
        try (Sketerm sketerm = Sketerm.launch(options("shutdown").build())) {
            Page page = sketerm.browser().openPage(DOCUMENT, OpenOptions.ephemeralIdentity().withViewport(320, 240));
            Paints paints = new Paints();
            PageStream stream = page.stream(paints);
            Painted first = solid(paints, stream, 17, 34, 51);
            stream.ack(first.serial());
            sketerm.close();
            assertTrue(stream.isClosed(), "Sketerm closes a stream even when its Page was not closed");
            assertNull(paints.ended.get(5, TimeUnit.SECONDS), "reader stopped without a protocol failure");
        }
    }

    private static @NonNull Builder options(@NonNull String name) throws IOException {
        assertNotNull(binaries);
        Path runtime = Path.of("/tmp", "sj-stream-" + ProcessHandle.current().pid() + "-" + name);
        Path state = Path.of("build", "stream-it-state", ProcessHandle.current().pid() + "-" + name).toAbsolutePath();
        Files.createDirectories(runtime);
        Files.createDirectories(state);
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("SKETERM_")) env.put(key, null);
        }
        return SketermOptions.builder().binaryPath(binaries.resolve("sketerm").toString()).env(env)
                .env("SKETERM_WEB_BIN", binaries.resolve("sketerm-webengine").toString())
                .env("SKETERM_MUX_BIN", binaries.resolve("sketerm-mux").toString())
                .env("SKETERM_WEB_GPU", "0").env("SKETERM_WEB_OZONE", "headless")
                .env("SKETERM_MCP_WEB_GUI", "0").env("XDG_RUNTIME_DIR", runtime.toString())
                .env("XDG_STATE_HOME", state.toString()).env("XDG_CONFIG_HOME", state.resolve("config").toString())
                .defaultTimeout(Duration.ofSeconds(30));
    }

    private static @NonNull SketermTransport observed(@NonNull SketermTransport delegate,
                                                       @NonNull CompletableFuture<Void> waitSent) {
        return new SketermTransport() {
            @Override public void send(@NonNull String message) {
                delegate.send(message);
                Map<String, Object> request = Json.parseObject(message);
                Map<String, Object> params = Json.optMap(request, "params");
                if (params != null && "web_wait".equals(params.get("name"))) waitSent.complete(null);
            }
            @Override public @Nullable String receive() { return delegate.receive(); }
            @Override public @NonNull String describeFailureContext() { return delegate.describeFailureContext(); }
            @Override public void close() { delegate.close(); }
        };
    }

    private static long helperPid(@NonNull Sketerm sketerm) throws IOException {
        Map<String, Object> capabilities = sketerm.session().callToolOrThrow("capabilities", Map.of()).structured();
        Path presencePath = Path.of(Json.str(capabilities, "web_socket")).resolveSibling("web.json");
        long pid = Json.longVal(Json.parseObject(Files.readString(presencePath)), "helper_pid");
        assertTrue(pid > 0, "the helper pid was published");
        return pid;
    }

    private static long cpuTicks(long pid) throws IOException {
        String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
    }

    private static long rssKib(long pid) throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"))) {
            if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
        }
        throw new AssertionError("no VmRSS for helper " + pid);
    }

    private static long descriptors(long pid) throws IOException {
        try (var entries = Files.list(Path.of("/proc", Long.toString(pid), "fd"))) {
            return entries.count();
        }
    }

    private static long median(@NonNull List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static @NonNull ByteBuffer authFrame(@NonNull String token) {
        ByteBuffer frame = ByteBuffer.allocate(5 + token.length()).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(1 + token.length()).put((byte) 1).put(token.getBytes(StandardCharsets.US_ASCII));
        return frame.flip();
    }

    private static void paint(@NonNull Page page, @NonNull String color) {
        repaint(page, "document.body.style.background='" + color + "'");
    }

    private static void repaint(@NonNull Page page, @NonNull String script) {
        assertEquals(1L, ((Number) page.evaluate("new Promise(resolve => { " + script + "; requestAnimationFrame("
                + "() => requestAnimationFrame(() => setTimeout(() => resolve(1), 80))) })",
                true, Duration.ofSeconds(5))).longValue(), "renderer completed a distinct paint turn");
    }

    private static @Nullable BufferedImage decodePicture(@NonNull ToolResult result) throws IOException {
        for (var content : result.content()) {
            if (content instanceof Image image) {
                return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.data())));
            }
        }
        return null;
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
                if ((shot.getRGB(x, y) | 0xff000000) != frame.argb(x, y)) {
                    if (mismatched++ == 0) firstMismatch = x + "," + y + " screenshot "
                            + Integer.toHexString(shot.getRGB(x, y)) + " stream " + Integer.toHexString(frame.argb(x, y));
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

    /** Acknowledge frames until none arrives for 300ms; the newest frame carrying the union of their damage. */
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
        return new Painted(latest.serial(), latest.size(), damage, latest.pixels(), latest.end());
    }

    /** At least one frame, then {@link #quiet}. */
    private static @NonNull Painted settle(@NonNull Paints paints, @NonNull PageStream stream) throws Exception {
        Painted first = next(paints, stream);
        Painted rest = quiet(paints, stream);
        List<Rect> damage = new ArrayList<>(first.damage());
        damage.addAll(rest.damage());
        return new Painted(rest.serial(), rest.size(), damage, rest.pixels(), rest.end());
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

    private static int differing(byte @NonNull [] a, byte @NonNull [] b) {
        int count = 0;
        for (int offset = 0; offset < a.length; offset += 4) {
            if (a[offset] != b[offset] || a[offset + 1] != b[offset + 1] || a[offset + 2] != b[offset + 2]
                    || a[offset + 3] != b[offset + 3]) count++;
        }
        return count;
    }

    private record Rect(int x, int y, int width, int height) {
        @NonNull Rect unite(@NonNull Rect other) {
            if (other.width == 0 || other.height == 0) return this;
            if (this.width == 0 || this.height == 0) return other;
            int x0 = Math.min(this.x, other.x);
            int y0 = Math.min(this.y, other.y);
            int x1 = Math.max(this.x + this.width, other.x + other.width);
            int y1 = Math.max(this.y + this.height, other.y + other.height);
            return new Rect(x0, y0, x1 - x0, y1 - y0);
        }
    }

    private record Painted(long serial, @NonNull SurfaceSize size, @NonNull List<Rect> damage,
                           byte @NonNull [] pixels, long end) {
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

        byte @NonNull [] region(@NonNull Rect rect) {
            byte[] out = new byte[rect.width() * rect.height() * 4];
            for (int row = 0; row < rect.height(); row++) {
                System.arraycopy(this.pixels, ((rect.y() + row) * this.size.pixelWidth() + rect.x()) * 4, out,
                        row * rect.width() * 4, rect.width() * 4);
            }
            return out;
        }
    }

    private record Captured(long ptsUs, int rate, int channels, int samples, byte @NonNull [] opus) {}

    private static final class Paints implements Listener {
        final @NonNull LinkedBlockingQueue<Painted> frames = new LinkedBlockingQueue<>();
        final @NonNull LinkedBlockingQueue<PageStream.Cursor> cursors = new LinkedBlockingQueue<>();
        final @NonNull LinkedBlockingQueue<Captured> audio = new LinkedBlockingQueue<>();
        final @NonNull CompletableFuture<Throwable> ended = new CompletableFuture<>();
        final @NonNull AtomicLong damageBytes = new AtomicLong();
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
            this.damageBytes.addAndGet(damage.pixels().remaining());
            ByteBuffer bytes = damage.pixels();
            for (int row = 0; row < damage.height(); row++) {
                bytes.get(this.pixels, ((damage.y() + row) * this.size.pixelWidth() + damage.x()) * 4,
                        damage.width() * 4);
            }
        }
        @Override public void frameEnd(@NonNull PageStream stream, @NonNull FrameEnd frame) {
            Painted painted = new Painted(frame.serial(), this.size, List.copyOf(this.damage), this.pixels.clone(),
                    Now.millis());
            this.latest = painted;
            this.frames.add(painted);
            this.damage.clear();
        }
        @Override public void cursor(@NonNull PageStream stream, PageStream.@NonNull Cursor cursor) {
            ByteBuffer image = null;
            if (cursor.image() != null) {
                image = ByteBuffer.allocate(cursor.image().remaining());
                image.put(cursor.image().duplicate()).flip();
            }
            this.cursors.add(new PageStream.Cursor(cursor.visible(), cursor.name(), cursor.width(), cursor.height(),
                    cursor.hotspotX(), cursor.hotspotY(), image));
        }
        @Override public void audio(@NonNull PageStream stream, PageStream.@NonNull Audio audio) {
            byte[] opus = new byte[audio.opus().remaining()];
            audio.opus().duplicate().get(opus);
            this.audio.add(new Captured(audio.ptsUs(), audio.rate(), audio.channels(), audio.samples(), opus));
        }
        @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
            this.ended.complete(failure);
        }
    }

    /** Decodes captured packets with the system libopus, the reference decoder. */
    private static final class OpusDecoder {
        static double rms(@NonNull List<Captured> packets) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup opus = SymbolLookup.libraryLookup("libopus.so.0", arena);
                Linker linker = Linker.nativeLinker();
                MethodHandle create = linker.downcallHandle(opus.findOrThrow("opus_decoder_create"),
                        FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
                MethodHandle decode = linker.downcallHandle(opus.findOrThrow("opus_decode"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
                MethodHandle destroy = linker.downcallHandle(opus.findOrThrow("opus_decoder_destroy"),
                        FunctionDescriptor.ofVoid(ADDRESS));
                MemorySegment error = arena.allocate(JAVA_INT);
                MemorySegment decoder = (MemorySegment) create.invokeExact(48000, 2, error);
                assertEquals(0, error.get(JAVA_INT, 0), "libopus created a decoder");
                try {
                    MemorySegment pcm = arena.allocate(JAVA_SHORT, 5760 * 2);
                    double sum = 0;
                    long count = 0;
                    for (Captured packet : packets) {
                        MemorySegment data = arena.allocateFrom(JAVA_BYTE, packet.opus());
                        int samples = (int) decode.invokeExact(decoder, data, packet.opus().length, pcm, 5760, 0);
                        assertEquals(packet.samples(), samples, "the packet decodes to its declared sample count");
                        for (int index = 0; index < samples * 2; index++) {
                            double value = pcm.getAtIndex(JAVA_SHORT, index);
                            sum += value * value;
                            count++;
                        }
                    }
                    return Math.sqrt(sum / count);
                } finally {
                    destroy.invokeExact(decoder);
                }
            } catch (Throwable failure) {
                if (failure instanceof AssertionError assertion) throw assertion;
                throw new AssertionError("libopus could not decode the stream's packets", failure);
            }
        }
    }

    private static final String CURSOR_PNG = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR4nGP4z8DQQAlmGDVg1IBRA4a"
            + "LAQDoJn8Qy7CZ9QAAAABJRU5ErkJggg==";

    private static final String JOURNEY_PAGE = "<!doctype html><html><head><title>stream</title><style>"
            + "html,body{margin:0;background:rgb(17,34,51)}"
            + "#inp{position:absolute;left:20px;top:20px;width:200px;height:24px;box-sizing:border-box;"
            + "border:1px solid rgb(0,0,0);padding:0;outline:none;font:16px monospace}"
            + "#hit{position:absolute;left:260px;top:20px;width:80px;height:28px;background:rgb(120,0,0)}"
            + "#sel{position:absolute;left:20px;top:80px;width:150px;font:14px sans-serif}"
            + "#tone{position:absolute;left:260px;top:80px;width:80px;height:28px}"
            + "#cur{position:absolute;left:400px;top:20px;width:60px;height:60px;background:rgb(200,200,0);"
            + "cursor:url(data:image/png;base64," + CURSOR_PNG + ") 2 3,auto}"
            + "#anim{position:absolute;left:400px;top:300px;width:50px;height:50px;background:rgb(255,0,0)}"
            + ".spin{animation:slide 1s linear infinite}"
            + "@keyframes slide{from{transform:translateX(0)}to{transform:translateX(100px)}}"
            + "#tall{display:none;position:absolute;left:0;top:0;width:10px;height:3000px}"
            + "#far{position:absolute;left:0;top:700px;width:300px;height:200px;background:rgb(0,0,255)}"
            + "#veil{display:none;position:fixed;left:0;top:0;width:100%;height:100%;z-index:10}"
            + "</style></head><body>"
            + "<input id=inp><div id=hit></div>"
            + "<select id=sel><option>alpha</option><option>beta</option><option>gamma</option>"
            + "<option>delta</option></select><button id=tone>Tone</button>"
            + "<div id=cur></div><div id=anim></div><div id=tall><div id=far></div></div><div id=veil></div>"
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
