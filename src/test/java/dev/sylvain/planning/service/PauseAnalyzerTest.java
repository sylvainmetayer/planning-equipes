package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PauseAnalyzer.JourneeAnimateurView;
import dev.sylvain.planning.service.PauseAnalyzer.PauseDueView;
import dev.sylvain.planning.service.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.PauseAnalyzer.SequenceView;

/**
 * The read-out is only worth something if its deadlines are the ones the
 * constraints enforce: six hours for an adult, four and a half for a minor,
 * a gap shorter than the legal break not counting as one. Each case below is a
 * day an organiser would actually meet.
 */
class PauseAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    private final PauseAnalyzer analyzer = new PauseAnalyzer();

    // --- nominal: the 13:00-20:00 relay ---------------------------------------

    @Test
    void aSevenHourStretchOwesOneBreakAtTheSixthHourOnTheStandHeldThen() {
        Stand stand = stand("JEUX", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(poste("p1", stand, creneau(1, 13, 0, 14, 0), alice));
        postes.add(poste("p2", stand, creneau(2, 14, 0, 20, 0), alice));
        postes.add(poste("p3", stand, creneau(2, 14, 0, 20, 0), bob));

        RapportPauses rapport = analyzer.analyze(planning(List.of(alice, bob), postes), surPoste(true));

        assertThat(rapport.pauseSurPoste()).isTrue();
        assertThat(rapport.pausesDues()).isEqualTo(1);
        assertThat(rapport.relaisManquants()).isZero();
        JourneeAnimateurView journee = journee(rapport, "alice");
        assertThat(journee.mineur()).isFalse();
        assertThat(journee.jour()).isEqualTo(1);
        assertThat(journee.sequences()).hasSize(1);
        SequenceView sequence = journee.sequences().getFirst();
        assertThat(sequence.debut()).isEqualTo(LocalTime.of(13, 0));
        assertThat(sequence.fin()).isEqualTo(LocalTime.of(20, 0));
        assertThat(sequence.minutes()).isEqualTo(420);
        PauseDueView pause = sequence.pausesDues().getFirst();
        assertThat(pause.heureLimite()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pause.dureeMinutes()).isEqualTo(20);
        assertThat(pause.standId()).isEqualTo("JEUX");
        assertThat(pause.relaisDisponible()).isTrue();
        assertThat(pause.relais()).extracting(PauseAnalyzer.RelaisView::animateurId).containsExactly("bob");
        // Bob's own six hours end at 20:00 exactly: nothing owed on his side.
        assertThat(rapport.journees()).extracting(JourneeAnimateurView::animateurId).containsExactly("alice");
    }

    @Test
    void aStretchOfExactlySixHoursOwesNothing() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 14, 0, 20, 0), alice))),
                surPoste(true));

        assertThat(rapport.pausesDues()).isZero();
        assertThat(rapport.journeesAnalysees()).isEqualTo(1);
        assertThat(rapport.journees()).isEmpty();
        assertThat(rapport.message()).contains("rien à organiser");
    }

    @Test
    void aStretchOfSixHoursAndOneMinuteOwesABreak() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 14, 0, 20, 1), alice))),
                surPoste(true));

        assertThat(rapport.pausesDues()).isEqualTo(1);
        assertThat(journee(rapport, "alice").sequences().getFirst().pausesDues().getFirst().heureLimite())
                .isEqualTo(LocalTime.of(20, 0));
    }

    // --- relays ---------------------------------------------------------------

    @Test
    void aLoneAnimateurOnTheStandHasNoRelayAndIsCountedAsSuch() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 13, 0, 20, 0), alice))),
                surPoste(true));

        PauseDueView pause = journee(rapport, "alice").sequences().getFirst().pausesDues().getFirst();
        assertThat(pause.relaisDisponible()).isFalse();
        assertThat(pause.relais()).isEmpty();
        assertThat(rapport.relaisManquants()).isEqualTo(1);
        assertThat(rapport.message()).contains("1 pause à prendre sur le poste").contains("sans relais possible");
    }

    @Test
    void aColleagueOnAnotherStandOrGoneBeforeTheDeadlineIsNoRelay() {
        Stand jeux = stand("JEUX", 2);
        Stand autre = stand("AUTRE", 1);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        Animateur carol = adulte("carol");
        Animateur dan = adulte("dan");
        List<PosteAffectation> postes = List.of(
                poste("p1", jeux, creneau(1, 13, 0, 20, 0), alice),
                poste("p2", autre, creneau(1, 13, 0, 20, 0), bob),          // other stand
                poste("p3", jeux, creneau(2, 13, 0, 18, 0), carol),         // gone at 19:00
                poste("p4", jeux, creneau(3, 19, 0, 20, 0), dan));          // arrives exactly at 19:00

        RapportPauses rapport = analyzer.analyze(planning(List.of(alice, bob, carol, dan), postes), surPoste(true));

        PauseDueView pause = journee(rapport, "alice").sequences().getFirst().pausesDues().getFirst();
        assertThat(pause.relais()).extracting(PauseAnalyzer.RelaisView::animateurId).containsExactly("dan");
    }

    @Test
    void relaysAreNamedOnceEvenWhenAColleagueHoldsTwoSeatsAndSortedByName() {
        Stand stand = stand("JEUX", 3);
        Animateur alice = adulte("alice");
        Animateur zoe = new Animateur("zoe", "Zoé", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bob = new Animateur("bob", "Bob", "Durand", LocalDate.of(1990, 1, 1), false);
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), alice),
                poste("p2", stand, creneau(1, 13, 0, 20, 0), zoe),
                poste("p3", stand, creneau(2, 18, 0, 19, 30), zoe),
                poste("p4", stand, creneau(1, 13, 0, 20, 0), bob));

        PauseDueView pause = journee(analyzer.analyze(planning(List.of(alice, zoe, bob), postes), surPoste(true)),
                "alice").sequences().getFirst().pausesDues().getFirst();

        assertThat(pause.relais()).extracting(PauseAnalyzer.RelaisView::animateurId).containsExactly("bob", "zoe");
    }

    // --- stretches, gaps, several breaks ----------------------------------------

    @Test
    void aGapShorterThanTheLegalBreakDoesNotSplitTheStretch() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 10, 0, 13, 0), alice),
                poste("p2", stand, creneau(2, 13, 15, 17, 0), alice));   // 15 min: not a break

        JourneeAnimateurView journee = journee(analyzer.analyze(planning(List.of(alice), postes), surPoste(true)),
                "alice");

        assertThat(journee.sequences()).hasSize(1);
        assertThat(journee.sequences().getFirst().minutes()).isEqualTo(420);
        assertThat(journee.sequences().getFirst().pausesDues().getFirst().heureLimite()).isEqualTo(LocalTime.of(16, 0));
        assertThat(journee.pausesPlanifiees()).isEmpty();
    }

    @Test
    void aScheduledGapSplitsTheDayAndIsListedAsTheBreakItIs() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 10, 0, 13, 0), alice),
                poste("p2", stand, creneau(2, 14, 0, 20, 0), alice));

        JourneeAnimateurView journee = journee(analyzer.analyze(planning(List.of(alice), postes), surPoste(true)),
                "alice");

        assertThat(journee.sequences()).extracting(SequenceView::minutes).containsExactly(180, 360);
        assertThat(journee.sequences()).allSatisfy(sequence -> assertThat(sequence.pausesDues()).isEmpty());
        assertThat(journee.pausesPlanifiees()).hasSize(1);
        assertThat(journee.pausesPlanifiees().getFirst().debut()).isEqualTo(LocalTime.of(13, 0));
        assertThat(journee.pausesPlanifiees().getFirst().fin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(journee.pausesPlanifiees().getFirst().minutes()).isEqualTo(60);
    }

    @Test
    void aTwentyMinuteGapIsABreakForAnAdult() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 10, 0, 16, 0), alice),
                poste("p2", stand, creneau(2, 16, 20, 20, 0), alice));

        JourneeAnimateurView journee = journee(analyzer.analyze(planning(List.of(alice), postes), surPoste(true)),
                "alice");

        assertThat(journee.sequences()).hasSize(2);
        assertThat(journee.pausesPlanifiees().getFirst().minutes()).isEqualTo(20);
    }

    @Test
    void aTenHourAmplitudeOwesOneBreakAndTwelveHoursAndAHalfOwesTwo() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses dix = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 14, 0, 0, 0), alice))), surPoste(true));
        assertThat(journee(dix, "alice").sequences().getFirst().pausesDues())
                .extracting(PauseDueView::heureLimite).containsExactly(LocalTime.of(20, 0));

        // 6 h, break, 6 h, break: the second one falls 6 h 20 after the first.
        RapportPauses treize = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p2", stand, creneau(2, 8, 0, 20, 30), alice))), surPoste(true));
        assertThat(journee(treize, "alice").sequences().getFirst().pausesDues())
                .extracting(PauseDueView::heureLimite).containsExactly(LocalTime.of(14, 0), LocalTime.of(20, 20));
    }

    @Test
    void aStretchRunningPastMidnightKeepsItsDeadlineOnTheClock() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 19, 0, 2, 0), alice))), surPoste(true));

        SequenceView sequence = journee(rapport, "alice").sequences().getFirst();
        assertThat(sequence.minutes()).isEqualTo(420);
        assertThat(sequence.fin()).isEqualTo(LocalTime.of(2, 0));
        assertThat(sequence.pausesDues().getFirst().heureLimite()).isEqualTo(LocalTime.of(1, 0));
    }

    @Test
    void theDeadlineNamesTheStandHeldAtThatMomentNotTheFirstOne() {
        Stand matin = stand("MATIN", 1);
        Stand soir = stand("SOIR", 1);
        Animateur alice = adulte("alice");
        List<PosteAffectation> postes = List.of(
                poste("p1", matin, creneau(1, 13, 0, 17, 0), alice),
                poste("p2", soir, creneau(2, 17, 0, 21, 0), alice));

        PauseDueView pause = journee(analyzer.analyze(planning(List.of(alice), postes), surPoste(true)), "alice")
                .sequences().getFirst().pausesDues().getFirst();

        assertThat(pause.heureLimite()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pause.standId()).isEqualTo("SOIR");
    }

    @Test
    void aPartiallyClosedStandIsReadOnItsEffectiveWindow() {
        // The slot says 14:00-20:00, the seat is effectively 14:00-19:00: five hours.
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        PosteAffectation poste = poste("p1", stand, creneau(1, 13, 0, 20, 0), alice);
        poste.setHeureDebutEffective(LocalTime.of(14, 0));
        poste.setHeureFinEffective(LocalTime.of(19, 0));

        RapportPauses rapport = analyzer.analyze(planning(List.of(alice), List.of(poste)), surPoste(true));

        assertThat(rapport.pausesDues()).isZero();
    }

    // --- minors -----------------------------------------------------------------

    @Test
    void aMinorOwesThirtyMinutesAtFourHoursAndAHalfAndATwentyMinuteGapIsNoBreakForThem() {
        Stand stand = stand("JEUX", 1);
        Animateur mineur = new Animateur("mia", "Mia", "Petit", JOUR.minusYears(16), false);
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 10, 0, 13, 0), mineur),
                poste("p2", stand, creneau(2, 13, 20, 16, 0), mineur));    // 20 min: a break for an adult only

        JourneeAnimateurView journee = journee(analyzer.analyze(planning(List.of(mineur), postes), surPoste(true)),
                "mia");

        assertThat(journee.mineur()).isTrue();
        assertThat(journee.sequences()).hasSize(1);
        PauseDueView pause = journee.sequences().getFirst().pausesDues().getFirst();
        assertThat(pause.heureLimite()).isEqualTo(LocalTime.of(14, 30));
        assertThat(pause.dureeMinutes()).isEqualTo(30);
    }

    @Test
    void anUnknownBirthDateIsReadWithTheAdultFigures() {
        Stand stand = stand("JEUX", 1);
        Animateur inconnu = new Animateur("x", "X", "X", null, false);
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(inconnu), List.of(poste("p1", stand, creneau(1, 13, 0, 20, 0), inconnu))),
                surPoste(true));

        JourneeAnimateurView journee = journee(rapport, "x");
        assertThat(journee.mineur()).isFalse();
        assertThat(journee.sequences().getFirst().pausesDues().getFirst().dureeMinutes()).isEqualTo(20);
    }

    // --- declaration, edges, ordering --------------------------------------------

    @Test
    void withoutTheDeclarationTheBreakIsStillReportedAndTheMessageSaysWhatToDo() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        RapportPauses rapport = analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 13, 0, 20, 0), alice))),
                surPoste(false));

        assertThat(rapport.pauseSurPoste()).isFalse();
        assertThat(rapport.pausesDues()).isEqualTo(1);
        assertThat(rapport.message()).contains("sans être déclarée").contains("Déclarez la pause");
    }

    @Test
    void anEmptyOrUnheldPlanReportsNothing() {
        assertThat(analyzer.analyze(null, null).journees()).isEmpty();
        assertThat(analyzer.analyze(new PlanningEvenement(JOUR, List.of(), List.of()), null).journeesAnalysees())
                .isZero();

        Stand stand = stand("JEUX", 1);
        PosteAffectation vide = new PosteAffectation("p1", stand, creneau(1, 13, 0, 20, 0));
        RapportPauses rapport = analyzer.analyze(planning(List.of(), List.of(vide)), surPoste(true));
        assertThat(rapport.journeesAnalysees()).isZero();
        assertThat(rapport.pausesDues()).isZero();
    }

    @Test
    void daysAreOrderedByDateThenByNameAndOnlyDaysWithSomethingToShowAppear() {
        Stand stand = stand("JEUX", 3);
        Animateur zoe = new Animateur("zoe", "Zoé", "A", LocalDate.of(1990, 1, 1), false);
        Animateur bob = new Animateur("bob", "Bob", "B", LocalDate.of(1990, 1, 1), false);
        Animateur court = adulte("court");
        Creneau lendemain = new Creneau(9L, 2, JOUR.plusDays(1), LocalTime.of(13, 0), LocalTime.of(20, 0));
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), zoe),
                poste("p2", stand, creneau(1, 13, 0, 20, 0), bob),
                poste("p3", stand, creneau(2, 14, 0, 18, 0), court),
                poste("p4", stand, lendemain, court));

        RapportPauses rapport = analyzer.analyze(planning(List.of(zoe, bob, court), postes), surPoste(true));

        assertThat(rapport.journeesAnalysees()).isEqualTo(4);
        assertThat(rapport.journees())
                .extracting(JourneeAnimateurView::animateurId, JourneeAnimateurView::date)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("bob", JOUR),
                        org.assertj.core.groups.Tuple.tuple("zoe", JOUR),
                        org.assertj.core.groups.Tuple.tuple("court", JOUR.plusDays(1)));
    }

    @Test
    void journeesAnimateurKeepsOnlyOneAnimateur() {
        Stand stand = stand("JEUX", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), alice),
                poste("p2", stand, creneau(1, 13, 0, 20, 0), bob));

        assertThat(analyzer.journeesAnimateur(planning(List.of(alice, bob), postes), surPoste(true), "bob"))
                .extracting(JourneeAnimateurView::animateurId).containsExactly("bob");
        assertThat(analyzer.journeesAnimateur(planning(List.of(alice, bob), postes), surPoste(true), "nobody")).isEmpty();
    }

    // --- rotation on the stand -------------------------------------------------

    @Test
    void aLoneBreakIsPlacedAtItsDeadlineAndSpansItsDuration() {
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        PauseDueView pause = journee(analyzer.analyze(
                planning(List.of(alice), List.of(poste("p1", stand, creneau(1, 13, 0, 20, 0), alice))), surPoste(true)),
                "alice").sequences().getFirst().pausesDues().getFirst();

        assertThat(pause.debut()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pause.fin()).isEqualTo(LocalTime.of(19, 20));
        assertThat(pause.simultanee()).isFalse();
    }

    @Test
    void threeColleaguesOnOneStandTakeTheirBreaksOneAfterTheOtherLatestFirst() {
        // Same 13:00-20:00 stretch for all three: the rotation packs the breaks
        // backwards from the shared deadline, in id order, so nobody is out at
        // the same time and each one still has a relay for the whole break.
        Stand stand = stand("JEUX", 3);
        Animateur a = adulte("a");
        Animateur b = adulte("b");
        Animateur c = adulte("c");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), a),
                poste("p2", stand, creneau(1, 13, 0, 20, 0), b),
                poste("p3", stand, creneau(1, 13, 0, 20, 0), c));

        RapportPauses rapport = analyzer.analyze(planning(List.of(a, b, c), postes), surPoste(true));

        assertThat(pause(rapport, "a").debut()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pause(rapport, "b").debut()).isEqualTo(LocalTime.of(18, 40));
        assertThat(pause(rapport, "c").debut()).isEqualTo(LocalTime.of(18, 20));
        assertThat(pause(rapport, "c").fin()).isEqualTo(LocalTime.of(18, 40));
        for (String id : List.of("a", "b", "c")) {
            assertThat(pause(rapport, id).simultanee()).isFalse();
            assertThat(pause(rapport, id).relais()).hasSize(2);
        }
        assertThat(rapport.relaisManquants()).isZero();
    }

    @Test
    void aBreakCannotStartSoEarlyThatTheRestOfTheStretchExceedsTheMark() {
        // 14:00-24:00: the break must end by 18:00 at the earliest (six hours
        // remain after it) and start by 20:00 at the latest. Two people: the
        // second one's break lands right before the first one's.
        Stand stand = stand("JEUX", 2);
        Animateur a = adulte("a");
        Animateur b = adulte("b");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 14, 0, 0, 0), a),
                poste("p2", stand, creneau(1, 14, 0, 0, 0), b));

        RapportPauses rapport = analyzer.analyze(planning(List.of(a, b), postes), surPoste(true));

        assertThat(pause(rapport, "a").debut()).isEqualTo(LocalTime.of(20, 0));
        assertThat(pause(rapport, "b").debut()).isEqualTo(LocalTime.of(19, 40));
        assertThat(pause(rapport, "b").heureLimite()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void whenTheWindowsLeaveNoRoomTheBreakIsFlaggedSimultaneousNotDropped() {
        // 08:00-20:00 leaves a twenty-minute window (13:40-14:00): two people
        // fit back to back, the third cannot — placed at the start of its
        // window, out at the same time as another, and said so.
        Stand stand = stand("JEUX", 3);
        Animateur a = adulte("a");
        Animateur b = adulte("b");
        Animateur c = adulte("c");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 8, 0, 20, 0), a),
                poste("p2", stand, creneau(1, 8, 0, 20, 0), b),
                poste("p3", stand, creneau(1, 8, 0, 20, 0), c));

        RapportPauses rapport = analyzer.analyze(planning(List.of(a, b, c), postes), surPoste(true));

        assertThat(pause(rapport, "a").debut()).isEqualTo(LocalTime.of(14, 0));
        assertThat(pause(rapport, "b").debut()).isEqualTo(LocalTime.of(13, 40));
        assertThat(pause(rapport, "b").simultanee()).isFalse();
        assertThat(pause(rapport, "c").debut()).isEqualTo(LocalTime.of(13, 40));
        assertThat(pause(rapport, "c").simultanee()).isTrue();
        assertThat(rapport.pausesDues()).isEqualTo(3);
    }

    @Test
    void theRelayMustCoverTheWholeBreakNotJustItsStart() {
        // Bob leaves at 19:10: present when Alice's break starts at 19:00, gone
        // before it ends. He is no relay.
        Stand stand = stand("JEUX", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), alice),
                poste("p2", stand, creneau(2, 15, 0, 19, 10), bob));

        PauseDueView pause = pause(analyzer.analyze(planning(List.of(alice, bob), postes), surPoste(true)), "alice");

        assertThat(pause.debut()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pause.relaisDisponible()).isFalse();
    }

    @Test
    void breaksOnDifferentStandsOrDaysNeverConstrainEachOther() {
        Stand jeux = stand("JEUX", 1);
        Stand autre = stand("AUTRE", 1);
        Animateur a = adulte("a");
        Animateur b = adulte("b");
        Animateur c = adulte("c");
        Creneau lendemain = new Creneau(9L, 2, JOUR.plusDays(1), LocalTime.of(13, 0), LocalTime.of(20, 0));
        List<PosteAffectation> postes = List.of(
                poste("p1", jeux, creneau(1, 13, 0, 20, 0), a),
                poste("p2", autre, creneau(1, 13, 0, 20, 0), b),
                poste("p3", jeux, lendemain, c));

        RapportPauses rapport = analyzer.analyze(planning(List.of(a, b, c), postes), surPoste(true));

        for (String id : List.of("a", "b", "c")) {
            assertThat(pause(rapport, id).debut()).as(id).isEqualTo(LocalTime.of(19, 0));
        }
    }

    @Test
    void aClampedBreakNeverPushesTheNextOneOntoItsSlot() {
        // Windows too tight for four people: three fit back to back, the fourth
        // is clamped to its floor and flagged. The rotation must not then place
        // a fifth over a colleague's slot while calling it conflict-free.
        Stand stand = stand("JEUX", 4);
        Animateur a = adulte("a");
        Animateur b = adulte("b");
        Animateur c = adulte("c");
        Animateur d = adulte("d");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 8, 0, 20, 0), a),
                poste("p2", stand, creneau(1, 8, 0, 20, 0), b),
                poste("p3", stand, creneau(1, 8, 0, 20, 0), c),
                poste("p4", stand, creneau(1, 8, 0, 20, 0), d));

        RapportPauses rapport = analyzer.analyze(planning(List.of(a, b, c, d), postes), surPoste(true));

        // Every break kept apart is really apart: two breaks may share an instant
        // only when at least one of them says so.
        List<PauseDueView> pauses = List.of(pause(rapport, "a"), pause(rapport, "b"), pause(rapport, "c"),
                pause(rapport, "d"));
        for (PauseDueView gauche : pauses) {
            for (PauseDueView droite : pauses) {
                if (gauche == droite || gauche.simultanee() || droite.simultanee()) {
                    continue;
                }
                boolean disjointes = !gauche.debut().isBefore(droite.fin()) || !droite.debut().isBefore(gauche.fin());
                assertThat(disjointes)
                        .as("%s–%s et %s–%s se chevauchent sans le dire", gauche.debut(), gauche.fin(),
                                droite.debut(), droite.fin())
                        .isTrue();
            }
        }
    }

    @Test
    void aBreakPastMidnightBelongsToTheEveningSeatItFallsIn() {
        // 19:00 → 02:00 is a seven-hour stretch: the break falls at 01:00, on the
        // next calendar day, and must still be attached to the evening's seat.
        Stand stand = stand("JEUX", 1);
        Animateur alice = adulte("alice");
        PosteAffectation poste = poste("p1", stand, creneau(1, 19, 0, 2, 0), alice);

        List<PauseAnalyzer.PauseAnimateurView> pauses =
                analyzer.pausesAnimateur(planning(List.of(alice), List.of(poste)), "alice");

        assertThat(pauses).hasSize(1);
        assertThat(pauses.getFirst().debut()).isEqualTo(LocalTime.of(1, 0));
        assertThat(pauses.getFirst().fallsInside(poste)).isTrue();
        // Another stand, or another day, is never a match.
        assertThat(pauses.getFirst().fallsInside(poste("p2", stand("AUTRE", 1), creneau(2, 19, 0, 2, 0), alice)))
                .isFalse();
        assertThat(pauses.getFirst().fallsInside(null)).isFalse();
    }

    private static PauseDueView pause(RapportPauses rapport, String animateurId) {
        return journee(rapport, animateurId).sequences().getFirst().pausesDues().getFirst();
    }

    /* ------------------------------- helpers ------------------------------- */

    @Test
    void theBreakNamesTheStandHeldAtThatInstantEvenAcrossASubLegalGap() {
        // 13:00-18:50 on one stand, 19:05-24:00 on another: the ten-minute gap is
        // shorter than the break, so it is one stretch, and the deadline at 19:00
        // falls inside the gap. The break belongs to the stand about to be held.
        Stand matin = stand("MATIN", 1);
        Stand soir = stand("SOIR", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        List<PosteAffectation> postes = List.of(
                poste("p1", matin, creneau(1, 13, 0, 18, 50), alice),
                poste("p2", soir, creneau(2, 19, 5, 0, 0), alice),
                poste("p3", soir, creneau(2, 19, 5, 0, 0), bob));

        PauseDueView pause = journee(analyzer.analyze(planning(List.of(alice, bob), postes), surPoste(true)), "alice")
                .sequences().getFirst().pausesDues().getFirst();

        assertThat(pause.standId()).isEqualTo("SOIR");
        // And the relay is looked for on that stand: bob only arrives at 19:05,
        // so at the deadline there is nobody yet — the honest answer.
        assertThat(pause.relaisDisponible()).isFalse();
    }

    @Test
    void aColleagueAlreadyOnTheEveningStandIsTheRelayOfThatBreak() {
        Stand matin = stand("MATIN", 1);
        Stand soir = stand("SOIR", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        List<PosteAffectation> postes = List.of(
                poste("p1", matin, creneau(1, 13, 0, 18, 50), alice),
                poste("p2", soir, creneau(2, 19, 5, 0, 0), alice),
                poste("p3", soir, creneau(3, 18, 0, 0, 0), bob));

        PauseDueView pause = journee(analyzer.analyze(planning(List.of(alice, bob), postes), surPoste(true)), "alice")
                .sequences().getFirst().pausesDues().getFirst();

        assertThat(pause.standId()).isEqualTo("SOIR");
        assertThat(pause.relais()).extracting(PauseAnalyzer.RelaisView::animateurId).containsExactly("bob");
    }

    @Test
    void pausesByAnimateurGivesTheSameLinesAsOneByOne() {
        Stand stand = stand("JEUX", 2);
        Animateur alice = adulte("alice");
        Animateur bob = adulte("bob");
        Animateur repos = adulte("repos");
        List<PosteAffectation> postes = List.of(
                poste("p1", stand, creneau(1, 13, 0, 20, 0), alice),
                poste("p2", stand, creneau(1, 13, 0, 20, 0), bob));
        PlanningEvenement planning = planning(List.of(alice, bob, repos), postes);

        var parAnimateur = analyzer.pausesByAnimateur(planning);

        assertThat(parAnimateur.keySet()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(parAnimateur.get("alice")).isEqualTo(analyzer.pausesAnimateur(planning, "alice"));
        assertThat(parAnimateur.get("bob")).isEqualTo(analyzer.pausesAnimateur(planning, "bob"));
        // Somebody who owes none is simply absent, and the caller falls back on an empty list.
        assertThat(parAnimateur).doesNotContainKey("repos");
    }

    private static JourneeAnimateurView journee(RapportPauses rapport, String animateurId) {
        return rapport.journees().stream()
                .filter(journee -> journee.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no day for " + animateurId));
    }

    private static ParametresLegaux surPoste(boolean declare) {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setPauseSurPoste(declare);
        return parametres;
    }

    private static PlanningEvenement planning(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return new PlanningEvenement(JOUR, animateurs, postes);
    }

    private static Stand stand(String id, int effectif) {
        return new Stand(id, id, Set.of("JEUX"), effectif, effectif, false);
    }

    private static Creneau creneau(long id, int hDebut, int mDebut, int hFin, int mFin) {
        return new Creneau(id, 1, JOUR, LocalTime.of(hDebut, mDebut), LocalTime.of(hFin, mFin));
    }

    private static Animateur adulte(String id) {
        return new Animateur(id, id, id.toUpperCase(java.util.Locale.ROOT), LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
