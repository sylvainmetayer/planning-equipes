package dev.sylvain.planning.service.mural;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.JourneeAnimateurView;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.PauseDueView;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.SequenceView;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralAlert;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralAlertType;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralShift;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralStand;
import dev.sylvain.planning.service.mural.AffichageMuralViewBuilder.Inputs;
import dev.sylvain.planning.service.mural.AffichageMuralViewBuilder.Settings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The wall display's rules, on hand-built plans: no database, no clock. */
class AffichageMuralViewBuilderTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);

    private static final Emplacement NORD = new Emplacement("nord", "Zone nord", null, null);

    private static final Emplacement SUD = new Emplacement("sud", "Zone sud", null, null);

    private final List<Creneau> creneaux = new ArrayList<>();

    private final List<PosteAffectation> postes = new ArrayList<>();

    private long nextCreneauId = 1;

    @Test
    void groupsTheSeatsOfAStandIntoItsShiftsOfTheDay() {
        Stand jeux = stand("jeux", "Jeux", NORD);
        Creneau matin = creneau(JOUR, "09:00", "13:00");
        Creneau apresMidi = creneau(JOUR, "14:00", "18:00");
        seat(jeux, matin, animateur("a1", "Camille", "Dupont"));
        seat(jeux, matin, null);
        seat(jeux, apresMidi, animateur("a2", "Léo", "Martin"));

        AffichageMuralView view = build(JOUR.atTime(10, 0), Settings.wholeEdition(false));

        assertThat(view.jour()).isEqualTo(JOUR);
        assertThat(view.stands()).singleElement().satisfies(stand -> {
            assertThat(stand.standNom()).isEqualTo("Jeux");
            assertThat(stand.emplacementNom()).isEqualTo("Zone nord");
            assertThat(stand.vacations())
                    .extracting(MuralShift::start)
                    .containsExactly(JOUR.atTime(9, 0), JOUR.atTime(14, 0));
            assertThat(stand.vacations().getFirst().noms()).containsExactly("Camille D.");
            assertThat(stand.vacations().getFirst().emptySeats()).isEqualTo(1);
        });
    }

    @Test
    void fullNamesOnlyWhenTheLinkAsksForThem() {
        Stand jeux = stand("jeux", "Jeux", NORD);
        seat(jeux, creneau(JOUR, "09:00", "13:00"), animateur("a1", "Camille", "Dupont"));

        assertThat(build(JOUR.atTime(10, 0), Settings.wholeEdition(false))
                        .stands()
                        .getFirst()
                        .vacations()
                        .getFirst()
                        .noms())
                .containsExactly("Camille D.");
        assertThat(build(JOUR.atTime(10, 0), Settings.wholeEdition(true))
                        .stands()
                        .getFirst()
                        .vacations()
                        .getFirst()
                        .noms())
                .containsExactly("Camille Dupont");
    }

    @Test
    void theDisplayNameFallsBackOnWhatTheFicheHolds() {
        assertThat(AffichageMuralViewBuilder.displayName(animateur("a1", "Camille", null), false))
                .isEqualTo("Camille");
        assertThat(AffichageMuralViewBuilder.displayName(animateur("a1", null, "Dupont"), false))
                .isEqualTo("D.");
        assertThat(AffichageMuralViewBuilder.displayName(animateur("a1", " Élise ", "Égal"), false))
                .isEqualTo("Élise É.");
    }

    @Test
    void theEmplacementFilterKeepsItsStandsOnly() {
        seat(stand("jeux", "Jeux", NORD), creneau(JOUR, "09:00", "13:00"), null);
        seat(stand("buvette", "Buvette", SUD), creneau(JOUR, "09:00", "13:00"), null);
        seat(stand("accueil", "Accueil", null), creneau(JOUR, "09:00", "13:00"), null);

        assertThat(build(JOUR.atTime(10, 0), Settings.wholeEdition(false)).stands())
                .extracting(MuralStand::standId)
                .containsExactly("accueil", "jeux", "buvette");
        AffichageMuralView sud = build(JOUR.atTime(10, 0), new Settings(false, true, Set.of("sud")));
        assertThat(sud.stands()).extracting(MuralStand::standId).containsExactly("buvette");
        assertThat(sud.alerts()).extracting(MuralAlert::standNom).containsExactly("Buvette");
    }

    /** A zone's screen whose emplacements were all deleted shows nothing — never, silently, everything. */
    @Test
    void aRestrictedLinkWithNoEmplacementLeftShowsNoStand() {
        seat(stand("jeux", "Jeux", NORD), creneau(JOUR, "09:00", "13:00"), null);

        assertThat(build(JOUR.atTime(10, 0), new Settings(false, true, Set.of()))
                        .stands())
                .isEmpty();
    }

    /** At one in the morning, the evening's 22:00-02:00 shift is still the day under way. */
    @Test
    void aShiftCrossingMidnightKeepsItsDayUnderWay() {
        Stand bar = stand("bar", "Bar", NORD);
        seat(bar, creneau(JOUR, "22:00", "02:00"), animateur("a1", "Camille", "Dupont"));
        creneau(JOUR.plusDays(1), "09:00", "13:00");

        AffichageMuralView view = build(JOUR.plusDays(1).atTime(1, 0), Settings.wholeEdition(false));

        assertThat(view.jour()).isEqualTo(JOUR);
        assertThat(view.stands().getFirst().vacations().getFirst().end())
                .isEqualTo(JOUR.plusDays(1).atTime(2, 0));
        assertThat(view.nextDay()).isEqualTo(JOUR.plusDays(1));
    }

    @Test
    void aDayWithoutTimeslotShowsNoStandAndNamesTheNextDay() {
        seat(stand("jeux", "Jeux", NORD), creneau(JOUR, "09:00", "13:00"), null);

        AffichageMuralView view = build(JOUR.minusDays(3).atTime(10, 0), Settings.wholeEdition(false));

        assertThat(view.jour()).isEqualTo(JOUR.minusDays(3));
        assertThat(view.stands()).isEmpty();
        assertThat(view.alerts()).isEmpty();
        assertThat(view.nextDay()).isEqualTo(JOUR);
    }

    @Test
    void emptySeatsAlertWithinTwoHoursOnly() {
        Stand jeux = stand("jeux", "Jeux", NORD);
        seat(jeux, creneau(JOUR, "09:00", "10:00"), null);
        seat(jeux, creneau(JOUR, "11:00", "12:00"), null);
        seat(jeux, creneau(JOUR, "12:00", "14:00"), null);
        seat(jeux, creneau(JOUR, "15:00", "16:00"), null);

        List<MuralAlert> alerts =
                build(JOUR.atTime(10, 30), Settings.wholeEdition(false)).alerts();

        assertThat(alerts).extracting(MuralAlert::type).containsOnly(MuralAlertType.EMPTY_SEATS);
        assertThat(alerts).extracting(MuralAlert::start).containsExactly(JOUR.atTime(11, 0), JOUR.atTime(12, 0));
    }

    @Test
    void breaksWithoutRelayOfTheDayAreAlertedUntilTheyEnd() {
        Stand jeux = stand("jeux", "Jeux", NORD);
        Animateur camille = animateur("a1", "Camille", "Dupont");
        seat(jeux, creneau(JOUR, "09:00", "18:00"), camille);
        RapportPauses pauses = new RapportPauses(
                1,
                2,
                2,
                0,
                0,
                List.of(new JourneeAnimateurView(
                        "a1",
                        "Camille Dupont",
                        false,
                        JOUR,
                        1,
                        List.of(new SequenceView(
                                LocalTime.of(9, 0),
                                LocalTime.of(18, 0),
                                540,
                                List.of(pause("11:00", false), pause("15:00", false), pause("16:00", true)))),
                        List.of(),
                        List.of())),
                "");

        AffichageMuralView view =
                AffichageMuralViewBuilder.build(inputs(JOUR.atTime(12, 0), pauses, null), Settings.wholeEdition(false));

        assertThat(view.alerts())
                .filteredOn(alert -> alert.type() == MuralAlertType.BREAK_WITHOUT_RELAY)
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.start()).isEqualTo(JOUR.atTime(15, 0));
                    assertThat(alert.nom()).isEqualTo("Camille D.");
                    assertThat(alert.standNom()).isEqualTo("Jeux");
                });
    }

    @Test
    void theConsigneOfTheDayTravelsWithTheView() {
        ConsigneEdition consigne = new ConsigneEdition(
                JOUR,
                LocalTime.of(13, 0),
                LocalTime.of(16, 0),
                "Plan canicule",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);

        AffichageMuralView view = AffichageMuralViewBuilder.build(
                inputs(JOUR.atTime(10, 0), null, consigne), Settings.wholeEdition(false));

        assertThat(view.consigne().motif()).isEqualTo("Plan canicule");
        assertThat(view.consigne().closedFrom()).isEqualTo(LocalTime.of(13, 0));
    }

    /**
     * The 01:00 break of a 22:00-04:00 night shift falls on the next calendar
     * day: read on the evening's date, it had ended before the shift began and
     * was never shown.
     */
    @Test
    void aBreakAfterMidnightOfANightShiftIsAlerted() {
        Stand bar = stand("bar", "Bar", NORD);
        seat(bar, creneau(JOUR, "22:00", "04:00"), animateur("a1", "Camille", "Dupont"));
        RapportPauses pauses = new RapportPauses(
                1,
                1,
                1,
                0,
                0,
                List.of(new JourneeAnimateurView(
                        "a1",
                        "Camille Dupont",
                        false,
                        JOUR,
                        1,
                        List.of(new SequenceView(
                                LocalTime.of(22, 0),
                                LocalTime.of(4, 0),
                                360,
                                List.of(new PauseDueView(
                                        LocalTime.of(1, 0),
                                        LocalTime.of(1, 20),
                                        LocalTime.of(1, 0),
                                        20,
                                        "bar",
                                        "Bar",
                                        1L,
                                        List.of(),
                                        false,
                                        false)))),
                        List.of(),
                        List.of())),
                "");

        for (LocalDateTime now : List.of(JOUR.atTime(23, 0), JOUR.plusDays(1).atTime(0, 30))) {
            assertThat(AffichageMuralViewBuilder.build(inputs(now, pauses, null), Settings.wholeEdition(false))
                            .alerts())
                    .filteredOn(alert -> alert.type() == MuralAlertType.BREAK_WITHOUT_RELAY)
                    .singleElement()
                    .satisfies(alert -> {
                        assertThat(alert.start()).isEqualTo(JOUR.plusDays(1).atTime(1, 0));
                        assertThat(alert.end()).isEqualTo(JOUR.plusDays(1).atTime(1, 20));
                    });
        }
    }

    /** « Jusqu'à minuit » is the consigne's own null end, and travels as such. */
    @Test
    void aConsigneUntilMidnightTravelsWithoutAnEnd() {
        ConsigneEdition consigne = new ConsigneEdition(
                JOUR,
                LocalTime.of(20, 0),
                null,
                "Arrêté préfectoral",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);

        AffichageMuralView view = AffichageMuralViewBuilder.build(
                inputs(JOUR.atTime(10, 0), null, consigne), Settings.wholeEdition(false));

        assertThat(view.consigne().closedFrom()).isEqualTo(LocalTime.of(20, 0));
        assertThat(view.consigne().closedUntil()).isNull();
    }

    /* ------------------------------ Fixtures ----------------------------- */

    private AffichageMuralView build(LocalDateTime now, Settings settings) {
        return AffichageMuralViewBuilder.build(inputs(now, null, null), settings);
    }

    private Inputs inputs(LocalDateTime now, RapportPauses pauses, ConsigneEdition consigne) {
        return new Inputs(
                "Année 2026",
                "TV",
                now,
                AffichageMuralViewBuilder.currentDay(creneaux, now),
                creneaux,
                postes,
                pauses,
                consigne);
    }

    private Creneau creneau(LocalDate date, String debut, String fin) {
        Creneau creneau = new Creneau(nextCreneauId++, 1, date, LocalTime.parse(debut), LocalTime.parse(fin));
        creneaux.add(creneau);
        return creneau;
    }

    private void seat(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation("p" + postes.size(), stand, creneau);
        poste.setAnimateur(animateur);
        postes.add(poste);
    }

    private static Stand stand(String id, String nom, Emplacement emplacement) {
        Stand stand = new Stand();
        stand.setId(id);
        stand.setNom(nom);
        stand.setEmplacement(emplacement);
        return stand;
    }

    private static Animateur animateur(String id, String prenom, String nom) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setPrenom(prenom);
        animateur.setNom(nom);
        return animateur;
    }

    private static PauseDueView pause(String debut, boolean relais) {
        LocalTime start = LocalTime.parse(debut);
        return new PauseDueView(start, start.plusMinutes(20), start, 20, "jeux", "Jeux", 1L, List.of(), relais, false);
    }
}
