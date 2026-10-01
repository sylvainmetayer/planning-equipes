package dev.sylvain.planning.service.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigMeteo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One request to Open-Meteo for every place at once, read back as a daily
 * maximum per place and per date.
 *
 * <p>The JDK's {@link HttpClient}, not the Vert.x client of the webhooks: the
 * job runs on a scheduler thread that may block, and {@code METEO_URL} is
 * set by the operator — there is no user-typed address to resolve and pin, so
 * nothing the Vert.x resolver buys is needed here. Redirects are not followed,
 * the whole exchange is bounded by ten seconds, and the JVM's proxy settings
 * apply (an operator routing egress through a proxy sets them once).</p>
 *
 * <p>Every failure — unreachable, slow, an error status, an unexpected body —
 * comes back as a {@link WeatherUnavailable} carrying a short sentence, never
 * as anything else: the caller records it and carries on.</p>
 */
@ApplicationScoped
public class OpenMeteoClient {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Far beyond a real answer — a few kilobytes for a dozen places over sixteen days. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** A place to query, already rounded to 0.01° (about a kilometre). */
    public record Coordinates(double latitude, double longitude) {

        public static Coordinates rounded(double latitude, double longitude) {
            return new Coordinates(Math.round(latitude * 100) / 100.0, Math.round(longitude * 100) / 100.0);
        }
    }

    /** One day of one place: each value {@code null} when the service gave none. */
    public record DailyForecast(LocalDate date, Double maxTemperature, Double maxGust, Integer weatherCode) {}

    /** The service could not answer usefully; {@code getMessage()} is meant for the screen. */
    public static final class WeatherUnavailable extends RuntimeException {

        public WeatherUnavailable(String message) {
            super(message);
        }

        public WeatherUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ConfigMeteo config;

    private final ObjectMapper mapper;

    private final HttpClient http;

    private final Duration requestTimeout;

    @Inject
    public OpenMeteoClient(ConfigMeteo config, ObjectMapper mapper) {
        this(config, mapper, TIMEOUT);
    }

    /** With another bound: the tests prove a slow service is cut without waiting ten seconds. */
    public OpenMeteoClient(ConfigMeteo config, ObjectMapper mapper, Duration timeout) {
        this.config = config;
        this.mapper = mapper;
        this.requestTimeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * The daily maxima of every place between two dates, in the order the
     * places were given.
     *
     * @throws WeatherUnavailable on any failure, with a sentence for the screen
     */
    public Map<Coordinates, Map<LocalDate, DailyForecast>> forecast(
            List<Coordinates> places, LocalDate start, LocalDate end, ZoneId zone) {
        if (places.isEmpty()) {
            return Map.of();
        }
        URI uri = uri(config.url(), places, start, end, zone);
        HttpResponse<InputStream> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(requestTimeout)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new WeatherUnavailable(
                    "Le service météo n'a pas répondu en " + requestTimeout.toSeconds() + " s.", e);
        } catch (IOException e) {
            throw new WeatherUnavailable(
                    "Service météo injoignable (" + e.getClass().getSimpleName() + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherUnavailable("Interrogation du service météo interrompue.", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new WeatherUnavailable("Le service météo a répondu " + response.statusCode() + ".");
            }
            return parse(mapper.readTree(body.readNBytes(MAX_BODY_BYTES)), places);
        } catch (IOException e) {
            throw new WeatherUnavailable("Réponse du service météo illisible.", e);
        }
    }

    /** {@code …?latitude=a,b&longitude=c,d&daily=…&timezone=…&start_date=…&end_date=…} */
    static URI uri(String base, List<Coordinates> places, LocalDate start, LocalDate end, ZoneId zone) {
        String latitudes = places.stream()
                .map(place -> String.format(Locale.ROOT, "%.2f", place.latitude()))
                .collect(Collectors.joining(","));
        String longitudes = places.stream()
                .map(place -> String.format(Locale.ROOT, "%.2f", place.longitude()))
                .collect(Collectors.joining(","));
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + "latitude=" + latitudes + "&longitude=" + longitudes
                + "&daily=temperature_2m_max,wind_gusts_10m_max,weather_code"
                + "&wind_speed_unit=kmh"
                + "&timezone=" + URLEncoder.encode(zone.getId(), StandardCharsets.UTF_8)
                + "&start_date=" + start + "&end_date=" + end);
    }

    /**
     * Reads the answer: an array with one object per place when several were
     * asked, a single object when one was. Anything else is « inattendu ».
     */
    static Map<Coordinates, Map<LocalDate, DailyForecast>> parse(JsonNode root, List<Coordinates> places) {
        List<JsonNode> locations = new ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(locations::add);
        } else if (root != null && root.isObject()) {
            locations.add(root);
        }
        if (locations.size() != places.size()) {
            throw new WeatherUnavailable("Réponse du service météo inattendue : " + locations.size() + " lieu(x) pour "
                    + places.size() + " demandé(s).");
        }
        Map<Coordinates, Map<LocalDate, DailyForecast>> forecasts = new HashMap<>();
        for (int i = 0; i < places.size(); i++) {
            forecasts.put(places.get(i), daily(locations.get(i)));
        }
        return forecasts;
    }

    private static Map<LocalDate, DailyForecast> daily(JsonNode location) {
        JsonNode daily = location.get("daily");
        JsonNode time = daily == null ? null : daily.get("time");
        if (time == null || !time.isArray()) {
            throw new WeatherUnavailable("Réponse du service météo inattendue : aucune série journalière.");
        }
        JsonNode temperatures = daily.get("temperature_2m_max");
        JsonNode gusts = daily.get("wind_gusts_10m_max");
        JsonNode codes = daily.get("weather_code");
        Map<LocalDate, DailyForecast> days = new HashMap<>();
        for (int i = 0; i < time.size(); i++) {
            LocalDate date;
            try {
                date = LocalDate.parse(time.get(i).asText());
            } catch (RuntimeException e) {
                throw new WeatherUnavailable("Réponse du service météo inattendue : date illisible.", e);
            }
            Integer code = number(codes, i) == null ? null : number(codes, i).intValue();
            days.put(date, new DailyForecast(date, number(temperatures, i), number(gusts, i), code));
        }
        return days;
    }

    private static Double number(JsonNode series, int index) {
        if (series == null || !series.isArray() || index >= series.size()) {
            return null;
        }
        JsonNode value = series.get(index);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }
}
