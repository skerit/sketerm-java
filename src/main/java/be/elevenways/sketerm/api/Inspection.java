package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One web_inspect answer: a bounded review of the live document (landmarks, focus, control names,
 * disclosure mismatches, horizontal overflow) plus the page errors and console errors seen.
 *
 * AIDEV-NOTE: element references are local to this inspection, never {@link Ref}s a web_act
 * takes. Sketerm states its coverage itself ({@link #coverage()}): the main document and open
 * shadow roots with accname-lite naming, not a full accessibility audit.
 *
 * @param documentId the document identity, stable for one loaded document
 * @param focus the element holding focus, or null
 * @param issues what the review found, at most 50 ({@link #truncated()} says when more were cut)
 * @param documentOverflow how many pixels the document overflows the viewport horizontally
 */
public record Inspection(long capturedAtMs,
                         String documentId,
                         String url,
                         int viewportWidth,
                         int viewportHeight,
                         List<Element> elements,
                         Integer focus,
                         List<Integer> landmarks,
                         List<Integer> controls,
                         List<Issue> issues,
                         int documentOverflow,
                         boolean truncated,
                         List<PageError> pageErrors,
                         long errorsDropped,
                         List<PageError> consoleErrors,
                         String coverage) {

    public Inspection {
        elements = List.copyOf(elements);
        landmarks = List.copyOf(landmarks);
        controls = List.copyOf(controls);
        issues = List.copyOf(issues);
        pageErrors = List.copyOf(pageErrors);
        consoleErrors = List.copyOf(consoleErrors);
    }

    /**
     * One element the review describes, named once and referenced by {@link #ref()} everywhere else.
     *
     * @param domId the element's id attribute, or null
     * @param attributes the accessibility-relevant attributes it carries
     */
    public record Element(int ref, String tag, String role, String name, String domId,
                         Map<String, String> attributes, boolean focusable) {

        public Element {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * One finding.
     *
     * @param kind what is wrong, in Sketerm's own vocabulary (for example a disclosure mismatch)
     * @param related a second element the finding involves, or null
     * @param detail the finding's extra facts, or null
     */
    public record Issue(String kind, int element, Integer related, Object detail) {
    }

    /**
     * One page error: an uncaught exception, an unhandled rejection or a console error.
     */
    public record PageError(long id, String kind, String message) {
    }

    /**
     * @return the element a reference names, or null
     */
    public Element element(int ref) {

        for (Element element : this.elements) {
            if (element.ref() == ref) {
                return element;
            }
        }

        return null;
    }

    static Inspection decode(Map<String, Object> structured) {

        Map<String, Object> inspection = Json.optMap(structured, "inspection");

        if (inspection == null) {
            throw new ProtocolMismatchException("web_inspect answered without an inspection object");
        }

        Map<String, Object> viewport = Json.optMap(inspection, "viewport");

        List<Element> elements = new ArrayList<>();
        for (Object raw : listOf(inspection, "elements")) {
            Map<String, Object> element = Json.asMap(raw, "web_inspect element");
            elements.add(new Element(intOf(element, "ref"), Json.optStr(element, "tag"),
                    Json.optStr(element, "role"), Json.optStr(element, "name"), Json.optStr(element, "dom_id"),
                    strings(Json.optMap(element, "attributes")), Json.optBool(element, "focusable", false)));
        }

        List<Issue> issues = new ArrayList<>();
        for (Object raw : listOf(inspection, "issues")) {
            Map<String, Object> issue = Json.asMap(raw, "web_inspect issue");
            Long related = Json.optLong(issue, "related");
            issues.add(new Issue(Json.optStr(issue, "kind"), intOf(issue, "element"),
                    related == null ? null : related.intValue(), issue.get("detail")));
        }

        Long focus = Json.optLong(inspection, "focus");
        Long captured = Json.optLong(structured, "captured_at_ms");
        Long dropped = Json.optLong(inspection, "errors_dropped");

        return new Inspection(captured == null ? 0 : captured,
                Json.optStr(inspection, "document_id"),
                Json.optStr(inspection, "url"),
                viewport == null ? 0 : intOf(viewport, "width"),
                viewport == null ? 0 : intOf(viewport, "height"),
                elements,
                focus == null ? null : focus.intValue(),
                ints(listOf(inspection, "landmarks")),
                ints(listOf(inspection, "controls")),
                issues,
                intOf(inspection, "document_overflow"),
                Json.optBool(inspection, "truncated", false),
                errors(listOf(inspection, "page_errors")),
                dropped == null ? 0 : dropped,
                errors(listOf(structured, "console_errors")),
                Json.optStr(inspection, "coverage"));
    }

    private static List<Object> listOf(Map<String, Object> map, String key) {
        List<Object> list = Json.optList(map, key);
        return list == null ? List.of() : list;
    }

    private static int intOf(Map<String, Object> map, String key) {
        Long value = Json.optLong(map, key);
        return value == null ? 0 : value.intValue();
    }

    private static List<Integer> ints(List<Object> raw) {

        List<Integer> values = new ArrayList<>();

        for (Object value : raw) {
            if (value instanceof Number number) {
                values.add(number.intValue());
            }
        }

        return values;
    }

    private static Map<String, String> strings(Map<String, Object> raw) {

        Map<String, String> values = new LinkedHashMap<>();

        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    values.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }

        return values;
    }

    private static List<PageError> errors(List<Object> raw) {

        List<PageError> errors = new ArrayList<>();

        for (Object value : raw) {
            Map<String, Object> error = Json.asMap(value, "web_inspect error");
            Long id = Json.optLong(error, "id");
            errors.add(new PageError(id == null ? 0 : id, Json.optStr(error, "kind"), Json.optStr(error, "message")));
        }

        return errors;
    }
}
