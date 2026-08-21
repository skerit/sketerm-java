package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * THE reader of a view handle, which the wire spells 'pane' with a GUI and 'view' headless.
 *
 * <p>Both spellings go back out as the 'pane' argument, so every caller holds one integer.</p>
 */
public final class Handles {

    private Handles() {
    }

    /**
     * @throws ProtocolMismatchException when neither spelling is present, which is what a server
     *         predating the structuredContent migration looks like
     */
    public static int of(Map<String, Object> structured, String what) {

        Long pane = structured == null ? null : Json.optLong(structured, "pane");

        if (pane != null) {
            return pane.intValue();
        }

        Long view = structured == null ? null : Json.optLong(structured, "view");

        if (view != null) {
            return view.intValue();
        }

        throw new ProtocolMismatchException(what + " carries no view handle: expected a 'pane' or"
                + " 'view' field in structuredContent, got "
                + (structured == null ? "no structured payload at all (a prose-only answer, so this"
                        + " server predates the structuredContent migration)" : structured.keySet()));
    }
}
