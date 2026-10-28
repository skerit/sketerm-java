package be.elevenways.sketerm.testing;

import be.elevenways.sketerm.api.Sketerm;
import be.elevenways.sketerm.api.SketermOptions;
import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.rpc.FakeTransport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A scripted Sketerm MCP server: one handler per tool name, plus the call log every assertion reads.
 *
 * It answers in the migrated shape by default (structuredContent beside a prose block), which is
 * exactly what the api layer requires; {@link #prose} scripts the old shape on purpose.
 * {@link #browser()} is a preset that behaves like a capable headless Sketerm, so a consumer test
 * drives the real api layer without a browser.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ScriptedSketerm implements AutoCloseable {

    /** The 1x1 PNG the {@link #browser()} preset answers every web_screenshot with. */
    public static final byte[] PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** The 1x1 JPEG the {@link #browser()} preset answers a jpeg web_frame with. */
    public static final byte[] PIXEL_JPEG = pixelJpeg();

    /** The one-page PDF (a 1440x810pt sheet) the {@link #browser()} preset prints every web_print_pdf as. */
    public static final byte[] PRINTED_PDF = ("%PDF-1.4\n1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj\n"
            + "2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj\n"
            + "3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 1440 810]>> endobj\n"
            + "trailer <</Root 1 0 R>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> tools = new LinkedHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<Integer, Map<String, Object>> views = new LinkedHashMap<>();
    /** Where an open of a url lands and with which status, by the url asked for. */
    private final Map<String, Map.Entry<String, Integer>> landings = new LinkedHashMap<>();
    private int nextView = 1;
    private int frameWidth = 1;
    private int frameHeight = 1;
    private volatile boolean streamsEnabled;
    private final List<ScriptedPageStream> streams = new CopyOnWriteArrayList<>();

    /** One recorded tools/call. */
    public record Call(String tool, Map<String, Object> arguments) {
    }

    /**
     * An ordered map of alternating keys and values.
     */
    public static Map<String, Object> map(Object... keysAndValues) {

        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }

        return result;
    }

    /**
     * The fields every browser answer carries, merged with the ones this call adds.
     */
    public static Map<String, Object> facts(Map<String, Object> extra) {

        Map<String, Object> structured = map("backend", "headless", "origin", "https://example.test",
                "url", "https://example.test/", "title", "Example", "loading", false);
        structured.putAll(extra);

        return structured;
    }

    /**
     * An image content block, added to a structured payload under the {@code __image} key.
     */
    public static Map<String, Object> imageBlock(String base64) {
        return imageBlock(base64, "image/png");
    }

    /**
     * An image content block of another media type.
     */
    public static Map<String, Object> imageBlock(String base64, String mimeType) {

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("data", base64);
        block.put("mimeType", mimeType);

        return block;
    }

    /**
     * A server that behaves like a capable headless Sketerm: every capability, views that open, navigate,
     * list, close, report and attest their policy, and screenshots of {@link #PIXEL_PNG}. Script a
     * tool again with {@link #on} to override one answer.
     */
    public static ScriptedSketerm browser() {

        ScriptedSketerm server = new ScriptedSketerm();

        server.on("capabilities", arguments -> map("web", true, "web_backend", "headless", "web_profiles", true,
                "web_capture", true, "web_downloads", true, "web_untrusted", true, "web_policy_ack", true,
                "web_input", true, "web_frames", true, "web_stream", server.streamsEnabled,
                "web_max_fps", true, "web_default_max_fps", 60, "web_print_pdf", true,
                "web_print_pdf_css_page", true));
        server.on("web_open", server::open);
        server.on("web_navigate", server::navigate);
        server.on("web_tabs", arguments -> server.tabs());
        server.on("web_close", server::close);
        server.on("web_policy", server::policy);
        server.on("web_screenshot", arguments -> server.viewFacts(arguments, map("width", 1, "height", 1,
                "bytes", PIXEL_PNG.length, "__image", imageBlock(Base64.getEncoder().encodeToString(PIXEL_PNG)))));
        server.on("web_wait", arguments -> server.viewFacts(arguments, map("waited_for", arguments.get("for"))));
        server.on("web_input", server::input);
        server.on("web_frame", server::frame);
        server.on("web_print_pdf", server::print);

        return server;
    }

    /**
     * The pixel size web_frame reports for its frames (the bytes stay the 1x1 placeholders), so a consumer can test
     * mapping frame pixels onto the 1280x800 viewport.
     */
    public synchronized ScriptedSketerm frameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
        return this;
    }

    /**
     * Each web_stream opens a real socket and runs the script only after its token was authenticated.
     *
     * @author Jelle De Loecker
     * @since 0.1.0
     */
    public @NonNull ScriptedSketerm onStream(@NonNull Consumer<ScriptedPageStream> script) {
        Objects.requireNonNull(script, "A scripted stream needs its script before listening");
        this.streamsEnabled = true;
        return this.on("web_stream", arguments -> {
            synchronized (this) {
                if (this.view(arguments) == null) {
                    return errorPayload("not_found", "No open stream view has handle " + arguments.get("pane"));
                }
            }
            try {
                ScriptedPageStream stream = new ScriptedPageStream(script);
                this.streams.add(stream);
                Map<String, Object> endpoint = stream.endpoint(Json.intVal(arguments, "pane"));
                endpoint.put("max_fps", arguments.getOrDefault("max_fps", 60));
                return endpoint;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @Override
    public void close() {
        for (ScriptedPageStream stream : this.streams) stream.close();
        this.streams.clear();
    }

    /**
     * Script a tool with a fixed structured payload.
     */
    public ScriptedSketerm on(String tool, Map<String, Object> structured) {
        return this.on(tool, arguments -> structured);
    }

    /**
     * Script a tool whose answer depends on its arguments.
     */
    public synchronized ScriptedSketerm on(String tool, Function<Map<String, Object>, Map<String, Object>> handler) {
        this.tools.put(tool, handler);
        return this;
    }

    /**
     * Script a tool that fails with a structured error.
     */
    public ScriptedSketerm onError(String tool, String code, String message, boolean retryable) {

        return this.on(tool, arguments -> errorPayload(code, message, retryable));
    }

    /**
     * Script a tool that answers with prose only: the pre-migration shape, or what a current server
     * started without --structured answers.
     */
    public ScriptedSketerm prose(String tool, String text) {

        return this.on(tool, arguments -> {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("__prose", text);
            return structured;
        });
    }

    /**
     * Script where an open of a url lands, as a server redirect or a refusal page would: the view shows the landed
     * url and the navigation fact names it and the status.
     */
    public synchronized ScriptedSketerm landing(String requested, String landed, int status) {
        this.landings.put(requested, Map.entry(landed, status));
        return this;
    }

    /**
     * Script web_eval with the value the page's code evaluates to, answered as the real server answers it: inline,
     * or, with out_file, written there (a string as itself, anything else as JSON) and named instead.
     *
     * @param values the value of the code the call carries (its 'code' or 'body'), from all its arguments
     */
    public ScriptedSketerm onEval(Function<Map<String, Object>, Object> values) {

        return this.on("web_eval", arguments -> {
            Object value = values.apply(arguments);
            Object file = arguments.get("out_file");

            if (!(file instanceof String path)) {
                return this.viewFacts(arguments, map("value", map("value", value)));
            }

            boolean text = value instanceof String;
            byte[] bytes = (text ? (String) value : Json.write(value)).getBytes(StandardCharsets.UTF_8);

            try {
                Files.write(Path.of(path), bytes);
            } catch (IOException failure) {
                return errorPayload("io_failed", "Could not write " + path + ": " + failure.getMessage());
            }

            return this.viewFacts(arguments, map("out_file", path, "bytes", bytes.length,
                    "format", text ? "text" : "json"));
        });
    }

    public List<Call> calls() {
        return List.copyOf(this.calls);
    }

    /**
     * @return every recorded call to one tool
     */
    public List<Call> callsTo(String tool) {
        return this.calls.stream().filter(call -> call.tool().equals(tool)).toList();
    }

    /**
     * @return the arguments of the last call to a tool
     * @throws IllegalStateException when the tool was never called
     */
    public Map<String, Object> lastArguments(String tool) {

        List<Call> matching = this.callsTo(tool);

        if (matching.isEmpty()) {
            throw new IllegalStateException("The test never called " + tool + "; it called " + this.calls);
        }

        return matching.getLast().arguments();
    }

    /**
     * @return the handles of the views the {@link #browser()} preset holds open
     */
    public synchronized List<Integer> openViews() {
        return List.copyOf(this.views.keySet());
    }

    /**
     * @return a fresh transport answering from this script; each transport is its own MCP session
     */
    public FakeTransport transport() {
        return new FakeTransport(this::answer);
    }

    /**
     * @return a Sketerm with the default options over a fresh transport
     */
    public Sketerm connect() {
        return this.connect(SketermOptions.builder().defaultTimeout(Duration.ofSeconds(5)).build());
    }

    /**
     * @return a Sketerm over a fresh transport, the full api stack as {@link Sketerm#launch} builds it
     */
    public Sketerm connect(SketermOptions options) {
        return Sketerm.connect(options, this.transport());
    }

    private synchronized Map<String, Object> open(Map<String, Object> arguments) {

        int handle = this.nextView++;
        String asked = arguments.get("url") instanceof String text ? text : "about:blank";
        Map.Entry<String, Integer> landing = this.landings.get(asked);
        String url = landing == null ? asked : landing.getKey();
        Map<String, Object> policy = Json.optMap(arguments, "policy");
        boolean untrusted = policy != null && Boolean.TRUE.equals(policy.get("untrusted"));
        Object profile = arguments.get("profile");

        Map<String, Object> view = map("view", handle, "url", url, "title", "", "loading", false,
                "profile", profile == null ? "" : profile,
                "profile_kind", Boolean.TRUE.equals(arguments.get("ephemeral")) ? "ephemeral"
                        : profile == null ? "default" : "named",
                "policy_active", policy != null, "policy_source", policy == null ? "none" : "call",
                "capture_active", arguments.containsKey("capture"), "paint", 1L);

        if (policy != null) {
            view.put("policy", policy);
        }

        if (untrusted) {
            view.put("untrusted", true);
        }

        this.views.put(handle, view);

        Map<String, Object> answer = facts(view);
        answer.put("origin", url);
        answer.put("snapshot", "doc 1 rev 1 url " + url + "\n[1] document \"\" {0 children}\n");
        answer.put("settled", true);
        answer.put("navigation", loaded(asked, url, landing == null ? 200 : landing.getValue()));
        answer.put("max_fps", arguments.getOrDefault("max_fps", 60));

        if (arguments.get("color_scheme") != null) {
            answer.put("color_scheme", arguments.get("color_scheme"));
        }

        return answer;
    }

    /** web_navigate to a url: the view moves there and the load produced a page. */
    private synchronized Map<String, Object> navigate(Map<String, Object> arguments) {

        Map<String, Object> view = this.view(arguments);

        if (view == null) {
            return errorPayload("not_found", "No open view has handle " + arguments.get("pane"));
        }

        if (arguments.get("url") instanceof String url) {
            view.put("url", url);
        }

        String url = (String) view.get("url");

        return this.viewFacts(arguments, map("settled", true, "navigation", loaded(url, url, 200)));
    }

    /** @return the navigation fact of a load of a url that landed at another (or the same) with the status */
    private static Map<String, Object> loaded(String requested, String url, int status) {
        boolean http = url.startsWith("http");
        return map("outcome", !http ? "non_http" : status >= 400 ? "http_error" : "page", "url", url,
                "requested_url", requested, "status", http ? status : null, "redirects", List.of(),
                "content_type", "text/html", "error", null, "detail", true);
    }

    /**
     * web_input: counts what arrived per kind (a key press is two edges) and repaints the view once.
     */
    private synchronized Map<String, Object> input(Map<String, Object> arguments) {

        Map<String, Object> view = this.view(arguments);

        if (view == null) {
            return errorPayload("not_found", "No open view has handle " + arguments.get("pane"));
        }

        List<Object> events = Json.optList(arguments, "events");

        if (events == null || events.isEmpty()) {
            return errorPayload("invalid_args", "web_input needs 'events'");
        }

        long[] counts = new long[4];

        for (Object element : events) {
            Map<String, Object> event = Json.asMap(element, "a web_input event");
            String type = Json.str(event, "type");
            switch (type) {
                case "pointer" -> counts[0]++;
                case "wheel" -> counts[1]++;
                case "key" -> counts[2] += "press".equals(event.getOrDefault("action", "press")) ? 2 : 1;
                case "text" -> counts[3]++;
                default -> {
                    return errorPayload("invalid_args", "unknown event type " + type);
                }
            }
        }

        view.put("paint", (Long) view.get("paint") + 1);
        // A frame long-poll waiting on this view sees the new paint at once.
        this.notifyAll();

        return this.viewFacts(arguments, map("sent", counts[0] + counts[1] + counts[2] + counts[3],
                "pointer", counts[0], "wheel", counts[1], "key", counts[2], "text", counts[3]));
    }

    /**
     * web_frame: the view's paint serial with an image when it differs from {@code since}, else unchanged.
     */
    private synchronized Map<String, Object> frame(Map<String, Object> arguments) {

        Map<String, Object> view = this.view(arguments);

        if (view == null) {
            return errorPayload("not_found", "No open view has handle " + arguments.get("pane"));
        }

        Long since = Json.optLong(arguments, "since");
        Long timeout = Json.optLong(arguments, "timeout_ms");
        String format = Json.optStr(arguments, "format");
        boolean png = "png".equals(format);

        // A long-poll, as the real server answers it: wait (releasing the script) until the view repaints or the
        // timeout passes, so a caller polling in a loop never spins.
        long deadline = System.nanoTime() + Duration.ofMillis(timeout == null ? 1000 : timeout).toNanos();
        while (since != null && since == ((Long) view.get("paint")).longValue() && this.views.containsValue(view)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                this.wait(Math.max(1, Duration.ofNanos(left).toMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long paint = (Long) view.get("paint");

        if (since != null && since == paint) {
            return this.viewFacts(arguments, map("frame", paint, "unchanged", true, "format", png ? "png" : "jpeg"));
        }

        byte[] image = png ? PIXEL_PNG : PIXEL_JPEG;

        return this.viewFacts(arguments, map("frame", paint, "unchanged", false, "format", png ? "png" : "jpeg",
                "width", this.frameWidth, "height", this.frameHeight, "viewport_width", 1280, "viewport_height", 800,
                "bytes", image.length,
                "__image", imageBlock(Base64.getEncoder().encodeToString(image), png ? "image/png" : "image/jpeg")));
    }

    private synchronized Map<String, Object> tabs() {

        List<Object> entries = new ArrayList<>();

        for (Map<String, Object> view : this.views.values()) {
            entries.add(new LinkedHashMap<>(view));
        }

        return map("backend", "headless", "views", entries);
    }

    private synchronized Map<String, Object> close(Map<String, Object> arguments) {

        Long pane = Json.optLong(arguments, "pane");

        if (pane == null || this.views.remove(pane.intValue()) == null) {
            return errorPayload("not_found", "No open view has handle " + pane);
        }

        this.notifyAll();

        return map("backend", "headless", "closed", pane.intValue(), "remaining", this.views.size(), "current", 0);
    }

    private synchronized Map<String, Object> policy(Map<String, Object> arguments) {

        Map<String, Object> view = this.view(arguments);

        if (view == null) {
            return errorPayload("not_found", "No open view has handle " + arguments.get("pane"));
        }

        Map<String, Object> answer = facts(view);
        answer.remove("untrusted");

        if (Boolean.TRUE.equals(view.get("untrusted"))) {
            answer.put("enforced", map("internet_sockets", "denied", "http_broker", "actual-address-validated",
                    "websockets", false, "webrtc", false));
        }

        return answer;
    }

    private synchronized Map<String, Object> viewFacts(Map<String, Object> arguments, Map<String, Object> extra) {

        Map<String, Object> view = this.view(arguments);

        if (view == null) {
            return errorPayload("not_found", "No open view has handle " + arguments.get("pane"));
        }

        Map<String, Object> answer = facts(map("view", view.get("view"), "url", view.get("url"), "title", ""));
        answer.putAll(extra);

        return answer;
    }

    /** Writes {@link #PRINTED_PDF} where web_print_pdf was told to, as the helper writes its file. */
    private Map<String, Object> print(Map<String, Object> arguments) {

        Path path = Path.of(Json.str(arguments, "path"));

        try {
            Files.write(path, PRINTED_PDF);
        } catch (IOException failure) {
            return errorPayload("io_failed", "Could not write " + path + ": " + failure.getMessage());
        }

        String sha256;
        try {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PRINTED_PDF));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }

        return this.viewFacts(arguments, map("path", path.toString(), "bytes", PRINTED_PDF.length,
                "sha256", sha256, "paper", "default",
                "css_page_size", Boolean.TRUE.equals(arguments.get("css_page_size")),
                "no_margins", Boolean.TRUE.equals(arguments.get("no_margins"))));
    }

    private Map<String, Object> view(Map<String, Object> arguments) {
        Long pane = Json.optLong(arguments, "pane");
        return pane == null ? null : this.views.get(pane.intValue());
    }

    private static byte[] pixelJpeg() {
        try {
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(pixel, "jpg", out)) {
                throw new IllegalStateException("This JDK writes no JPEG");
            }
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static Map<String, Object> errorPayload(String code, String message) {
        return errorPayload(code, message, false);
    }

    /** @return a tool's structured failure, marked so {@link #callResult} answers it as an error */
    private static Map<String, Object> errorPayload(String code, String message, boolean retryable) {
        return map("error", map("code", code, "message", message, "retryable", retryable), "__isError", true);
    }

    private String answer(Map<String, Object> request) {

        String method = Json.str(request, "method");
        Object id = request.get("id");

        if (id == null) {
            return null;
        }

        Map<String, Object> result = switch (method) {
            case "initialize" -> initializeResult();
            case "ping" -> new LinkedHashMap<>();
            case "tools/call" -> this.callResult(Json.map(request, "params"));
            default -> throw new IllegalStateException("The scripted server has no answer for " + method);
        };

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        return Json.write(response);
    }

    private Map<String, Object> callResult(Map<String, Object> params) {

        String tool = Json.str(params, "name");
        Map<String, Object> arguments = Json.optMap(params, "arguments");

        this.calls.add(new Call(tool, arguments == null ? Map.of() : arguments));

        Function<Map<String, Object>, Map<String, Object>> handler;

        synchronized (this) {
            handler = this.tools.get(tool);
        }

        if (handler == null) {
            return errorResult("unknown_tool", "the tool '" + tool + "' is not scripted", false);
        }

        Map<String, Object> structured = new LinkedHashMap<>(handler.apply(arguments == null ? Map.of() : arguments));

        if (structured.remove("__prose") instanceof String prose) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(textBlock(prose)));
            return result;
        }

        boolean isError = Boolean.TRUE.equals(structured.remove("__isError"));
        Object image = structured.remove("__image");

        List<Object> content = new ArrayList<>();
        content.add(textBlock("prose lane"));

        if (image instanceof Map) {
            content.add(image);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("structuredContent", structured);

        if (isError) {
            result.put("isError", true);
        }

        return result;
    }

    private static Map<String, Object> errorResult(String code, String message, boolean retryable) {

        Map<String, Object> structured = errorPayload(code, message, retryable);
        structured.remove("__isError");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textBlock(message)));
        result.put("structuredContent", structured);
        result.put("isError", true);

        return result;
    }

    private static Map<String, Object> textBlock(String text) {

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);

        return block;
    }

    private static Map<String, Object> initializeResult() {

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "sketerm-fake");
        serverInfo.put("version", "0.0.0");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", McpSession.PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return result;
    }
}
