package be.elevenways.sketerm.api;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One event of a {@link Page#input} batch: a pointer edge, a wheel step, a key edge or text at the caret, at
 * VIEWPORT coordinates (the logical size the view was opened with, not a frame's pixels).
 *
 * A DOWN edge stays held until its UP: a caller that presses must release, or the page keeps a button or a
 * key down.
 */
public sealed interface InputEvent permits InputEvent.Pointer, InputEvent.Wheel, InputEvent.Key, InputEvent.Text {

    /** The largest magnitude web_input takes for a coordinate or a wheel delta; Sketerm refuses the batch beyond it. */
    int COORDINATE_LIMIT = 65_535;

    /**
     * @return this event as one element of web_input's {@code events}
     */
    Map<String, Object> toWire();

    /**
     * @param clicks the click count a down/up pair reports, 1 to 3
     */
    record Pointer(PointerAction action, int x, int y, MouseButton button, int clicks, Set<InputModifier> modifiers)
            implements InputEvent {

        public Pointer {
            if (action == null || button == null) {
                throw new IllegalArgumentException("A pointer event names its action and button");
            }
            if (clicks < 1 || clicks > 3) {
                throw new IllegalArgumentException("A pointer event reports 1 to 3 clicks, got " + clicks);
            }
            inRange("x", x);
            inRange("y", y);
            modifiers = frozen(modifiers);
        }

        @Override
        public Map<String, Object> toWire() {
            Map<String, Object> wire = head("pointer", modifiers);
            wire.put("action", this.action.wire());
            wire.put("x", this.x);
            wire.put("y", this.y);
            wire.put("button", this.button.wire());
            wire.put("clicks", this.clicks);
            return wire;
        }
    }

    /**
     * @param dy positive scrolls DOWN
     */
    record Wheel(int x, int y, int dx, int dy, Set<InputModifier> modifiers) implements InputEvent {

        public Wheel {
            inRange("x", x);
            inRange("y", y);
            inRange("dx", dx);
            inRange("dy", dy);
            modifiers = frozen(modifiers);
        }

        @Override
        public Map<String, Object> toWire() {
            Map<String, Object> wire = head("wheel", modifiers);
            wire.put("x", this.x);
            wire.put("y", this.y);
            wire.put("dx", this.dx);
            wire.put("dy", this.dy);
            return wire;
        }
    }

    /**
     * @param key a named key (Enter, Tab, Shift, Control, F5, ...) or one character
     */
    record Key(KeyAction action, String key, Set<InputModifier> modifiers) implements InputEvent {

        public Key {
            if (action == null || key == null || key.isEmpty()) {
                throw new IllegalArgumentException("A key event names its action and its key");
            }
            modifiers = frozen(modifiers);
        }

        @Override
        public Map<String, Object> toWire() {
            Map<String, Object> wire = head("key", modifiers);
            wire.put("action", this.action.wire());
            wire.put("key", this.key);
            return wire;
        }
    }

    /**
     * Text inserted at the caret as trusted character input.
     */
    record Text(String text) implements InputEvent {

        public Text {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("A text event carries text");
            }
        }

        @Override
        public Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("type", "text");
            wire.put("text", this.text);
            return wire;
        }
    }

    static Pointer move(int x, int y) {
        return new Pointer(PointerAction.MOVE, x, y, MouseButton.LEFT, 1, Set.of());
    }

    static Pointer down(int x, int y, MouseButton button, InputModifier... modifiers) {
        return new Pointer(PointerAction.DOWN, x, y, button, 1, modifiers(modifiers));
    }

    static Pointer up(int x, int y, MouseButton button, InputModifier... modifiers) {
        return new Pointer(PointerAction.UP, x, y, button, 1, modifiers(modifiers));
    }

    /**
     * @return a left click at a point: its down and its up edge
     */
    static List<InputEvent> click(int x, int y) {
        return List.of(down(x, y, MouseButton.LEFT), up(x, y, MouseButton.LEFT));
    }

    static Wheel wheel(int x, int y, int dx, int dy) {
        return new Wheel(x, y, dx, dy, Set.of());
    }

    static Key keyDown(String key) {
        return new Key(KeyAction.DOWN, key, Set.of());
    }

    static Key keyUp(String key) {
        return new Key(KeyAction.UP, key, Set.of());
    }

    static Key press(String key, InputModifier... modifiers) {
        return new Key(KeyAction.PRESS, key, modifiers(modifiers));
    }

    static Text text(String text) {
        return new Text(text);
    }

    private static Set<InputModifier> modifiers(InputModifier... modifiers) {
        return modifiers.length == 0 ? Set.of() : EnumSet.copyOf(List.of(modifiers));
    }

    /**
     * @return the value clamped to what web_input takes, for a caller scaling coordinates it does not control
     */
    static int clamp(long value) {
        return (int) Math.max(-COORDINATE_LIMIT, Math.min(COORDINATE_LIMIT, value));
    }

    private static void inRange(String name, int value) {
        if (value < -COORDINATE_LIMIT || value > COORDINATE_LIMIT) {
            throw new IllegalArgumentException("An input event's " + name + " lies within +-" + COORDINATE_LIMIT
                    + ", got " + value);
        }
    }

    private static Set<InputModifier> frozen(Set<InputModifier> modifiers) {
        return modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(modifiers));
    }

    private static Map<String, Object> head(String type, Set<InputModifier> modifiers) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", type);
        if (!modifiers.isEmpty()) {
            // Schema order, never set iteration order.
            wire.put("modifiers", EnumSet.copyOf(modifiers).stream().map(InputModifier::wire).toList());
        }
        return wire;
    }
}
