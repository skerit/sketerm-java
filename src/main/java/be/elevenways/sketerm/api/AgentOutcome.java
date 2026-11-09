package be.elevenways.sketerm.api;

/**
 * THE wake-up vocabulary that ends an agent_send or agent_wait, mirroring the results' outcome enum.
 *
 * Only {@link #DONE} means the job settled with an answer; {@link #STILL_WORKING}, {@link #SENT} and
 * {@link #QUEUED} are DATA: the call's budget ran out while the agent legitimately works on.
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public enum AgentOutcome implements WireValue {

    /** The agent settled: idle, no subagents, no background tasks; the turn carries the job's answer. */
    DONE("done"),
    /** It asks something; answer with {@link Agents#answer}. */
    NEEDS_INPUT("needs_input"),
    /** The turn ended on an error the agent does not recover from by itself. */
    ERROR("error"),
    /** The app exited. */
    EXITED("exited"),
    /** Its terminal session was lost. */
    CONNECTION_LOST("connection_lost"),
    /** Its terminal session came back. */
    CONNECTION_RESTORED("connection_restored"),
    /** No screen or record change for the stall_after_min an open asked for. */
    STALLED("stalled"),
    /** A completed message woke the wait. */
    MESSAGE("message"),
    /** A completed message containing the match text woke the wait. */
    MATCH("match"),
    /** The budget ran out while the turn goes on; wait again. */
    STILL_WORKING("still_working"),
    /** The prompt went in and the call returned before the agent started on it. */
    SENT("sent"),
    /** The agent was busy: its app holds the prompt for its next turn. */
    QUEUED("queued"),
    /** A wait on several agents saw every one settle. */
    ALL_SETTLED("all_settled");

    private final String wire;

    AgentOutcome(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return this.wire;
    }

    /**
     * @return whether the turn is still unsettled, so a later {@link Agents#waitFor} can say how it ends
     */
    public boolean isUnfinished() {
        return this == STILL_WORKING || this == SENT || this == QUEUED;
    }

    public static AgentOutcome fromWire(String wire) {
        return WireValues.parse(AgentOutcome.class, wire);
    }

    /**
     * @throws SketermApiException when the token names no member
     */
    public static AgentOutcome require(String wire) {
        return WireValues.require(AgentOutcome.class, wire);
    }
}
