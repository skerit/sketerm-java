package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a sub-agent waits on the user for, as the agent_* results' interaction says; {@link Agents#answer} answers it.
 *
 * Claude Code opens on a fresh folder with a permission: whether to trust it ("Yes, I trust this folder" is
 * choice "1").
 *
 * @param kind     permission, question or choice
 * @param title    the prompt's heading, null when the app showed none
 * @param detail   the prompt's body, null when none
 * @param options  what it offers, in order: {@link Agents#answer} takes a label, its 1-based number or a unique part
 * @param freeText whether it also takes a free-text answer instead of an option
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public record AgentInteraction(Kind kind, String title, String detail, List<Option> options, boolean freeText) {

    public AgentInteraction {
        Objects.requireNonNull(kind, "kind");
        options = List.copyOf(options);
    }

    /**
     * THE interaction-kind vocabulary, mirroring interaction.kind.
     */
    public enum Kind implements WireValue {

        /** The app asks leave to use a tool or a folder. */
        PERMISSION("permission"),
        /** The model asks the user a question. */
        QUESTION("question"),
        /** The app offers a choice of its own. */
        CHOICE("choice");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return this.wire;
        }
    }

    /**
     * One offered answer.
     *
     * @param label    what the app shows
     * @param key      the letter the app takes to choose it, null when it takes the number
     * @param selected whether the app has it highlighted
     */
    public record Option(String label, String key, boolean selected) {

        public Option {
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * @return the interaction a result carries, or null when it carries none
     * @throws SketermApiException when its kind is not a known one
     */
    static AgentInteraction decode(Map<String, Object> structured) {

        Map<String, Object> interaction = Json.optMap(structured, "interaction");

        if (interaction == null) {
            return null;
        }

        List<Option> options = new ArrayList<>();
        List<Object> listed = Json.optList(interaction, "options");

        if (listed != null) {
            for (Object element : listed) {
                Map<String, Object> option = Json.asMap(element, "an interaction option");
                options.add(new Option(Json.str(option, "label"), Json.optStr(option, "key"),
                        Json.optBool(option, "selected", false)));
            }
        }

        return new AgentInteraction(WireValues.require(Kind.class, Json.str(interaction, "kind")),
                Json.optStr(interaction, "title"),
                Json.optStr(interaction, "detail"),
                options,
                Json.optBool(interaction, "free_text", false));
    }
}
