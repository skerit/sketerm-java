package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * What web_profile_reset erased; the name stays usable and the next open mints a fresh jar.
 *
 * @param profile the name that was erased
 * @param deleted whether storage was actually removed
 * @param retiredContext the identity-context id that was dropped, 0 when the server named none
 */
public record ProfileResetResult(String profile, boolean deleted, int retiredContext) {

    static ProfileResetResult decode(Map<String, Object> structured) {

        Long retired = Json.optLong(structured, "retired_context");

        return new ProfileResetResult(Json.optStr(structured, "profile"),
                Json.optBool(structured, "deleted", false),
                retired == null ? 0 : retired.intValue());
    }
}
