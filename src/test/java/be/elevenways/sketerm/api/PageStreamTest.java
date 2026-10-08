package be.elevenways.sketerm.api;

import be.elevenways.sketerm.testing.ScriptedPageStream;
import be.elevenways.sketerm.testing.ScriptedSketerm;
import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.sketerm.api.PageStream.Audio;
import be.elevenways.sketerm.api.PageStream.Cursor;
import be.elevenways.sketerm.api.PageStream.Damage;
import be.elevenways.sketerm.api.PageStream.FrameEnd;
import be.elevenways.sketerm.api.PageStream.SurfaceSize;
import be.elevenways.sketerm.json.Json;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.Thread.State;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoder and published socket fixture exercise the fixed binary contract without an engine.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class PageStreamTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void streamFrameRateOptionIsValidatedEchoedAndCapabilityGated() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {
            socket.surfaceSize(1, 1, 320, 240);
            socket.damage(0, 0, 1, 1, new byte[]{0, 0, 0, (byte) 255});
            socket.frameEnd(1);
        }); Sketerm sketerm = server.connect()) {
            Page page = sketerm.browser().openPage();
            int before = server.callsTo("web_stream").size();
            assertThrows(InvalidArgsException.class, () -> page.stream(new PageStream.Listener() {}, 0));
            assertThrows(InvalidArgsException.class, () -> page.stream(new PageStream.Listener() {}, 241));
            assertEquals(before, server.callsTo("web_stream").size(), "step 1: invalid rates open no socket");
            try (PageStream stream = page.stream(new PageStream.Listener() {}, 30)) {
                assertEquals(30, Json.intVal(server.lastArguments("web_stream"), "max_fps"),
                        "step 2: the stream override reaches MCP");
            }
            server.on("capabilities", ScriptedSketerm.map("web_max_fps", false));
            before = server.callsTo("web_stream").size();
            assertThrows(UnavailableException.class, () -> page.stream(new PageStream.Listener() {}, 15));
            assertEquals(before, server.callsTo("web_stream").size(), "step 3: unsupported caps open no socket");
        }
    }

    @Test
    void fixtureDeliversBorrowedTypedEventsAndEveryInputWithoutMcp() throws Exception {
        LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();
        CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {
            peer.complete(socket);
            socket.surfaceSize(2, 1, 320, 240);
            socket.damage(0, 0, 2, 1, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            socket.frameEnd(10);
            socket.cursor(true, "pointer");
            socket.cursor(false, "");
            socket.cursor(false, 1, 1, 0, 0, new byte[]{9, 8, 7, 6});
            socket.audio(123456, 48000, 2, 960, new byte[]{42, 43});
        }); Sketerm sketerm = server.connect()) {
            // 1. Registration precedes AUTH and all initial events arrive in order.
            assertTrue(sketerm.browser().supportsStreams(), "step 1: stream capability is advertised");
            Page page = sketerm.browser().openPage();
            PageStream stream = page.stream(new PageStream.Listener() {
                @Override public void surfaceSize(@NonNull PageStream stream, @NonNull SurfaceSize size) {
                    events.add(size);
                }
                @Override public void damage(@NonNull PageStream stream, @NonNull Damage damage) {
                    assertTrue(damage.pixels().isReadOnly(), "step 1: borrowed bytes cannot mutate the decoder");
                    byte[] pixels = new byte[damage.pixels().remaining()];
                    damage.pixels().get(pixels);
                    events.add(pixels);
                }
                @Override public void frameEnd(@NonNull PageStream stream, @NonNull FrameEnd frame) {
                    events.add(frame);
                }
                @Override public void cursor(@NonNull PageStream stream, @NonNull Cursor cursor) {
                    events.add(cursor.name() == null ? cursor.image().get(0) : cursor.name());
                }
                @Override public void audio(@NonNull PageStream stream, @NonNull Audio audio) {
                    events.add(List.of(audio.ptsUs(), audio.rate(), audio.channels(), audio.samples(),
                            audio.opus().get(0)));
                }
                @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
                    ended.complete(failure);
                }
            });
            assertEquals(new PageStream.SurfaceSize(2, 1, 320, 240), events.poll(5, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, (byte[]) events.poll(5, TimeUnit.SECONDS));
            assertEquals(new PageStream.FrameEnd(10), events.poll(5, TimeUnit.SECONDS));
            assertEquals("pointer", events.poll(5, TimeUnit.SECONDS));
            assertEquals("", events.poll(5, TimeUnit.SECONDS));
            assertEquals((byte) 9, events.poll(5, TimeUnit.SECONDS));
            assertEquals(List.of(123456L, 48000, 2, 960, (byte) 42), events.poll(5, TimeUnit.SECONDS));
            ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);
            assertNull(socket.receive(Duration.ofMillis(50)), "step 1: no automatic ACK");

            // 2. Typed input maps onto exact upstream tags and CEF modifier bits, never web_input.
            stream.input(new InputEvent.Pointer(PointerAction.DOWN, -2, 3, MouseButton.RIGHT, 3,
                    Set.of(InputModifier.SHIFT, InputModifier.CTRL, InputModifier.ALT, InputModifier.META)),
                    InputEvent.wheel(4, 5, -6, 7), InputEvent.press("Enter"), InputEvent.text("hi"));
            assertMessage(socket, 16, bytes(15).put((byte) 1).putInt(-2).putInt(3).put((byte) 2).put((byte) 3)
                    .putInt(142).array());
            assertMessage(socket, 17, bytes(20).putInt(4).putInt(5).putInt(-6).putInt(7).putInt(0).array());
            assertMessage(socket, 18, bytes(12).put((byte) 0).putInt(0).putShort((short) 5)
                    .put("Enter".getBytes(StandardCharsets.UTF_8)).array());
            assertMessage(socket, 18, bytes(12).put((byte) 1).putInt(0).putShort((short) 5)
                    .put("Enter".getBytes(StandardCharsets.UTF_8)).array());
            assertMessage(socket, 19, new byte[]{'h', 'i'});
            stream.key(KeyAction.PRESS, KeyCode.KEY_A, InputModifier.CTRL);
            assertMessage(socket, 18, bytes(8).put((byte) 0).putInt(4).putShort((short) 1).put((byte) 'a').array());
            assertMessage(socket, 18, bytes(8).put((byte) 1).putInt(4).putShort((short) 1).put((byte) 'a').array());
            stream.focus();
            stream.blur();
            stream.resize(640, 480);
            stream.ack(10);
            assertMessage(socket, 20, new byte[]{1});
            assertMessage(socket, 20, new byte[]{0});
            assertMessage(socket, 21, bytes(8).putInt(640).putInt(480).array());
            assertMessage(socket, 22, bytes(8).putLong(10).array());
            assertTrue(server.callsTo("web_input").isEmpty(), "step 2: no MCP input requests");
            assertEquals(Map.of("pane", page.handle()), server.lastArguments("web_stream"));

            // 3. Invalid outbound values send nothing, and Page close unblocks the socket reader.
            assertThrows(IllegalArgumentException.class, () -> stream.ack(10), "step 3: duplicate ACK");
            assertThrows(IllegalArgumentException.class, () -> stream.ack(11), "step 3: undelivered ACK");
            assertThrows(IllegalArgumentException.class, () -> stream.resize(319, 240));
            assertThrows(IllegalArgumentException.class, () -> stream.input(InputEvent.text("\ud800")));
            assertThrows(IllegalArgumentException.class, () -> stream.key(KeyAction.DOWN, KeyCode.UNIDENTIFIED));
            assertNull(socket.receive(Duration.ofMillis(50)), "step 3: invalid inputs never sent");
            page.close();
            assertTrue(stream.isClosed(), "step 3: Page owns its stream");
            assertNull(ended.get(5, TimeUnit.SECONDS), "step 3: local shutdown has no protocol error");
            socket.ended().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, stream::focus, "step 3: closed stream cannot write");
        }
    }

    @Test
    void eofAndExplicitCloseNotifyExactlyOnceWithoutInterruptingTheCallback() throws Exception {
        for (boolean localClose : List.of(false, true)) {
            CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
            CompletableFuture<Termination> terminated = new CompletableFuture<>();
            AtomicInteger notifications = new AtomicInteger();
            try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(peer::complete);
                 Sketerm sketerm = server.connect()) {
                PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                    @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
                        notifications.incrementAndGet();
                        terminated.complete(new Termination(failure, Thread.currentThread().isInterrupted()));
                    }
                });
                ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);
                if (localClose) stream.close();
                else socket.close();
                Termination callback = terminated.get(5, TimeUnit.SECONDS);
                assertNull(callback.failure(), "EOF and deliberate close are orderly");
                assertFalse(callback.interrupted(), "terminal callback must not inherit shutdown interruption");
                stream.close();
                stream.close();
                assertEquals(1, notifications.get(), "idempotent close never re-notifies the listener");
            }
        }
    }

    @Test
    void closingABlockedWriterDoesNotChangeTheTerminalFailure() throws Exception {
        JobRunner jobs = JobRunner.create("sketerm-write-cancellation");
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "skjava-cancel-");
        Path path = directory.resolve("s");
        CompletableFuture<SocketChannel> accepted = new CompletableFuture<>();
        CompletableFuture<Throwable> writerResult = new CompletableFuture<>();
        CompletableFuture<Termination> terminated = new CompletableFuture<>();
        AtomicInteger notifications = new AtomicInteger();
        try (ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listening.bind(UnixDomainSocketAddress.of(path));
            jobs.startThread(() -> {
                try { accepted.complete(listening.accept()); }
                catch (Throwable failure) { accepted.completeExceptionally(failure); }
            });
            try (ScriptedSketerm server = ScriptedSketerm.browser().on("web_stream", arguments ->
                    ScriptedSketerm.map("backend", "headless", "view", arguments.get("pane"), "route", "direct",
                            "socket_path", path.toString(), "token", ScriptedPageStream.TOKEN, "protocol_version", 1,
                            "max_unacked_frames", 2, "pixel_format", "bgra-premultiplied", "audio", false));
                 Sketerm sketerm = server.connect()) {
                PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                    @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
                        notifications.incrementAndGet();
                        terminated.complete(new Termination(failure, Thread.currentThread().isInterrupted()));
                    }
                });
                try (SocketChannel peer = accepted.get(5, TimeUnit.SECONDS)) {
                    ((SocketChannel) member(stream, "channel")).setOption(StandardSocketOptions.SO_SNDBUF, 1024);
                    InputEvent text = InputEvent.text("a".repeat(PageStream.MAX_TEXT));
                    Thread writer = jobs.startThread(() -> {
                        try {
                            while (true) stream.input(text);
                        } catch (Throwable failure) {
                            writerResult.complete(failure);
                        }
                    });
                    // The peer never reads: wait for actual socket backpressure, not a guessed sleep.
                    awaitState(writer, State.WAITING, "writeOutput");
                    stream.close();
                    assertTrue(writerResult.get(5, TimeUnit.SECONDS) instanceof IllegalStateException,
                            "cancelled write is a closed-stream refusal, not a new transport failure");
                    Termination callback = terminated.get(5, TimeUnit.SECONDS);
                    assertNull(callback.failure(), "deliberate close determines the terminal outcome");
                    assertNull(stream.failure(), "late writer cancellation cannot overwrite the outcome");
                    assertFalse(callback.interrupted(), "terminal callback is not interrupted");
                    assertEquals(1, notifications.get(), "reader publishes termination once");
                }
            }
        } finally {
            jobs.shutdownNow();
            assertTrue(jobs.awaitTermination(5000), "blocked writer was released by socket close");
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void textAndKeyLimitsCountUtf8BytesAndRefuseWholeBatchesBeforeWriting() throws Exception {
        CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(peer::complete);
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
            ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);

            // 1. ASCII, two-byte characters and supplementary characters can each fill exactly 4096 bytes.
            for (String text : List.of("a".repeat(PageStream.MAX_TEXT),
                    "\u00e9".repeat(PageStream.MAX_TEXT / 2), "\ud83d\ude00".repeat(PageStream.MAX_TEXT / 4))) {
                stream.input(InputEvent.text(text));
                byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
                assertEquals(PageStream.MAX_TEXT, encoded.length, "step 1: the byte boundary, not UTF-16 units");
                assertMessage(socket, 19, encoded);
            }

            // 2. Invalid text or keys at the end of a batch prevent even its preceding pointer edge.
            for (String text : List.of("a".repeat(PageStream.MAX_TEXT + 1),
                    "\u00e9".repeat(PageStream.MAX_TEXT / 2 + 1),
                    "\ud83d\ude00".repeat(PageStream.MAX_TEXT / 4) + "a", "\ud800")) {
                assertThrows(IllegalArgumentException.class,
                        () -> stream.input(InputEvent.move(1, 1), InputEvent.text(text)),
                        "step 2: invalid or oversized UTF-8 text is refused before the batch");
            }
            for (String name : List.of("a".repeat(PageStream.MAX_KEY_NAME + 1),
                    "\u00e9".repeat(PageStream.MAX_KEY_NAME / 2) + "a", "\ud800")) {
                assertThrows(IllegalArgumentException.class,
                        () -> stream.input(InputEvent.move(1, 1), InputEvent.keyDown(name)),
                        "step 2: key names have a separate 64-byte limit");
            }
            assertNull(socket.receive(Duration.ofMillis(50)), "step 2: not even the pointer was sent");
            stream.close();
        }
    }

    @Test
    void everySupportedPhysicalKeyProducesANameAcceptedByWebkeys() throws Exception {
        Set<String> named = Set.of("enter", "space", "tab", "delete", "end", "home", "insert", "pageup",
                "pagedown", "escape", "shift", "control", "alt", "meta", "super", "up", "down", "left",
                "right", "backspace");
        CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(peer::complete);
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
            ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);
            int accepted = 0;
            for (KeyCode code : KeyCode.values()) {
                try {
                    stream.key(KeyAction.DOWN, code);
                } catch (IllegalArgumentException unsupported) {
                    continue;
                }
                ScriptedPageStream.Message message = socket.receive(WAIT);
                assertNotNull(message, "supported physical code sent its edge: " + code);
                assertEquals(18, message.tag());
                ByteBuffer body = message.buffer();
                assertEquals(0, body.get(), "down edge: " + code);
                assertEquals(0, body.getInt(), "no modifier flags: " + code);
                int length = Short.toUnsignedInt(body.getShort());
                assertEquals(length, body.remaining(), "exact name byte count: " + code);
                byte[] bytes = new byte[length];
                body.get(bytes);
                String name = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(name.codePointCount(0, name.length()) == 1
                                || named.contains(name.toLowerCase(Locale.ROOT)) || name.matches("F([1-9]|1[0-2])"),
                        "webkeys accepts the supported mapping: " + code + " -> " + name);
                if (code == KeyCode.META_LEFT || code == KeyCode.META_RIGHT) {
                    assertEquals("Meta", name, "Meta is an accepted alias of Super in webkeys");
                }
                accepted++;
            }
            assertEquals(82, accepted, "every supported physical code was checked, not only Enter and letters");
            assertNull(socket.receive(Duration.ofMillis(50)), "unsupported physical codes sent nothing");
            stream.close();
        }
    }

    @Test
    void fixtureRejectsOversizedClientFramesAndKeyNames() throws Exception {
        for (boolean oversizedText : List.of(true, false)) {
            CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
            try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(peer::complete);
                 Sketerm sketerm = server.connect()) {
                Page page = sketerm.browser().openPage();
                Map<String, Object> endpoint = sketerm.session().callToolOrThrow("web_stream",
                        Map.of("pane", page.handle())).structured();
                try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    client.connect(UnixDomainSocketAddress.of(Json.str(endpoint, "socket_path")));
                    ByteBuffer auth = ByteBuffer.wrap(framed(packet(1,
                            ByteBuffer.wrap(ScriptedPageStream.TOKEN.getBytes(StandardCharsets.US_ASCII)))));
                    while (auth.hasRemaining()) client.write(auth);
                    ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);
                    ByteBuffer hostile = oversizedText
                            ? bytes(4).putInt(PageStream.MAX_CLIENT_MESSAGE_BYTES + 1).flip()
                            : ByteBuffer.wrap(framed(packet(18, bytes(7 + PageStream.MAX_KEY_NAME + 1)
                                    .put((byte) 0).putInt(0).putShort((short) (PageStream.MAX_KEY_NAME + 1))
                                    .put(new byte[PageStream.MAX_KEY_NAME + 1]))));
                    while (hostile.hasRemaining()) client.write(hostile);
                    socket.ended().get(5, TimeUnit.SECONDS);
                    assertTrue(socket.failure() instanceof IOException,
                            "fixture refuses the corrected helper limit before recording hostile input");
                    assertNull(socket.receive(Duration.ofMillis(50)), "hostile input was not recorded as valid");
                }
            }
        }
    }

    @Test
    void callbacksCanAcknowledgeImmediatelyAndAThirdOutstandingFrameFailsClosed() throws Exception {
        CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
        CompletableFuture<Long> initial = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {
            peer.complete(socket);
            socket.surfaceSize(1, 1, 320, 240);
            socket.damage(0, 0, 1, 1, new byte[]{0, 0, 0, (byte) 255});
            socket.frameEnd(1);
        }); Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                @Override public void frameEnd(@NonNull PageStream stream, @NonNull FrameEnd frame) {
                    stream.ack(frame.serial());
                    initial.complete(frame.serial());
                }
            });
            assertEquals(1L, initial.get(5, TimeUnit.SECONDS).longValue(),
                    "the initial callback has an addressable stream");
            assertMessage(peer.get(5, TimeUnit.SECONDS), 22, bytes(8).putLong(1).array());
            stream.close();
        }
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {
            socket.surfaceSize(1, 1, 320, 240);
            for (long serial = 1; serial <= 3; serial++) {
                socket.damage(0, 0, 1, 1, new byte[]{0, 0, 0, (byte) 255});
                socket.frameEnd(serial);
            }
        }); Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
                    ended.complete(failure);
                }
            });
            assertTrue(ended.get(5, TimeUnit.SECONDS) instanceof ProtocolMismatchException,
                    "the peer cannot overrun the fixed two-frame window");
            assertTrue(stream.isClosed());
        }
    }

    @Test
    void decoderRejectsHostileBodiesFramingUtf8BoundsAndOverflow() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {});
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
            PageStream.Listener listener = new PageStream.Listener() {};
            List<byte[]> hostile = new ArrayList<>();
            hostile.add(new byte[0]);
            hostile.add(new byte[]{99});
            hostile.add(new byte[]{1});
            hostile.add(packet(2, bytes(17).putInt(-1).putInt(1).putInt(320).putInt(240).put((byte) 1)));
            hostile.add(packet(2, bytes(17).putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE)
                    .putInt(320).putInt(240).put((byte) 1)));
            hostile.add(packet(2, bytes(17).putInt(2).putInt(1).putInt(319).putInt(240).put((byte) 1)));
            hostile.add(packet(2, bytes(17).putInt(2).putInt(1).putInt(320).putInt(240).put((byte) 2)));
            hostile.add(new byte[]{3});
            hostile.add(packet(3, bytes(20).putInt(2).putInt(0).putInt(1).putInt(1).putInt(0)));
            hostile.add(packet(3, bytes(16).putInt(0).putInt(0).putInt(0).putInt(1)));
            hostile.add(packet(3, bytes(16).putInt(0).putInt(0).putInt(Integer.MAX_VALUE)
                    .putInt(Integer.MAX_VALUE)));
            hostile.add(packet(3, bytes(16).putInt(-1).putInt(0).putInt(1).putInt(1)));
            hostile.add(packet(3, bytes(19).putInt(0).putInt(0).putInt(1).putInt(1).put(new byte[3])));
            hostile.add(packet(3, bytes(21).putInt(0).putInt(0).putInt(1).putInt(1).put(new byte[5])));
            hostile.add(packet(4, bytes(8).putLong(0)));
            hostile.add(packet(4, bytes(9).putLong(1).put((byte) 0)));
            hostile.add(packet(5, bytes(5).put((byte) 2).put((byte) 0).putShort((short) 1).put((byte) 'x')));
            hostile.add(new byte[]{5, 1, 2});
            hostile.add(new byte[]{5, 1, 0, 0, 0});
            hostile.add(new byte[]{5, 1, 0, 1, 0, (byte) 0xff});
            hostile.add(new byte[]{5, 1, 0, 2, 0, (byte) 0xc0, (byte) 0x80});
            hostile.add(new byte[]{5, 1, 0, 3, 0, (byte) 0xed, (byte) 0xa0, (byte) 0x80});
            hostile.add(packet(5, bytes(18).put((byte) 1).put((byte) 1).putInt(1).putInt(1).putInt(1).putInt(0)));
            hostile.add(packet(5, bytes(18).put((byte) 1).put((byte) 1)
                    .putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE).putInt(0).putInt(0)));
            hostile.add(packet(6, bytes(16).putLong(1).putInt(44100).put((byte) 2)
                    .putShort((short) 960).put((byte) 0)));
            hostile.add(packet(6, bytes(16).putLong(1).putInt(48000).put((byte) 3)
                    .putShort((short) 960).put((byte) 0)));
            hostile.add(packet(6, bytes(16).putLong(1).putInt(48000).put((byte) 2)
                    .putShort((short) 0).put((byte) 0)));
            hostile.add(packet(6, bytes(15).putLong(1).putInt(48000).put((byte) 2).putShort((short) 960)));
            hostile.add(packet(6, bytes(4016).putLong(1).putInt(48000).put((byte) 2)
                    .putShort((short) 960).put(new byte[4001])));
            for (byte[] body : hostile) {
                PageStreamDecoder decoder = new PageStreamDecoder(true);
                decoder.decode(ByteBuffer.wrap(surface()), stream, listener);
                assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(body), stream, listener),
                        "hostile body " + List.of(body.length, body.length == 0 ? -1 : body[0]));
            }

            // 1. EOF at every cut, including a partial length, is truncation rather than a normal close.
            byte[] complete = framed(surface());
            for (int cut = 1; cut < complete.length; cut++) {
                byte[] truncated = new byte[cut];
                System.arraycopy(complete, 0, truncated, 0, cut);
                PageStreamDecoder decoder = new PageStreamDecoder(true);
                assertThrows(IOException.class, () -> decoder.read(channel(truncated), stream, listener));
            }
            for (int length : new int[]{0, -1, PageStream.MAX_MESSAGE_BYTES + 1}) {
                PageStreamDecoder decoder = new PageStreamDecoder(true);
                assertThrows(IOException.class, () -> decoder.read(channel(bytes(4).putInt(length).array()),
                        stream, listener), "length cannot control an unbounded allocation");
            }
            PageStreamDecoder decoder = new PageStreamDecoder(false);
            assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(packet(6, bytes(16).putLong(1)
                    .putInt(48000).put((byte) 2).putShort((short) 960).put((byte) 0))), stream, listener),
                    "audio cannot arrive when its endpoint did not advertise audio");
            assertFalse(decoder.read(channel(new byte[0]), stream, listener), "boundary EOF is orderly");
            PageStreamDecoder banded = new PageStreamDecoder(false);
            banded.decode(ByteBuffer.wrap(packet(2, bytes(17).putInt(1024).putInt(1024)
                    .putInt(1024).putInt(1024).put((byte) 1))), stream, listener);
            assertThrows(IOException.class, () -> banded.decode(ByteBuffer.wrap(packet(3, bytes(16).putInt(0)
                    .putInt(0).putInt(1024).putInt(257))), stream, listener), "bands over 1 MiB fail before pixels");
            stream.close();
        }
    }

    @Test
    void decoderReusesPixelStorageAndFailsIncompleteOrOutOfOrderFrames() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {});
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
            List<ByteBuffer> borrowed = new ArrayList<>();
            PageStream.Listener listener = new PageStream.Listener() {
                @Override public void damage(@NonNull PageStream stream, @NonNull Damage damage) {
                    borrowed.add(damage.pixels());
                }
            };
            PageStreamDecoder decoder = new PageStreamDecoder(true);
            assertTrue(decoder.read(channel(framed(surface())), stream, listener));
            assertTrue(decoder.read(channel(framed(damage((byte) 1))), stream, listener));
            assertEquals((byte) 1, borrowed.getFirst().get(0));
            assertThrows(IOException.class, () -> decoder.read(channel(new byte[0]), stream, listener),
                    "EOF before FrameEnd is not a complete frame");
            decoder.decode(ByteBuffer.wrap(packet(4, bytes(8).putLong(10))), stream, listener);
            assertThrows(IllegalArgumentException.class, () -> stream.ack(9), "ACK needs an actually delivered serial");
            stream.ack(10);
            decoder.read(channel(framed(damage((byte) 2))), stream, listener);
            assertEquals((byte) 2, borrowed.getFirst().get(0), "the first view shares the reused decoder buffer");
            assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(packet(4, bytes(8).putLong(10))),
                    stream, listener), "duplicate serial fails closed");
            decoder.decode(ByteBuffer.wrap(packet(4, bytes(8).putLong(Long.MIN_VALUE))), stream, listener);
            stream.ack(Long.MIN_VALUE);
            assertThrows(IOException.class, () -> new PageStreamDecoder(true).decode(ByteBuffer.wrap(damage((byte) 1)),
                    stream, listener), "damage cannot precede SurfaceSize");
            stream.close();
        }
    }

    @Test
    void malformedSocketMessagesCloseAndSketermShutdownClosesOwnedStreams() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(
                socket -> socket.raw(new byte[]{1, 0, 0, 0, 99}));
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                @Override public void closed(@NonNull PageStream stream, @Nullable Throwable cause) {
                    failure.complete(cause);
                }
            });
            assertTrue(failure.get(5, TimeUnit.SECONDS) instanceof IOException, "wire failures reach the listener");
            assertTrue(stream.isClosed(), "malformed peer cannot keep its reader alive");
            assertNotNull(stream.failure());
        }
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {})) {
            Sketerm sketerm = server.connect();
            try {
                PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
                sketerm.close();
                assertTrue(stream.isClosed(), "Sketerm owns streams even if their Page was never closed");
            } finally {
                sketerm.close();
            }
        }
    }

    @Test
    void endpointAndCapabilitiesFailClosedButAllowUnrelatedCommonFacts() throws Exception {
        try (ScriptedSketerm server = ScriptedSketerm.browser(); Sketerm sketerm = server.connect()) {
            assertFalse(sketerm.browser().supportsStreams(), "unconfigured fixture has no stream socket");
            server.on("capabilities", ScriptedSketerm.map("web_stream", null));
            assertFalse(sketerm.browser().supportsStreams());
            server.on("capabilities", ScriptedSketerm.map("web_stream", "true"));
            assertThrows(ProtocolMismatchException.class, () -> sketerm.browser().supportsStreams());
            Page page = sketerm.browser().openPage();
            Map<String, Object> facts = ScriptedSketerm.map("backend", "headless", "view", page.handle(),
                    "route", "direct", "socket_path", "/tmp/not-a-stream", "token", ScriptedPageStream.TOKEN,
                    "protocol_version", 1, "max_unacked_frames", 2,
                    "pixel_format", "bgra-premultiplied", "audio", true);
            // Only what the client cannot work without is refused: the decoder version, the socket and its token.
            for (Map.Entry<String, Object> invalid : Map.<String, Object>ofEntries(
                    Map.entry("protocol_version", 2), Map.entry("token", ""), Map.entry("socket_path", 7)).entrySet()) {
                Map<String, Object> hostile = new LinkedHashMap<>(facts);
                hostile.put(invalid.getKey(), invalid.getValue());
                server.on("web_stream", hostile);
                assertThrows(ProtocolMismatchException.class, () -> page.stream(new PageStream.Listener() {}),
                        "endpoint validation for " + invalid.getKey());
            }
            int calls = server.callsTo("web_stream").size();
            assertThrows(IllegalArgumentException.class, () -> page.stream(null));
            assertEquals(calls, server.callsTo("web_stream").size(), "listener validation precedes MCP");
        }
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {});
             Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {});
            assertEquals("direct", stream.route());
            assertTrue(stream.hasAudio());
        }
    }

    private static void assertMessage(@NonNull ScriptedPageStream socket, int tag, byte @NonNull [] expected)
            throws InterruptedException {
        ScriptedPageStream.Message message = socket.receive(WAIT);
        assertNotNull(message, "the socket received tag " + tag);
        assertEquals(tag, message.tag());
        assertArrayEquals(expected, message.body());
    }

    private record Termination(@Nullable Throwable failure, boolean interrupted) {}

    private static @NonNull Object member(@NonNull Object owner, @NonNull String name)
            throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void awaitState(@NonNull Thread thread, @NonNull State state, @Nullable String method) {
        long deadline = Now.millis() + WAIT.toMillis();
        while (Now.millis() < deadline) {
            boolean matching = thread.getState() == state;
            if (matching && method != null) {
                matching = false;
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(PageStream.class.getName())
                            && frame.getMethodName().equals(method)) {
                        matching = true;
                    }
                }
            }
            if (matching) return;
            Thread.yield();
        }
        throw new AssertionError("Thread did not reach " + state + " at " + method + ": " + thread.getState());
    }

    private static @NonNull ByteBuffer bytes(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static byte @NonNull [] packet(int tag, @NonNull ByteBuffer body) {
        return bytes(body.capacity() + 1).put((byte) tag).put(body.array()).array();
    }
    private static byte @NonNull [] framed(byte @NonNull [] message) {
        return bytes(message.length + 4).putInt(message.length).put(message).array();
    }
    private static byte @NonNull [] surface() {
        return packet(2, bytes(17).putInt(2).putInt(1).putInt(320).putInt(240).put((byte) 1));
    }
    private static byte @NonNull [] damage(byte value) {
        return packet(3, bytes(20).putInt(0).putInt(0).putInt(1).putInt(1).putInt(value));
    }
    private static @NonNull ReadableByteChannel channel(byte @NonNull [] bytes) {
        return Channels.newChannel(new ByteArrayInputStream(bytes));
    }
}
