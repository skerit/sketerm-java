package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.process.SketermProcess;
import be.elevenways.sketerm.rpc.JsonRpcConnection;
import be.elevenways.sketerm.rpc.SketermTransport;
import be.elevenways.sketerm.rpc.StdioTransport;

import java.time.Duration;
import java.util.List;

/**
 * A launched Sketerm server: process, transport, connection and session composed as one closeable.
 *
 * Closing tears the stack down in order and cannot hang: the process layer ends in a
 * force-kill.
 */
public final class Sketerm implements AutoCloseable {

    /** What `sketerm mcp` prints and exits with for a flag it does not know (since 2026-07). */
    private static final String UNKNOWN_FLAG_MESSAGE = "sketerm mcp: unknown flag";
    private static final int UNKNOWN_FLAG_EXIT = 2;
    /** Its stdout closing (the handshake's failure) races its exit status. */
    private static final long UNKNOWN_FLAG_EXIT_WAIT_MS = 2_000;

    private final SketermOptions options;
    private final SketermProcess process;
    private final JsonRpcConnection connection;
    private final McpSession session;
    private final Browser browser;
    private final ToolCalls calls;

    private volatile boolean closed;

    private Sketerm(SketermOptions options,
                    SketermProcess process,
                    JsonRpcConnection connection,
                    McpSession session) {
        this.options = options;
        this.process = process;
        this.connection = connection;
        this.session = session;
        this.calls = new ToolCalls(session, options.defaultTimeout());
        this.browser = new Browser(this.calls);
    }

    /**
     * Launch `sketerm mcp` with the default options.
     */
    public static Sketerm launch() {
        return launch(SketermOptions.defaults());
    }

    /**
     * Spawn the child and complete the MCP handshake.
     *
     * A server that predates {@link SketermOptions#STRUCTURED_FLAG} refuses it as an unknown flag
     * before it speaks MCP (exit 2), so that one refusal relaunches it with
     * {@link SketermOptions#legacyCommand()}: such a server always sends structuredContent.
     *
     * @throws be.elevenways.sketerm.process.SketermProcessException when the binary cannot start
     * @throws be.elevenways.sketerm.mcp.McpException when the handshake fails
     */
    public static Sketerm launch(SketermOptions options) {

        Sketerm sketerm = start(options, options.command(), true);

        return sketerm != null ? sketerm : start(options, options.legacyCommand(), false);
    }

    /**
     * @return null when {@code flagMayBeUnknown} and the child refused the structured flag as unknown
     */
    private static Sketerm start(SketermOptions options, List<String> command, boolean flagMayBeUnknown) {

        SketermProcess process = SketermProcess.start(command,
                options.workingDirectory(),
                options.environment());

        JsonRpcConnection connection = new JsonRpcConnection(new StdioTransport(process));

        Duration timeout = options.defaultTimeout();

        if (timeout != null) {
            connection.setTimeoutMs(timeout.toMillis());
        }

        try {
            return new Sketerm(options, process, connection, McpSession.initialize(connection));
        } catch (RuntimeException e) {
            boolean refused = flagMayBeUnknown && refusedAsUnknownFlag(process);
            connection.close();

            if (refused) {
                return null;
            }

            throw e;
        }
    }

    /**
     * Whether the child exited the way an older `sketerm mcp` answers any flag it does not know.
     */
    static boolean refusedAsUnknownFlag(SketermProcess process) {

        Integer code = process.exitCodeWithin(UNKNOWN_FLAG_EXIT_WAIT_MS);

        return code != null && code == UNKNOWN_FLAG_EXIT
                && process.getStderrTail().stream().anyMatch(line -> line.contains(UNKNOWN_FLAG_MESSAGE));
    }

    /**
     * Complete the MCP handshake over a transport someone else opened: a scripted server in a test,
     * or a future non-stdio transport. {@link #process()} is then null; closing closes the transport.
     *
     * @throws be.elevenways.sketerm.mcp.McpException when the handshake fails
     */
    public static Sketerm connect(SketermOptions options, SketermTransport transport) {

        JsonRpcConnection connection = new JsonRpcConnection(transport);

        Duration timeout = options.defaultTimeout();

        if (timeout != null) {
            connection.setTimeoutMs(timeout.toMillis());
        }

        try {
            return new Sketerm(options, null, connection, McpSession.initialize(connection));
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public SketermOptions options() {
        return this.options;
    }

    /**
     * The browser face; every web_* call goes through it.
     */
    public Browser browser() {
        return this.browser;
    }

    /**
     * The session underneath, for tools this face does not model yet.
     */
    public McpSession session() {
        return this.session;
    }

    public JsonRpcConnection connection() {
        return this.connection;
    }

    /**
     * @return the child process, or null for a server reached through {@link #connect}
     */
    public SketermProcess process() {
        return this.process;
    }

    /**
     * @return the server name from the handshake, or null when it sent none
     */
    public String serverName() {
        return this.session.getServerName();
    }

    /**
     * @return the server version from the handshake, or null when it sent none
     */
    public String serverVersion() {
        return this.session.getServerVersion();
    }

    /**
     * Close the session, which closes the connection, the transport and finally the child.
     */
    @Override
    public void close() {

        if (this.closed) {
            return;
        }

        this.closed = true;
        this.calls.closeStreams(null);
        this.session.close();
    }
}
