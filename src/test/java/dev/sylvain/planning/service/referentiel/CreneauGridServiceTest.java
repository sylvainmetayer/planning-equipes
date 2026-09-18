package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.CreneauGridService.GridAnomaly;
import dev.sylvain.planning.service.referentiel.CreneauGridService.GridAnomalyType;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RegleRecurrence;
import dev.sylvain.planning.service.referentiel.CreneauGridService.SeveriteGrille;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test of the recurrence expansion and the grid audit: no Quarkus,
 * no database. The stand and feasibility parts of the report are covered by
 * {@link OuvertureStandsAnalyzerTest} and {@link FeasibilityAnalyzerTest} —
 * this one exercises what {@link CreneauGridService} adds on top of them,
 * with an empty stand list so those two contribute nothing.
 */
class CreneauGridServiceTest {

    private static final ParametresLegaux LEGAUX = new ParametresLegaux();

    private final CreneauGridService service = new CreneauGridService();

    /* ------------------------------ Generation ------------------------------ */

    @Test
    void deuxFenetresSurUneSemaineSansWeekEndDonnentDixCreneaux() {
        // 2026-07-06 is a Monday: the range covers two full weeks minus the weekends.
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(new RegleRecurrence(
                TypeJoursHoraire.JOURS_SEMAINE,
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 12),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                Set.of(),
                Set.of(),
                List.of(fenetre("09:00", "12:00"), fenetre("14:00", "18:00"))));

