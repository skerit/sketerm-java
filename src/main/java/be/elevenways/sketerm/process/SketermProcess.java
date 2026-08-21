package be.elevenways.sketerm.process;

import be.elevenways.protoblast.common.thread.JobRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A supervised Sketerm child process: UTF-8 line streams plus a stderr ring kept for diagnostics.
 *
 * <p>A shutdown hook destroys the child, so a crashed JVM never orphans a browser.</p>
 */
public final class SketermProcess implements AutoCloseable {

    /** How many stderr lines the diagnostic ring keeps. */
    public static final int STDERR_RING_SIZE = 200;

    // Sketerm gives its browser helper up to four seconds to exit cleanly and another two after
    // SIGTERM. Stay beyond that window so profile cookies flush before force becomes necessary.
    private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000;
    private static final long DIAGNOSTIC_GRACE_MS = 500;

    private final List<String> command;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final JobRunner jobRunner;
    private final Deque<String> stderrRing = new ArrayDeque<>();
    private final CountDownLatch stderrDrained = new CountDownLatch(1);
    private final Thread shutdownHook;

    private volatile boolean closed;

    private SketermProcess(List<String> command, Process process) {

        this.command = List.copyOf(command);
        this.process = process;

        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        this.jobRunner = JobRunner.create("sketerm-process");
        this.jobRunner.startThread(this::pumpStderr);

        this.shutdownHook = new Thread(process::destroyForcibly, "sketerm-process-reaper");
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
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

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(false);

        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }

        if (environmentOverrides != null) {
            Map<String, String> environment = builder.environment();

            for (Map.Entry<String, String> entry : environmentOverrides.entrySet()) {
                if (entry.getValue() == null) {
                    environment.remove(entry.getKey());
                } else {
                    environment.put(entry.getKey(), entry.getValue());
                }
            }
        }

        try {
            return new SketermProcess(command, builder.start());
        } catch (IOException e) {
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
     * @return the child's exit code, or null while it is still running
     */
    public Integer getExitCode() {
        return this.process.isAlive() ? null : this.process.exitValue();
    }

    public BufferedWriter getStdin() {
        return this.stdin;
    }

    public BufferedReader getStdout() {
        return this.stdout;
    }

    /**
     * @return the most recent stderr lines, oldest first
     */
    public List<String> getStderrTail() {
        synchronized (this.stderrRing) {
            return new ArrayList<>(this.stderrRing);
        }
    }

    /**
     * A single-string description of how the child is doing, suitable for appending to any failure.
     *
     * <p>A dying child races its own diagnostics, so this waits briefly for the exit status and for
     * the stderr pump to finish rather than reporting "still running" a millisecond too early.</p>
     */
    public String describeFailureContext() {

        try {
            this.process.waitFor(DIAGNOSTIC_GRACE_MS, TimeUnit.MILLISECONDS);
            this.stderrDrained.await(DIAGNOSTIC_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        StringBuilder builder = new StringBuilder();
        Integer exit = this.getExitCode();

        builder.append("process ").append(String.join(" ", this.command));
        builder.append(exit == null ? " is still running" : " exited with code " + exit);

        List<String> tail = this.getStderrTail();

        if (!tail.isEmpty()) {
            builder.append("; stderr tail:\n").append(String.join("\n", tail));
        } else {
            builder.append("; stderr was empty");
        }

        return builder.toString();
    }

    /**
     * Close stdin, then destroy, wait, and finally force-kill.
     */
    @Override
    public void close() {
        this.close(DEFAULT_CLOSE_TIMEOUT_MS);
    }

    /**
     * @param timeoutMs how long a SIGTERM'ed child gets before destroyForcibly
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

        this.process.destroy();

        try {
            if (!this.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                this.process.destroyForcibly();
                this.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            this.process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        this.jobRunner.shutdownNow();

        try {
            this.stdout.close();
        } catch (IOException ignored) {
            // Same as stdin.
        }

        try {
            Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down; the hook is running or ran.
        }
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

    private void pumpStderr() {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(this.process.getErrorStream(), StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                synchronized (this.stderrRing) {
                    this.stderrRing.addLast(line);

                    while (this.stderrRing.size() > STDERR_RING_SIZE) {
                        this.stderrRing.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // The stream dies with the process; the ring keeps whatever arrived.
        } finally {
            this.stderrDrained.countDown();
        }
    }
}
