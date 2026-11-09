package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.time.Duration;
import java.util.Map;

/**
 * The sub-agent face of a session: it runs Claude Code (or another adapter) through the agent tool group and talks to
 * it in records, never raw screens.
 *
 * The server must publish that group: launch with {@code SketermOptions.builder().toolGroups("agent,core")}. An agent
 * runs on the per-user sketerm-mux daemon and OUTLIVES this server until {@link #close} ends it.
 *
 * A fresh Claude Code first asks whether to trust its folder: {@link #open} answers with that
 * {@link AgentState#interaction()}, and {@code answer(id, "1")} trusts it. Then {@link #send} a prompt and, while its
 * {@link AgentTurn#outcome()} is unfinished, {@link #waitFor} the turn again; a done turn carries the job's answer.
 *
 * AIDEV-NOTE: every call sends timeout_ms, the default when the caller gave none. The server waits up to 60 s by
 * default, longer than the client's own per-call deadline (30 s), and {@link ToolCalls#rpcTimeoutMs} only outlives
 * a wait it can see in the arguments.
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public final class Agents {

    static final String OPEN = "agent_open";
    static final String SEND = "agent_send";
    static final String ANSWER = "agent_answer";
    static final String WAIT = "agent_wait";
    static final String READ = "agent_read";
    static final String CLOSE = "agent_close";

    /** The server's own default wait, sent when the caller names none. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(60_000);

    private final ToolCalls calls;

    Agents(ToolCalls calls) {
        this.calls = calls;
    }

    /**
     * Start an agent and wait (bounded) until it is ready or asks something.
     *
     * @return its id and state; {@link AgentState#interaction()} holds a first question such as the folder trust
     * @throws ConflictException when the name is taken by a live agent
     * @throws InvalidArgsException when the server refuses an argument
     */
    public AgentState open(AgentOptions options) {

        AgentOptions chosen = options == null ? AgentOptions.claude() : options;
        Map<String, Object> arguments = chosen.toWire();
        ToolCalls.putTimeout(arguments, timeoutOrDefault(chosen.timeout()));

        return AgentState.decode(this.calls.structured(OPEN, arguments), OPEN);
    }

    /**
     * Send a prompt and wait (bounded) for the turn; a busy agent's app queues it for its next turn.
     *
     * @param timeout how long to wait, at most {@link AgentOptions#MAX_TIMEOUT}; null for {@link #DEFAULT_TIMEOUT}
     * @return the turn: {@link AgentOutcome#DONE} carries the job's answer, an unfinished outcome asks for
     *         {@link #waitFor}
     * @throws ConflictException while the agent waits for an answer
     */
    public AgentTurn send(String agentId, String text, Duration timeout) {

        if (text == null || text.isEmpty()) {
            throw new InvalidArgsException("An agent prompt must not be empty", SEND, false, null);
        }

        Map<String, Object> arguments = agentArguments(agentId, SEND);
        arguments.put("text", text);
        ToolCalls.putTimeout(arguments, timeoutOrDefault(requireTimeout(timeout, SEND)));

        return AgentTurn.decode(this.calls.structured(SEND, arguments), SEND);
    }

    /**
     * Answer the agent's pending prompt with one of its options, then wait (bounded, {@link #DEFAULT_TIMEOUT}) for the
     * turn, as agent_answer does.
     *
     * @param choice an option label, its 1-based number or a unique part of a label
     * @return the turn after the answer; an answer that unblocks a running job carries that job's answer
     */
    public AgentTurn answer(String agentId, String choice) {

        if (choice == null || choice.isEmpty()) {
            throw new InvalidArgsException("An agent answer needs a choice", ANSWER, false, null);
        }

        Map<String, Object> arguments = agentArguments(agentId, ANSWER);
        arguments.put("choice", choice);
        ToolCalls.putTimeout(arguments, DEFAULT_TIMEOUT);

        return AgentTurn.decode(this.calls.structured(ANSWER, arguments), ANSWER);
    }

    /**
     * Wait (bounded) for the agent's next wake-up; a turn that finished meanwhile answers at once.
     *
     * @param timeout how long to wait, at most {@link AgentOptions#MAX_TIMEOUT}; null for {@link #DEFAULT_TIMEOUT}
     */
    public AgentTurn waitFor(String agentId, Duration timeout) {

        Map<String, Object> arguments = agentArguments(agentId, WAIT);
        ToolCalls.putTimeout(arguments, timeoutOrDefault(requireTimeout(timeout, WAIT)));

        return AgentTurn.decode(this.calls.structured(WAIT, arguments), WAIT);
    }

    /**
     * The newest job's answer, when no result handed it out before.
     *
     * @return the read; its {@link AgentTurn#answer()} is null and {@link AgentTurn#answerRecordId()} points at the
     *         record when an earlier result (a done send) already carried it
     */
    public AgentTurn finalAnswer(String agentId) {

        Map<String, Object> arguments = agentArguments(agentId, READ);
        arguments.put("final", true);

        return AgentTurn.decode(this.calls.structured(READ, arguments), READ);
    }

    /**
     * Stop the agent: its sessions are killed and its waiters end.
     *
     * @return whether the server reports it closed
     * @throws NotFoundException when no agent has that id
     */
    public boolean close(String agentId) {

        Map<String, Object> structured = this.calls.structured(CLOSE, agentArguments(agentId, CLOSE));

        return Json.optBool(structured, "closed", false);
    }

    private static Map<String, Object> agentArguments(String agentId, String tool) {

        if (agentId == null || agentId.isBlank()) {
            throw new InvalidArgsException("An agent call needs the agent's id", tool, false, null);
        }

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("agent", agentId);

        return arguments;
    }

    private static Duration timeoutOrDefault(Duration timeout) {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * @return the timeout itself
     * @throws InvalidArgsException when it is above the schema's {@link AgentOptions#MAX_TIMEOUT}
     */
    static Duration requireTimeout(Duration timeout, String tool) {

        if (timeout != null && timeout.compareTo(AgentOptions.MAX_TIMEOUT) > 0) {
            throw new InvalidArgsException("An agent wait is at most " + AgentOptions.MAX_TIMEOUT.toMillis()
                    + " ms; wait again with waitFor for longer turns: " + timeout, tool, false, null);
        }

        return timeout;
    }
}