        assertThat(creneaux).hasSize(10);
        assertThat(creneaux)
                .extracting(Creneau::getDate)
                .doesNotContain(LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 12));
        assertThat(creneaux.getFirst().getDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(creneaux.getFirst().getHeureDebut()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void lesExclusionsRetirentUneDateQueLeSelecteurRetenait() {
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(new RegleRecurrence(
                TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 8),
                Set.of(),
                Set.of(),
                Set.of(LocalDate.of(2026, 7, 7)),
                List.of(fenetre("09:00", "12:00"))));

        assertThat(creneaux)
                .extracting(Creneau::getDate)
                .containsExactly(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 8));
    }

    @Test
    void leJourNestPasRenseigneCarIlEstRecalculeALaLecture() {
        List<Creneau> creneaux = CreneauGridService.generateRecurrence(regleSimple());

        assertThat(creneaux).extracting(Creneau::getJour).containsOnly(0);
    }

    @Test
    void unSelecteurSansBornesEstRefuseAvecUnMessageActionnable() {
        RegleRecurrence withoutBounds = new RegleRecurrence(
                TypeJoursHoraire.TOUS, null, null, Set.of(), Set.of(), Set.of(), List.of(fenetre("09:00", "12:00")));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(withoutBounds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateDebut");
    }

    @Test
    void uneFenetreSansHeureDeFinEstRefusee() {
        RegleRecurrence ouverte = new RegleRecurrence(
                TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 6),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(new FenetreHoraire(LocalTime.of(14, 0), null)));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(ouverte))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heure de fin");
    }

    @Test
    void uneRegleQueLesExclusionsVidentEstRefuseePlutotQueSilencieuse() {
        RegleRecurrence videe = new RegleRecurrence(
                TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 6),
                Set.of(),
                Set.of(),
                Set.of(LocalDate.of(2026, 7, 6)),
                List.of(fenetre("09:00", "12:00")));

        assertThatThrownBy(() -> CreneauGridService.generateRecurrence(videe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aucune date");
    }

    /* ------------------------------ Validation ------------------------------ */

    /**
     * Two vacations of the same day that overlap are the normal shape of a
     * staggered handover, never a mistake. It used to depend on the grid's
     * declared mode — an overlap between two amplitudes was a duplicate entry —
     * and there is no second reading left to tell apart.
     */
    @Test
    void unChevauchementEntreVacationsDuMemeJourNestPasUneAnomalie() {
        List<Creneau> qui = List.of(creneau("2026-07-06", "09:00", "13:00"), creneau("2026-07-06", "12:00", "18:00"));

        assertThat(validate(qui, List.of(), List.of(), LEGAUX, List.of()).anomalies())
                .isEmpty();
    }

    @Test
    void unTrouDansLaJourneeEstSignale() {
        List<Creneau> withHole =
                List.of(creneau("2026-07-06", "09:00", "12:00"), creneau("2026-07-06", "14:00", "18:00"));

        List<GridAnomaly> anomalies =
                validate(withHole, List.of(), List.of(), LEGAUX, List.of()).anomalies();

        assertThat(anomalies).extracting(GridAnomaly::type).contains(GridAnomalyType.TROU_DANS_LA_JOURNEE);
        assertThat(anomalies).extracting(GridAnomaly::message).anyMatch(message -> message.contains("120 min"));
    }

    @Test
    void unDoublonExactEstUneErreurBloquante() {
        List<Creneau> doublon =
                List.of(creneau("2026-07-06", "09:00", "12:00"), creneau("2026-07-06", "09:00", "12:00"));

        CreneauGridService.RapportGrille rapport = validate(doublon, List.of(), List.of(), LEGAUX, List.of());

        assertThat(rapport.hasNoBlockingAnomaly()).isFalse();
        assertThat(rapport.anomalies()).extracting(GridAnomaly::type).contains(GridAnomalyType.DOUBLON);
    }

    @Test
    void uneVacationDepassantLeReposQuotidienEstUneErreur() {
        // 07:00 -> 23:00 = 960 min, above 1440 - 660 (default daily rest) = 780.
        List<Creneau> trop = List.of(creneau("2026-07-06", "07:00", "23:00"));

        CreneauGridService.RapportGrille rapport = validate(trop, List.of(), List.of(), LEGAUX, List.of());

        assertThat(rapport.hasNoBlockingAnomaly()).isFalse();
        assertThat(rapport.anomalies())
                .extracting(GridAnomaly::type)
                .contains(GridAnomalyType.REPOS_QUOTIDIEN_IMPOSSIBLE);
    }

    /**
     * The 6 h ceiling (art. L3121-16) now lives with the other legal rules, and
     * the warning reads it from there: an organiser who raises it is heard.
     */
    @Test
    void uneVacationDepassantLePlafondLegalEstUnAvertissementReglable() {
        List<Creneau> longue = List.of(creneau("2026-07-06", "09:00", "16:30"));

        assertThat(typesDetectes(longue)).contains(GridAnomalyType.VACATION_TROP_LONGUE);

        ParametresLegaux permissifs = new ParametresLegaux();
        permissifs.setDureeVacationMaxMinutes(8 * 60);
        assertThat(validate(longue, List.of(), List.of(), permissifs, List.of()).anomalies())
                .extracting(GridAnomaly::type)
                .doesNotContain(GridAnomalyType.VACATION_TROP_LONGUE);
    }

    @Test
    void uneDateIsoleeDePlusieursMoisEstSignalee() {
        List<Creneau> withMistake = List.of(
                creneau("2026-07-06", "09:00", "12:00"),
                creneau("2026-07-07", "09:00", "12:00"),
                creneau("2026-08-06", "09:00", "12:00"));

        assertThat(typesDetectes(withMistake)).contains(GridAnomalyType.DATE_ISOLEE);
    }

    @Test
    void unePauseDeQuelquesJoursNestPasUneDateIsolee() {
        List<Creneau> withPause =
                List.of(creneau("2026-07-06", "09:00", "12:00"), creneau("2026-07-11", "09:00", "12:00"));

        assertThat(typesDetectes(withPause)).doesNotContain(GridAnomalyType.DATE_ISOLEE);
    }

    @Test
    void unCreneauTraversantMinuitNaPasUneDureeNegative() {
        List<Creneau> nuit = List.of(creneau("2026-07-06", "20:00", "00:00"));

        CreneauGridService.RapportGrille rapport = validate(nuit, List.of(), List.of(), LEGAUX, List.of());

        assertThat(rapport.hasNoBlockingAnomaly()).isTrue();
        assertThat(rapport.anomalies()).isEmpty();
    }

    /* ------------------------------ Diagnostic ------------------------------ */

    @Test
    void leDiagnosticDecritLaGrilleEtCompteLesRelais() {
        Creneau relais = creneau("2026-07-06", "12:00", "13:00");
        relais.setCouverturePause(true);

        CreneauGridService.DiagnosticGrille diagnostic =
                CreneauGridService.diagnose(List.of(creneau("2026-07-06", "09:00", "12:00"), relais));

        assertThat(diagnostic.nombreCreneaux()).isEqualTo(2);
        assertThat(diagnostic.premiereDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(diagnostic.derniereDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(diagnostic.contientCouverturePause()).isTrue();
        assertThat(diagnostic.explication()).contains("1 relais repas");
    }

    @Test
    void uneGrilleVideLeDitPlutotQueDeDeviner() {
        CreneauGridService.DiagnosticGrille diagnostic = CreneauGridService.diagnose(List.of());

        assertThat(diagnostic.nombreCreneaux()).isZero();
        assertThat(diagnostic.premiereDate()).isNull();
        assertThat(diagnostic.explication()).contains("aucun créneau");
    }

    // A hand-typed meal relay (12-13 at half headcount) is only meaningful
    // where a meal break may be taken. Outside the windows it halves the seats
    // for nothing: the grid is the one place that reads the flag against the
    // legal parameters.
    @Test
    void relaisRepasHorsFenetreEstSignale() {
        Creneau relais = creneau("2026-07-06", "16:00", "17:00");
        relais.setCouverturePause(true);
        Creneau midi = creneau("2026-07-06", "12:00", "13:00");
        midi.setCouverturePause(true);
        Creneau nuit = creneau("2026-07-06", "23:00", "00:00");
        nuit.setCouverturePause(true);

        List<GridAnomaly> anomalies = validate(List.of(relais, midi, nuit), List.of(), List.of(), LEGAUX, List.of())
                .anomalies();

        List<GridAnomaly> horsFenetre = anomalies.stream()
                .filter(anomalie -> anomalie.type() == GridAnomalyType.RELAIS_REPAS_HORS_FENETRE)
                .toList();
        assertThat(horsFenetre).hasSize(2);
        assertThat(horsFenetre).allSatisfy(anomalie -> {
            assertThat(anomalie.severite()).isEqualTo(SeveriteGrille.AVERTISSEMENT);
            assertThat(anomalie.message()).contains("relais repas").contains("midi 12:00-14:00");
        });
        assertThat(horsFenetre)
                .extracting(GridAnomaly::message)
                .anyMatch(message -> message.contains("16:00-17:00"))
                .anyMatch(message -> message.contains("23:00-00:00"));
    }

    // Issue #577: a lock names its vacation by day and hours, so it survives a
    // delete-and-recreate. What the grid reports is the other case — the day
    // did not come back — and it reports it on a preview, before « remplacer
    // la grille » is written.
    @Test
    void unVerrouillageQueLaGrilleNePortePlusEstSignale() {
        Creneau tenue = creneau("2026-07-06", "14:00", "18:00");

        VerrouillagePlanning garde = verrouillage(LocalDate.of(2026, 7, 6), "14:00", "18:00");
        VerrouillagePlanning perdu = verrouillage(LocalDate.of(2026, 7, 7), "14:00", "18:00");
        VerrouillagePlanning perduAussi = verrouillage(LocalDate.of(2026, 7, 7), "14:00", "18:00");

        List<GridAnomaly> anomalies =
                validate(List.of(tenue), List.of(), List.of(), LEGAUX, List.of(garde, perdu, perduAussi))
                        .anomalies()
                        .stream()
                        .filter(anomalie -> anomalie.type() == GridAnomalyType.VERROUILLAGE_SANS_VACATION)
                        .toList();

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.getFirst().severite()).isEqualTo(SeveriteGrille.AVERTISSEMENT);
        assertThat(anomalies.getFirst().date()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(anomalies.getFirst().message()).contains("2 verrouillage(s)").contains("2026-07-07 14:00-18:00");
    }

    @Test
    void unVerrouillageQuiNeViseAucuneVacationNEstPasSignale() {
        // ANIMATEUR and STAND locks carry no day: they never fall with the grid.
        VerrouillagePlanning surAnimateur = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        surAnimateur.setAnimateurId("A1");

        assertThat(validate(List.of(), List.of(), List.of(), LEGAUX, List.of(surAnimateur))
                        .anomalies())
                .isEmpty();
    }

    private static VerrouillagePlanning verrouillage(LocalDate date, String debut, String fin) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning("V-" + date + debut, TypeVerrouillage.CRENEAU);
        verrouillage.setCreneauDate(date);
        verrouillage.setCreneauHeureDebut(LocalTime.parse(debut));
        verrouillage.setCreneauHeureFin(LocalTime.parse(fin));
        return verrouillage;
    }

    /* -------------------------------- Outils -------------------------------- */

    /** The verdict over the edition's own meal windows — the ones every test here judges against. */
    private CreneauGridService.RapportGrille validate(
            List<Creneau> creneaux,
            List<dev.sylvain.planning.domain.Stand> stands,
            List<dev.sylvain.planning.domain.Animateur> animateurs,
            ParametresLegaux legaux,
            List<VerrouillagePlanning> verrouillages) {
        return service.validate(creneaux, stands, animateurs, legaux, verrouillages, FenetreRepas.from(legaux));
    }

    private List<GridAnomalyType> typesDetectes(List<Creneau> creneaux) {
        return validate(creneaux, List.of(), List.of(), LEGAUX, List.of()).anomalies().stream()
                .map(GridAnomaly::type)
                .toList();
    }

    private static RegleRecurrence regleSimple() {
        return new RegleRecurrence(
                TypeJoursHoraire.TOUS,
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 8),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(fenetre("09:00", "12:00")));
    }

    private static FenetreHoraire fenetre(String debut, String fin) {
        return new FenetreHoraire(LocalTime.parse(debut), LocalTime.parse(fin));
    }

    private static Creneau creneau(String date, String debut, String fin) {
        return new Creneau(null, 0, LocalDate.parse(date), LocalTime.parse(debut), LocalTime.parse(fin));
    }
}
