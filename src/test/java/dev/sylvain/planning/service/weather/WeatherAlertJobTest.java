package dev.sylvain.planning.service.weather;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.config.ConfigMeteo;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.edition.EditionActivationService;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.testing.FakeHttpReceiver;
import dev.sylvain.planning.testing.FakeHttpReceiver.Script;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The morning weather alert end to end, against a fake Open-Meteo on the
 * loopback: what it queries, what it raises, what it tells — and that it
 * never poses a consigne.
 */
@QuarkusTest
class WeatherAlertJobTest {

    private static final String ADMIN = "admin@example.org";

    /** The test database's edition, the active one: only it is queried (ADR 0072). */
    private static final String EDITION = "E1";

    private static FakeHttpReceiver receiver;

    @Inject
    WeatherAlertJob job;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ConsigneService consignes;

    @Inject
    ObjectMapper mapper;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @Inject
    EditionActivationService activation;

    @Inject
    JourJClock clock;

    private LocalDate jour;

    private String prereglage;

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = new FakeHttpReceiver();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.close();
    }

    @BeforeEach
    void seed() {
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
        QuarkusMock.installMockForType(
                new OpenMeteoClient(config, mapper, Duration.ofSeconds(2)), OpenMeteoClient.class);
        receiver.reset();
        mailbox.clear();
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM meteo_etat");
        execute("DELETE FROM parametres_meteo");
        persistence.clearDatabase();

        jour = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(2);
        Emplacement kiosque = kiosque();
        Stand stand = new Stand(null, "Stand météo", Set.of(typologie()), 1, 2, false);
        stand.setEmplacement(kiosque);
        referenceData.createStand(stand);
        referenceData.createCreneau(new Creneau(null, 1, jour, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        prereglage = consignes
                .savePrereglage(new PrereglageConsigne(
                        null,
                        "Plan canicule",
                        LocalTime.of(12, 0),
                        LocalTime.of(18, 0),
                        "Canicule",
                        List.of(),
                        null,
                        null,
                        null))
                .id();
        saveSettings(true, 5, prereglage, null);
    }

    @AfterEach
    void handBack() {
        execute("DELETE FROM parametres_meteo");
        execute("DELETE FROM meteo_etat");
        execute("DELETE FROM notification_planifiee");
    }

    @Test
    void aCrossedThresholdRaisesAnAlertAMailAndALinkButNeverAConsigne() {
        receiver.answer("/v1/forecast", Script.json(200, forecast(36.4, 20, 1)));
        String consignesAvant = given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        WeatherAlertService.RunResult result = job.run().orElseThrow();

        assertThat(result.outcome()).isEqualTo(WeatherState.Outcome.READ);
        assertThat(result.newAlerts()).isEqualTo(1);
        JsonPath alerte = weatherAlerts();
        assertThat(alerte.getString("[0].libelle"))
                .contains("36 °C prévus à Kiosque météo (seuil 33 °C)")
                .contains("Suggestion : Plan canicule");
        assertThat(alerte.getString("[0].lien"))
                .startsWith("/consignes-solveur?onglet=consignes&date=" + jour)
                .contains("nouvelle=1")
                .endsWith("prereglage=" + prereglage);
        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getText())
                .contains("36 °C")
                .contains("Open-Meteo.com (CC BY 4.0)")
                .contains("Rien n'a été appliqué");
        // A suggestion, never a consigne: the consignes read the same as before.
        assertThat(given().header(EditionContext.HEADER, EDITION)
                        .when()
                        .get("/api/consignes")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo(consignesAvant);
        assertThat(receiver.received()).hasSize(1);
        assertThat(receiver.received().get(0).query())
                .contains("start_date=" + jour)
                .contains("latitude=43.30");
    }

    /**
     * Today is watched, but a consigne is posed on a coming day only: the
     * alert says what is forecast and suggests nothing it could not apply.
     */
    @Test
    void anAlertAboutTodaySuggestsNoPresetAndLinksToNoForm() {
        persistence.clearDatabase();
        jour = clock.today();
        Stand stand = new Stand(null, "Stand du jour", Set.of(typologie()), 1, 2, false);
        stand.setEmplacement(kiosque());
        referenceData.createStand(stand);
        referenceData.createCreneau(new Creneau(null, 1, jour, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        receiver.answer("/v1/forecast", Script.json(200, forecast(36.4, 20, 1)));

        assertThat(job.run().orElseThrow().newAlerts()).isEqualTo(1);

        JsonPath alerte = weatherAlerts();
        assertThat(alerte.getString("[0].libelle")).contains("36 °C").doesNotContain("Suggestion");
        assertThat(alerte.getString("[0].lien")).isNull();
        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getText()).doesNotContain("Préparer la consigne");
    }

    @Test
    void aStableForecastStaysSilentAndAWorseningOneSpeaks() {
        receiver.answer("/v1/forecast", Script.json(200, forecast(34.0, 20, 1)));
        assertThat(job.run().orElseThrow().newAlerts()).isEqualTo(1);
        assertThat(job.run().orElseThrow().newAlerts()).isZero();

        // Easing off then coming back to the same level is not news either.
        receiver.answer("/v1/forecast", Script.json(200, forecast(30.0, 20, 1)));
        assertThat(job.run().orElseThrow().newAlerts()).isZero();
        receiver.answer("/v1/forecast", Script.json(200, forecast(34.5, 20, 1)));
        assertThat(job.run().orElseThrow().newAlerts()).isZero();

        receiver.answer("/v1/forecast", Script.json(200, forecast(37.2, 20, 1)));
        assertThat(job.run().orElseThrow().newAlerts()).isEqualTo(1);
        assertThat(weatherAlerts().getList("libelle")).hasSize(2);
        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(2);
    }

    @Test
    void aThresholdNotCrossedRaisesNothing() {
        receiver.answer("/v1/forecast", Script.json(200, forecast(30.0, 40, 3)));

        WeatherAlertService.RunResult result = job.run().orElseThrow();

        assertThat(result.outcome()).isEqualTo(WeatherState.Outcome.READ);
        assertThat(result.newAlerts()).isZero();
        assertThat(weatherAlerts().getList("$")).isEmpty();
        assertThat(settings().getString("state.outcome")).isEqualTo("READ");
        assertThat(settings().getString("state.lastReadAt")).isNotNull();
    }

    @Test
    void anUnreachableServiceIsRecordedThenSaidOnceOnTheSecondDay() {
        receiver.answer("/v1/forecast", Script.json(500, "{}"));

        assertThat(job.run().orElseThrow().outcome()).isEqualTo(WeatherState.Outcome.UNREACHABLE);
        JsonPath etat = settings();
        assertThat(etat.getString("state.outcome")).isEqualTo("UNREACHABLE");
        assertThat(etat.getString("state.error")).isEqualTo("Le service météo a répondu 500.");
        assertThat(etat.getString("state.unreachableSince")).isNotNull();
        assertThat(mailbox.getMailsSentTo(ADMIN)).isEmpty();

        execute("UPDATE meteo_etat SET injoignable_depuis = now() - interval '2 days'");
        job.run();
        job.run();

        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getText()).contains("ne répond plus");
        assertThat(given().header(EditionContext.HEADER, EDITION)
                        .when()
                        .get("/api/alertes")
                        .jsonPath()
                        .getList("type"))
                .containsOnlyOnce("METEO_INJOIGNABLE");
    }

    @Test
    void anUnexpectedAnswerIsUnreachableToo() {
        receiver.answer("/v1/forecast", Script.json(200, "{\"error\":true,\"reason\":\"quota\"}"));

        assertThat(job.run().orElseThrow().outcome()).isEqualTo(WeatherState.Outcome.UNREACHABLE);
        assertThat(settings().getString("state.error")).contains("inattendue");
    }

    @Test
    void withoutALocatedPlaceNothingIsQueried() {
        persistence.clearDatabase();
        referenceData.createStand(new Stand(null, "Stand sans lieu", Set.of(typologie()), 1, 2, false));
        referenceData.createCreneau(new Creneau(null, 1, jour, LocalTime.of(10, 0), LocalTime.of(12, 0)));

        assertThat(job.run().orElseThrow().outcome()).isEqualTo(WeatherState.Outcome.NO_PLACE);
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void anEventBeyondTheForecastQueriesNothing() {
        persistence.clearDatabase();
        Stand stand = new Stand(null, "Stand lointain", Set.of(typologie()), 1, 2, false);
        stand.setEmplacement(kiosque());
        referenceData.createStand(stand);
        referenceData.createCreneau(new Creneau(null, 1, jour.plusDays(40), LocalTime.of(10, 0), LocalTime.of(12, 0)));

        assertThat(job.run().orElseThrow().outcome()).isEqualTo(WeatherState.Outcome.OUT_OF_FORECAST);
        assertThat(receiver.received()).isEmpty();
        JsonPath test = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/parametres/meteo/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(test.getBoolean("outOfForecast")).isTrue();
    }

    /** Between two events no edition is active (ADR 0072), and nothing is queried. */
    @Test
    void withoutAnActiveEditionNothingIsQueried() {
        receiver.answer("/v1/forecast", Script.json(200, forecast(40.0, 20, 1)));
        activation.deactivate(EDITION);
        try {
            assertThat(job.run()).isEmpty();
            assertThat(receiver.received()).isEmpty();
            assertThat(settings().getBoolean("editionMayEmit")).isFalse();
        } finally {
            activation.activate(EDITION);
        }
    }

    @Test
    void theTestQueriesNowAndRaisesNothing() {
        receiver.answer("/v1/forecast", Script.json(200, forecast(36.4, 75, 95)));
        saveSettings(false, 5, prereglage, settings().getString("settings.modifieLe"));

        JsonPath test = given().header(EditionContext.HEADER, EDITION)
                .when()
                .post("/api/parametres/meteo/test")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(test.getString("days[0].date")).isEqualTo(jour.toString());
        assertThat(test.getInt("days[0].maxTemperature")).isEqualTo(36);
        assertThat(test.getInt("days[0].maxGust")).isEqualTo(75);
        assertThat(test.getBoolean("days[0].storm")).isTrue();
        assertThat(test.getList("days[0].exceeded")).containsExactly("chaleur", "rafales", "orage");
        assertThat(weatherAlerts().getList("$")).isEmpty();
        assertThat(mailbox.getMailsSentTo(ADMIN)).isEmpty();
    }

    @Test
    void theSettingsAreCheckedAndAStaleSaveIsRefused() {
        String modifieLe = settings().getString("settings.modifieLe");
        saveSettingsExpecting(400, true, 15, prereglage, modifieLe);
        saveSettingsExpecting(400, true, 5, "inconnu", modifieLe);
        saveSettings(true, 6, prereglage, modifieLe);

        // The same read sent again: somebody saved meanwhile.
        saveSettingsExpecting(409, true, 7, prereglage, modifieLe);
        assertThat(settings().getInt("settings.horizonJours")).isEqualTo(6);

        // A preset deleted takes its suggestion with it.
        consignes.deletePrereglage(prereglage);
        assertThat(settings().getString("settings.prereglageChaleur")).isNull();
    }

    /* --------------------------------- Helpers -------------------------------- */

    /** A game category, created once: a stand always offers one. */
    private String typologie() {
        return referenceData.listTypologies().stream()
                .filter(typologie -> "Jeux météo".equals(typologie.label()))
                .findFirst()
                .orElseGet(() -> referenceData.createTypologie(
                        new TypologieItem(null, null, "Jeux météo", false, null, null, null)))
                .id();
    }

    /** The located place, created once: clearing the plan leaves the places alone. */
    private Emplacement kiosque() {
        return referenceData.listEmplacements().stream()
                .filter(emplacement -> "Kiosque météo".equals(emplacement.getNom()))
                .findFirst()
                .orElseGet(() -> referenceData.createEmplacement(new Emplacement(null, "Kiosque météo", 43.30, 5.37)));
    }

    private String forecast(double temperature, double gust, int code) {
        return "{\"latitude\":43.3,\"longitude\":5.37,\"daily\":{\"time\":[\"" + jour + "\"],"
                + "\"temperature_2m_max\":[" + temperature + "],\"wind_gusts_10m_max\":[" + gust + "],"
                + "\"weather_code\":[" + code + "]}}";
    }

    private static JsonPath weatherAlerts() {
        JsonPath alertes = given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/alertes")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<Map<String, Object>> meteo = alertes.<Map<String, Object>>getList("$").stream()
                .filter(alerte -> "METEO_ALERTE".equals(alerte.get("type")))
                .toList();
        try {
            return JsonPath.from(new ObjectMapper().writeValueAsString(meteo));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonPath settings() {
        return given().header(EditionContext.HEADER, EDITION)
                .when()
                .get("/api/parametres/meteo")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static void saveSettings(boolean actif, int horizon, String prereglageChaleur, String modifieLe) {
        saveSettingsExpecting(200, actif, horizon, prereglageChaleur, modifieLe);
    }

    private static void saveSettingsExpecting(
            int status, boolean actif, int horizon, String prereglageChaleur, String modifieLe) {
        Map<String, Object> body = new HashMap<>();
        body.put("actif", actif);
        body.put("horizonJours", horizon);
        body.put("seuilTemperature", 33);
        body.put("seuilRafales", 60);
        body.put("orage", true);
        body.put("prereglageChaleur", prereglageChaleur);
        body.put("modifieLe", modifieLe);
        given().header(EditionContext.HEADER, EDITION)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .put("/api/parametres/meteo")
                .then()
                .statusCode(status);
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }
}
