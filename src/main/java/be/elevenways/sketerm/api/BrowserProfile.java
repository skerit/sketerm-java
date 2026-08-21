package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.time.Instant;
import java.util.Map;

/**
 * One named persistent browsing identity, as web_profiles reports it.
 *
 * <p>The context id is opaque and is retired by a reset, so a profile is addressed by NAME
 * everywhere; the id is only good for recognising that a reset happened.</p>
 *
 * @param name the name web_open takes as 'profile'
 * @param context the engine identity-context id, which changes when the profile is reset
 * @param views how many open views currently use it
 * @param lastUsedMs when it was last opened, in epoch milliseconds; 0 when the server sent none
 * @param live whether the running browser helper has been told about it
 */
public record BrowserProfile(String name, int context, int views, long lastUsedMs, boolean live) {

    /**
     * @return whether at least one open view holds this profile, which a reset refuses
     */
    public boolean inUse() {
        return this.views > 0;
    }

    /**
     * @return the last-used instant, or null when the server reported none
     */
    public Instant lastUsed() {
        return this.lastUsedMs <= 0 ? null : Instant.ofEpochMilli(this.lastUsedMs);
    }

    static BrowserProfile decode(Map<String, Object> entry) {

        return new BrowserProfile(Json.optStr(entry, "name"),
                longOf(entry, "context").intValue(),
                longOf(entry, "views").intValue(),
                longOf(entry, "last_used_ms"),
                Json.optBool(entry, "live", false));
    }

    private static Long longOf(Map<String, Object> entry, String key) {

        Long value = Json.optLong(entry, key);

        return value == null ? 0L : value;
    }
}
