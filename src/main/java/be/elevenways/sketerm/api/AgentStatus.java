package be.elevenways.sketerm.api;

/**
 * THE sub-agent state vocabulary, mirroring the agent_* results' state enum.
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public enum AgentStatus implements WireValue {

    /** The app is launching; input sent now may be lost until {@link AgentState#ready()}. */
    STARTING("starting"),
    /** A turn is running. */
    WORKING("working"),
    /** The turn waits on subagents the agent started. */
    WAITING_SUBAGENT("waiting_subagent"),
    /** The turn waits on background tasks the agent started. */
    WAITING_BACKGROUND("waiting_background"),
    /** The agent asks something; {@link AgentState#interaction()} says what, {@link Agents#answer} answers it. */
    WAITING_USER("waiting_user"),
    /** The app recovers from a provider error by itself. */
    RETRYING("retrying"),
    /** Nothing runs; a prompt starts a new job. */
    IDLE("idle"),
    /** The app is gone. */
    EXITED("exited"),
    /** Its terminal session cannot be reached right now. */
    DISCONNECTED("disconnected");

    private final String wire;

    AgentStatus(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether the agent still works on a turn
     */
    public boolean isBusy() {
        return this == WORKING || this == WAITING_SUBAGENT || this == WAITING_BACKGROUND || this == RETRYING;
    }

    public static AgentStatus fromWire(String wire) {
        return WireValues.parse(AgentStatus.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static AgentStatus require(String wire) {
        return WireValues.require(AgentStatus.class, wire);
    }
}
