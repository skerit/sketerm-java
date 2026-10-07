package be.elevenways.sketerm.testing;

import be.elevenways.sketerm.api.Frame;
import be.elevenways.sketerm.api.FrameFormat;
import be.elevenways.sketerm.api.InputEvent;
import be.elevenways.sketerm.api.InputModifier;
import be.elevenways.sketerm.api.InputResult;
import be.elevenways.sketerm.api.InvalidArgsException;
import be.elevenways.sketerm.api.MouseButton;
import be.elevenways.sketerm.api.NetworkPolicy;
import be.elevenways.sketerm.api.NotFoundException;
import be.elevenways.sketerm.api.OpenOptions;
import be.elevenways.sketerm.api.Page;
import be.elevenways.sketerm.api.PageStream;
import be.elevenways.sketerm.api.PointerAction;
import be.elevenways.sketerm.api.Screenshot;
import be.elevenways.sketerm.api.Sketerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The published test fixture drives the whole api stack, so a consumer's test needs no browser.
 */
class ScriptedSketermTest {

    @Test
    void streamScriptAssertionIsRecordedAndClosesItsSocket() throws Exception {
        AssertionError assertion = new AssertionError("consumer's scripted stream assertion");
        CompletableFuture<ScriptedPageStream> peer = new CompletableFuture<>();
        CompletableFuture<Throwable> terminated = new CompletableFuture<>();
        try (ScriptedSketerm server = ScriptedSketerm.browser().onStream(socket -> {
            peer.complete(socket);
            throw assertion;
        }); Sketerm sketerm = server.connect()) {
            PageStream stream = sketerm.browser().openPage().stream(new PageStream.Listener() {
                @Override public void closed(@NonNull PageStream stream, @Nullable Throwable failure) {
                    terminated.complete(failure);
                }
            });
            ScriptedPageStream socket = peer.get(5, TimeUnit.SECONDS);
            socket.ended().get(5, TimeUnit.SECONDS);
            assertSame(assertion, socket.failure(), "the fixture preserves a consumer's AssertionError");
            assertNull(terminated.get(5, TimeUnit.SECONDS), "the client sees orderly EOF from fixture teardown");
            assertTrue(stream.isClosed(), "the failed script cannot leave its connected socket alive");
            socket.close();
            assertSame(assertion, socket.failure(), "idempotent cleanup does not overwrite the assertion");
        }
    }

    @Test
    @DisplayName("The browser preset opens, verifies, photographs and closes views through Sketerm.connect")
    void browserPresetJourney() {

        ScriptedSketerm server = ScriptedSketerm.browser();

        try (Sketerm sketerm = server.connect()) {

            // 1. A connected Sketerm has no process of its own
            assertNull(sketerm.process(), "step 1: the transport was opened by the script");
            assertTrue(sketerm.browser().supportsUntrusted(), "step 1: the preset advertises untrusted mode");
            assertTrue(sketerm.browser().isHeadless(), "step 1: on its own headless helper, not a user's GUI");

            // 2. An untrusted open is acknowledged and verified exactly as a real server answers
            Page untrusted = sketerm.browser().openPage("https://folio.example/", OpenOptions.ephemeralIdentity()
                    .withPolicy(NetworkPolicy.builder().untrusted().build()));
            assertTrue(untrusted.isPolicyActive(), "step 2: the policy is active");
            assertTrue(untrusted.policy().untrusted(), "step 2: and attested untrusted");

            // 3. A profiled view lives beside it, and both are listed
            Page profiled = sketerm.browser().openPage("https://music.example/", OpenOptions.inProfile("music"));
            assertEquals("music", server.lastArguments("web_open").get("profile"), "step 3: the profile went out");
            assertEquals(List.of(1, 2), server.openViews(), "step 3: both views are open");

            // 4. A screenshot is the preset's pixel
            Screenshot shot = untrusted.screenshot();
            assertArrayEquals(ScriptedSketerm.PIXEL_PNG, shot.bytes(), "step 4: the scripted PNG");

            // 5. Closing removes a view; a closed handle is unknown to the server afterwards
            untrusted.close();
            assertEquals(List.of(2), server.openViews(), "step 5: only the profiled view remains");
            assertThrows(NotFoundException.class, () -> sketerm.browser().page(1), "step 5: view 1 is gone");
            profiled.close();
        }
    }

