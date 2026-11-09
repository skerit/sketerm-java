package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.sketerm.api.FakeSketermServer.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sub-agent face, decoding the answers a real Claude Code run gave (sketerm 2026-11, Claude Code 2.1.319).
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
class AgentsApiTest {

    private static final String WATCH = "/usr/bin/sketerm-mcp mcp agent-wait --socket /run/user/1001/agents.sock"
            + " claude-dfmn";

    /** agent_open on a fresh folder: Claude Code asks whether to trust it. */
    private static final String OPENED = """
            {"binary": "/home/agent/.local/bin/claude", "binary_version": "2.1.319 (Claude Code)", "path_prepend": [],
             "cwd": "/work", "args": [], "env_names": [], "prompt_sent": false, "agent": "claude-dfmn",
             "app": "claude", "source": "screen", "state": "waiting_user", "ready": false,
             "session": "agent-claude-dfmn", "conversation": "2f5186b7-64c5-4480-bfbe-11a76a1e6f6c",
             "transport": "local", "events": [{"seq": 1, "kind": "needs_input", "at": "2026-11-09T16:01:47+01:00",
             "text": "permission: Permission Required: Accessing workspace:",
             "detail": "1. Yes, I trust this folder\\n2. No, exit", "count": 1}],
             "interaction": {"kind": "permission", "title": "Permission Required: Accessing workspace:",
             "detail": "/work\\nQuick safety check: Is this a project you created or one you trust?",
             "hint": "Enter y/n:", "options": [{"label": "Yes, I trust this folder", "selected": false, "key": "y"},
             {"label": "No, exit", "selected": false, "key": "n"}], "free_text": false},
             "watch_command": "WATCH"}
            """;

    /** agent_answer {choice: "1"} on the trust prompt. */
    private static final String ANSWERED = """
            {"answered": "Yes, I trust this folder", "agent": "claude-dfmn", "app": "claude", "source": "screen",
             "state": "idle", "ready": true, "session": "agent-claude-dfmn",
             "conversation": "2f5186b7-64c5-4480-bfbe-11a76a1e6f6c", "transport": "local",
             "facts": {"context_window_tokens": 1000000, "cost_usd": 0}, "events": [], "watch_command": "WATCH"}
            """;

    /** agent_send that settled within its budget. */
    private static final String DONE = """
            {"queued": false, "agent": "claude-dfmn", "app": "claude", "source": "screen", "state": "idle",
             "ready": true, "session": "agent-claude-dfmn", "conversation": "2f5186b7-64c5-4480-bfbe-11a76a1e6f6c",
             "transport": "local", "facts": {"context_used_percent": 5, "context_window_tokens": 1000000,
             "context_used_tokens": 46574, "rate_7d_used_percent": 44, "rate_7d_resets_at": 1794625200,
             "cost_usd": 0.2168118}, "outcome": "done", "records": [{"id": 4, "kind": "assistant",
             "at": "2026-11-09T16:01:57+01:00", "text": "{\\"color\\": \\"#c8006e\\", \\"sum\\": 5}", "job": 0,
             "synthetic": false}], "jobs": [{"job": 0, "omitted_messages": 0, "omitted_tools": 2,
             "returned_before": 0}], "cut_ids": [], "message": "{\\"color\\": \\"#c8006e\\", \\"sum\\": 5}",
             "timed_out": false, "events": [{"seq": 3, "kind": "done", "at": "2026-11-09T16:01:57+01:00",
             "text": "{\\"color\\": \\"#c8006e\\", \\"sum\\": 5}", "detail": "", "count": 1, "job": 0, "record": 4}],
             "watch_command": "WATCH"}
            """;

    /** agent_wait whose budget ran out with nothing new. */
    private static final String STILL_WORKING = """
            {"agent": "claude-dfmn", "app": "claude", "source": "screen", "state": "idle", "ready": true,
             "session": "agent-claude-dfmn", "conversation": "2f5186b7-64c5-4480-bfbe-11a76a1e6f6c",
             "transport": "local", "facts": {"context_used_percent": 5, "context_window_tokens": 1000000,
             "context_used_tokens": 53135, "rate_7d_used_percent": 44, "rate_7d_resets_at": 1794625200,
             "cost_usd": 0.2793622}, "outcome": "still_working", "timed_out": true, "events": [],
             "watch_command": "WATCH"}
            """;

    /** agent_read {final: true} after the done send already handed the answer out. */
    private static final String FINAL_HANDED_OUT = """
            {"records": [], "jobs": [{"job": 0, "omitted_messages": 0, "omitted_tools": 2, "returned_before": 1,
             "earlier": {"id": 4, "kind": "assistant", "chars": 57}}], "cut_ids": [], "final": true,
             "detail": "selected", "next_since": 4, "more": false, "agent": "claude-dfmn", "app": "claude",
             "source": "screen", "state": "idle", "ready": true, "session": "agent-claude-dfmn",
             "conversation": "2f5186b7-64c5-4480-bfbe-11a76a1e6f6c", "transport": "local",
             "facts": {"context_used_percent": 5, "cost_usd": 0.2793622}, "events": [], "watch_command": "WATCH"}
            """;

