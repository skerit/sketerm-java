package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;

/**
 * A profile's SESSION-DEFAULT policy: what {@link Browser#openPage(String, OpenOptions)} installs
 * when it opens in that profile and the call itself carries no policy.
 *
 * @param durable always false, by design: the registration lives in memory and is gone when the
 *                server exits, because a durable copy could be silently lost by a store rebuild
 */
public record ProfilePolicy(String profile,
                            PolicySource source,
                            NetworkPolicy policy,
                            boolean durable) {

    static ProfilePolicy decode(String profile, Map<String, Object> structured) {

        String named = Json.optStr(structured, "profile");

        return new ProfilePolicy(named == null || named.isEmpty() ? profile : named,
                PolicySource.fromWire(Json.optStr(structured, "policy_source")),
                NetworkPolicy.decode(structured),
                Json.optBool(structured, "durable", false));
    }
}
