package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One web_network answer: the blocking counters plus a bounded page of the request log.
 *
 * @param nextSeq the newest seq this answer covered: pass it as 'since' to list exactly the entries after it (a
 *                server without {@link Browser#supportsExactNetworkCursor()} answered that plus one); 0 when absent
 * @param more    with 'since': further entries after nextSeq are already logged, so ask again
 * @param dropped with 'since': entries after it the bounded ring overwrote before they were read
 */
public record NetworkLog(boolean blockingEnabled,
                         int blocked,
                         int totalRequests,
                         int rulesLoaded,
                         long nextSeq,
                         boolean more,
                         long dropped,
                         List<NetworkRequest> requests) {

    /**
     * One logged request; status, size and duration only exist once it completed.
     *
     * @param type the resource type the engine classified it as, for example document or script
     * @param reason   why a blocked entry was refused - {@link DenialReason#FILTER_LIST} is the
     *                 adblock engine, every other member the enforced policy - and null when the
     *                 answer named none at all, which a server predating policies always does
     * @param redirect whether this hop ended in a server redirect, its status being the redirect's
     * @param prevSeq  the hop this request continues after a redirect, null for a request of its own; only a helper
     *                 with net-log-detail links hops, and a blocked hop is linked too
     * @param urlTruncated whether the url is only the head of a longer one (past the server's log bound)
     */
    public record NetworkRequest(long seq,
                                 boolean blocked,
                                 String type,
                                 String method,
                                 String url,
                                 Integer status,
                                 Long durationMs,
                                 Long size,
                                 boolean pending,
                                 DenialReason reason,
                                 boolean redirect,
                                 Long prevSeq,
                                 boolean urlTruncated) {

        /**
         * @return whether the enforced policy, rather than the ad filter, refused this request
         */
        public boolean refusedByPolicy() {
            return this.blocked && this.reason != null && this.reason.isPolicy();
        }
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
                        Json.optBool(entry, "pending", false),
                        DenialReason.optional(Json.optStr(entry, "reason")),
                        Json.optBool(entry, "redirect", false),
                        Json.optLong(entry, "prev_seq"),
                        Json.optBool(entry, "url_truncated", false)));
            }
        }

        Long nextSeq = Json.optLong(structured, "next_seq");
        Long blocked = Json.optLong(structured, "blocked");
        Long total = Json.optLong(structured, "total_requests");
        Long rules = Json.optLong(structured, "rules_loaded");
        Long dropped = Json.optLong(structured, "dropped");

        return new NetworkLog(Json.optBool(structured, "blocking_enabled", false),
                blocked == null ? 0 : blocked.intValue(),
                total == null ? 0 : total.intValue(),
                rules == null ? 0 : rules.intValue(),
                nextSeq == null ? 0 : nextSeq,
                Json.optBool(structured, "more", false),
                dropped == null ? 0 : dropped,
                List.copyOf(requests));
    }
}
