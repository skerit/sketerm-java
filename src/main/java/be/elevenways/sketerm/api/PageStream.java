package be.elevenways.sketerm.api;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.binary.BinaryWriter;
import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.sketerm.rpc.TransportException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binary stream callbacks run in order on one owned reader, with borrowed buffers valid only inside the callback.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PageStream implements AutoCloseable {

    public static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DAMAGE_BYTES = 1024 * 1024;
    public static final int MAX_TEXT = 4096;
    public static final int MAX_KEY_NAME = 64;
    public static final int MAX_CLIENT_MESSAGE_BYTES = MAX_TEXT + 1;
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_UNACKED_FRAMES = 2;
    public static final String PIXEL_FORMAT = "bgra-premultiplied";

    private final @NonNull SocketChannel channel;
    private final @NonNull Listener listener;
    private final @NonNull ToolCalls owner;
    private final @NonNull JobRunner jobs = JobRunner.create("sketerm-page-stream");
    private final @NonNull Object writeLock = new Object();
    private final @NonNull Object lifecycleLock = new Object();
    private final @NonNull AtomicBoolean closed = new AtomicBoolean();
    private final @NonNull BinaryWriter output = new BinaryWriter();
    private final @NonNull BinaryWriter header = new BinaryWriter(16);
    private final boolean audio;
    private final @NonNull String route;
    private volatile @Nullable Throwable failure;
    private long deliveredSerial;
    private long acknowledgedSerial;
    private final long @NonNull [] outstanding = new long[MAX_UNACKED_FRAMES];
    private int outstandingCount;

    /**
     * Callbacks can use their stream argument before Page.stream returns, including explicit frame acknowledgement.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public interface Listener {
        default void surfaceSize(@NonNull PageStream stream, @NonNull SurfaceSize size) {}
        default void damage(@NonNull PageStream stream, @NonNull Damage damage) {}
        default void frameEnd(@NonNull PageStream stream, @NonNull FrameEnd frame) {}
        default void cursor(@NonNull PageStream stream, @NonNull Cursor cursor) {}
        default void audio(@NonNull PageStream stream, @NonNull Audio audio) {}
        default void closed(@NonNull PageStream stream, @Nullable Throwable failure) {}
    }

    /**
     * Pixel dimensions and logical input dimensions are distinct even when the scale is one.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record SurfaceSize(int pixelWidth, int pixelHeight, int logicalWidth, int logicalHeight) {}

    /**
     * Pixels are packed BGRA premultiplied rows without stride padding and must be copied before returning if retained.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record Damage(int x, int y, int width, int height, @NonNull ByteBuffer pixels) {}

    /**
     * Serial is an opaque unsigned 64-bit wire value and the caller explicitly acknowledges completed presentation.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record FrameEnd(long serial) {}

    /**
     * Exactly one of name and image is present; image pixels are premultiplied BGRA like damage, borrowed until
     * callback return.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record Cursor(boolean visible, @Nullable String name, int width, int height,
                         int hotspotX, int hotspotY, @Nullable ByteBuffer image) {}

    /**
     * Opus bytes are borrowed, samples count per channel, and ptsUs is CEF capture time with an unspecified epoch.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record Audio(long ptsUs, int rate, int channels, int samples, @NonNull ByteBuffer opus) {}

    private PageStream(@NonNull SocketChannel channel, @NonNull Listener listener, @NonNull ToolCalls owner,
                       boolean audio, @NonNull String route) {
        this.channel = channel;
        this.listener = listener;
        this.owner = owner;
        this.audio = audio;
        this.route = route;
    }

    /**
     * Checks only what this client cannot work without: the V1 decoder, the socket and its token.
     */
    static @NonNull PageStream connect(@NonNull Map<String, Object> facts, @NonNull Listener listener,
                                      @NonNull ToolCalls owner) {
        Objects.requireNonNull(listener, "A stream needs its listener before connecting");
        if (!(facts.get("protocol_version") instanceof Number version) || version.longValue() != PROTOCOL_VERSION) {
            throw new ProtocolMismatchException("web_stream does not speak binary stream v" + PROTOCOL_VERSION);
        }
        String path = string(facts, "socket_path");
        String token = string(facts, "token");
        String route = facts.get("route") instanceof String value ? value : "";
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(path));
            PageStream stream = new PageStream(channel, listener, owner, Boolean.TRUE.equals(facts.get("audio")), route);
            stream.send(1, token.getBytes(StandardCharsets.UTF_8));
            return stream;
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new TransportException("Cannot connect the local web_stream socket", failure);
        }
    }

    void start() {
        this.jobs.startThread(this::readLoop);
    }

    public boolean hasAudio() { return this.audio; }
    public @NonNull String route() { return this.route; }
    public boolean isClosed() { return this.closed.get(); }
    public @Nullable Throwable failure() { return this.failure; }

    /**
     * Input uses the binary socket, so a pending MCP wait never queues pointer or keyboard events behind it.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public void input(@NonNull InputEvent... events) {
        this.input(List.of(events));
    }

    public void input(@NonNull List<InputEvent> events) {
        Objects.requireNonNull(events);
        if (events.isEmpty()) throw new IllegalArgumentException("Stream input needs at least one event");
        // Validate the entire batch before its first edge goes out.
        for (InputEvent event : events) {
            Objects.requireNonNull(event);
            if (event instanceof InputEvent.Key key) encoded(key.key(), MAX_KEY_NAME);
            if (event instanceof InputEvent.Text text) encoded(text.text(), MAX_TEXT);
        }
        synchronized (this.writeLock) {
            for (InputEvent event : events) {
                this.output.reset().u32(0).u8(0);
                int tag = switch (event) {
                    case InputEvent.Pointer pointer -> {
                        this.output.u8(switch (pointer.action()) {
                            case MOVE -> 0;
                            case DOWN -> 1;
                            case UP -> 2;
                            case LEAVE -> 3;
                        }).i32(pointer.x()).i32(pointer.y()).u8(switch (pointer.button()) {
                            case LEFT -> 0;
                            case MIDDLE -> 1;
                            case RIGHT -> 2;
                        }).u8(pointer.clicks()).u32(modifiers(pointer.modifiers()));
                        yield 16;
                    }
                    case InputEvent.Wheel wheel -> {
                        this.output.i32(wheel.x()).i32(wheel.y()).i32(wheel.dx()).i32(wheel.dy())
                                .u32(modifiers(wheel.modifiers()));
                        yield 17;
                    }
                    case InputEvent.Key key -> {
                        ByteBuffer name = encoded(key.key(), MAX_KEY_NAME);
                        this.output.u8(key.action() == KeyAction.UP ? 1 : 0)
                                .u32(modifiers(key.modifiers())).u16(name.remaining())
                                .bytes(name.array(), name.arrayOffset() + name.position(), name.remaining());
                        if (key.action() == KeyAction.PRESS) {
                            this.writeOutput(18);
                            this.output.array()[5] = 1;
                        }
                        yield 18;
                    }
                    case InputEvent.Text text -> {
                        ByteBuffer bytes = encoded(text.text(), MAX_TEXT);
                        this.output.bytes(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
                        yield 19;
                    }
                };
                this.writeOutput(tag);
            }
        }
    }

    /**
     * ACK is cumulative through a delivered serial and is never sent automatically.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public void ack(long serial) {
        synchronized (this.writeLock) {
            int through = -1;
            for (int index = 0; index < this.outstandingCount; index++) {
                if (this.outstanding[index] == serial) through = index;
            }
            if (serial == 0 || Long.compareUnsigned(serial, this.deliveredSerial) > 0
                    || Long.compareUnsigned(serial, this.acknowledgedSerial) <= 0 || through < 0) {
                throw new IllegalArgumentException("ACK must advance through a delivered frame");
            }
            this.output.reset().u32(0).u8(0).i64(serial);
            this.writeOutput(22);
            this.acknowledgedSerial = serial;
            int remaining = this.outstandingCount - through - 1;
            System.arraycopy(this.outstanding, through + 1, this.outstanding, 0, remaining);
            this.outstandingCount = remaining;
        }
    }

    public void resize(int logicalWidth, int logicalHeight) {
        if (logicalWidth < 320 || logicalWidth > 3840 || logicalHeight < 240 || logicalHeight > 2160) {
            throw new IllegalArgumentException("Stream viewport must be 320-3840 by 240-2160 logical pixels");
        }
        synchronized (this.writeLock) {
            this.output.reset().u32(0).u8(0).u32(logicalWidth).u32(logicalHeight);
            this.writeOutput(21);
        }
    }

    public void focus() { this.send(20, new byte[]{1}); }
    public void blur() { this.send(20, new byte[]{0}); }

    /**
     * Physical codes use the helper's US-layout key names, while text insertion belongs in an InputEvent.Text.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public void key(@NonNull KeyAction action, @NonNull KeyCode code, @NonNull InputModifier... modifiers) {
        Set<InputModifier> held = modifiers.length == 0 ? Set.of() : EnumSet.copyOf(List.of(modifiers));
        this.input(new InputEvent.Key(action, keyName(code), held));
    }

    @Override
    public void close() {
        this.close(null);
    }

    private void close(@Nullable Throwable failure) {
        synchronized (this.lifecycleLock) {
            if (this.closed.get()) return;
            this.failure = failure;
            this.closed.set(true);
        }
        // Never take writeLock here: closing must unblock a writer whose peer stopped reading.
        try {
            this.channel.close();
        } catch (IOException cleanup) {
            if (failure != null) failure.addSuppressed(cleanup);
        }
        this.owner.releaseStream(this);
        // Socket close wakes the reader; interruption would poison its final listener callback.
        this.jobs.shutdown();
    }

    private void readLoop() {
        PageStreamDecoder decoder = new PageStreamDecoder(this.audio);
        try {
            while (!this.closed.get() && decoder.read(this.channel, this, this.listener)) {}
        } catch (IOException | RuntimeException failure) {
            this.close(failure);
        } finally {
            this.close();
            this.listener.closed(this, this.failure);
        }
    }

    void delivered(long serial) {
        synchronized (this.writeLock) {
            if (this.outstandingCount == MAX_UNACKED_FRAMES) {
                throw new ProtocolMismatchException("Page stream exceeded two unacknowledged frames");
            }
            this.outstanding[this.outstandingCount++] = serial;
            this.deliveredSerial = serial;
        }
    }

    private void send(int tag, byte @NonNull [] body) {
        synchronized (this.writeLock) {
            this.output.reset().u32(0).u8(0).bytes(body);
            this.writeOutput(tag);
        }
    }

    private void writeOutput(int tag) {
        if (this.closed.get()) throw new IllegalStateException("Page stream is closed", this.failure);
        int end = this.output.length();
        this.header.reset().u32(end - 4).u8(tag);
        System.arraycopy(this.header.array(), 0, this.output.array(), 0, this.header.length());
        ByteBuffer bytes = ByteBuffer.wrap(this.output.array(), 0, end);
        try {
            while (bytes.hasRemaining()) this.channel.write(bytes);
        } catch (IOException failure) {
            this.close(failure);
            if (this.failure == null) throw new IllegalStateException("Page stream was closed during write", failure);
            throw new TransportException("Page stream write failed", failure);
        }
    }

    private static int modifiers(@NonNull Set<InputModifier> modifiers) {
        int bits = 0;
        for (InputModifier modifier : modifiers) {
            bits |= switch (modifier) {
                case SHIFT -> 1 << 1;
                case CTRL -> 1 << 2;
                case ALT -> 1 << 3;
                case META -> 1 << 7;
            };
        }
        return bits;
    }

    private static @NonNull String keyName(@NonNull KeyCode code) {
        Objects.requireNonNull(code, "A stream key needs its physical code");
        return switch (code) {
            case KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J, KEY_K, KEY_L, KEY_M,
                    KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z ->
                    code.dom().substring(3).toLowerCase(Locale.ROOT);
            case DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9 ->
                    code.dom().substring(5);
            case SHIFT_LEFT, SHIFT_RIGHT -> "Shift";
            case CONTROL_LEFT, CONTROL_RIGHT -> "Control";
            case ALT_LEFT, ALT_RIGHT -> "Alt";
            case META_LEFT, META_RIGHT -> "Meta";
            case ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, ARROW_UP -> code.dom().substring(5);
            case BACKSPACE -> "BackSpace";
            case BACKQUOTE -> "`";
            case BACKSLASH -> "\\";
            case BRACKET_LEFT -> "[";
            case BRACKET_RIGHT -> "]";
            case COMMA -> ",";
            case EQUAL -> "=";
            case MINUS -> "-";
            case PERIOD -> ".";
            case QUOTE -> "'";
            case SEMICOLON -> ";";
            case SLASH -> "/";
            case ENTER, SPACE, TAB, DELETE, END, HOME, INSERT, PAGE_DOWN, PAGE_UP, ESCAPE,
                    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> code.dom();
            default -> throw new IllegalArgumentException("The stream helper does not support physical key " + code);
        };
    }

    private static @NonNull ByteBuffer encoded(@NonNull String value, int maxBytes) {
        if (value.length() > maxBytes) throw new IllegalArgumentException("Stream text exceeds its byte limit");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (bytes.remaining() > maxBytes) throw new IllegalArgumentException("Stream text exceeds its byte limit");
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Stream text must contain valid Unicode", invalid);
        }
    }

    private static @NonNull String string(@NonNull Map<String, Object> facts, @NonNull String key) {
        if (!(facts.get(key) instanceof String value) || value.isEmpty()) {
            throw new ProtocolMismatchException("web_stream." + key + " must be a nonempty string");
        }
        return value;
    }
}