    private static Map<String, Object> measured(String json) {
        return Json.parseObject(json.replace("WATCH", WATCH));
    }

    @Test
    @DisplayName("Open, trust the folder, send, wait and close decode the measured answers")
    void agentJourney() {

        FakeSketermServer server = new FakeSketermServer()
                .on(Agents.OPEN, measured(OPENED))
                .on(Agents.ANSWER, measured(ANSWERED))
                .on(Agents.SEND, measured(DONE))
                .on(Agents.WAIT, measured(STILL_WORKING))
                .on(Agents.READ, measured(FINAL_HANDED_OUT))
                .on(Agents.CLOSE, map("agent", "claude-dfmn", "closed", true, "sessions", List.of("agent-claude-dfmn")));

        Agents agents = server.agents();

        // 1. The open sends only the arguments the schema has, the deadline always included
        AgentState opened = agents.open(AgentOptions.builder()
                .cwd(Path.of("/work"))
                .model("opus")
                .env("CLAUDE_CONFIG_DIR", "/home/agent/.claude-audit")
                .args("--verbose")
                .build());
        Map<String, Object> openArgs = server.lastArguments(Agents.OPEN);

        assertEquals("claude", openArgs.get("app"), "step 1: claude is the default app");
        assertEquals("/work", openArgs.get("cwd"), "step 1: the cwd went out as a path string");
        assertEquals("opus", openArgs.get("model"), "step 1: the model went out");
        assertEquals(Map.of("CLAUDE_CONFIG_DIR", "/home/agent/.claude-audit"), openArgs.get("env"),
                "step 1: the env went out as a name-to-value object");
        assertEquals(List.of("--verbose"), openArgs.get("args"), "step 1: the args went out");
        assertEquals(60_000L, ((Number) openArgs.get("timeout_ms")).longValue(),
                "step 1: the server's default wait went out explicitly");
        assertFalse(openArgs.containsKey("name"), "step 1: an unset field leaves the server's default");

        // 2. The opened agent waits on the folder trust
        assertEquals("claude-dfmn", opened.agent(), "step 2: the id came back");
        assertEquals(AgentStatus.WAITING_USER, opened.status(), "step 2: it waits on the user");
        assertFalse(opened.ready(), "step 2: and is not ready");
        assertTrue(opened.needsAnswer(), "step 2: so it needs an answer");
        assertEquals(AgentInteraction.Kind.PERMISSION, opened.interaction().kind(), "step 2: a permission");
        assertEquals("Yes, I trust this folder", opened.interaction().options().get(0).label(),
                "step 2: whose first option trusts the folder");
        assertEquals("y", opened.interaction().options().get(0).key(), "step 2: with its key letter");
        assertFalse(opened.interaction().freeText(), "step 2: and no free-text route");
        assertEquals("2f5186b7-64c5-4480-bfbe-11a76a1e6f6c", opened.conversation(), "step 2: its conversation");
        assertEquals(AgentFacts.NONE, opened.facts(), "step 2: no facts were reported yet");
        assertEquals(WATCH, opened.watchCommand(), "step 2: the watch command came back");

        // 3. Answering the trust prompt leaves it idle and ready
        AgentTurn trusted = agents.answer(opened.agent(), "1");

        assertEquals("1", server.lastArguments(Agents.ANSWER).get("choice"), "step 3: the choice went out");
        assertEquals("claude-dfmn", server.lastArguments(Agents.ANSWER).get("agent"), "step 3: for this agent");
        assertEquals(AgentStatus.IDLE, trusted.state().status(), "step 3: the agent is idle");
        assertTrue(trusted.state().ready(), "step 3: and ready");
        assertNull(trusted.state().interaction(), "step 3: nothing is asked any more");
        assertNull(trusted.outcome(), "step 3: the trust answer started no turn");
        assertNull(trusted.answer(), "step 3: so it carries no answer");
        assertEquals(0, trusted.facts().costUsd().signum(), "step 3: a reported zero cost is zero, not absent");
        assertNull(trusted.facts().contextUsedTokens(), "step 3: an unreported fact is null, not 0");

        // 4. A send that settled carries the job's final assistant record and the facts
        AgentTurn done = agents.send(opened.agent(), "Read shot.png", Duration.ofMinutes(2));
        Map<String, Object> sendArgs = server.lastArguments(Agents.SEND);

        assertEquals("Read shot.png", sendArgs.get("text"), "step 4: the prompt went out");
        assertEquals(120_000L, ((Number) sendArgs.get("timeout_ms")).longValue(), "step 4: with its wait");
        assertEquals(AgentOutcome.DONE, done.outcome(), "step 4: the job settled");
        assertTrue(done.isDone(), "step 4: done");
        assertEquals("{\"color\": \"#c8006e\", \"sum\": 5}", done.answer(), "step 4: the answer is the record's text");
        assertEquals(4L, done.answerRecordId(), "step 4: of record 4");
        assertEquals(0L, done.job(), "step 4: in job 0");
        assertFalse(done.timedOut(), "step 4: within the budget");

        AgentFacts facts = done.facts();

        assertEquals(new BigDecimal("0.2168118"), facts.costUsd(), "step 4: the cost is exact");
        assertEquals(46_574L, facts.contextUsedTokens(), "step 4: the context in use");
        assertEquals(1_000_000L, facts.contextWindowTokens(), "step 4: the context window");
        assertEquals(5, facts.contextUsedPercent(), "step 4: the context percentage");
        assertEquals(44, facts.rate7dUsedPercent(), "step 4: the seven-day limit spent");
        assertEquals(Instant.ofEpochSecond(1794625200L), facts.rate7dResetsAt(), "step 4: and when it resets");
        assertEquals(6, facts.all().size(), "step 4: every reported fact is kept by name");

        // 5. A wait whose budget ran out is data, not a failure
        AgentTurn waiting = agents.waitFor(opened.agent(), null);

        assertEquals(60_000L, ((Number) server.lastArguments(Agents.WAIT).get("timeout_ms")).longValue(),
                "step 5: no timeout sends the server's default");
        assertEquals(AgentOutcome.STILL_WORKING, waiting.outcome(), "step 5: still working");
        assertTrue(waiting.outcome().isUnfinished(), "step 5: which is unfinished");
        assertTrue(waiting.timedOut(), "step 5: the budget ran out");
        assertNull(waiting.answer(), "step 5: no answer");

        // 6. The final read after a done send points at the record handed out before
        AgentTurn read = agents.finalAnswer(opened.agent());

        assertEquals(true, server.lastArguments(Agents.READ).get("final"), "step 6: a final read went out");
        assertNull(read.outcome(), "step 6: a read has no outcome");
        assertNull(read.answer(), "step 6: the text is not repeated");
        assertEquals(4L, read.answerRecordId(), "step 6: but the record is named");
        assertEquals(0L, read.job(), "step 6: in its job");

        // 7. Closing reports it closed
        assertTrue(agents.close(opened.agent()), "step 7: the agent is closed");
        assertEquals("claude-dfmn", server.lastArguments(Agents.CLOSE).get("agent"), "step 7: by its id");
    }

