package be.elevenways.sketerm.api;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to launch the `sketerm mcp` child: argv, environment and the default per-call deadline.
 */
public final class SketermOptions {

    /** What the binary is called when nothing pins it; resolved through PATH. */
    public static final String DEFAULT_BINARY = "sketerm";

    /** The groups the api layer needs: browser for web_*, core for capabilities. */
    public static final String DEFAULT_TOOL_GROUPS = "browser,core";

    private final String binaryPath;
    private final String toolGroups;
    private final String instanceName;
    private final List<String> extraArgs;
    private final File workingDirectory;
    private final Map<String, String> environment;
    private final Duration defaultTimeout;

    private SketermOptions(Builder builder) {
        this.binaryPath = builder.binaryPath;
        this.toolGroups = builder.toolGroups;
        this.instanceName = builder.instanceName;
        this.extraArgs = List.copyOf(builder.extraArgs);
        this.workingDirectory = builder.workingDirectory;
        // Not Map.copyOf: a null value is meaningful here, it removes an inherited variable.
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        this.defaultTimeout = builder.defaultTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The default launch: `sketerm mcp --tools browser,core` with the JVM's own cwd and environment.
     */
    public static SketermOptions defaults() {
        return builder().build();
    }

    public String binaryPath() {
        return this.binaryPath;
    }

    public String toolGroups() {
        return this.toolGroups;
    }

    /**
     * @return the --name value, or null for an anonymous server
     */
    public String instanceName() {
        return this.instanceName;
    }

    public List<String> extraArgs() {
        return this.extraArgs;
    }

    /**
     * @return the child's cwd, or null for the JVM's
     */
    public File workingDirectory() {
        return this.workingDirectory;
    }

    /**
     * @return environment entries added to the inherited environment; a null value removes one
     */
    public Map<String, String> environment() {
        return this.environment;
    }

    public Duration defaultTimeout() {
        return this.defaultTimeout;
    }

    /**
     * @return the full argv, the executable first
     */
    public List<String> command() {

        List<String> command = new ArrayList<>();
        command.add(this.binaryPath);
        command.add("mcp");

        if (this.toolGroups != null && !this.toolGroups.isBlank()) {
            command.add("--tools");
            command.add(this.toolGroups);
        }

        if (this.instanceName != null && !this.instanceName.isBlank()) {
            command.add("--name");
            command.add(this.instanceName);
        }

        command.addAll(this.extraArgs);

        return List.copyOf(command);
    }

    public static final class Builder {

        private String binaryPath = DEFAULT_BINARY;
        private String toolGroups = DEFAULT_TOOL_GROUPS;
        private String instanceName;
        private final List<String> extraArgs = new ArrayList<>();
        private File workingDirectory;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Duration defaultTimeout = Duration.ofSeconds(30);

        private Builder() {
        }

        public Builder binaryPath(String binaryPath) {
            this.binaryPath = binaryPath;
            return this;
        }

        /**
         * @param toolGroups the --tools value; blank or null publishes every group
         */
        public Builder toolGroups(String toolGroups) {
            this.toolGroups = toolGroups;
            return this;
        }

        /**
         * @param instanceName the --name value, which makes the server's sessions survive restarts
         */
        public Builder instanceName(String instanceName) {
            this.instanceName = instanceName;
            return this;
        }

        public Builder extraArgs(String... args) {
            this.extraArgs.addAll(List.of(args));
            return this;
        }

        public Builder extraArgs(List<String> args) {
            this.extraArgs.addAll(args);
            return this;
        }

        public Builder workingDirectory(File workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * @param value null removes the variable from the inherited environment
         */
        public Builder env(String name, String value) {
            this.environment.put(name, value);
            return this;
        }

        public Builder env(Map<String, String> environment) {
            this.environment.putAll(environment);
            return this;
        }

        /**
         * @param defaultTimeout the deadline applied to every call that names none
         */
        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public SketermOptions build() {
            return new SketermOptions(this);
        }
    }
}
