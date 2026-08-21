package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;

/**
 * One web_policy answer: the installed policy plus its live accounting.
 *
 * <p>This is the machine-readable half of every policy refusal sentence: when a traffic tool throws
 * a {@link RefusedException}, this says which budget went and by how much.</p>
 *
 * @param active whether any policy is installed on the view at all
 * @param source where the installed policy came from
 * @param serial the engine's policy generation, which a tighten moves
 * @param policy the effective policy, re-typed from the answer's echo; null when none is installed
 * @param requests allowed requests so far, the document included
 * @param bytes received body bytes so far
 * @param navigations main-frame loads so far, redirect hops included
 * @param msLeft of a deadline; 0 both when none is set and when it ran out, which {@link #exhausted}
 *               disambiguates
 * @param exhausted whether a budget latched, permanently, for this view
 * @param exhaustedReason which budget it was, {@link DenialReason#NONE} while none has
 * @param denied refusals by reason since the policy was installed, absent reasons meaning zero
 * @param durable always false: policies and profile defaults live for the server's lifetime only
 */
public record PolicyStatus(boolean active,
                           PolicySource source,
                           int serial,
                           NetworkPolicy policy,
                           long requests,
                           long bytes,
                           long navigations,
                           long msLeft,
                           boolean exhausted,
                           DenialReason exhaustedReason,
                           Map<DenialReason, Long> denied,
                           boolean durable) {

    static PolicyStatus decode(Map<String, Object> structured) {

        Map<DenialReason, Long> denied = new EnumMap<>(DenialReason.class);
        Map<String, Object> raw = Json.optMap(structured, "denied");

        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {

                Long count = Json.optLong(raw, entry.getKey());

                if (count != null && count != 0) {
                    denied.put(DenialReason.require(entry.getKey()), count);
                }
            }
        }

        DenialReason reason = DenialReason.requireExhaustion(
                Json.optStr(structured, "exhausted_reason"));

        return new PolicyStatus(Json.optBool(structured, "policy_active", false),
                PolicySource.fromWire(Json.optStr(structured, "policy_source")),
                zero(Json.optLong(structured, "policy_serial")).intValue(),
                NetworkPolicy.decode(structured),
                zero(Json.optLong(structured, "requests")),
                zero(Json.optLong(structured, "bytes")),
                zero(Json.optLong(structured, "navigations")),
                zero(Json.optLong(structured, "ms_left")),
                Json.optBool(structured, "exhausted", false),
                reason == null ? DenialReason.NONE : reason,
                Collections.unmodifiableMap(denied),
                Json.optBool(structured, "durable", false));
    }

    /**
     * @return how often one reason refused a request, 0 when it never did
     */
    public long denied(DenialReason reason) {
        return this.denied.getOrDefault(reason, 0L);
    }

    private static Long zero(Long value) {
        return value == null ? 0L : value;
    }
}
