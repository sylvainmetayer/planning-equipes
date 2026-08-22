package dev.sylvain.planning.solver.constraints;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;

/**
 * The move filter was covered by no test at all, while it is the one place in
 * the solver where a mistake produces neither a violation nor an exception: it
 * decides what local search is allowed to <b>propose</b>. Too permissive, it
 * only costs computing time (the score catches up); too strict, it hides
 * assignments that were perfectly legal and the score plateaus above zero hard
 * without anything reporting it.
 *
 * <p>The property tested here is therefore asymmetric, and deliberately so:
 * <b>everything the filter rejects must be penalised by at least one of the
 * constraints it claims to mirror</b>. The converse is not required — the filter
 * only mirrors the aggregated constraints (daily totals, rest, breaks) in their
 * single-timeslot form, and deliberately lets through what only the score can
 * settle.</p>
 */
class EligibleAnimateurMoveFilterTest extends ConstraintTestBase {

    /** The six constraints the filter claims to mirror, in the order of its javadoc. */
    private static final List<String> CONTRAINTES_REFLETEES = List.of(
            "animateurDisponible",
            "standReserveAuxMajeurs",
            "travailInterditJourFerieMineur",
            "travailDeNuitInterditPourMineur",
            "dureeQuotidienneMaxMineur",
            "travailContinuMaxMineur");

    /** 14 July 2026 — a public holiday, and in the same ISO week as D1..D5. */
    private static final LocalDate FERIE = LocalDate.of(2026, 7, 14);

    /**
     * {@code ConstraintVerifier} asserts, it does not measure:
     * {@code penalizesBy(0)} fails exactly when the constraint penalises, which
     * is the information wanted here.
     */
    private boolean penalise(String contrainte, PosteAffectation poste) {
        try {
            verify(contrainte).given(poste, poste.getAnimateur()).penalizesBy(0);
            return false;
        } catch (AssertionError pénalisé) {
            return true;
        }
    }

    private boolean atLeastOneConstraintPenalizes(PosteAffectation poste) {
        return CONTRAINTES_REFLETEES.stream().anyMatch(contrainte -> penalise(contrainte, poste));
    }

    /** Every (animateur × stand × timeslot) combination the filter tells apart. */
    private List<PosteAffectation> situation() {
        Animateur majeur = referentMajeur("MAJ");
        Animateur mineur = mineurDebutant("MIN");
        Animateur mineurJeune = under16DebutantMineur("MIN14");
        Animateur indisponible = referentMajeur("INDISPO");
        indisponible.setJoursIndisponibles(java.util.Set.of(D1));

        List<Stand> stands = List.of(standWithStrategy("STAND-OUVERT"), stand("STAND-MAJ", true, "STRATEGIE"));
        List<Creneau> creneaux = List.of(
                matin("F-MATIN", 1, D1),
                afternoon("F-AM", 1, D1),
                nuit("F-NUIT", 1, D1),
                longDay("F-LONGUE", 1, D1),
                creneau("F-7H30", 1, D1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(16, 30)),
                matin("F-FERIE", 7, FERIE),
                longDay("F-FERIE-LONGUE", 7, FERIE));

        List<PosteAffectation> cas = new ArrayList<>();
        for (Animateur animateur : List.of(majeur, mineur, mineurJeune, indisponible)) {
            for (Stand stand : stands) {
                for (Creneau creneau : creneaux) {
                    cas.add(poste(stand, creneau, animateur));
                }
            }
        }
        return cas;
    }

    /** The property: the filter never hides an assignment the score would accept. */
    @Test
    void leFiltreNeRejetteJamaisUneAffectationQueLesContraintesAcceptent() {
        List<String> rejetsInjustifies = new ArrayList<>();
        for (PosteAffectation cas : situation()) {
            if (EligibleAnimateurMoveFilter.isEligible(cas, cas.getAnimateur())) {
                continue;
            }
            if (!atLeastOneConstraintPenalizes(cas)) {
                rejetsInjustifies.add(cas.getAnimateur().getId() + " sur " + cas.getStand().getId()
                        + " au créneau " + cas.getCreneau().getHeureDebut() + "–" + cas.getCreneau().getHeureFin()
                        + " le " + cas.getCreneau().getDate());
            }
        }

        assertThat(rejetsInjustifies)
                .as("affectations écartées par le filtre alors qu'aucune contrainte reflétée ne les pénalise "
                        + "— la recherche locale ne les proposera jamais, le 0 hard peut en devenir inatteignable")
                .isEmpty();
    }

    /** A null animateur is an unassignment, not an assignment to filter. */
    @Test
    void unPosteNonPourvuResteToujoursProposable() {
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(standWithStrategy("S"), matin("C", 1, D1), null), null)).isTrue();
    }

    /**
     * The counterpart of the property test: the filter really is good for
     * something. Without those rejections it would have no reason to exist, and a
     * test passing over a filter turned into a pass-through would prove
     * nothing.
     */
    @Test
    void leFiltreEcarteBienLesAffectationsIllegales() {
        Animateur mineur = mineurDebutant("MIN");
        Animateur indisponible = referentMajeur("INDISPO");
        indisponible.setJoursIndisponibles(java.util.Set.of(D1));

        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(standWithStrategy("S"), matin("C1", 1, D1), indisponible), indisponible)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(stand("S-MAJ", true, "STRATEGIE"), matin("C2", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(standWithStrategy("S"), nuit("C3", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(standWithStrategy("S"), longDay("C4", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                poste(standWithStrategy("S"), matin("C5", 7, FERIE), mineur), mineur)).isFalse();
    }
}
