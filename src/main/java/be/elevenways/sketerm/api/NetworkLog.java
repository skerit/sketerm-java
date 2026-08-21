package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One web_network answer: the blocking counters plus a bounded page of the request log.
 *
 * @param nextSeq pass as 'since' on the next call to get only newer entries, 0 when absent
 */
public record NetworkLog(boolean blockingEnabled,
                         int blocked,
                         int totalRequests,
                         int rulesLoaded,
                         long nextSeq,
                         List<NetworkRequest> requests) {

    /**
     * One logged request; status, size and duration only exist once it completed.
     *
     * @param type the resource type the engine classified it as, for example document or script
     */
    public record NetworkRequest(long seq,
                                 boolean blocked,
                                 String type,
                                 String method,
                                 String url,
                                 Integer status,
                                 Long durationMs,
                                 Long size,
                                 boolean pending) {
    }

    static NetworkLog decode(Map<String, Object> structured) {

        List<NetworkRequest> requests = new ArrayList<>();
        List<Object> raw = Json.optList(structured, "requests");

        if (raw != null) {
            for (Object element : raw) {
                Map<String, Object> entry = Json.asMap(element, "a web_network request");
                Long seq = Json.optLong(entry, "seq");
                Long status = Json.optLong(entry, "status");

                requests.add(new NetworkRequest(seq == null ? 0 : seq,
                        Json.optBool(entry, "blocked", false),
                        Json.optStr(entry, "type"),
                        Json.optStr(entry, "method"),
                        Json.optStr(entry, "url"),
                        status == null ? null : status.intValue(),
                        Json.optLong(entry, "duration_ms"),
                        Json.optLong(entry, "size"),
                        Json.optBool(entry, "pending", false)));
            }
        }

        Long nextSeq = Json.optLong(structured, "next_seq");
        Long blocked = Json.optLong(structured, "blocked");
        Long total = Json.optLong(structured, "total_requests");
        Long rules = Json.optLong(structured, "rules_loaded");

        return new NetworkLog(Json.optBool(structured, "blocking_enabled", false),
                blocked == null ? 0 : blocked.intValue(),
                total == null ? 0 : total.intValue(),
                rules == null ? 0 : rules.intValue(),
                nextSeq == null ? 0 : nextSeq,
                List.copyOf(requests));
    }
}
