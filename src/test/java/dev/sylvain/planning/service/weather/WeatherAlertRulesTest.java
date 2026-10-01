package dev.sylvain.planning.service.weather;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresMeteo;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.weather.OpenMeteoClient.Coordinates;
import dev.sylvain.planning.service.weather.OpenMeteoClient.DailyForecast;
import dev.sylvain.planning.service.weather.WeatherAlertService.Alert;
import dev.sylvain.planning.service.weather.WeatherAlertService.Phenomenon;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The rules of the weather alert, without a network or a database. */
class WeatherAlertRulesTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 7, 10);

    private static final Coordinates KIOSQUE = Coordinates.rounded(43.3, 5.37);

    private static final ParametresMeteo REGLAGES =
            new ParametresMeteo(true, 5, 33, 60, true, "canicule", null, "orage", null);

    private static JoursEvenement jours(LocalDate... dates) {
        return new JoursEvenement(List.of(dates));
    }

    @Test
    void theWindowIsTheEventWithinTheHorizonAndTheRealForecast() {
        JoursEvenement evenement =
                jours(AUJOURDHUI.minusDays(1), AUJOURDHUI, AUJOURDHUI.plusDays(3), AUJOURDHUI.plusDays(9));

        assertThat(WeatherAlertService.window(AUJOURDHUI, AUJOURDHUI, 5, evenement))
                .containsExactly(AUJOURDHUI, AUJOURDHUI.plusDays(3));
    }

    /** A simulated date far ahead of the real one leaves the forecast: nothing is asked. */
    @Test
    void aSimulatedDateBeyondTheForecastLeavesNothingToAsk() {
        LocalDate simule = AUJOURDHUI.plusDays(40);
        JoursEvenement evenement = jours(simule, simule.plusDays(1));

        assertThat(WeatherAlertService.window(simule, AUJOURDHUI, 5, evenement)).isEmpty();
        // Behind the real date, the simulated days already past are not forecast either.
        assertThat(WeatherAlertService.window(AUJOURDHUI.minusDays(3), AUJOURDHUI, 5, jours(AUJOURDHUI.minusDays(2))))
                .isEmpty();
    }

    @Test
    void theForecastStopsFifteenDaysAfterTheRealToday() {
        JoursEvenement evenement = jours(AUJOURDHUI.plusDays(14), AUJOURDHUI.plusDays(15), AUJOURDHUI.plusDays(16));

        assertThat(WeatherAlertService.window(AUJOURDHUI.plusDays(10), AUJOURDHUI, 14, evenement))
                .containsExactly(AUJOURDHUI.plusDays(14), AUJOURDHUI.plusDays(15));
    }

    @Test
    void onlyLocatedPlacesOfOpenStandsAreWatched() {
        Stand situe = new Stand("S1", "Kiosque", Set.of(), 1, 2, false);
        situe.setEmplacement(new Emplacement("E1", "Kiosque", 43.30123, 5.36988));
        Stand voisin = new Stand("S2", "Buvette", Set.of(), 1, 2, false);
        voisin.setEmplacement(new Emplacement("E2", "Buvette", 43.30199, 5.37001));
        Stand sansLieu = new Stand("S3", "Volant", Set.of(), 1, 2, false);
        Stand lieuSansCoordonnees = new Stand("S4", "Cour", Set.of(), 1, 2, false);
        lieuSansCoordonnees.setEmplacement(new Emplacement("E4", "Cour", null, null));
        Creneau creneau = new Creneau(1L, 1, AUJOURDHUI, LocalTime.of(10, 0), LocalTime.of(12, 0));

        var places = WeatherAlertService.placesByDate(
                List.of(AUJOURDHUI, AUJOURDHUI.plusDays(1)),
                List.of(situe, voisin, sansLieu, lieuSansCoordonnees),
                List.of(creneau));

        // The two neighbours round to one point, and a day without a timeslot has nothing open.
        assertThat(places).containsOnlyKeys(AUJOURDHUI);
        assertThat(places.get(AUJOURDHUI)).containsOnlyKeys(KIOSQUE);
        assertThat(places.get(AUJOURDHUI).get(KIOSQUE)).containsExactly("Buvette", "Kiosque");
    }

    private static Map<LocalDate, Map<Coordinates, SortedSet<String>>> kiosque() {
        return Map.of(AUJOURDHUI, Map.of(KIOSQUE, new TreeSet<>(Set.of("Kiosque"))));
    }

    private static Map<Coordinates, Map<LocalDate, DailyForecast>> prevision(
            Double temperature, Double gust, Integer code) {
        return Map.of(KIOSQUE, Map.of(AUJOURDHUI, new DailyForecast(AUJOURDHUI, temperature, gust, code)));
    }

    @Test
    void aCrossedThresholdRaisesOneAlertPerPhenomenon() {
        List<Alert> alerts = WeatherAlertService.evaluate(REGLAGES, kiosque(), prevision(36.4, 72.0, 96));

        assertThat(alerts)
                .extracting(Alert::phenomenon, Alert::value, Alert::level)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Phenomenon.HEAT, 36, 1),
                        org.assertj.core.groups.Tuple.tuple(Phenomenon.GUST, 72, 1),
                        org.assertj.core.groups.Tuple.tuple(Phenomenon.STORM, 96, 1));
        assertThat(alerts.get(0).places()).containsExactly("Kiosque");
        assertThat(alerts.get(0).key()).isEqualTo("2026-07-10|HEAT|1");
    }

    @Test
    void aThresholdNotCrossedRaisesNothing() {
        assertThat(WeatherAlertService.evaluate(REGLAGES, kiosque(), prevision(32.9, 59.0, 3)))
                .isEmpty();
        // Storms are watched only when asked for.
        ParametresMeteo sansOrage = new ParametresMeteo(true, 5, 33, 60, false, null, null, null, null);
        assertThat(WeatherAlertService.evaluate(sansOrage, kiosque(), prevision(20.0, 10.0, 95)))
                .isEmpty();
    }

    /** The level climbs by two degrees: 33 and 34.9 are the same alert, 35 is a worse one. */
    @Test
    void heatLevelsClimbTwoDegreesAtATime() {
        assertThat(WeatherAlertService.evaluate(REGLAGES, kiosque(), prevision(33.0, null, null))
                        .get(0)
                        .level())
                .isZero();
        assertThat(WeatherAlertService.evaluate(REGLAGES, kiosque(), prevision(34.9, null, null))
                        .get(0)
                        .level())
                .isZero();
        assertThat(WeatherAlertService.evaluate(REGLAGES, kiosque(), prevision(35.0, null, null))
                        .get(0)
                        .level())
                .isEqualTo(1);
    }

    @Test
    void theSentenceNamesThePlacesAndTheSuggestionOrTheConsigneInPlace() {
        Alert chaleur = new Alert(AUJOURDHUI, Phenomenon.HEAT, 36, 33, 1, List.of("Château", "Kiosque"));

        assertThat(WeatherAlertService.label(chaleur, "Plan canicule", null))
                .isEqualTo("Ven. 10/07 : 36 °C prévus à Château, Kiosque (seuil 33 °C). Suggestion : Plan canicule.");
        assertThat(WeatherAlertService.label(chaleur, "Plan canicule", "Arrêté préfectoral"))
                .endsWith("Consigne « Arrêté préfectoral » déjà en place.");
        Alert orage = new Alert(AUJOURDHUI, Phenomenon.STORM, 96, 95, 1, List.of("A", "B", "C", "D", "E", "F"));
        assertThat(WeatherAlertService.label(orage, null, null))
                .isEqualTo("Ven. 10/07 : orage avec grêle prévu à A, B, C, D et 2 autres lieux.");
    }

    /** A consigne is posed on a coming day only: an alert about today or before links to no form. */
    @Test
    void onlyAnAlertAboutAComingDayLinksToTheConsigneForm() {
        String demain = AUJOURDHUI.plusDays(1) + "|HEAT|0";

        assertThat(WeatherAlertService.consigneRoute(demain, Set.of(), REGLAGES, AUJOURDHUI))
                .contains("/consignes-solveur?onglet=consignes&date=" + AUJOURDHUI.plusDays(1)
                        + "&nouvelle=1&prereglage=canicule");
        // Under a consigne already: the date's consigne is shown, no form.
        assertThat(WeatherAlertService.consigneRoute(demain, Set.of(AUJOURDHUI.plusDays(1)), REGLAGES, AUJOURDHUI))
                .contains("/consignes-solveur?onglet=consignes&date=" + AUJOURDHUI.plusDays(1));
        assertThat(WeatherAlertService.consigneRoute(AUJOURDHUI + "|HEAT|0", Set.of(), REGLAGES, AUJOURDHUI))
                .isEmpty();
        assertThat(WeatherAlertService.consigneRoute(
                        AUJOURDHUI.minusDays(1) + "|STORM|0", Set.of(), REGLAGES, AUJOURDHUI))
                .isEmpty();
        assertThat(WeatherAlertService.consigneRoute("D42", Set.of(), REGLAGES, AUJOURDHUI))
                .isEmpty();
    }

    @Test
    void aKeyReadsBackOrNot() {
        assertThat(WeatherAlertService.parseKey("2026-07-10|GUST|2"))
                .contains(new WeatherAlertService.AlertKey(AUJOURDHUI, Phenomenon.GUST, 2));
        assertThat(WeatherAlertService.parseKey("injoignable|2026-07-10")).isEmpty();
        assertThat(WeatherAlertService.parseKey("D42")).isEmpty();
    }
}
