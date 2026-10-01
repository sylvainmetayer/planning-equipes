package dev.sylvain.planning.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A local HTTP server standing in for whatever the application calls out to —
 * a webhook receiver, the weather service — on the JDK's own server, so the
 * tests need no dependency and no network.
 *
 * <p>Each path answers what the test scripted (a status, a body, headers, a
 * delay) and every request is kept, headers and body, for the assertions.</p>
 */
public final class FakeHttpReceiver implements AutoCloseable {

    /** One request as it arrived. */
    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** What a path answers. */
    public record Script(int status, String body, Map<String, String> headers, long delayMillis) {

        public static Script status(int status) {
            return new Script(status, "", Map.of(), 0);
        }

        public static Script json(int status, String body) {
            return new Script(status, body, Map.of("Content-Type", "application/json"), 0);
        }
    }

    private final HttpServer server;

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    public FakeHttpReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port><path>} */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public FakeHttpReceiver answer(String path, Script script) {
        scripts.put(path, script);
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> receivedOn(String path) {
        return received.stream().filter(request -> request.path().equals(path)).toList();
    }

    public void reset() {
        received.clear();
        scripts.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()),
                body));
        Script script = scripts.getOrDefault(path, Script.status(200));
        if (script.delayMillis() > 0) {
            try {
                Thread.sleep(script.delayMillis());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        script.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = script.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(script.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