    @Test
    @DisplayName("A page is driven by hand and watched frame by frame through the preset")
    void handInputAndFrameJourney() {

        ScriptedSketerm server = ScriptedSketerm.browser();

        try (Sketerm sketerm = server.connect()) {

            Page page = sketerm.browser().openPage("https://example.test/", OpenOptions.ephemeralIdentity());

            // 1. Both preflights are advertised, and the first frame is a JPEG of the viewport
            assertTrue(sketerm.browser().supportsInput() && sketerm.browser().supportsFrames(),
                    "step 1: the preset advertises hand input and frames");
            Frame first = page.frame(0, Duration.ofMillis(500));
            assertFalse(first.unchanged(), "step 1: a painted frame");
            assertEquals(FrameFormat.JPEG, first.format(), "step 1: JPEG by default");
            assertArrayEquals(ScriptedSketerm.PIXEL_JPEG, first.bytes(), "step 1: the image bytes");
            assertEquals(1280, first.viewportWidth(), "step 1: the coordinate space input uses");
            assertEquals("jpeg", server.lastArguments("web_frame").get("format"), "step 1: the format went out");

            // 2. Asking past it answers unchanged, with no image
            Frame still = page.frame(first.serial(), Duration.ofMillis(200));
            assertTrue(still.unchanged(), "step 2: nothing newer");
            assertEquals(first.serial(), still.serial(), "step 2: the serial stays");
            assertEquals(0, still.bytes().length, "step 2: no image");

            // 3. A shift-drag and text go out in order as one batch, a press as two edges
            List<InputEvent> drag = new ArrayList<>();
            drag.add(InputEvent.keyDown("Shift"));
            drag.add(InputEvent.down(10, 20, MouseButton.LEFT, InputModifier.SHIFT));
            drag.add(InputEvent.move(40, 20));
            drag.add(InputEvent.up(40, 20, MouseButton.LEFT, InputModifier.SHIFT));
            drag.add(InputEvent.keyUp("Shift"));
            drag.add(InputEvent.press("a", InputModifier.CTRL));
            drag.add(InputEvent.text("hallo"));
            InputResult sent = page.input(drag);
            assertEquals(new InputResult(8, 3, 0, 4, 1), sent, "step 3: every edge counted");
            List<?> wire = (List<?>) server.lastArguments("web_input").get("events");
            assertEquals(Map.of("type", "pointer", "action", "down", "x", 10, "y", 20, "button", "left",
                    "clicks", 1, "modifiers", List.of("shift")), wire.get(1), "step 3: a pointer edge on the wire");
            assertEquals(Map.of("type", "key", "action", "press", "key", "a", "modifiers", List.of("ctrl")),
                    wire.get(5), "step 3: a chorded press");

            // 4. The input repainted the view, so the next frame is newer; PNG when asked
            Frame after = page.frame(first.serial(), FrameFormat.PNG, null, 640, Duration.ofMillis(200));
            assertTrue(after.serial() > first.serial(), "step 4: a newer paint");
            assertArrayEquals(ScriptedSketerm.PIXEL_PNG, after.bytes(), "step 4: the PNG");
            assertEquals(640L, ((Number) server.lastArguments("web_frame").get("max_width")).longValue(),
                    "step 4: the downscale went out");

            // 5. An empty batch, or one past the cap, is refused before anything is sent
            int calls = server.callsTo("web_input").size();
            assertThrows(InvalidArgsException.class, () -> page.input(List.of()), "step 5: empty");
            assertThrows(InvalidArgsException.class, () -> page.input(Collections.nCopies(
                    Page.MAX_INPUT_EVENTS + 1, InputEvent.move(1, 1))), "step 5: over the cap");
            assertEquals(calls, server.callsTo("web_input").size(), "step 5: nothing was sent");
            assertThrows(IllegalArgumentException.class, () -> new InputEvent.Pointer(PointerAction.DOWN, 1, 1,
                    MouseButton.LEFT, 4, Set.of()), "step 5: no quadruple click");
            assertThrows(IllegalArgumentException.class, () -> InputEvent.move(InputEvent.COORDINATE_LIMIT + 1, 0),
                    "step 5: no coordinate past what web_input takes");
            assertThrows(IllegalArgumentException.class, () -> InputEvent.wheel(0, 0, 0,
                    -InputEvent.COORDINATE_LIMIT - 1), "step 5: nor a wheel delta");
            assertEquals(InputEvent.COORDINATE_LIMIT, InputEvent.clamp(Long.MAX_VALUE),
                    "step 5: a scaled value is clamped to the limit");
        }
    }

    @Test
    @DisplayName("A server driving the user's GUI is not headless")
    void guiBackendIsNotHeadless() {

        ScriptedSketerm server = ScriptedSketerm.browser()
                .on("capabilities", ScriptedSketerm.map("web", true, "web_backend", "not_yet_determined",
                        "web_gui", true));

        try (Sketerm sketerm = server.connect()) {
            // 1. A granted GUI drives the user's own browser, even before the backend is determined
            assertTrue(sketerm.browser().isAvailable(), "step 1: a GUI can run the web tools");
            assertFalse(sketerm.browser().isHeadless(), "step 1: but they would drive a browser the user owns");

            // 2. Without the grant, an undetermined backend is the server's own headless helper
            server.on("capabilities", ScriptedSketerm.map("web", true, "web_backend", "not_yet_determined",
                    "web_gui", false));
            assertTrue(sketerm.browser().isHeadless(), "step 2: no grant, so the private helper");
        }
    }

    @Test
    @DisplayName("Scripting a tool again overrides the preset, and refusals carry their code")
    void overridesAndRefusals() {

        ScriptedSketerm server = ScriptedSketerm.browser()
                .onError("web_open", "unavailable", "no helper", true);

        try (Sketerm sketerm = server.connect()) {
            assertThrows(RuntimeException.class, () -> sketerm.browser().openPage("https://example.test/"),
                    "the scripted refusal wins over the preset");
            assertEquals(1, server.callsTo("web_open").size(), "the open was attempted once");
        }
    }
}