    @Test
    @DisplayName("A final read that still holds the answer returns its text")
    void finalAnswerReturnsUndeliveredText() {

        Map<String, Object> read = new LinkedHashMap<>(measured(FINAL_HANDED_OUT));
        read.put("records", List.of(map("id", 7, "kind", "notice", "text", "permission answered", "job", 1),
                map("id", 8, "kind", "assistant", "text", "first", "job", 1),
                map("id", 9, "kind", "assistant", "text", "the answer", "job", 1)));

        AgentTurn turn = new FakeSketermServer().on(Agents.READ, read).agents().finalAnswer("claude-dfmn");

        assertEquals("the answer", turn.answer(), "the job's last assistant record is the answer");
        assertEquals(9L, turn.answerRecordId(), "named by its id");
        assertEquals(1L, turn.job(), "in its job");
    }

    @Test
    @DisplayName("Options outside agent_open's schema are refused before anything goes out")
    void optionsRefusedLocally() {

        assertThrows(InvalidArgsException.class, () -> AgentOptions.builder().cwd(Path.of("relative")).build(),
                "a relative cwd");
        assertThrows(InvalidArgsException.class, () -> AgentOptions.builder().env("1BAD", "x").build(),
                "a bad variable name");
        assertThrows(InvalidArgsException.class, () -> AgentOptions.builder().env("A", "line\nbreak").build(),
                "a control character in a value");
        assertThrows(InvalidArgsException.class, () -> AgentOptions.builder().app(" ").build(), "a blank app");
        assertThrows(InvalidArgsException.class, () -> AgentOptions.builder().timeout(Duration.ofMinutes(3)).build(),
                "a wait above the schema's maximum");

        FakeSketermServer server = new FakeSketermServer();
        Agents agents = server.agents();

        assertThrows(InvalidArgsException.class, () -> agents.send("claude-dfmn", "hi", Duration.ofMinutes(3)),
                "a send wait above the maximum");
        assertThrows(InvalidArgsException.class, () -> agents.send(" ", "hi", null), "a blank agent id");
        assertTrue(server.calls().stream().noneMatch(call -> call.tool().startsWith("agent_")),
                "and nothing went out");
    }

    @Test
    @DisplayName("A tool failure and an unknown state fail as typed api exceptions")
    void failuresAreTyped() {

        FakeSketermServer server = new FakeSketermServer()
                .onError(Agents.CLOSE, "not_found", "no agent claude-gone", false)
                .on(Agents.WAIT, map("agent", "claude-dfmn", "state", "napping", "outcome", "done"));
        Agents agents = server.agents();

        assertThrows(NotFoundException.class, () -> agents.close("claude-gone"), "not_found is typed");
        assertThrows(SketermApiException.class, () -> agents.waitFor("claude-dfmn", null),
                "an unknown state fails closed");
    }
}
