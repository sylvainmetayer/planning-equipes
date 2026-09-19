package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AffectationConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    @Test
    void posteVideEstPenalise() {
        verify("posteDoitEtrePourvu")
                .given(poste(standStrat, creneauMatin, null))
                .penalizesBy(1);
    }

    @Test
    void postePourvuNEstPasPenalise() {
        verify("posteDoitEtrePourvu")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    /**
     * The one difference between a renfort and a seat (issue #505): nobody is
     * missing on it, so leaving it empty is never a violation. Without this,
     * declaring a capacity a stand cannot always staff would make every plan
     * infeasible — the very inflation `effectifMax` was kept out of seat
     * generation to avoid.
     */
    @Test
    void anEmptyRenfortIsNotAViolation() {
        PosteAffectation renfort = new PosteAffectation("P-RENFORT", standStrat, creneauMatin);
        renfort.setOptionnel(true);

        verify("posteDoitEtrePourvu").given(renfort).penalizesBy(0);
    }

    @Test
    void animateurIndisponibleEstPenalise() {
        Animateur indisponible = referentMajeur("A1");
        indisponible.setJoursIndisponibles(Set.of(D1));
        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, indisponible))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void animateurDisponibleNEstPasPenalise() {
        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    @Test
    void doubleAffectationSurMemeCreneauEstPenalisee() {
        Animateur a1 = referentMajeur("A1");
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, creneauMatin, a1), poste(standWithStrategy("STAND-2"), creneauMatin, a1))
                .penalizesBy(1);
    }

    @Test
    void deuxAnimateursSurMemeCreneauNeSontPasPenalises() {
        verify("pasDeChevauchementHoraire")
                .given(
                        poste(standStrat, creneauMatin, referentMajeur("A1")),
                        poste(standStrat, creneauMatin, referentMajeur("A2")))
                .penalizesBy(0);
    }

    @Test
    void memeAnimateurSurCreneauxDifferentsNEstPasPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, creneauMatin, a1), poste(standStrat, creneauAprem, a1))
                .penalizesBy(0);
    }

    // --- B10: two distinct timeslots that overlap -------------------------

    @Test
    void deuxCreneauxDistinctsQuiSeChevauchentSontPenalises() {
        // The exact case the audit quotes: 10:00-14:00 and 12:00-16:00, two
        // different timeslots, hence invisible to the old identity comparison.
        Animateur a1 = referentMajeur("A1");
        Creneau matinee = creneau("J1-10-14", 1, D1, LocalTime.of(10, 0), LocalTime.of(14, 0));
        Creneau midi = creneau("J1-12-16", 1, D1, LocalTime.of(12, 0), LocalTime.of(16, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, matinee, a1), poste(standWithStrategy("STAND-2"), midi, a1))
                .penalizesBy(1);
    }

    @Test
    void deuxCreneauxContigusNeSontPasPenalises() {
        // End to end (end = start): no overlap — this is exactly the handover of
        // a relay the way automatic slicing produces it.
        Animateur a1 = referentMajeur("A1");
        Creneau avant = creneau("J1-10-14", 1, D1, LocalTime.of(10, 0), LocalTime.of(14, 0));
        Creneau apres = creneau("J1-14-18", 1, D1, LocalTime.of(14, 0), LocalTime.of(18, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, avant, a1), poste(standWithStrategy("STAND-2"), apres, a1))
                .penalizesBy(0);
    }

    @Test
    void creneauFranchissantMinuitChevauchantLeLendemainEstPenalise() {
        // 20:00 → 00:00 on day D overlaps 23:00 → 01:00: the end of the night
        // timeslot must be computed on the next day, not before its own start.
        Animateur a1 = referentMajeur("A1");
        Creneau nuitJ1 = creneau("J1-NUIT-CH", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        Creneau tardJ1 = creneau("J1-TARD-CH", 1, D1, LocalTime.of(23, 0), LocalTime.of(1, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, nuitJ1, a1), poste(standWithStrategy("STAND-2"), tardJ1, a1))
                .penalizesBy(1);
    }

    /**
     * Issue #60: a stand closed in the middle of a créneau splits it into two
     * open segments, each becoming its own poste with a narrowed effective
     * window — but both postes still reference the very same {@code Creneau}
     * object (a hard requirement: {@code poste_affectation.creneau_id} is a
     * foreign key to a real, persisted créneau, so it can't be split into two
     * separate créneau rows). Without reading the effective window here, both
     * postes would resolve to the identical full-créneau interval and always
     * be flagged as a double-booking, even though the animateur genuinely
     * works the two segments back-to-back around the closure.
     */
    @Test
    void memeAnimateurSurDeuxSegmentsOuvertsDuMemeCreneauNEstPasPenalise() {
        Animateur a1 = referentMajeur("A1");
        // 9h-14h créneau closed 11h-13h for STAND-STRAT: two open segments, 9-11 and 13-14.
        Creneau creneau = creneau("J1-9-14", 1, D1, LocalTime.of(9, 0), LocalTime.of(14, 0));
        verify("pasDeChevauchementHoraire")
                .given(
                        posteWithEffectiveFenetre(standStrat, creneau, a1, LocalTime.of(9, 0), LocalTime.of(11, 0)),
                        posteWithEffectiveFenetre(standStrat, creneau, a1, LocalTime.of(13, 0), LocalTime.of(14, 0)))
                .penalizesBy(0);
    }

    // --- Quota per typologie (issue #594) ----------------------------------

    /** « Les hommes jeu, 4 créneaux au maximum » — the case that asked for the rule. */
    private static final QuotaTypologie QUATRE_STRATEGIE = new QuotaTypologie("STRATEGIE", 4);

    @Test
    void auPlafondDeCreneauxSurUneTypologieRienNEstPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, matin("Q-J1", 1, D1), a1),
                        poste(standStrat, afternoon("Q-J1-AM", 1, D1), a1),
                        poste(standStrat, matin("Q-J2", 2, D2), a1),
                        poste(standStrat, afternoon("Q-J2-AM", 2, D2), a1),
                        QUATRE_STRATEGIE)
                .penalizesBy(0);
    }

    @Test
    void auDelaDuPlafondChaqueCreneauEnTropEstPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, matin("Q2-J1", 1, D1), a1),
                        poste(standStrat, afternoon("Q2-J1-AM", 1, D1), a1),
                        poste(standStrat, matin("Q2-J2", 2, D2), a1),
                        poste(standStrat, afternoon("Q2-J2-AM", 2, D2), a1),
                        poste(standStrat, matin("Q2-J3", 3, D3), a1),
                        poste(standStrat, afternoon("Q2-J3-AM", 3, D3), a1),
                        QUATRE_STRATEGIE)
                .penalizesBy(2);
    }

    /** The cap is per animateur: two people holding four each are both inside it. */
    @Test
    void lePlafondEstParAnimateur() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, matin("Q3-J1", 1, D1), a1),
                        poste(standStrat, afternoon("Q3-J1-AM", 1, D1), a1),
                        poste(standStrat, matin("Q3-J2", 2, D2), a2),
                        poste(standStrat, afternoon("Q3-J2-AM", 2, D2), a2),
                        new QuotaTypologie("STRATEGIE", 2))
                .penalizesBy(0);
    }

    /** A typologie with no QuotaTypologie fact caps nothing, whatever the load. */
    @Test
    void uneTypologieSansPlafondNImposeRien() {
        Animateur a1 = referentMajeur("A1");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, matin("Q4-J1", 1, D1), a1),
                        poste(standStrat, afternoon("Q4-J1-AM", 1, D1), a1),
                        poste(standStrat, matin("Q4-J2", 2, D2), a1),
                        poste(standStrat, afternoon("Q4-J2-AM", 2, D2), a1),
                        poste(standStrat, matin("Q4-J3", 3, D3), a1),
                        new QuotaTypologie("AMBIANCE", 1))
                .penalizesBy(0);
    }

    /**
     * The scope is the edition, not the day: five créneaux held in one single
     * day and five spread over five days both break a cap of four by one. A
     * refactor slipping a date into the grouping would make the second case
     * free, and nothing else would notice.
     */
    @Test
    void lePlafondPorteSurLEditionEtPasSurLaJournee() {
        Animateur memeJour = referentMajeur("A-JOUR");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, creneau("Q5-1", 1, D1, LocalTime.of(8, 0), LocalTime.of(9, 0)), memeJour),
                        poste(standStrat, creneau("Q5-2", 1, D1, LocalTime.of(9, 0), LocalTime.of(10, 0)), memeJour),
                        poste(standStrat, creneau("Q5-3", 1, D1, LocalTime.of(10, 0), LocalTime.of(11, 0)), memeJour),
                        poste(standStrat, creneau("Q5-4", 1, D1, LocalTime.of(11, 0), LocalTime.of(12, 0)), memeJour),
                        poste(standStrat, creneau("Q5-5", 1, D1, LocalTime.of(12, 0), LocalTime.of(13, 0)), memeJour),
                        QUATRE_STRATEGIE)
                .penalizesBy(1);

        Animateur cinqJours = referentMajeur("A-SEMAINE");
        verify("plafondCreneauxParTypologie")
                .given(
                        poste(standStrat, matin("Q6-J1", 1, D1), cinqJours),
                        poste(standStrat, matin("Q6-J2", 2, D2), cinqJours),
                        poste(standStrat, matin("Q6-J3", 3, D3), cinqJours),
                        poste(standStrat, matin("Q6-J4", 4, D4), cinqJours),
                        poste(standStrat, matin("Q6-J5", 5, D5), cinqJours),
                        QUATRE_STRATEGIE)
                .penalizesBy(1);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void aPastHoleIsHistoryNotAViolation() {
        verify("posteDoitEtrePourvu")
                .given(postePasse(standStrat, creneauMatin, null))
                .penalizesBy(0);
    }

    @Test
    void anOverlapBetweenTwoPastSeatsIsHistoryButOneReachingIntoTheFutureIsCharged() {
        Animateur a1 = referentMajeur("A1");
        Creneau chevauchant = creneau("J1-11-15", 1, D1, LocalTime.of(11, 0), LocalTime.of(15, 0));
        verify("pasDeChevauchementHoraire")
                .given(postePasse(standStrat, creneauMatin, a1), postePasse(standStrat, chevauchant, a1))
                .penalizesBy(0);
        verify("pasDeChevauchementHoraire")
                .given(postePasse(standStrat, creneauMatin, a1), poste(standStrat, chevauchant, a1))
                .penalizesBy(1);
    }

    @Test
    void aQuotaCountsThePastSeatsAndIsChargedOnlyWhileASeatIsStillAhead() {
        Animateur a1 = referentMajeur("A1");
        QuotaTypologie plafond = new QuotaTypologie("STRATEGIE", 1);
        // Two past seats over a cap of one: history.
        verify("plafondCreneauxParTypologie")
                .given(postePasse(standStrat, creneauMatin, a1), postePasse(standStrat, creneauAprem, a1), plafond)
                .penalizesBy(0);
        // The same two, plus a third still ahead: the past two count, and
        // the whole breach is charged — the third seat is where it is paid.
        verify("plafondCreneauxParTypologie")
                .given(
                        postePasse(standStrat, creneauMatin, a1),
                        postePasse(standStrat, creneauAprem, a1),
                        poste(standStrat, matin("J2-MATIN", 2, D2), a1),
                        plafond)
                .penalizesBy(2);
    }
}
