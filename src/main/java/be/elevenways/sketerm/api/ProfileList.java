package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole web_profiles answer: the profiles plus whether this server can isolate one at all.
 *
 * @param profiles every named profile the store knows, whether or not a view holds it
 * @param store the directory their cookies and caches live in, null when there is none
 * @param contextsSupported whether the browser helper advertises isolated identity contexts
 * @param unavailableReason why profiles cannot be used, null when they can
 */
public record ProfileList(List<BrowserProfile> profiles,
                          String store,
                          boolean contextsSupported,
                          String unavailableReason) {

    public ProfileList {
        profiles = List.copyOf(profiles);
    }

    /**
     * @return the profile with that name, empty when nothing has ever been opened in it
     */
    public Optional<BrowserProfile> find(String name) {
        return this.profiles.stream().filter(profile -> profile.name().equals(name)).findFirst();
    }

    public boolean isEmpty() {
        return this.profiles.isEmpty();
    }

    static ProfileList decode(Map<String, Object> structured) {

        List<Object> raw = Json.optList(structured, "profiles");
        List<BrowserProfile> profiles = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                profiles.add(BrowserProfile.decode(Json.asMap(element, "a web_profiles entry")));
            }
        }

        return new ProfileList(profiles,
                Json.optStr(structured, "store"),
                Json.optBool(structured, "contexts_supported", false),
                Json.optStr(structured, "unavailable_reason"));
    }
}
