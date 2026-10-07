package be.elevenways.sketerm.api;

import be.elevenways.protoblast.common.binary.BinaryFormatException;
import be.elevenways.protoblast.common.binary.BinaryReader;
import be.elevenways.sketerm.api.PageStream.Listener;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * One bounded receive buffer is reused for every message, including borrowed damage and audio payloads.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class PageStreamDecoder {
    private final @NonNull ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    private final @NonNull ByteBuffer body = ByteBuffer.allocate(PageStream.MAX_MESSAGE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final boolean audio;
    private int pixelWidth;
    private int pixelHeight;
    private long serial;
    private boolean damagePending;

    PageStreamDecoder(boolean audio) { this.audio = audio; }

    boolean read(@NonNull ReadableByteChannel channel, @NonNull PageStream stream,
                 @NonNull Listener listener) throws IOException {
        this.header.clear();
        if (!fully(channel, this.header, true)) {
            if (this.damagePending) throw new EOFException("Stream ended before FrameEnd");
            return false;
        }
        long length = new BinaryReader(this.header.array()).u32();
        if (length < 1 || length > PageStream.MAX_MESSAGE_BYTES) throw invalid("message length");
        this.body.clear().limit((int) length);
        fully(channel, this.body, false);
        this.body.flip();
        this.decode(this.body, stream, listener);
        return true;
    }

    void decode(@NonNull ByteBuffer message, @NonNull PageStream stream, @NonNull Listener listener)
            throws IOException {
        message.order(ByteOrder.LITTLE_ENDIAN);
        if (message.remaining() < 1 || message.remaining() > PageStream.MAX_MESSAGE_BYTES) {
            throw invalid("message length");
        }
        if (!message.hasArray()) throw invalid("decoder requires its owned receive storage");
        BinaryReader reader = new BinaryReader(message.array(), message.arrayOffset() + message.position(),
                message.remaining());
        try {
            int tag = reader.u8();
            switch (tag) {
                case 2 -> {
                    int width = positive(reader.i32());
                    int height = positive(reader.i32());
                    int logicalWidth = positive(reader.i32());
                    int logicalHeight = positive(reader.i32());
                    if (logicalWidth < 320 || logicalWidth > 3840 || logicalHeight < 240 || logicalHeight > 2160
                            || (long) width * height > Integer.MAX_VALUE / 4
                            || reader.u8() != 1 || this.damagePending) throw invalid("surface size or format");
                    reader.requireEnd();
                    this.pixelWidth = width;
                    this.pixelHeight = height;
                    listener.surfaceSize(stream,
                            new PageStream.SurfaceSize(width, height, logicalWidth, logicalHeight));
                }
                case 3 -> {
                    int x = nonnegative(reader.i32());
                    int y = nonnegative(reader.i32());
                    int width = positive(reader.i32());
                    int height = positive(reader.i32());
                    long pixels = (long) width * height;
                    if (this.pixelWidth == 0 || width > this.pixelWidth || height > this.pixelHeight
                            || x > this.pixelWidth - width || y > this.pixelHeight - height
                            || pixels > PageStream.MAX_DAMAGE_BYTES / 4) throw invalid("damage bounds or byte count");
                    exact(reader, (int) pixels * 4);
                    this.damagePending = true;
                    listener.damage(stream, new PageStream.Damage(x, y, width, height, borrowed(reader)));
                }
                case 4 -> {
                    long next = reader.i64();
                    reader.requireEnd();
                    if (this.pixelWidth == 0 || !this.damagePending || next == 0
                            || Long.compareUnsigned(next, this.serial) <= 0) throw invalid("frame serial or order");
                    this.serial = next;
                    this.damagePending = false;
                    stream.delivered(next);
                    listener.frameEnd(stream, new PageStream.FrameEnd(next));
                }
                case 5 -> {
                    int visible = reader.u8();
                    int kind = reader.u8();
                    if (visible > 1) throw invalid("cursor visibility");
                    if (kind == 0) {
                        int length = reader.u16();
                        if (length == 0 && visible == 1) throw invalid("empty visible cursor name");
                        exact(reader, length);
                        listener.cursor(stream,
                                new PageStream.Cursor(visible == 1, utf8(borrowed(reader)), 0, 0, 0, 0, null));
                    } else if (kind == 1) {
                        int width = positive(reader.i32());
                        int height = positive(reader.i32());
                        int x = reader.i32();
                        int y = reader.i32();
                        long pixels = (long) width * height;
                        if (pixels > (PageStream.MAX_MESSAGE_BYTES - 19) / 4 || x < 0 || x >= width
                                || y < 0 || y >= height) throw invalid("cursor image or hotspot");
                        exact(reader, (int) pixels * 4);
                        listener.cursor(stream, new PageStream.Cursor(visible == 1, null, width, height, x, y,
                                 borrowed(reader)));
                    } else throw invalid("cursor kind");
                }
                case 6 -> {
                    long pts = reader.i64();
                    int rate = positive(reader.i32());
                    int channels = reader.u8();
                    int samples = reader.u16();
                    if (!this.audio
                            || (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000 && rate != 48000)
                            || channels < 1 || channels > 2 || samples < 1 || samples > rate * 120 / 1000
                            || reader.remaining() < 1 || reader.remaining() > 4000) throw invalid("audio format");
                    listener.audio(stream, new PageStream.Audio(pts, rate, channels, samples, borrowed(reader)));
                }
                default -> throw invalid("unknown downstream tag " + tag);
            }
        } catch (BinaryFormatException malformed) {
            throw new IOException("Invalid page stream message body", malformed);
        }
    }

    private static boolean fully(@NonNull ReadableByteChannel channel, @NonNull ByteBuffer target, boolean allowEof)
            throws IOException {
        int start = target.position();
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                if (allowEof && target.position() == start) return false;
                throw new EOFException("Truncated stream message");
            }
        }
        return true;
    }

    private static int positive(int value) throws IOException {
        if (value <= 0) throw invalid("positive u32 outside Java range");
        return value;
    }

    private static int nonnegative(int value) throws IOException {
        if (value < 0) throw invalid("u32 outside Java range");
        return value;
    }

    private static void exact(@NonNull BinaryReader body, int length) throws IOException {
        if (body.remaining() != length) throw invalid("body byte count");
    }

    private static @NonNull ByteBuffer borrowed(@NonNull BinaryReader body) {
        return ByteBuffer.wrap(body.array(), body.position(), body.remaining()).slice().asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    private static @NonNull String utf8(@NonNull ByteBuffer body) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(body).toString();
        } catch (CharacterCodingException invalid) {
            throw invalid("UTF-8");
        }
    }

    private static @NonNull IOException invalid(@NonNull String reason) {
        return new IOException("Invalid page stream v1 " + reason);
    }
}
