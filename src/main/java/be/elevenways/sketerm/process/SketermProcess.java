package be.elevenways.sketerm.process;

import be.elevenways.protoblast.server.process.CapturedOutput;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.SubprocessException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * A supervised Sketerm child process: UTF-8 line streams plus a stderr tail kept for diagnostics.
 *
 * The child runs through protoblast's {@link Subprocess}, so a forced stop reaches its whole
 * process tree and a crashed JVM never orphans a browser.
 *
 * AIDEV-NOTE: closing sends end-of-file first, which `sketerm mcp` answers by shutting its
 * browser helpers down itself (measured: gone in about 0.2s with every child reaped). Only a child
 * that outlives the close timeout is stopped as a tree: SIGTERM to the root and its descendants,
 * SIGKILL after {@link #STOP_GRACE}. Signalling the tree straight away would hit the helpers
 * before Sketerm flushed their profile cookies.
 */
public final class SketermProcess implements AutoCloseable {

    /** How many stderr lines {@link #getStderrTail()} keeps. */
    public static final int STDERR_RING_SIZE = 200;

    /** How long a tree that ignored end-of-file gets between SIGTERM and SIGKILL. */
    public static final Duration STOP_GRACE = Duration.ofSeconds(2);

    // Sketerm gives its browser helper up to four seconds to exit cleanly and another two after
    // SIGTERM. Stay beyond that window so profile cookies flush before force becomes necessary.
    private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000;
    private static final long DIAGNOSTIC_GRACE_MS = 500;
    private static final int STDERR_BYTES = 64 * 1024;

    private final List<String> command;
    private final RunningProcess process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;

    private volatile boolean closed;

    private SketermProcess(List<String> command, RunningProcess process) {

        this.command = List.copyOf(command);
        this.process = process;

        this.stdin = new BufferedWriter(new OutputStreamWriter(process.stdin(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8));
    }

    /**
     * Spawn a child process with stderr kept separate from stdout.
     *
     * @param command the full argv, the executable first
     * @param workingDirectory the child's cwd, or null for the JVM's
     * @param environmentOverrides entries added to (a null value removes from) the inherited environment
     * @throws SketermProcessException when the executable cannot be started
     */
    public static SketermProcess start(List<String> command,
                                       File workingDirectory,
                                       Map<String, String> environmentOverrides) {

        if (command == null || command.isEmpty()) {
            throw new SketermProcessException("Cannot start a process without a command");
        }

        Subprocess subprocess = Subprocess.of(new ArrayList<>(command))
                .stdinPipe()
                .streamStdout()
                .stderrLimit(STDERR_BYTES)
                .stopGrace(STOP_GRACE);

        if (workingDirectory != null) {
            subprocess.directory(workingDirectory.toPath());
        }

        if (environmentOverrides != null) {
            for (Map.Entry<String, String> entry : environmentOverrides.entrySet()) {
                subprocess.environment(entry.getKey(), entry.getValue());
            }
        }

        try {
            return new SketermProcess(command, subprocess.start());
        } catch (SubprocessException e) {
            throw new SketermProcessException("Failed to start " + String.join(" ", command), e);
        }
    }

    public List<String> getCommand() {
        return this.command;
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    /**
     * @return the child's pid
     */
    public long pid() {
        return this.process.pid();
    }

    /**
     * @return the child's exit code, or null while it is still running or its outcome is still settling
     */
    public Integer getExitCode() {

        if (this.process.isAlive()) {
            return null;
        }

        ProcessOutcome outcome = this.outcomeWithin(DIAGNOSTIC_GRACE_MS);

        return outcome == null ? null : outcome.exitCode();
    }

    public BufferedWriter getStdin() {
        return this.stdin;
    }

    public BufferedReader getStdout() {
        return this.stdout;
    }

    /**
     * @return the most recent stderr lines, oldest first, at most {@link #STDERR_RING_SIZE}
     */
    public List<String> getStderrTail() {
        return tailLines(this.process.stderrSoFar());
    }

    /**
     * A single-string description of how the child is doing, suitable for appending to any failure.
     *
     * A dying child races its own diagnostics, so this waits briefly for its outcome (stderr
     * fully drained) rather than reporting "still running" a millisecond too early.
     */
    public String describeFailureContext() {

        ProcessOutcome outcome = this.outcomeWithin(DIAGNOSTIC_GRACE_MS);

        StringBuilder builder = new StringBuilder();

        builder.append("process ").append(String.join(" ", this.command));
        builder.append(outcome == null ? " is still running" : " exited with code " + outcome.exitCode());

        List<String> tail = outcome == null ? this.getStderrTail() : tailLines(outcome.stderr());

        if (!tail.isEmpty()) {
            builder.append("; stderr tail:\n").append(String.join("\n", tail));
        } else {
            builder.append("; stderr was empty");
        }

        return builder.toString();
    }

    /**
     * Send end-of-file, wait for the child to leave, and stop its tree when it does not.
     */
    @Override
    public void close() {
        this.close(DEFAULT_CLOSE_TIMEOUT_MS);
    }

    /**
     * @param timeoutMs how long the child gets to exit on end-of-file before its tree is stopped
     */
    public void close(long timeoutMs) {

        if (this.closed) {
            return;
        }

        this.closed = true;

        try {
            this.stdin.close();
        } catch (IOException ignored) {
            // The child may already be gone; nothing to salvage.
        }

        if (this.process.isAlive()) {
            this.outcomeWithin(timeoutMs);
        }

        // Stops the tree when the child is still running, releases stdout and waits for the outcome.
        this.process.close();
    }

    /**
     * A convenience factory for the environment-override map.
     */
    public static Map<String, String> environment(String... keysAndValues) {

        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("environment() needs an even number of arguments");
        }

        Map<String, String> result = new LinkedHashMap<>();

        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put(keysAndValues[i], keysAndValues[i + 1]);
        }

        return result;
    }

    /**
     * @return the outcome when it arrives within the wait, else null
     */
    private ProcessOutcome outcomeWithin(long millis) {
        try {
            return this.process.await(Duration.ofMillis(millis));
        } catch (TimeoutException stillRunning) {
            return null;
        } catch (CompletionException interrupted) {
            return null;
        }
    }

    /**
     * @return the complete lines of a stderr capture, its newest {@link #STDERR_RING_SIZE}; a line the
     *         byte cap cut at its start is dropped
     */
    private static List<String> tailLines(CapturedOutput stderr) {

        String text = stderr.text();

        if (text.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));

        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        if (stderr.truncated() && !lines.isEmpty()) {
            lines.remove(0);
        }

        int from = Math.max(0, lines.size() - STDERR_RING_SIZE);

        return List.copyOf(lines.subList(from, lines.size()));
    }
}
