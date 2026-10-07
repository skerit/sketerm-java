package be.elevenways.sketerm.api;

import be.elevenways.sketerm.mcp.McpSession;
import be.elevenways.sketerm.rpc.JsonRpcConnection;
import be.elevenways.sketerm.testing.ScriptedSketerm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The api tests' handle on a {@link ScriptedSketerm}: the published script plus the package-private
 * stack ({@link ToolCalls}, {@link Browser}) these tests drive directly.
 */
final class FakeSketermServer {

    private final ScriptedSketerm script = new ScriptedSketerm();

    /** One recorded tools/call. */
    record Call(String tool, Map<String, Object> arguments) {
    }

    static Map<String, Object> map(Object... keysAndValues) {
        return ScriptedSketerm.map(keysAndValues);
    }

    static Map<String, Object> facts(Map<String, Object> extra) {
        return ScriptedSketerm.facts(extra);
    }

    static Map<String, Object> imageBlock(String base64) {
        return ScriptedSketerm.imageBlock(base64);
    }

    FakeSketermServer on(String tool, Map<String, Object> structured) {
        this.script.on(tool, structured);
        return this;
    }

    FakeSketermServer on(String tool, Function<Map<String, Object>, Map<String, Object>> handler) {
        this.script.on(tool, handler);
        return this;
    }

    FakeSketermServer onError(String tool, String code, String message, boolean retryable) {
        this.script.onError(tool, code, message, retryable);
        return this;
    }

    FakeSketermServer prose(String tool, String text) {
        this.script.prose(tool, text);
        return this;
    }

    List<Call> calls() {
        return this.script.calls().stream().map(call -> new Call(call.tool(), call.arguments())).toList();
    }

    List<Call> callsTo(String tool) {
        return this.calls().stream().filter(call -> call.tool().equals(tool)).toList();
    }

    Map<String, Object> lastArguments(String tool) {
        return this.script.lastArguments(tool);
    }

    /**
     * Build the whole client stack over this fake, ready to drive.
     */
    Browser browser() {
        return new Browser(this.toolCalls());
    }

    ToolCalls toolCalls() {

        JsonRpcConnection connection = new JsonRpcConnection(this.script.transport());

        return new ToolCalls(McpSession.initialize(connection), Duration.ofSeconds(5));
    }
}
