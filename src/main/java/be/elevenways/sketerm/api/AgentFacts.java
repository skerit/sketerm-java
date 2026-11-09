package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a sub-agent's app reports about itself, as the agent_* results' facts say.
 *
 * AIDEV-NOTE: every fact is nullable on purpose. The server sends only the values the app reported so far, and a fact
 * not reported yet is ABSENT, never 0; reading null as zero would claim a free or empty agent.
 *
 * @param costUsd             what the session cost so far, in US dollars (cost_usd)
 * @param contextUsedTokens   the tokens of the context window in use (context_used_tokens)
 * @param contextWindowTokens the context window's size (context_window_tokens)
 * @param contextUsedPercent  how full the context window is (context_used_percent)
 * @param rate7dUsedPercent   how much of the seven-day usage limit is spent (rate_7d_used_percent)
 * @param rate7dResetsAt      when that limit resets (rate_7d_resets_at, epoch seconds on the wire)
 * @param all                 every fact the result carried, by its wire name, the ones above included
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public record AgentFacts(BigDecimal costUsd,
                         Long contextUsedTokens,
                         Long contextWindowTokens,
                         Integer contextUsedPercent,
                         Integer rate7dUsedPercent,
                         Instant rate7dResetsAt,
                         Map<String, Number> all) {

    /** Facts of an agent whose app reported nothing yet. */
    public static final AgentFacts NONE = new AgentFacts(null, null, null, null, null, null, Map.of());

    public AgentFacts {
        all = Collections.unmodifiableMap(new LinkedHashMap<>(all));
    }

    /**
     * @return the facts a result carries; {@link #NONE} when it carries none
     */
    static AgentFacts decode(Map<String, Object> structured) {

        Map<String, Object> facts = Json.optMap(structured, "facts");

        if (facts == null || facts.isEmpty()) {
            return NONE;
        }

        Map<String, Number> all = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                all.put(entry.getKey(), number);
            }
        }

        Number cost = all.get("cost_usd");
        Long resets = Json.optLong(facts, "rate_7d_resets_at");

        return new AgentFacts(cost == null ? null : new BigDecimal(cost.toString()),
                Json.optLong(facts, "context_used_tokens"),
                Json.optLong(facts, "context_window_tokens"),
                intOrNull(facts, "context_used_percent"),
                intOrNull(facts, "rate_7d_used_percent"),
                resets == null ? null : Instant.ofEpochSecond(resets),
                all);
    }

    private static Integer intOrNull(Map<String, Object> facts, String key) {

        Long value = Json.optLong(facts, key);

        return value == null ? null : value.intValue();
    }
}
