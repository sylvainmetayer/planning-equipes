package dev.sylvain.planning.service.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigMeteo;
import dev.sylvain.planning.service.weather.OpenMeteoClient.Coordinates;
import dev.sylvain.planning.service.weather.OpenMeteoClient.WeatherUnavailable;
import dev.sylvain.planning.testing.FakeHttpReceiver;
import dev.sylvain.planning.testing.FakeHttpReceiver.Script;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The weather client against a local fake of Open-Meteo: what it asks, what it reads, how it fails. */
class OpenMeteoClientTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 15);

    private static final Coordinates KIOSQUE = Coordinates.rounded(43.296482, 5.36978);

    private static final Coordinates CHATEAU = Coordinates.rounded(43.2801, 5.3253);

    private FakeHttpReceiver receiver;

    @BeforeEach
    void start() throws IOException {
        receiver = new FakeHttpReceiver();
    }

    @AfterEach
    void stop() {
        receiver.close();
    }

    private OpenMeteoClient client(Duration timeout) {
        String url = receiver.url("/v1/forecast");
        ConfigMeteo config = new ConfigMeteo() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String url() {
                return url;
            }

            @Override
            public String cron() {
                return "0 0 6 * * ?";
            }
        };
        return new OpenMeteoClient(config, new ObjectMapper(), timeout);
    }

    private static String daily(double temperature, double gust, int code) {
        return "{\"latitude\":43.3,\"longitude\":5.37,\"daily\":{\"time\":[\"2026-07-15\"],"
                + "\"temperature_2m_max\":[" + temperature + "],\"wind_gusts_10m_max\":[" + gust + "],"
                + "\"weather_code\":[" + code + "]}}";
    }

    @Test
    void onePlaceIsAskedOnceAndReadBack() {
        receiver.answer("/v1/forecast", Script.json(200, daily(36.4, 41.0, 3)));

        var forecasts = client(Duration.ofSeconds(5)).forecast(List.of(KIOSQUE), JOUR, JOUR, ZoneId.of("Europe/Paris"));

        assertThat(forecasts.get(KIOSQUE).get(JOUR).maxTemperature()).isEqualTo(36.4);
        assertThat(forecasts.get(KIOSQUE).get(JOUR).weatherCode()).isEqualTo(3);
        String query = receiver.receivedOn("/v1/forecast").get(0).query();
        assertThat(query)
                .contains("latitude=43.30")
                .contains("longitude=5.37")
                .contains("daily=temperature_2m_max,wind_gusts_10m_max,weather_code")
                .contains("timezone=Europe%2FParis")
                .contains("start_date=2026-07-15")
                .contains("end_date=2026-07-15");
    }

    /** Several places travel in one request, and come back as an array in the same order. */
    @Test
    void severalPlacesShareOneRequest() {
        receiver.answer("/v1/forecast", Script.json(200, "[" + daily(30, 20, 1) + "," + daily(35, 70, 95) + "]"));

        var forecasts = client(Duration.ofSeconds(5))
                .forecast(List.of(KIOSQUE, CHATEAU), JOUR, JOUR, ZoneId.of("Europe/Paris"));

        assertThat(receiver.received()).hasSize(1);
        assertThat(receiver.received().get(0).query())
                .contains("latitude=43.30,43.28")
                .contains("longitude=5.37,5.33");
        assertThat(forecasts.get(KIOSQUE).get(JOUR).maxTemperature()).isEqualTo(30.0);
        assertThat(forecasts.get(CHATEAU).get(JOUR).maxGust()).isEqualTo(70.0);
    }

    @Test
    void anErrorStatusIsUnavailableNotAnException() {
        receiver.answer("/v1/forecast", Script.json(500, "{\"reason\":\"boom\"}"));
        OpenMeteoClient client = client(Duration.ofSeconds(5));
        List<Coordinates> places = List.of(KIOSQUE);
        ZoneId utc = ZoneId.of("UTC");

        assertThatThrownBy(() -> client.forecast(places, JOUR, JOUR, utc))
                .isInstanceOf(WeatherUnavailable.class)
                .hasMessage("Le service météo a répondu 500.");
    }

    @Test
    void aSlowServiceIsCut() {
        receiver.answer("/v1/forecast", new Script(200, daily(30, 20, 1), Map.of(), 3_000));

        OpenMeteoClient client = client(Duration.ofMillis(500));
        List<Coordinates> places = List.of(KIOSQUE);
        ZoneId utc = ZoneId.of("UTC");

        assertThatThrownBy(() -> client.forecast(places, JOUR, JOUR, utc))
                .isInstanceOf(WeatherUnavailable.class)
                .hasMessageContaining("n'a pas répondu");
    }

    @Test
    void anUnexpectedBodyIsUnavailable() {
        OpenMeteoClient client = client(Duration.ofSeconds(5));
        List<Coordinates> places = List.of(KIOSQUE);
        ZoneId utc = ZoneId.of("UTC");

        receiver.answer("/v1/forecast", Script.json(200, "{\"error\":true}"));
        assertThatThrownBy(() -> client.forecast(places, JOUR, JOUR, utc))
                .isInstanceOf(WeatherUnavailable.class)
                .hasMessageContaining("inattendue");

        receiver.answer("/v1/forecast", Script.json(200, "pas du json"));
        assertThatThrownBy(() -> client.forecast(places, JOUR, JOUR, utc)).isInstanceOf(WeatherUnavailable.class);

        // Two places asked, one answered: the order would be a guess.
        receiver.answer("/v1/forecast", Script.json(200, "[" + daily(30, 20, 1) + "]"));
        List<Coordinates> twoPlaces = List.of(KIOSQUE, CHATEAU);
        assertThatThrownBy(() -> client.forecast(twoPlaces, JOUR, JOUR, utc))
                .isInstanceOf(WeatherUnavailable.class)
                .hasMessageContaining("inattendue");
    }

    @Test
    void aRedirectIsNotFollowed() {
        receiver.answer("/v1/forecast", new Script(302, "", Map.of("Location", receiver.url("/ailleurs")), 0));
        OpenMeteoClient client = client(Duration.ofSeconds(5));
        List<Coordinates> places = List.of(KIOSQUE);
        ZoneId utc = ZoneId.of("UTC");

        assertThatThrownBy(() -> client.forecast(places, JOUR, JOUR, utc))
                .isInstanceOf(WeatherUnavailable.class)
                .hasMessage("Le service météo a répondu 302.");
        assertThat(receiver.receivedOn("/ailleurs")).isEmpty();
    }

    @Test
    void aMissingValueIsReadAsNone() {
        receiver.answer(
                "/v1/forecast",
                Script.json(
                        200,
                        "{\"daily\":{\"time\":[\"2026-07-15\"],\"temperature_2m_max\":[null],"
                                + "\"wind_gusts_10m_max\":[12.5]}}"));

        var day = client(Duration.ofSeconds(5))
                .forecast(List.of(KIOSQUE), JOUR, JOUR, ZoneId.of("UTC"))
                .get(KIOSQUE)
                .get(JOUR);

        assertThat(day.maxTemperature()).isNull();
        assertThat(day.maxGust()).isEqualTo(12.5);
        assertThat(day.weatherCode()).isNull();
    }
}
