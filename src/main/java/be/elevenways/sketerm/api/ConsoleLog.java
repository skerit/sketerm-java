package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One web_console answer: the page's console output mirrored since the view opened.
 *
 * The server sends the lines as text, one {@code [id] level: message} per line; a message that
 * spans lines continues on the lines after its head.
 *
 * @param count how many lines this answer holds
 * @param dropped how many older lines the server's mirror no longer holds
 * @param lastId the newest line id, the {@code since} cursor of the next call
 * @param lines the lines, oldest first
 */
public record ConsoleLog(int count, long dropped, long lastId, List<Line> lines) {

    private static final Pattern HEAD = Pattern.compile("^\\[(\\d+)] ([a-z_]+): ?(.*)$");

    public ConsoleLog {
        lines = List.copyOf(lines);
    }

    /**
     * One console line.
     *
     * @param level the console method or event: log, info, warn, error, debug, or exception
     */
    public record Line(long id, String level, String text) {
    }

    static ConsoleLog decode(Map<String, Object> structured) {

        Long count = Json.optLong(structured, "count");
        Long dropped = Json.optLong(structured, "dropped");
        Long lastId = Json.optLong(structured, "last_id");

        return new ConsoleLog(count == null ? 0 : count.intValue(),
                dropped == null ? 0 : dropped,
                lastId == null ? 0 : lastId,
                parse(Json.optStr(structured, "lines")));
    }

    static List<Line> parse(String text) {

        List<Line> lines = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return lines;
        }

        Long id = null;
        String level = null;
        StringBuilder message = null;

        for (String raw : text.split("\n", -1)) {

            Matcher head = HEAD.matcher(raw);

            if (head.matches()) {
                if (id != null) {
                    lines.add(new Line(id, level, message.toString()));
                }

                id = Long.parseLong(head.group(1));
                level = head.group(2);
                message = new StringBuilder(head.group(3));
            } else if (id != null && !raw.isEmpty()) {
                message.append('\n').append(raw);
            }
        }

        if (id != null) {
            lines.add(new Line(id, level, message.toString()));
        }

        return lines;
    }
}
