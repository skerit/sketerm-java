package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a sub-agent's turn stands after agent_send, agent_answer, agent_wait or a final agent_read.
 *
 * AIDEV-NOTE: the server hands every record out ONCE, whichever result carries it. A {@link AgentOutcome#DONE} send
 * already holds the job's answer, so a later {@link Agents#finalAnswer} answers no text, only
 * {@link #answerRecordId()} pointing at the record handed out before. Keep the answer from the turn that carried it.
 *
 * @param state          where the agent stands after the call
 * @param outcome        the wake-up that ended the wait; null for a read, which waits for nothing
 * @param answer         the text of the job's final assistant record, null when this result carried none
 * @param answerRecordId the id of that record, or of the one an earlier result handed out instead; null when none
 * @param job            the job (one prompt and all the agent did until the next) the answer belongs to, null when
 *                       none
 * @param timedOut       whether the call's budget ran out before a wake-up
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public record AgentTurn(AgentState state,
                        AgentOutcome outcome,
                        String answer,
                        Long answerRecordId,
                        Long job,
                        boolean timedOut) {

    public AgentTurn {
        Objects.requireNonNull(state, "state");
    }

    /**
     * @return the agent's id
     */
    public String agent() {
        return this.state.agent();
    }

    /**
     * @return what its app reports about itself
     */
    public AgentFacts facts() {
        return this.state.facts();
    }

    /**
     * @return whether the job settled
     */
    public boolean isDone() {
        return this.outcome == AgentOutcome.DONE;
    }

    /**
     * @throws ProtocolMismatchException when the answer names no agent or no state
     * @throws SketermApiException when the outcome, state or interaction kind is not a known one
     */
    static AgentTurn decode(Map<String, Object> structured, String tool) {

        AgentState state = AgentState.decode(structured, tool);
        String outcome = Json.optStr(structured, "outcome");
        Map<String, Object> last = lastAssistant(Json.optList(structured, "records"));
        String answer = null;
        Long recordId = null;
        Long job = null;

        if (last != null) {
            answer = Json.optStr(last, "text");
            recordId = Json.optLong(last, "id");
            job = Json.optLong(last, "job");
        } else {
            // A result can name the answer in message without a record for it (a job's answer that does not
            // repeat), or point at the record an earlier result handed out.
            answer = Json.optStr(structured, "message");
            Map<String, Object> earlier = newestEarlier(Json.optList(structured, "jobs"));

            if (earlier != null) {
                recordId = Json.optLong(Json.optMap(earlier, "earlier"), "id");
                job = Json.optLong(earlier, "job");
            }
        }

        return new AgentTurn(state,
                outcome == null ? null : AgentOutcome.require(outcome),
                answer,
                recordId,
                job,
                Json.optBool(structured, "timed_out", false));
    }

    /**
     * @return the newest job's last assistant record, or null when the records hold none
     */
    private static Map<String, Object> lastAssistant(List<Object> records) {

        if (records == null) {
            return null;
        }

        Map<String, Object> found = null;
        long foundJob = Long.MIN_VALUE;

        // Records are chronological, so the last assistant one of the highest job is its final answer.
        for (Object element : records) {
            Map<String, Object> record = Json.asMap(element, "an agent record");
            Long job = Json.optLong(record, "job");
            long current = job == null ? Long.MIN_VALUE : job;

            if ("assistant".equals(Json.optStr(record, "kind")) && current >= foundJob) {
                found = record;
                foundJob = current;
            }
        }

        return found;
    }

    /**
     * @return the newest job entry that points at an earlier record, or null when none does
     */
    private static Map<String, Object> newestEarlier(List<Object> jobs) {

        if (jobs == null) {
            return null;
        }

        Map<String, Object> found = null;

        for (Object element : jobs) {
            Map<String, Object> job = Json.asMap(element, "an agent job");

            if (Json.optMap(job, "earlier") != null) {
                found = job;
            }
        }

        return found;
    }
}
