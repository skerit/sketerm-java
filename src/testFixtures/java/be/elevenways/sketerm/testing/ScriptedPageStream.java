package be.elevenways.sketerm.testing;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.sketerm.api.PageStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A real local binary socket fixture authenticates the SDK and records upstream messages without a browser engine.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ScriptedPageStream implements AutoCloseable {
    public static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private final @NonNull Path directory;
    private final @NonNull Path socket;
    private final @NonNull ServerSocketChannel server;
    private final @NonNull JobRunner jobs = JobRunner.create("sketerm-scripted-stream");
    private final @NonNull BlockingQueue<Message> received = new LinkedBlockingQueue<>();
    private final @NonNull CompletableFuture<Void> ended = new CompletableFuture<>();
    private final @NonNull AtomicBoolean closed = new AtomicBoolean();
    private final @NonNull Object writeLock = new Object();
    private volatile @Nullable SocketChannel client;
    private volatile @Nullable Throwable failure;

    /**
     * The body excludes tag and length, and its buffer accessor returns a fresh little-endian view for assertions.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public record Message(int tag, byte @NonNull [] body) {
        public @NonNull ByteBuffer buffer() { return ByteBuffer.wrap(this.body).order(ByteOrder.LITTLE_ENDIAN); }
    }

    ScriptedPageStream(@NonNull Consumer<ScriptedPageStream> script) throws IOException {
        this.directory = Files.createTempDirectory(Path.of("/tmp"), "skjava-stream-");
        this.socket = this.directory.resolve("s");
        try {
            this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException | RuntimeException failure) {
            this.jobs.shutdownNow();
            try {
                Files.deleteIfExists(this.directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        try {
            this.server.bind(UnixDomainSocketAddress.of(this.socket));
            this.jobs.startThread(() -> this.accept(script));
        } catch (IOException | RuntimeException failure) {
            this.close();
            throw failure;
        }
    }

    public @NonNull Map<String, Object> endpoint(int handle) {
        return ScriptedSketerm.map("backend", "headless", "view", handle, "route", "direct",
                "socket_path", this.socket.toString(), "token", TOKEN, "protocol_version", 1,
                "max_unacked_frames", 2, "pixel_format", "bgra-premultiplied", "audio", true,
                "title", "Scripted stream", "loading", false);
    }

    public void surfaceSize(int pixelWidth, int pixelHeight, int logicalWidth, int logicalHeight) {
        this.message(2, body(17).putInt(pixelWidth).putInt(pixelHeight).putInt(logicalWidth)
                .putInt(logicalHeight).put((byte) 1).array());
    }

    public void damage(int x, int y, int width, int height, byte @NonNull [] pixels) {
        if ((long) width * height != pixels.length / 4L || pixels.length % 4 != 0
                || pixels.length > PageStream.MAX_DAMAGE_BYTES) {
            throw new IllegalArgumentException("Scripted damage needs exact packed BGRA bytes within the band cap");
        }
        this.message(3, body(16 + pixels.length).putInt(x).putInt(y).putInt(width).putInt(height).put(pixels).array());
    }

    public void frameEnd(long serial) { this.message(4, body(8).putLong(serial).array()); }

    public void cursor(boolean visible, @NonNull String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IllegalArgumentException("Scripted cursor name exceeds u16");
        this.message(5, body(4 + bytes.length).put((byte) (visible ? 1 : 0)).put((byte) 0)
                .putShort((short) bytes.length).put(bytes).array());
    }

    public void cursor(boolean visible, int width, int height, int hotspotX, int hotspotY, byte @NonNull [] image) {
        this.message(5, body(18 + image.length).put((byte) (visible ? 1 : 0)).put((byte) 1)
                .putInt(width).putInt(height).putInt(hotspotX).putInt(hotspotY).put(image).array());
    }

    public void audio(long ptsUs, int rate, int channels, int samples, byte @NonNull [] opus) {
        this.message(6, body(15 + opus.length).putLong(ptsUs).putInt(rate).put((byte) channels)
                .putShort((short) samples).put(opus).array());
    }

    /**
     * Raw messages deliberately allow malformed bodies so consumers can prove their protocol failures.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public void message(int tag, byte @NonNull [] body) {
        this.raw(body(body.length + 5).putInt(body.length + 1).put((byte) tag).put(body).array());
    }

    public void raw(byte @NonNull [] bytes) {
        synchronized (this.writeLock) {
            SocketChannel channel = this.client;
            if (channel == null || this.closed.get()) {
                throw new IllegalStateException("Scripted stream is not connected");
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    /**
     * A timed receive returns null only when no upstream message arrived before the deadline.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public @Nullable Message receive(@NonNull Duration timeout) throws InterruptedException {
        return this.received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public @NonNull CompletableFuture<Void> ended() { return this.ended; }
    public @Nullable Throwable failure() { return this.failure; }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        try {
            this.server.close();
            SocketChannel channel = this.client;
            if (channel != null) channel.close();
        } catch (IOException failure) {
            if (this.failure != null) this.failure.addSuppressed(failure);
        } finally {
            this.jobs.shutdownNow();
            try {
                Files.deleteIfExists(this.socket);
                Files.deleteIfExists(this.directory);
            } catch (IOException failure) {
                if (this.failure != null) this.failure.addSuppressed(failure);
            }
            this.ended.complete(null);
        }
    }

    private void accept(@NonNull Consumer<ScriptedPageStream> script) {
        boolean completed = false;
        try {
            SocketChannel channel = this.server.accept();
            this.client = channel;
            if (this.closed.get()) { channel.close(); return; }
            Message authentication = read(channel);
            if (authentication == null || authentication.tag() != 1
                    || !Arrays.equals(TOKEN.getBytes(StandardCharsets.US_ASCII), authentication.body())) {
                throw new IOException("Invalid stream AUTH");
            }
            this.jobs.startThread(() -> this.readInputs(channel));
            script.accept(this);
            completed = true;
        } catch (Throwable failure) {
            if (!this.closed.get()) this.failure = failure;
        } finally {
            if (!completed) this.close();
        }
    }

    private void readInputs(@NonNull SocketChannel channel) {
        try {
            Message message;
            while ((message = read(channel)) != null) this.received.add(message);
        } catch (IOException failure) {
            if (!this.closed.get()) this.failure = failure;
        } finally {
            this.close();
        }
    }

    private static @Nullable Message read(@NonNull SocketChannel channel) throws IOException {
        ByteBuffer header = body(4);
        while (header.hasRemaining()) {
            if (channel.read(header) < 0) {
                if (header.position() == 0) return null;
                throw new EOFException("Truncated scripted stream header");
            }
        }
        int length = header.flip().getInt();
        if (length < 1 || length > PageStream.MAX_CLIENT_MESSAGE_BYTES) throw new IOException("Invalid input length");
        ByteBuffer message = body(length);
        while (message.hasRemaining()) {
            if (channel.read(message) < 0) throw new EOFException("Truncated scripted stream body");
        }
        message.flip();
        int tag = Byte.toUnsignedInt(message.get());
        byte[] payload = new byte[length - 1];
        message.get(payload);
        if (tag == 18) {
            if (payload.length < 7) throw new IOException("Truncated key input");
            int nameLength = Short.toUnsignedInt(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(5));
            if (nameLength == 0 || nameLength > PageStream.MAX_KEY_NAME || payload.length != 7 + nameLength) {
                throw new IOException("Invalid key name length");
            }
        }
        return new Message(tag, payload);
    }

    private static @NonNull ByteBuffer body(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
