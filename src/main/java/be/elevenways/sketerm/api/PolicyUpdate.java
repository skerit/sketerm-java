package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One web_policy_set answer for a live view: what the update actually narrowed, and what it refused.
 *
 * <p>A live policy can only TIGHTEN, so what already ran under the old policy stays within the new
 * one's story. A field that would loosen is named in {@link #ignored()} rather than applied; a
 * request in which EVERY field would loosen is refused outright with a {@link RefusedException}.</p>
 *
 * @param serial the policy generation after the update
 * @param policy the effective policy afterwards, re-typed from the answer's echo
 * @param tightened the fields the update narrowed
 * @param ignored the fields refused because they would have loosened
 */
public record PolicyUpdate(int serial,
                           NetworkPolicy policy,
                           List<String> tightened,
                           List<String> ignored) {

    public PolicyUpdate {
        tightened = List.copyOf(tightened);
        ignored = List.copyOf(ignored);
    }

    static PolicyUpdate decode(Map<String, Object> structured) {

        Long serial = Json.optLong(structured, "policy_serial");

        return new PolicyUpdate(serial == null ? 0 : serial.intValue(),
                NetworkPolicy.decode(structured),
                names(structured, "tightened"),
                names(structured, "ignored"));
    }

    /**
     * @return whether anything at all was narrowed
     */
    public boolean changedAnything() {
        return !this.tightened.isEmpty();
    }

    private static List<String> names(Map<String, Object> structured, String key) {

        List<Object> raw = Json.optList(structured, key);

        if (raw == null) {
            return List.of();
        }

        List<String> names = new ArrayList<>(raw.size());

        for (Object element : raw) {
            names.add(String.valueOf(element));
        }

        return names;
    }
}
