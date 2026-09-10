package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.SeveriteFragilite;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plain-Java test, no Quarkus and no solve — the whole point of the analyzer is
 * that the answer comes out of the persisted seats and the referential alone.
 */
class FragiliteAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final LocalDate LENDEMAIN = JOUR.plusDays(1);

    private final FragiliteAnalyzer analyzer = new FragiliteAnalyzer();

    @Test
    void anEmptyPlanReportsNothingAndSaysWhy() {
        RapportFragilite rapport = analyzer.analyze(new PlanningEvenement(null, List.of(), List.of()));

        assertThat(rapport.animateurs()).isEmpty();
        assertThat(rapport.competencesRares()).isEmpty();
        assertThat(rapport.groupesAnalyses()).isZero();
        assertThat(rapport.message()).contains("Aucun planning persisté");
    }

    @Test
    void everySeatAnimateurHoldsDropsItsStandBelowItsEffectif() {
        // One stand of two seats, both filled: whoever leaves takes the group
        // under its effectif floor, since seats are generated at effectifMin.
        Stand stand = stand("A", 2, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        List<PosteAffectation> postes = seats(stand, matin, alice, bob);

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), postes));

        assertThat(rapport.groupesAnalyses()).isEqualTo(1);
        assertThat(rapport.animateurs()).hasSize(2);
        AnimateurFragilite ligne = ligne(rapport, "alice");
        assertThat(ligne.affectations()).isEqualTo(1);
        assertThat(ligne.postesEffondres()).isEqualTo(1);
        assertThat(ligne.postes().getFirst().standId()).isEqualTo("A");
        assertThat(ligne.postes().getFirst().siegesRequis()).isEqualTo(2);
        assertThat(ligne.postes().getFirst().siegesPourvus()).isEqualTo(2);
        assertThat(ligne.postes().getFirst().siegesLiberes()).isEqualTo(1);
    }

    @Test
    void aGroupAlreadyShortIsNotBlamedOnAnybodyLeaving() {
        // Two seats, one of them empty: the group is already under its floor.
        // Reporting it as "collapsing" would blame the person still holding it
        // for a hole they did not open.
        Stand stand = stand("A", 2, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        List<PosteAffectation> postes = seats(stand, matin, alice, null);

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice), postes));

        assertThat(rapport.groupesDejaSousEffectif()).isEqualTo(1);
        assertThat(ligne(rapport, "alice").postesEffondres()).isZero();
        assertThat(ligne(rapport, "alice").affectations()).isEqualTo(1);
    }

    @Test
    void aSeatNobodyElseCouldTakeMakesItsHolderIrreplaceable() {
        // Alice is the only animateur competent for the stand: her seat has no
        // substitute at all.
        Stand stand = stand("A", 1, "ESCAPE");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "ESCAPE");
        Animateur bob = animateur("bob", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, matin, alice)));

        AnimateurFragilite ligne = ligne(rapport, "alice");
        assertThat(ligne.postesIrremplacables()).isEqualTo(1);
        assertThat(ligne.severite()).isEqualTo(SeveriteFragilite.CRITIQUE);
        assertThat(ligne.postes().getFirst().remplacants()).isZero();
        assertThat(rapport.animateursIrremplacables()).isEqualTo(1);
        assertThat(rapport.message()).contains("1 animateur laisserait au moins un poste");
    }

    @Test
    void acompetentColleagueFreeAtThatMomentMakesTheSeatReplaceable() {
        Stand stand = stand("A", 1, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, matin, alice)));

        assertThat(ligne(rapport, "alice").postesIrremplacables()).isZero();
        assertThat(ligne(rapport, "alice").postes().getFirst().remplacants()).isEqualTo(1);
        assertThat(rapport.animateursIrremplacables()).isZero();
    }

    @Test
    void aColleagueBusyOnAnOverlappingSeatIsNotASubstitute() {
        // Bob is competent and available that day, but he is already holding a
        // seat that overlaps the one Alice would vacate.
        Stand premier = stand("A", 1, "JEUX");
        Stand second = stand("B", 1, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        List<PosteAffectation> postes = new ArrayList<>(seats(premier, matin, alice));
        postes.addAll(seats(second, matin, bob));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), postes));

        assertThat(ligne(rapport, "alice").postesIrremplacables()).isEqualTo(1);
        assertThat(ligne(rapport, "bob").postesIrremplacables()).isEqualTo(1);
    }

    @Test
    void aColleagueUnavailableThatDayIsNotASubstitute() {
        Stand stand = stand("A", 1, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        bob.getJoursIndisponibles().add(JOUR);

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, matin, alice)));

        assertThat(ligne(rapport, "alice").postesIrremplacables()).isEqualTo(1);
        // Bob does not count as a specialist either, so the stand shows as
        // resting on Alice alone.
        assertThat(rapport.competencesRares()).hasSize(1);
        assertThat(rapport.competencesRares().getFirst().animateurId()).isEqualTo("alice");
    }

    @Test
    void aMinorIsNotASubstituteOnAnAdultsOnlyStand() {
        Stand stand = stand("A", 1, "JEUX");
        stand.setReserveMajeurs(true);
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur mineur = animateur("mineur", "JEUX");
        mineur.setDateNaissance(JOUR.minusYears(16));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, mineur), seats(stand, matin, alice)));

        assertThat(ligne(rapport, "alice").postesIrremplacables()).isEqualTo(1);
        assertThat(rapport.competencesRares().getFirst().specialistes()).isEqualTo(1);
    }

    @Test
    void aStandNobodyIsCompetentForIsReportedAsCritical() {
        Stand stand = stand("A", 1, "ESCAPE");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice), seats(stand, matin, alice)));

        CompetenceRare rare = rapport.competencesRares().getFirst();
        assertThat(rare.specialistes()).isZero();
        assertThat(rare.animateurId()).isNull();
        assertThat(rare.renforts()).isZero();
        assertThat(rare.severite()).isEqualTo(SeveriteFragilite.CRITIQUE);
        assertThat(rapport.groupesSansSpecialiste()).isEqualTo(1);
        assertThat(rapport.ninjaConfigure()).isFalse();
    }

    @Test
    void aStandWithTwoSpecialistsIsNotReportedAtAll() {
        Stand stand = stand("A", 1, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, matin, alice)));

        assertThat(rapport.competencesRares()).isEmpty();
        assertThat(rapport.totalCompetencesRares()).isZero();
    }

    @Test
    void aNinjaNeverHidesTheLastSpecialistButCountsAsAReinforcement() {
        // The decision this analyzer documents: a ninja is competent everywhere
        // as far as the solver is concerned, so counting them as a specialist
        // would erase the very signal this screen exists for. They are counted
        // apart, as a mitigation.
        Stand stand = stand("A", 1, "ESCAPE");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "ESCAPE");
        Animateur ninja = animateur("ninja", "POLYVALENT");
        ninja.applyNinjaTypologie("POLYVALENT");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, ninja), seats(stand, matin, alice)));

        CompetenceRare rare = rapport.competencesRares().getFirst();
        assertThat(rare.specialistes()).isEqualTo(1);
        assertThat(rare.animateurId()).isEqualTo("alice");
        assertThat(rare.renforts()).isEqualTo(1);
        assertThat(rare.severite()).isEqualTo(SeveriteFragilite.MODEREE);
        assertThat(rapport.ninjaConfigure()).isTrue();
        // …and the same ninja does make Alice's seat replaceable, because the
        // solver would dispatch them on it.
        assertThat(ligne(rapport, "alice").postesIrremplacables()).isZero();
        assertThat(ligne(rapport, "alice").competencesRares()).isEqualTo(1);
        assertThat(ligne(rapport, "alice").severite()).isEqualTo(SeveriteFragilite.ELEVEE);
    }

    @Test
    void animateursAreRankedByCriticalityAndUnassignedOnesAreLeftOut() {
        Stand rare = stand("RARE", 1, "ESCAPE");
        Stand commun = stand("COMMUN", 1, "JEUX");
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau apresMidi = creneau(2, LocalTime.of(14, 0), LocalTime.of(16, 0));
        Animateur alice = animateur("alice", "ESCAPE");
        Animateur bob = animateur("bob", "JEUX");
        Animateur carole = animateur("carole", "JEUX");
        Animateur absent = animateur("absent", "JEUX");

        List<PosteAffectation> postes = new ArrayList<>(seats(rare, matin, alice));
        postes.addAll(seats(commun, matin, bob));
        postes.addAll(seats(commun, apresMidi, carole));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob, carole, absent), postes));

        assertThat(rapport.animateurs())
                .extracting(AnimateurFragilite::animateurId)
                .containsExactly("alice", "bob", "carole");
        assertThat(rapport.animateurs().getFirst().severite()).isEqualTo(SeveriteFragilite.CRITIQUE);
    }

    @Test
    void aSeatOnAnotherDayIsAnalysedOnItsOwnDay() {
        // Availability and busy windows are per date: a colleague working the
        // next day is free today.
        Stand stand = stand("A", 1, "JEUX");
        Creneau aujourdhui = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau demain = new Creneau(2L, 2, LENDEMAIN, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand, aujourdhui, alice));
        postes.addAll(seats(stand, demain, bob));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), postes));

        assertThat(rapport.groupesAnalyses()).isEqualTo(2);
        assertThat(ligne(rapport, "alice").postesIrremplacables()).isZero();
        assertThat(ligne(rapport, "bob").postesIrremplacables()).isZero();
    }

    @Test
    void twoWindowsOfTheSameTimeslotAreTwoDistinctGroups() {
        // A stand partially closed inside a créneau generates seats carrying an
        // effective window; those are separate groups, and a substitute busy on
        // one of them is still free on the other.
        Stand stand = stand("A", 1, "JEUX");
        Creneau journee = creneau(1, LocalTime.of(10, 0), LocalTime.of(18, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");

        PosteAffectation matin = new PosteAffectation("p1", stand, journee);
        matin.setHeureDebutEffective(LocalTime.of(10, 0));
        matin.setHeureFinEffective(LocalTime.of(12, 0));
        matin.setAnimateur(alice);
        PosteAffectation soir = new PosteAffectation("p2", stand, journee);
        soir.setHeureDebutEffective(LocalTime.of(16, 0));
        soir.setHeureFinEffective(LocalTime.of(18, 0));
        soir.setAnimateur(bob);

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), List.of(matin, soir)));

        assertThat(rapport.groupesAnalyses()).isEqualTo(2);
        assertThat(ligne(rapport, "alice").postes().getFirst().heureDebut()).isEqualTo(LocalTime.of(10, 0));
        // Bob's window does not overlap Alice's, so each can cover the other.
        assertThat(ligne(rapport, "alice").postesIrremplacables()).isZero();
        assertThat(ligne(rapport, "bob").postesIrremplacables()).isZero();
    }

    @Test
    void aColleagueWorkingThroughMidnightIsNotFreeTheNextMorning() {
        // Bob holds 22:00 → 02:00 on day J. The seat Alice would vacate runs
        // 00:00 → 04:00 on day J+1, and the two really do overlap from midnight
        // to 02:00. Bucketing busy windows per calendar day hid that overlap and
        // declared Bob available — the analyzer promising the opposite.
        Stand stand = stand("A", 1, "JEUX");
        Creneau nuit = creneau(1, LocalTime.of(22, 0), LocalTime.of(2, 0));
        Creneau petitMatin = new Creneau(2L, 2, LENDEMAIN, LocalTime.of(0, 0), LocalTime.of(4, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand, nuit, bob));
        postes.addAll(seats(stand, petitMatin, alice));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), postes));

        assertThat(ligne(rapport, "alice").postesIrremplacables()).isEqualTo(1);
        assertThat(ligne(rapport, "alice").postes().getFirst().remplacants()).isZero();
    }

    @Test
    void aColleagueWhoseNightShiftEndsBeforeTheSeatStartsStaysASubstitute() {
        // The other side of the same fix: 22:00 → 01:00 on day J leaves the
        // 02:00 → 04:00 seat of day J+1 free, and an absolute timeline must not
        // turn every night worker into an unavailable one.
        Stand stand = stand("A", 1, "JEUX");
        Creneau nuit = creneau(1, LocalTime.of(22, 0), LocalTime.of(1, 0));
        Creneau petitMatin = new Creneau(2L, 2, LENDEMAIN, LocalTime.of(2, 0), LocalTime.of(4, 0));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand, nuit, bob));
        postes.addAll(seats(stand, petitMatin, alice));

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), postes));

        assertThat(ligne(rapport, "alice").postes().getFirst().remplacants()).isEqualTo(1);
    }

    @Test
    void aBreakCoveringShiftSaysWhyItsFloorIsHalfTheStandsMinimum() {
        // Seat generation halves the headcount on a couverture de pause, so the
        // group's floor is 2 where the stand is configured at 4. Both figures
        // travel, and the flag is what keeps them from reading as a
        // contradiction.
        Stand stand = stand("A", 4, "JEUX");
        Creneau pause = creneau(1, LocalTime.of(12, 0), LocalTime.of(13, 0));
        pause.setCouverturePause(true);
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, pause, alice, bob)));

        PosteFragile poste = ligne(rapport, "alice").postes().getFirst();
        assertThat(poste.couverturePause()).isTrue();
        assertThat(poste.effectifMin()).isEqualTo(4);
        assertThat(poste.siegesRequis()).isEqualTo(2);
    }

    @Test
    void theEffectifShownIsTheWindowsWhenTheWindowNamesOne() {
        // The stand is configured at 1, but the day's opening window asks for 3:
        // that is the figure the screen must show next to the three seats,
        // otherwise « 3 seats required, minimum 1 » reads as a contradiction.
        Stand stand = stand("A", 1, "JEUX");
        Creneau slot = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0), null, 3)));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");
        Animateur carol = animateur("carol", "JEUX");

        RapportFragilite rapport =
                analyzer.analyze(planning(List.of(alice, bob, carol), seats(stand, slot, alice, bob, carol)));

        PosteFragile poste = ligne(rapport, "alice").postes().getFirst();
        assertThat(poste.effectifMin()).isEqualTo(3);
        assertThat(poste.siegesRequis()).isEqualTo(3);
    }

    @Test
    void theEffectifShownFallsBackToTheStandsWhenSeatsMatchNoWindow() {
        // Hand-authored seats on a slot the windows only partly cover: no open
        // segment is this group's window, so the stand's minimum is what the
        // screen can honestly show.
        Stand stand = stand("A", 2, "JEUX");
        Creneau slot = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        stand.setOuvertures(List.of(new OuvertureStand(null, JOUR, LocalTime.of(10, 0), LocalTime.of(11, 0), null, 5)));
        Animateur alice = animateur("alice", "JEUX");
        Animateur bob = animateur("bob", "JEUX");

        RapportFragilite rapport = analyzer.analyze(planning(List.of(alice, bob), seats(stand, slot, alice, bob)));

        assertThat(ligne(rapport, "alice").postes().getFirst().effectifMin()).isEqualTo(2);
    }

    private static AnimateurFragilite ligne(RapportFragilite rapport, String animateurId) {
        return rapport.animateurs().stream()
                .filter(candidat -> candidat.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + animateurId));
    }

    private static PlanningEvenement planning(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return new PlanningEvenement(JOUR, animateurs, postes);
    }

    private static Stand stand(String id, int effectifMin, String typologie) {
        return new Stand(id, id, Set.of(typologie), effectifMin, effectifMin, false);
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, JOUR, debut, fin);
    }

    private static Animateur animateur(String id, String typologie) {
        Animateur animateur =
                new Animateur(id, id, id.toUpperCase(java.util.Locale.ROOT), LocalDate.of(1990, 1, 1), false);
        animateur.getCompetences().put(typologie, NiveauCompetence.AUTONOME);
        return animateur;
    }

    /** One poste per seat, {@code null} standing for a seat nobody holds. */
    private static List<PosteAffectation> seats(Stand stand, Creneau creneau, Animateur... titulaires) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < titulaires.length; seat++) {
            PosteAffectation poste =
                    new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau);
            poste.setAnimateur(titulaires[seat]);
            postes.add(poste);
        }
        return postes;
    }
}
