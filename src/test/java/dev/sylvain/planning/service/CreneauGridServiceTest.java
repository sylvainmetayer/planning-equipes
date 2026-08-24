package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.CreneauGridService.GridAnomaly;
import dev.sylvain.planning.service.CreneauGridService.RegleRecurrence;
import dev.sylvain.planning.service.CreneauGridService.GridAnomalyType;

/**
 * Plain unit test of the recurrence expansion and the grid audit: no Quarkus,
 * no database. The stand and feasibility parts of the report are covered by
 * {@link OuvertureStandsAnalyzerTest} and {@link FeasibilityAnalyzerTest} —
 * this one exercises what {@link CreneauGridService} adds on top of them,
 * with an empty stand list so those two contribute nothing.
 */
class CreneauGridServiceTest {

    private static final ParametresDecoupage DECOUPAGE = new ParametresDecoupage();
    private static final ParametresLegaux LEGAUX = new ParametresLegaux();

    private final CreneauGridService service = new CreneauGridService();

    /* ------------------------------ Generation ------------------------------ */

    @Test
    void deuxFenetresSurUneSemaineSansWeekEndDonnentDixCreneaux() {
        // 2026-07-06 is a Monday: the range covers two full weeks minus the weekends.
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(new RegleRecurrence(
                TypeJoursHoraire.JOURS_SEMAINE,
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                Set.of(), Set.of(),
                List.of(fenetre("09:00", "12:00"), fenetre("14:00", "18:00"))));

        assertThat(creneaux).hasSize(10);
        assertThat(creneaux).extracting(Creneau::getDate).doesNotContain(
                LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 12));
        assertThat(creneaux.getFirst().getDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(creneaux.getFirst().getHeureDebut()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void lesExclusionsRetirentUneDateQueLeSelecteurRetenait() {
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(new RegleRecurrence(
                TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 8),
                Set.of(), Set.of(), Set.of(LocalDate.of(2026, 7, 7)),
                List.of(fenetre("09:00", "12:00"))));

        assertThat(creneaux).extracting(Creneau::getDate)
                .containsExactly(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 8));
    }

    @Test
    void leJourNestPasRenseigneCarIlEstRecalculeALaLecture() {
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(regleSimple());

        assertThat(creneaux).extracting(Creneau::getJour).containsOnly(0);
    }

