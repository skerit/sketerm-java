package be.elevenways.sketerm.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real network observations, not JavaScript constructor-presence checks. */
class UntrustedPageIT {
    @BeforeAll
    static void copyBinaries() throws Exception {
        PageApiIT.copyBinaries();
    }

    @Test
    void localHttpWorksButWebSocketAndWebRtcCannotReachOurServers() throws Exception {
        // PageApiIT's binaries: the sibling checkout's copies, or the installed Sketerm when that is not built.
        assumeTrue(PageApiIT.server != null && PageApiIT.helper != null, "no headless sketerm binaries");
        // Chromium's private root/socket path has a strict length bound and requires private parents.
        Path runtime = Files.createTempDirectory(Path.of("/tmp"), "sju",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try (ProbeServers probes = new ProbeServers();
             Sketerm sketerm = Sketerm.launch(PageApiIT.options("untrusted")
                     .env("XDG_RUNTIME_DIR", runtime.toString()).build())) {
            Browser browser = sketerm.browser();
            assumeTrue(browser.supportsUntrusted(), "binary predates untrusted mode or backend is unsupported");
            String origin = "http://abc.folio.localhost:" + probes.http.getAddress().getPort();

            // 1. An ordinary view really connects to the controlled WebSocket and STUN listeners.
            Page ordinary = browser.openPage(origin, OpenOptions.ephemeralIdentity());
            assertEquals("Socket probe", ordinary.evaluate("document.title"), "step 1: fixture loaded");
            assertEquals(true, ordinary.evaluate(probes.webSocketProbe(), true, Duration.ofSeconds(8)),
                    "step 1: ordinary WebSocket completes the handshake");
            assertEquals(true, ordinary.evaluate(probes.webRtcProbe(), true, Duration.ofSeconds(8)),
                    "step 1: ordinary RTCPeerConnection creates an offer");
            assertTrue(probes.ws.get() > 0, "step 1: server observed the WebSocket handshake");
            assertTrue(probes.stun.get() > 0, "step 1: server observed actual WebRTC STUN packets");
            ordinary.close();

            // 2. Untrusted HTTP with explicit host:port and private-address opt-in loads locally.
            NetworkPolicy policy = NetworkPolicy.builder().untrusted()
                    .allowHosts("abc.folio.localhost:" + probes.http.getAddress().getPort())
                    .allowSchemes(UrlScheme.HTTP).allowPrivateAddresses(true).build();
            Page restricted = browser.openPage(origin, OpenOptions.ephemeralIdentity().withPolicy(policy)
                    .withColorScheme(ColorScheme.DARK));
            assertTrue(restricted.isPolicyActive(), "step 2: correlated install is active");
            assertTrue(restricted.policy().untrusted(), "step 2: restricted enforcement is attested");
            assertTrue(browser.supportsPolicyAcknowledgement(), "step 2: helper advertises net-policy-ack");
            assertEquals("Socket probe", restricted.evaluate("document.title"), "step 2: dev origin loaded");
            assertEquals(true, restricted.evaluate("matchMedia('(prefers-color-scheme: dark)').matches"),
                    "step 2: native dark preference was applied");

            // 3. Repeat on fresh listeners: zero packets cannot be confused with control retransmits.
            try (ProbeServers blocked = new ProbeServers()) {
                assertEquals(false, restricted.evaluate(blocked.webSocketProbe(), true, Duration.ofSeconds(8)),
                        "step 3: untrusted WebSocket cannot open");
                restricted.evaluate(blocked.webRtcProbe(), true, Duration.ofSeconds(8));
                assertEquals(0, blocked.ws.get(), "step 3: no WebSocket handshake reached the server");
                assertEquals(0, blocked.stun.get(), "step 3: no WebRTC packet reached the server");
                blocked.assertHealthy();
            }
            restricted.close();

            // 4. Turning off the explicit opt-in refuses the same loopback-resolving origin.
            Page refused = browser.openPage(origin, OpenOptions.ephemeralIdentity().withPolicy(
                    NetworkPolicy.builder().untrusted()
                            .allowHosts("abc.folio.localhost:" + probes.http.getAddress().getPort())
                            .allowSchemes(UrlScheme.HTTP).build()));
            PolicyStatus status = refused.policy();
            assertTrue(status.denied(DenialReason.PRIVATE_ADDRESS)
                            + status.denied(DenialReason.RESOLVED_PRIVATE_ADDRESS) > 0,
                    "step 4: local origin was refused with typed private-address accounting");
            refused.close();
            probes.assertHealthy();
        } finally {
            // Only empty parents are ours to remove; helper roots belong to its supervisor.
            Path roots = runtime.resolve("sketerm/u");
            if (Files.isDirectory(roots)) {
                try (var entries = Files.list(roots)) {
                    if (entries.findAny().isEmpty()) Files.delete(roots);
                }
            }
            Path sketermDir = runtime.resolve("sketerm");
            if (Files.isDirectory(sketermDir)) {
                try (var entries = Files.list(sketermDir)) {
                    if (entries.findAny().isEmpty()) Files.delete(sketermDir);
                }
            }
            try (var entries = Files.list(runtime)) {
                if (entries.findAny().isEmpty()) Files.delete(runtime);
            }
        }
    }

    private static final class ProbeServers implements AutoCloseable {
        final HttpServer http;
        final ServerSocket websocket = new ServerSocket();
        final DatagramSocket udp;
        final AtomicInteger ws = new AtomicInteger();
        final AtomicInteger stun = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread tcpWorker;
        final Thread udpWorker;
        volatile boolean closed;

        ProbeServers() throws Exception {
            http = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            http.createContext("/", exchange -> {
                byte[] body = "<html><title>Socket probe</title><body>local fixture</body></html>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, body.length);
                try (var stream = exchange.getResponseBody()) { stream.write(body); }
            });
            http.start();
            websocket.bind(new InetSocketAddress("127.0.0.1", 0));
            udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            tcpWorker = Thread.ofVirtual().start(() -> {
                while (!closed) {
                    try (Socket socket = websocket.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                                StandardCharsets.US_ASCII));
                        String key = null;
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
                        }
                        if (key == null) throw new IllegalStateException("missing WebSocket handshake key");
                        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                        .getBytes(StandardCharsets.US_ASCII)));
                        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        ws.incrementAndGet();
                        socket.getInputStream().read();
                    } catch (Throwable error) {
                        if (!closed) failure.compareAndSet(null, error);
                    }
                }
            });
            udpWorker = Thread.ofVirtual().start(() -> {
                while (!closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                        udp.receive(packet);
                        if (packet.getLength() >= 20 && packet.getData()[4] == 0x21
                                && packet.getData()[5] == 0x12 && packet.getData()[6] == (byte) 0xa4
                                && packet.getData()[7] == 0x42) stun.incrementAndGet();
                    } catch (Throwable error) {
                        if (!closed) failure.compareAndSet(null, error);
                    }
                }
            });
        }

        String webSocketProbe() {
            return "new Promise(resolve => { let s; try { s = new WebSocket('ws://127.0.0.1:"
                    + websocket.getLocalPort() + "/'); } catch(e) { resolve(false); return; }"
                    + "const timer = setTimeout(() => { s.close(); resolve(false); }, 2000);"
                    + "s.onopen = () => { clearTimeout(timer); s.close(); resolve(true); };"
                    + "s.onerror = () => { clearTimeout(timer); resolve(false); }; })";
        }

        String webRtcProbe() {
            return "(async () => { let pc; try { pc = new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:"
                    + udp.getLocalPort() + "'}]}); pc.createDataChannel('probe');"
                    + "await pc.setLocalDescription(await pc.createOffer());"
                    + "await new Promise(resolve => setTimeout(resolve, 2500)); pc.close(); return true;"
                    + "} catch(e) { if(pc) pc.close(); return false; } })()";
        }

        void assertHealthy() {
            assertNull(failure.get(), "controlled network listener failed: " + failure.get());
        }

        @Override
        public void close() throws Exception {
            closed = true;
            http.stop(0);
            websocket.close();
            udp.close();
            tcpWorker.join(6000);
            udpWorker.join(6000);
            assertFalse(tcpWorker.isAlive(), "WebSocket fixture worker retired");
            assertFalse(udpWorker.isAlive(), "STUN fixture worker retired");
            assertHealthy();
        }
    }
}
