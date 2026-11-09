package be.elevenways.sketerm.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How to open a sub-agent with agent_open; every field but the app is optional and null leaves the server's default.
 *
 * {@code env} is how one server runs agents under separate Claude Code identities: CLAUDE_CONFIG_DIR per agent. The
 * variables are set on every process started with the app's binary, relaunches included, after the adapter's own
 * unset list. They are NOT for secrets: on an SSH host they may ride the start script on the ssh command line.
 *
 * @author Matthias Vandermaesen
 * @since 0.1.0
 */
public final class AgentOptions {

    /** Claude Code's adapter id. */
    public static final String CLAUDE = "claude";

    /** What agent_open waits at most for the agent to start (the schema's max of timeout_ms). */
    public static final Duration MAX_TIMEOUT = Duration.ofMillis(120_000);

    /** The schema's cap on args entries and on env variables. */
    static final int MAX_ENTRIES = 64;
    /** The schema's cap on one args entry or env value, in UTF-8 bytes. */
    static final int MAX_VALUE_BYTES = 4096;

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final String app;
    private final String name;
    private final Path cwd;
    private final String model;
    private final String effort;
    private final Map<String, String> env;
    private final List<String> args;
    private final Duration timeout;

    private AgentOptions(Builder builder) {
        this.app = builder.app;
        this.name = builder.name;
        this.cwd = builder.cwd;
        this.model = builder.model;
        this.effort = builder.effort;
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(builder.env));
        this.args = List.copyOf(builder.args);
        this.timeout = builder.timeout;
    }

    /**
     * @return a builder for Claude Code
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Claude Code in the server's own working directory, with its defaults.
     */
    public static AgentOptions claude() {
        return builder().build();
    }

    /** @return the adapter id: claude, opencode or a user adapter */
    public String app() {
        return this.app;
    }

    /** @return the alias usable wherever the id is, null for none */
    public String name() {
        return this.name;
    }

    /** @return the absolute working directory, null for the server's */
    public Path cwd() {
        return this.cwd;
    }

    /** @return the model for this session only, null for the app's default */
    public String model() {
        return this.model;
    }

    /** @return the reasoning effort for this session only, null for the app's default */
    public String effort() {
        return this.effort;
    }

    /** @return the variables set on the app's processes, in insertion order */
    public Map<String, String> env() {
        return this.env;
    }

    /** @return the extra arguments inserted right after the binary, before the adapter's own */
    public List<String> args() {
        return this.args;
    }

    /** @return how long agent_open waits for the agent, null for the server's default (60 s) */
    public Duration timeout() {
        return this.timeout;
    }

    /**
     * The agent_open arguments, without timeout_ms ({@link Agents} owns the deadline).
     */
    Map<String, Object> toWire() {

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("app", this.app);
        ToolCalls.put(arguments, "name", this.name);
        ToolCalls.put(arguments, "cwd", this.cwd == null ? null : this.cwd.toString());
        ToolCalls.put(arguments, "model", this.model);
        ToolCalls.put(arguments, "effort", this.effort);

        if (!this.env.isEmpty()) {
            arguments.put("env", new LinkedHashMap<>(this.env));
        }

        if (!this.args.isEmpty()) {
            arguments.put("args", new ArrayList<>(this.args));
        }

        return arguments;
    }

    private static void requireWireText(String value, String what) {

        if (CONTROL.matcher(value).find()) {
            throw new InvalidArgsException(what + " must hold no control characters", Agents.OPEN, false, null);
        }

        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new InvalidArgsException(what + " is longer than " + MAX_VALUE_BYTES + " bytes", Agents.OPEN,
                    false, null);
        }
    }

    public static final class Builder {

        private String app = CLAUDE;
        private String name;
        private Path cwd;
        private String model;
        private String effort;
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> args = new ArrayList<>();
        private Duration timeout;

        private Builder() {
        }

        /**
         * @param app the adapter id; claude by default
         */
        public Builder app(String app) {
            this.app = app;
            return this;
        }

        /**
         * @param name an alias unique among this machine's live agents (1-64 letters, digits, '.', '-', '_'); a taken
         *             one is refused as a conflict
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * @param cwd an absolute working directory
         */
        public Builder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        /**
         * @param model the model for this session only, never saved as the user's default
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * @param effort the reasoning effort for this session only
         */
        public Builder effort(String effort) {
            this.effort = effort;
            return this;
        }

        /**
         * @param name  a name matching {@code [A-Za-z_][A-Za-z0-9_]*}, like CLAUDE_CONFIG_DIR
         * @param value at most 4096 bytes of UTF-8, no control characters
         */
        public Builder env(String name, String value) {
            this.env.put(name, value);
            return this;
        }

        public Builder env(Map<String, String> environment) {
            this.env.putAll(environment);
            return this;
        }

        public Builder args(String... args) {
            this.args.addAll(List.of(args));
            return this;
        }

        public Builder args(List<String> args) {
            this.args.addAll(args);
            return this;
        }

        /**
         * @param timeout how long agent_open waits for the agent, at most {@link #MAX_TIMEOUT}
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * @throws InvalidArgsException when a value is outside what agent_open's schema takes
         */
        public AgentOptions build() {

            if (this.app == null || this.app.isBlank()) {
                throw new InvalidArgsException("An agent needs an app (adapter id)", Agents.OPEN, false, null);
            }

            if (this.cwd != null && !this.cwd.isAbsolute()) {
                throw new InvalidArgsException("An agent's cwd must be absolute: " + this.cwd, Agents.OPEN, false,
                        null);
            }

            if (this.env.size() > MAX_ENTRIES || this.args.size() > MAX_ENTRIES) {
                throw new InvalidArgsException("An agent takes at most " + MAX_ENTRIES + " env variables and "
                        + MAX_ENTRIES + " args", Agents.OPEN, false, null);
            }

            for (Map.Entry<String, String> entry : this.env.entrySet()) {
                if (entry.getKey() == null || !ENV_NAME.matcher(entry.getKey()).matches()) {
                    throw new InvalidArgsException("Not an environment variable name: " + entry.getKey(),
                            Agents.OPEN, false, null);
                }

                if (entry.getValue() == null) {
                    throw new InvalidArgsException("The env variable " + entry.getKey() + " has no value",
                            Agents.OPEN, false, null);
                }

                // AIDEV-NOTE: the refusal names the variable, never its value.
                requireWireText(entry.getValue(), "The env variable " + entry.getKey());
            }

            for (String arg : this.args) {
                if (arg == null || arg.isEmpty()) {
                    throw new InvalidArgsException("An agent arg must not be empty", Agents.OPEN, false, null);
                }

                requireWireText(arg, "An agent arg");
            }

            Agents.requireTimeout(this.timeout, Agents.OPEN);

            return new AgentOptions(this);
        }
    }
}
