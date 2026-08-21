package be.elevenways.sketerm.rpc;

import be.elevenways.sketerm.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcConnectionTest {

    @Test
    @DisplayName("A connection correlates answers, maps errors and survives stray frames")
    void correlationJourney() {

        FakeTransport transport = new FakeTransport(request -> {

            Long id = Json.optLong(request, "id");
            String method = Json.str(request, "method");

            if (id == null) {
                return null;
            }

            return switch (method) {
                case "ping" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
                case "echo" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"seen\":\""
                        + Json.str(Json.map(request, "params"), "value") + "\"}}";
                case "boom" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
                default -> null;
            };
        });

        try (JsonRpcConnection connection = new JsonRpcConnection(transport)) {

            connection.setTimeoutMs(2_000);

            // 1. An empty result becomes an empty map, never null
            Map<String, Object> pong = connection.call("ping", new LinkedHashMap<>());
            assertNotNull(pong, "step 1: ping answers");
            assertTrue(pong.isEmpty(), "step 1: an empty result is an empty map");

            // 2. Ids correlate: the answer carries this call's params back
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("value", "first");
            assertEquals("first", Json.str(connection.call("echo", params), "seen"),
                    "step 2: the first echo");

            params.put("value", "second");
            assertEquals("second", Json.str(connection.call("echo", params), "seen"),
                    "step 2: the second echo, so ids advanced");

            // 3. A notification and an unknown-id frame are both dropped, not mistaken for answers
            transport.push("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}");
            transport.push("{\"jsonrpc\":\"2.0\",\"id\":9999,\"result\":{}}");
            connection.notify("notifications/initialized", null);

            params.put("value", "third");
            assertEquals("third", Json.str(connection.call("echo", params), "seen"),
                    "step 3: the connection still answers after stray frames");

            // 4. A JSON-RPC error object becomes a typed exception
            JsonRpcException error = assertThrows(JsonRpcException.class,
                    () -> connection.call("boom", null), "step 4: an error object throws");
            assertEquals(-32601, error.getCode(), "step 4: the code survives");
            assertTrue(error.getMessage().contains("method not found"), "step 4: the message survives");

            // 5. Requests are well-formed JSON-RPC on the wire
            Map<String, Object> firstSent = Json.parseObject(transport.getSent().getFirst());
            assertEquals("2.0", Json.str(firstSent, "jsonrpc"), "step 5: the version member");
            assertEquals("ping", Json.str(firstSent, "method"), "step 5: the method member");
        }
    }

    @Test
    @DisplayName("A silent server trips the per-call timeout and names the context")
    void timeout() {

        FakeTransport transport = new FakeTransport(request -> null);
        transport.setFailureContext("the fake server said nothing");

        try (JsonRpcConnection connection = new JsonRpcConnection(transport)) {

            CallTimeoutException failure = assertThrows(CallTimeoutException.class,
                    () -> connection.call("ping", null, 150), "the call gives up");

            assertTrue(failure.getMessage().contains("Timed out"), "the failure is a timeout");
            assertTrue(failure.getMessage().contains("ping"), "it names the method");
            assertTrue(failure.getMessage().contains("the fake server said nothing"),
                    "it carries the transport's diagnostics");
            assertTrue(failure.outcomeUnknown(), "the sent request must not be retried blindly");
            assertEquals("ping", failure.method(), "the typed failure names the method");
        }
    }

    @Test
    @DisplayName("End of stream drains every pending call with the transport's diagnostics")
    void eofDrainsPending() throws Exception {

        FakeTransport transport = new FakeTransport(request -> null);
        transport.setFailureContext("process exited with code 3; stderr tail:\nfatal: no display");

        try (JsonRpcConnection connection = new JsonRpcConnection(transport)) {

            Thread caller = new Thread(() -> connection.call("ping", null, 10_000));
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            caller.setUncaughtExceptionHandler((t, e) -> failure.set(e));
            caller.start();

            // Let the request reach the transport before the stream dies.
            while (transport.getSent().isEmpty()) {
                Thread.sleep(5);
            }

            transport.endOfStream();
            caller.join(5_000);

            assertNotNull(failure.get(), "the pending call failed");
            assertTrue(failure.get() instanceof TransportException, "with a transport failure");
            assertTrue(failure.get().getMessage().contains("closed its output stream"),
                    "naming the EOF");
            assertTrue(failure.get().getMessage().contains("exited with code 3"),
                    "carrying the exit code");
            assertTrue(failure.get().getMessage().contains("fatal: no display"),
                    "carrying the stderr tail");

            TransportException terminal = assertThrows(TransportException.class,
                    () -> connection.call("ping", null, 10_000),
                    "a later call fails immediately instead of waiting without a reader");
            assertTrue(terminal.getMessage().contains("Connection has failed"),
                    "the terminal connection state is explicit");
        }
    }

    @Test
    @DisplayName("A malformed protocol frame poisons the connection and drains its call")
    void malformedFrameIsTerminal() throws Exception {

        FakeTransport transport = new FakeTransport(request -> null);

        try (JsonRpcConnection connection = new JsonRpcConnection(transport)) {
            Thread caller = new Thread(() -> connection.call("ping", null, 10_000));
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            caller.setUncaughtExceptionHandler((t, e) -> failure.set(e));
            caller.start();

            while (transport.getSent().isEmpty()) {
                Thread.sleep(5);
            }

            transport.push("not-json");
            caller.join(5_000);

            assertNotNull(failure.get(), "the pending call fails instead of timing out");
            assertTrue(failure.get().getMessage().contains("Reader failed"),
                    "the malformed frame is reported as a terminal reader failure");
        }
    }

    @Test
    @DisplayName("Closing fails a pending call before the transport shutdown can block")
    void closeDrainsBeforeTransportShutdown() throws Exception {

        FakeTransport transport = new FakeTransport(request -> null);
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        Thread caller = new Thread(() -> connection.call("ping", null, 10_000));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        caller.setUncaughtExceptionHandler((t, e) -> failure.set(e));
        caller.start();

        while (transport.getSent().isEmpty()) {
            Thread.sleep(5);
        }

        connection.close();
        caller.join(1_000);

        assertNotNull(failure.get(), "the pending caller is released by close");
        assertTrue(failure.get().getMessage().contains("Connection closed"),
                "with a definite close, not an ambiguous timeout");
    }
}
