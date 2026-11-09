package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.Map;
import java.util.Objects;

/**
 * Where a sub-agent stands, as every agent_* result says.
 *
 * @param agent        the machine-unique id every {@link Agents} call takes, like {@code claude-k3f9}
 * @param name         the alias {@link AgentOptions#name()} gave it, null when none
 * @param app          the adapter it runs: claude, opencode or a user adapter
 * @param status       its state
 * @param ready        whether input sent now is not lost
 * @param conversation the app's own session id ({@code claude --resume} takes it), null while unknown
 * @param interaction  what it waits on the user for, null when nothing
 * @param facts        what its app reports about itself; never null, {@link AgentFacts#NONE} before any report
 * @param watchCommand a command line that blocks until the agent's next wake-up, for a caller that waits outside
 *                     this client; null when the result named none
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public record AgentState(String agent,
                         String name,
                         String app,
                         AgentStatus status,
                         boolean ready,
                         String conversation,
                         AgentInteraction interaction,
                         AgentFacts facts,
                         String watchCommand) {

    public AgentState {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facts, "facts");
    }

    /**
     * @return whether the agent waits for {@link Agents#answer}
     */
    public boolean needsAnswer() {
        return this.interaction != null;
    }

    /**
     * @throws ProtocolMismatchException when the answer names no agent or no state
     * @throws SketermApiException when the state or interaction kind is not a known one
     */
    static AgentState decode(Map<String, Object> structured, String tool) {

        String agent = Json.optStr(structured, "agent");
        String state = Json.optStr(structured, "state");

        if (agent == null || agent.isEmpty() || state == null) {
            throw new ProtocolMismatchException(tool + " answered no agent and state; its keys were "
                    + structured.keySet());
        }

        return new AgentState(agent,
                Json.optStr(structured, "name"),
                Json.optStr(structured, "app"),
                AgentStatus.require(state),
                Json.optBool(structured, "ready", false),
                Json.optStr(structured, "conversation"),
                AgentInteraction.decode(structured),
                AgentFacts.decode(structured),
                Json.optStr(structured, "watch_command"));
    }
}
