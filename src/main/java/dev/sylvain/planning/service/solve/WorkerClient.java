package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The calls the application makes to one worker (ADR 0086) — every connection
 * between the two starts here, never on the worker's side.
 *
 * <p>The worker's certificate is <b>pinned</b>: a rented machine often has an
 * address and no name, so no authority vouches for it, and the fingerprint
 * the operator configured is the one thing that says this is their worker.
 * With a fingerprint, that certificate and no other is
 * accepted, whatever name it carries; without one, the JDK's ordinary trust
 * applies, which is what a permanent worker behind a real certificate needs.</p>
 */
final class WorkerClient {

    /** Where a worker is and how to recognise it. {@code fingerprint} is the SHA-256 of its certificate, or {@code null}. */
    record Endpoint(String url, String secret, String fingerprint) {

        Endpoint {
            url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            fingerprint = fingerprint == null || fingerprint.isBlank() ? null : normalise(fingerprint);
        }

        @Override
        public String toString() {
            // Never the secret: this ends up in logs and in error messages.
            return url;
        }
    }

    /** The worker answered, and said no. */
    static final class Refused extends RuntimeException {

        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static final String SOLVES = "solves/";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    /** The problem goes up a home line: megabytes raw, a hundred-odd kilobytes zipped, but the line may be slow. */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(3);

    private final Endpoint endpoint;
    private final ObjectMapper json;
    private final HttpClient http;

    WorkerClient(Endpoint endpoint, ObjectMapper json) {
        this.endpoint = endpoint;
        this.json = json;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (endpoint.fingerprint() != null) {
            builder.sslContext(pinned(endpoint.fingerprint()));
        }
        this.http = builder.build();
    }

    Endpoint endpoint() {
        return endpoint;
    }

    /** The version the worker runs. */
    String version() {
        return call(get("health"), CALL_TIMEOUT).path("version").asText();
    }

    String submit(byte[] payload, SolverConfiguration.SearchSettings settings, String version) {
        String query = "solves?version=" + java.net.URLEncoder.encode(version, java.nio.charset.StandardCharsets.UTF_8)
                + "&seconds=" + settings.seconds()
                + "&plateau=" + settings.plateauSeconds()
                + "&gain=" + settings.plateauGainMediumPerSeat()
                + "&engine=" + settings.engine().configValue();
        HttpRequest.Builder request = request(query)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        return call(request, UPLOAD_TIMEOUT).path("id").asText();
    }

    SolveWorker.Status status(String id, int cursor) {
        return read(call(get(SOLVES + id + "?cursor=" + cursor), CALL_TIMEOUT), SolveWorker.Status.class);
    }

    void stop(String id) {
        call(request(SOLVES + id + "/stop").POST(HttpRequest.BodyPublishers.noBody()), CALL_TIMEOUT);
    }

    SolveWorker.Answer answer(String id) {
        return read(call(get(SOLVES + id + "/result"), CALL_TIMEOUT), SolveWorker.Answer.class);
    }

    /** The last lines the worker logged. */
    List<String> logs() {
        return read(call(get("logs"), CALL_TIMEOUT).path("lines"), StringList.class);
    }

    private static final class StringList extends java.util.ArrayList<String> {}

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(endpoint.url() + "/worker/" + path))
                .header("Authorization", "Bearer " + endpoint.secret());
    }

    private JsonNode call(HttpRequest.Builder request, Duration timeout) {
        try {
            HttpResponse<byte[]> response =
                    http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode body = json.readTree(response.body());
            if (response.statusCode() >= 300) {
                throw new Refused(response.statusCode(), body.path("error").asText("HTTP " + response.statusCode()));
            }
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted while calling the worker", e));
        }
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String normalise(String fingerprint) {
        return fingerprint.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * A context that accepts the one certificate whose SHA-256 is
     * {@code fingerprint}. An <em>extended</em> trust manager on purpose: the
     * JDK then leaves the whole decision to it, name check included — the
     * certificate of a machine known by its address carries no name to check.
     */
    private static SSLContext pinned(String fingerprint) {
        TrustManager pin = new X509ExtendedTrustManager() {
            private void check(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("The worker presented no certificate");
                }
                String presented;
                try {
                    presented = HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                } catch (GeneralSecurityException e) {
                    throw new CertificateException(e);
                }
                if (!MessageDigest.isEqual(presented.getBytes(), fingerprint.getBytes())) {
                    throw new CertificateException("The worker's certificate is not the pinned one");
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("Not a server");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                throw new CertificateException("Not a server");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Not a server");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {pin}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
