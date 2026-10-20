package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What web_profile_save committed to disk.
 *
 * @param engineRunning false when no browser engine ran, so nothing was held in memory and the store was already
 *                      complete
 * @param profiles the named profiles whose jars are on disk
 * @param store the browser data root the jars were committed under, null when the server named none
 */
public record ProfileSaveResult(boolean engineRunning, List<String> profiles, String store) {

    public ProfileSaveResult {
        profiles = List.copyOf(profiles);
    }

    static ProfileSaveResult decode(Map<String, Object> structured) {

        List<Object> raw = Json.optList(structured, "profiles");
        List<String> profiles = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                if (!(element instanceof String name)) {
                    throw new ProtocolMismatchException("web_profile_save listed a profile that is not a string");
                }
                profiles.add(name);
            }
        }

        return new ProfileSaveResult(Json.optBool(structured, "engine_running", false), profiles,
                Json.optStr(structured, "store"));
    }
}
