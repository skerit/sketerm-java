package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.process.SketermProcess;
import be.elevenways.sketerm.rpc.JsonRpcConnection;
import be.elevenways.sketerm.rpc.SketermTransport;
import be.elevenways.sketerm.rpc.StdioTransport;

import java.time.Duration;

/**
 * A launched Sketerm server: process, transport, connection and session composed as one closeable.
 *
 * Closing tears the stack down in order and cannot hang: the process layer ends in a
 * force-kill.
 */
public final class Sketerm implements AutoCloseable {

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
     * @throws be.elevenways.sketerm.process.SketermProcessException when the binary cannot start
     * @throws be.elevenways.sketerm.mcp.McpException when the handshake fails
     */
    public static Sketerm launch(SketermOptions options) {

        SketermProcess process = SketermProcess.start(options.command(),
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
            connection.close();
            throw e;
        }
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
        this.calls.closeStreams();
        this.session.close();
    }
}