    @Test
    void unSelecteurSansBornesEstRefuseAvecUnMessageActionnable() {
        RegleRecurrence withoutBounds = new RegleRecurrence(TypeJoursHoraire.TOUS, null, null,
                Set.of(), Set.of(), Set.of(), List.of(fenetre("09:00", "12:00")));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(withoutBounds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateDebut");
    }

    @Test
    void uneFenetreSansHeureDeFinEstRefusee() {
        RegleRecurrence ouverte = new RegleRecurrence(TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
                Set.of(), Set.of(), Set.of(), List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(ouverte))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heure de fin");
    }

    @Test
    void uneRegleQueLesExclusionsVidentEstRefuseePlutotQueSilencieuse() {
        RegleRecurrence videe = new RegleRecurrence(TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
                Set.of(), Set.of(), Set.of(LocalDate.of(2026, 7, 6)), List.of(fenetre("09:00", "12:00")));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(videe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aucune date");
    }

    /* ------------------------------ Validation ------------------------------ */

    @Test
    void unChevauchementEstSignaleEntreAmplitudesEtIgnoreEntreVacations() {
        List<Creneau> qui = List.of(creneau("2026-07-06", "09:00", "13:00"), creneau("2026-07-06", "12:00", "18:00"));

        assertThat(typesDetectes(qui, ModeGrilleCreneaux.AMPLITUDES))
                .contains(GridAnomalyType.CHEVAUCHEMENT);
        assertThat(typesDetectes(qui, ModeGrilleCreneaux.VACATIONS))
                .doesNotContain(GridAnomalyType.CHEVAUCHEMENT);
    }

    @Test
    void unTrouDansLaJourneeEstSignale() {
        List<Creneau> withHole = List.of(creneau("2026-07-06", "09:00", "12:00"), creneau("2026-07-06", "14:00", "18:00"));

        List<GridAnomaly> anomalies = service
                .validate(withHole, List.of(), List.of(), ModeGrilleCreneaux.AMPLITUDES, DECOUPAGE, LEGAUX)
                .anomalies();

        assertThat(anomalies).extracting(GridAnomaly::type).contains(GridAnomalyType.TROU_DANS_LA_JOURNEE);
        assertThat(anomalies).extracting(GridAnomaly::message).anyMatch(message -> message.contains("120 min"));
    }

    @Test
    void unDoublonExactEstUneErreurBloquante() {
        List<Creneau> doublon = List.of(creneau("2026-07-06", "09:00", "12:00"), creneau("2026-07-06", "09:00", "12:00"));

        CreneauGridService.RapportGrille rapport = service.validate(doublon, List.of(), List.of(),
                ModeGrilleCreneaux.AMPLITUDES, DECOUPAGE, LEGAUX);

        assertThat(rapport.hasNoBlockingAnomaly()).isFalse();
        assertThat(rapport.anomalies()).extracting(GridAnomaly::type).contains(GridAnomalyType.DOUBLON);
    }

    @Test
    void uneVacationDepassantLeReposQuotidienEstUneErreur() {
        // 07:00 -> 23:00 = 960 min, above 1440 - 660 (default daily rest) = 780.
        List<Creneau> trop = List.of(creneau("2026-07-06", "07:00", "23:00"));

        CreneauGridService.RapportGrille rapport = service.validate(trop, List.of(), List.of(),
                ModeGrilleCreneaux.VACATIONS, DECOUPAGE, LEGAUX);

        assertThat(rapport.hasNoBlockingAnomaly()).isFalse();
        assertThat(rapport.anomalies()).extracting(GridAnomaly::type)
                .contains(GridAnomalyType.REPOS_QUOTIDIEN_IMPOSSIBLE);
        // The very same grid read as amplitudes is a perfectly ordinary event day.
        assertThat(service.validate(trop, List.of(), List.of(), ModeGrilleCreneaux.AMPLITUDES, DECOUPAGE, LEGAUX)
                .hasNoBlockingAnomaly()).isTrue();
    }

    @Test
    void uneDateIsoleeDePlusieursMoisEstSignalee() {
        List<Creneau> withMistake = List.of(
                creneau("2026-07-06", "09:00", "12:00"),
                creneau("2026-07-07", "09:00", "12:00"),
                creneau("2026-08-06", "09:00", "12:00"));

        assertThat(typesDetectes(withMistake, ModeGrilleCreneaux.AMPLITUDES))
                .contains(GridAnomalyType.DATE_ISOLEE);
    }

    @Test
    void unePauseDeQuelquesJoursNestPasUneDateIsolee() {
        List<Creneau> withPause = List.of(
                creneau("2026-07-06", "09:00", "12:00"),
                creneau("2026-07-11", "09:00", "12:00"));

        assertThat(typesDetectes(withPause, ModeGrilleCreneaux.AMPLITUDES))
                .doesNotContain(GridAnomalyType.DATE_ISOLEE);
    }

    @Test
    void unCreneauTraversantMinuitNaPasUneDureeNegative() {
        List<Creneau> nuit = List.of(creneau("2026-07-06", "20:00", "00:00"));

        CreneauGridService.RapportGrille rapport = service.validate(nuit, List.of(), List.of(),
                ModeGrilleCreneaux.AMPLITUDES, DECOUPAGE, LEGAUX);

        assertThat(rapport.hasNoBlockingAnomaly()).isTrue();
        assertThat(rapport.anomalies()).isEmpty();
    }

    /* ------------------------------ Diagnostic ------------------------------ */

    @Test
    void unGrilleAvecFamillesEstCertainementDesVacations() {
        Creneau vacation = creneau("2026-07-06", "09:00", "12:00");
        Creneau otherFamily = creneau("2026-07-06", "09:30", "12:30");
        otherFamily.setFamille(1);

        CreneauGridService.DiagnosticGrille diagnostic = CreneauGridService
                .diagnose(List.of(vacation, otherFamily), DECOUPAGE);

        assertThat(diagnostic.modeProbable()).isEqualTo(ModeGrilleCreneaux.VACATIONS);
        assertThat(diagnostic.modeCertain()).isTrue();
        assertThat(diagnostic.nombreFamilles()).isEqualTo(2);
    }

    @Test
    void deLonguesJourneesSansFamilleSuggerentDesAmplitudesSansCertitude() {
        CreneauGridService.DiagnosticGrille diagnostic = CreneauGridService
                .diagnose(List.of(creneau("2026-07-06", "09:00", "20:00")), DECOUPAGE);

        assertThat(diagnostic.modeProbable()).isEqualTo(ModeGrilleCreneaux.AMPLITUDES);
        assertThat(diagnostic.modeCertain()).isFalse();
    }

    @Test
    void uneGrilleVideLeDitPlutotQueDeDeviner() {
        CreneauGridService.DiagnosticGrille diagnostic = CreneauGridService.diagnose(List.of(), DECOUPAGE);

        assertThat(diagnostic.nombreCreneaux()).isZero();
        assertThat(diagnostic.modeProbable()).isNull();
    }

    /* -------------------------------- Outils -------------------------------- */

    private List<GridAnomalyType> typesDetectes(List<Creneau> creneaux, ModeGrilleCreneaux mode) {
        return service.validate(creneaux, List.of(), List.of(), mode, DECOUPAGE, LEGAUX)
                .anomalies().stream().map(GridAnomaly::type).toList();
    }

    private static RegleRecurrence regleSimple() {
        return new RegleRecurrence(TypeJoursHoraire.TOUS, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 8),
                Set.of(), Set.of(), Set.of(), List.of(fenetre("09:00", "12:00")));
    }

    private static FenetreHoraire fenetre(String debut, String fin) {
        return new FenetreHoraire(LocalTime.parse(debut), LocalTime.parse(fin));
    }

    private static Creneau creneau(String date, String debut, String fin) {
        return new Creneau(null, 0, LocalDate.parse(date), LocalTime.parse(debut), LocalTime.parse(fin));
    }
}
