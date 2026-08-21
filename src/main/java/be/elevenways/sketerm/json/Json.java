package be.elevenways.sketerm.json;

import be.elevenways.protoblast.common.dry.Dry;

import java.util.List;
import java.util.Map;

/**
 * The single JSON seam of sketerm-java, a thin facade over one shared protoblast {@link Dry}.
 *
 * <p>Dry hands small integers back as {@link Integer} and larger ones as {@link Long}, so every
 * numeric getter here normalises through {@link Number} instead of casting.</p>
 */
public final class Json {

    private static final Dry DRY = new Dry();

    private Json() {
    }

    /**
     * Parse one JSON document that must be an object.
     *
     * @throws JsonException when the text is unparseable or is not a JSON object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {

        if (text == null) {
            throw new JsonException("Cannot parse null JSON text");
        }

        Object parsed;

        try {
            parsed = DRY.parse(text);
        } catch (RuntimeException e) {
            throw new JsonException("Failed to parse JSON: " + snippet(text), e);
        }

        if (!(parsed instanceof Map)) {
            throw new JsonException("Expected a JSON object but got "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName())
                    + ": " + snippet(text));
        }

        return (Map<String, Object>) parsed;
    }

    /**
     * Serialize a value to a single-line JSON document that is safe to use as an NDJSON frame.
     *
     * @throws JsonException when the writer emitted a raw newline (which would split the frame)
     */
    public static String write(Object value) {

        String json = DRY.toJson(value);

        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            throw new JsonException("Serialized JSON contains a raw newline and is not NDJSON-safe");
        }

        return json;
    }

    /**
     * @throws JsonException when the key is absent or null
     */
    public static Object require(Map<String, Object> map, String key) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            throw new JsonException("Missing required key '" + key + "'");
        }

        return value;
    }

    /**
     * @throws JsonException when the key is absent or not a string
     */
    public static String str(Map<String, Object> map, String key) {

        Object value = require(map, key);

        if (!(value instanceof String s)) {
            throw new JsonException(typeError(key, "string", value));
        }

        return s;
    }

    /**
     * @return the string value, or null when the key is absent or holds null
     * @throws JsonException when the key holds a non-string
     */
    public static String optStr(Map<String, Object> map, String key) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String s)) {
            throw new JsonException(typeError(key, "string", value));
        }

        return s;
    }

    /**
     * @throws JsonException when the key is absent or not numeric
     */
    public static int intVal(Map<String, Object> map, String key) {
        return (int) longVal(map, key);
    }

    /**
     * @throws JsonException when the key is absent or not numeric
     */
    public static long longVal(Map<String, Object> map, String key) {

        Object value = require(map, key);

        if (!(value instanceof Number n)) {
            throw new JsonException(typeError(key, "number", value));
        }

        return n.longValue();
    }

    /**
     * @return the numeric value, or null when the key is absent or holds null
     * @throws JsonException when the key holds a non-number
     */
    public static Long optLong(Map<String, Object> map, String key) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof Number n)) {
            throw new JsonException(typeError(key, "number", value));
        }

        return n.longValue();
    }

    /**
     * @throws JsonException when the key is absent or not a boolean
     */
    public static boolean boolVal(Map<String, Object> map, String key) {

        Object value = require(map, key);

        if (!(value instanceof Boolean b)) {
            throw new JsonException(typeError(key, "boolean", value));
        }

        return b;
    }

    /**
     * @param fallback the value used when the key is absent or holds null
     * @throws JsonException when the key holds a non-boolean
     */
    public static boolean optBool(Map<String, Object> map, String key, boolean fallback) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            return fallback;
        }

        if (!(value instanceof Boolean b)) {
            throw new JsonException(typeError(key, "boolean", value));
        }

        return b;
    }

    /**
     * @throws JsonException when the key is absent or not a list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {

        Object value = require(map, key);

        if (!(value instanceof List)) {
            throw new JsonException(typeError(key, "array", value));
        }

        return (List<Object>) value;
    }

    /**
     * @return the list value, or null when the key is absent or holds null
     * @throws JsonException when the key holds a non-list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> optList(Map<String, Object> map, String key) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof List)) {
            throw new JsonException(typeError(key, "array", value));
        }

        return (List<Object>) value;
    }

    /**
     * @throws JsonException when the key is absent or not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> map, String key) {

        Object value = require(map, key);

        if (!(value instanceof Map)) {
            throw new JsonException(typeError(key, "object", value));
        }

        return (Map<String, Object>) value;
    }

    /**
     * @return the object value, or null when the key is absent or holds null
     * @throws JsonException when the key holds a non-object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> optMap(Map<String, Object> map, String key) {

        Object value = map == null ? null : map.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof Map)) {
            throw new JsonException(typeError(key, "object", value));
        }

        return (Map<String, Object>) value;
    }

    /**
     * Cast an already-parsed element, used when walking arrays of objects.
     *
     * @throws JsonException when the element is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object element, String what) {

        if (!(element instanceof Map)) {
            throw new JsonException("Expected " + what + " to be an object but got "
                    + describe(element));
        }

        return (Map<String, Object>) element;
    }

    private static String typeError(String key, String expected, Object value) {
        return "Key '" + key + "' should be a " + expected + " but is " + describe(value);
    }

    private static String describe(Object value) {

        if (value == null) {
            return "null";
        }

        return value.getClass().getSimpleName() + " (" + snippet(String.valueOf(value)) + ")";
    }

    private static String snippet(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
