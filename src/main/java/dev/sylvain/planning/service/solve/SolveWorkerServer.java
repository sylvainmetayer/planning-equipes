package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The worker as a program (ADR 0086): {@link SolveWorker} behind a handful of
 * HTTP routes, and nothing else of the application.
 *
 * <p>Started in place of the application from the same image
 * ({@code SOLVER_WORKER_MODE=true}), deliberately without its container: no
 * database, no migration, no scheduled job and no mail can run here because
 * none of them is ever built. Every call but none carries the shared secret;
 * the worker calls nobody.</p>
 *
 * <p>Without a keystore it speaks plain HTTP and listens on the loopback
 * address alone — a development machine, a test. Anywhere else it is HTTPS,
 * with the certificate the application pins.</p>
 */
public final class SolveWorkerServer implements AutoCloseable {

    /** A real event is a hundred-odd kilobytes zipped; this is the room for one fifty times that. */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static final int LOG_LINES = 200;

    private static final String PREFIX = "/worker/";

    private final SolveWorker worker;
    private final byte[] secret;
    private final String version;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final LogTail logs = new LogTail();

    /**
     * @param tls {@code null} for plain HTTP, which {@code address} must then
     *            keep on the loopback interface
     */
    public SolveWorkerServer(
            SolveWorker worker, String secret, String version, InetSocketAddress address, SSLContext tls) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A worker needs a secret: SOLVER_WORKER_SECRET");
        }
        if (tls == null && !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("A worker without TLS listens on the loopback address only");
        }
        this.worker = worker;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.version = version;
        try {
            if (tls == null) {
                server = HttpServer.create(address, 0);
            } else {
                HttpsServer https = HttpsServer.create(address, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(tls));
                server = https;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/worker", this::handle);
        // More than one: a status poll must not wait behind the upload of a problem.
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread serving = new Thread(runnable, "solve-worker-http");
            serving.setDaemon(true);
            serving.setContextClassLoader(SolveWorkerServer.class.getClassLoader());
            return serving;
        }));
        logs.setLevel(Level.INFO);
        java.util.logging.Logger.getLogger("").addHandler(logs);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        java.util.logging.Logger.getLogger("").removeHandler(logs);
    }

    private static final String ERROR = "error";
    private static final String VERSION = "version";
    private static final String SOLVES = "solves";

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorised(exchange)) {
                answer(exchange, 401, Map.of(ERROR, "Missing or wrong secret"));
                return;
            }
            try {
                route(exchange);
            } catch (SolveWorker.Busy | VersionMismatch e) {
                answer(exchange, 409, Map.of(ERROR, e.getMessage()));
            } catch (SolveWorker.Unknown e) {
                answer(exchange, 404, Map.of(ERROR, e.getMessage()));
            } catch (SolveExchange.Refused | IllegalArgumentException | UncheckedIOException e) {
                // A body that is not a problem of this format, down to one that is not even zipped.
                answer(exchange, 400, Map.of(ERROR, String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                answer(exchange, 500, Map.of(ERROR, e.toString()));
            }
        }
    }

    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null
                && header.startsWith("Bearer ")
                && MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8), secret);
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] path =
                exchange.getRequestURI().getPath().substring(PREFIX.length()).split("/");
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        boolean get = "GET".equals(method);
        if (get && path.length == 1 && "health".equals(path[0])) {
            answer(exchange, 200, Map.of(VERSION, version, "solving", worker.isSolving()));
        } else if (get && path.length == 1 && "logs".equals(path[0])) {
            answer(exchange, 200, Map.of("lines", logs.lines()));
        } else if (!SOLVES.equals(path[0]) || !routeSolve(exchange, method, path, query)) {
            answer(exchange, 404, Map.of(ERROR, "No such route"));
        }
    }

    /** The routes under {@code /solves}; {@code false} when none of them matches. */
    private boolean routeSolve(HttpExchange exchange, String method, String[] path, Map<String, String> query)
            throws IOException {
        boolean get = "GET".equals(method);
        boolean post = "POST".equals(method);
        if (post && path.length == 1) {
            submit(exchange, query);
        } else if (get && path.length == 2) {
            answer(exchange, 200, worker.status(path[1], Integer.parseInt(query.getOrDefault("cursor", "0"))));
        } else if (post && path.length == 3 && "stop".equals(path[2])) {
            worker.stop(path[1]);
            answer(exchange, 202, Map.of());
        } else if (get && path.length == 3 && "result".equals(path[2])) {
            SolveWorker.Answer plan = worker.answer(path[1]);
            if (plan == null) {
                answer(exchange, 409, Map.of(ERROR, "This solve has no plan: still running, or failed"));
            } else {
                answer(exchange, 200, plan);
            }
        } else {
            return false;
        }
        return true;
    }

    private void submit(HttpExchange exchange, Map<String, String> query) throws IOException {
        // A score computed under another version's rules is worth nothing to
        // the caller: refused before anything is read.
        if (!version.equals(query.get(VERSION))) {
            throw new VersionMismatch(
                    "This worker runs version " + version + ", the problem comes from " + query.get(VERSION));
        }
        byte[] payload;
        try (InputStream body = exchange.getRequestBody()) {
            payload = body.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (payload.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("The problem is larger than " + MAX_BODY_BYTES + " bytes");
        }
        SolverConfiguration.SearchSettings settings = new SolverConfiguration.SearchSettings(
                Long.parseLong(required(query, "seconds")),
                Long.parseLong(required(query, "plateau")),
                Double.parseDouble(required(query, "gain")),
                SolverEngine.of(required(query, "engine")));
        answer(exchange, 201, Map.of("id", worker.submit(payload, settings)));
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing parameter " + name);
        }
        return value;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    query.put(
                            pair.substring(0, equals),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
    }

    private void answer(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class VersionMismatch extends RuntimeException {
        VersionMismatch(String message) {
            super(message);
        }
    }

    /** The last lines logged, kept for the application to fetch when a solve fails — and printed, since nothing else is. */
    private static final class LogTail extends Handler {

        private final Deque<String> lines = new ArrayDeque<>();
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public synchronized void publish(LogRecord entry) {
            if (!isLoggable(entry)) {
                return;
            }
            String line = Instant.ofEpochMilli(entry.getMillis()) + " " + entry.getLevel() + " "
                    + formatter.formatMessage(entry)
                    + (entry.getThrown() == null ? "" : " — " + entry.getThrown());
            if (lines.size() == LOG_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
            System.err.println(line);
        }

        synchronized java.util.List<String> lines() {
            return java.util.List.copyOf(lines);
        }

        @Override
        public void flush() {
            System.err.flush();
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }

    /**
     * {@code SOLVER_WORKER_SECRET} is required; {@code SOLVER_WORKER_PORT}
     * defaults to 8443; {@code SOLVER_WORKER_TLS_KEYSTORE} (a PKCS#12 file)
     * and {@code SOLVER_WORKER_TLS_KEYSTORE_PASSWORD} switch HTTPS on, and
     * the listening address from the loopback one to every interface.
     */
    public static void main(String[] args) throws GeneralSecurityException, IOException {
        // Before anything logs: the solver logs through the JBoss log manager,
        // and only as the JDK's own does its root logger carry the tail above.
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        // INFO and no finer, said here rather than left to a default: the
        // solver logs every step at DEBUG, and a search that formats its own
        // trace is several times slower.
        java.util.logging.Logger.getLogger("").setLevel(Level.INFO);
        SolveWorkerServer server = start(System.getenv());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "solve-worker-stop"));
    }

    /** The worker {@code environment} describes, listening. */
    static SolveWorkerServer start(Map<String, String> environment) throws GeneralSecurityException, IOException {
        String keystore = environment.get("SOLVER_WORKER_TLS_KEYSTORE");
        int port = Integer.parseInt(environment.getOrDefault("SOLVER_WORKER_PORT", "8443"));
        SSLContext tls = keystore == null || keystore.isBlank()
                ? null
                : tls(Path.of(keystore), environment.getOrDefault("SOLVER_WORKER_TLS_KEYSTORE_PASSWORD", ""));
        InetSocketAddress address = tls == null
                ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                : new InetSocketAddress(port);
        String packaged = SolveWorkerServer.class.getPackage().getImplementationVersion();
        String version = packaged == null ? "dev" : packaged;
        SolveWorkerServer server = new SolveWorkerServer(
                new SolveWorker(), environment.get("SOLVER_WORKER_SECRET"), version, address, tls);
        System.err.println("Solve worker " + version + " listening on "
                + (tls == null ? "http://127.0.0.1:" : "https://0.0.0.0:") + server.port());
        return server;
    }

    static SSLContext tls(Path keystore, String password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }
}
