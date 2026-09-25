package dev.sylvain.planning.solver.constraints;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    /**
     * The five constraints the filter claims to mirror, in the order of its
     * javadoc. {@code travailContinuMaxMineur} left the list with ADR 0048: a
     * long seat is compliant as soon as a colleague relays the break it owes,
     * which the pair alone cannot tell.
     */
    private static final List<String> CONTRAINTES_REFLETEES = List.of(
            "animateurDisponible",
            "standReserveAuxMajeurs",
            "travailInterditJourFerieMineur",
            "travailDeNuitInterditPourMineur",
            "dureeQuotidienneMaxMineur");

    /** 14 July 2026 — a public holiday, and in the same ISO week as D1..D5. */
    private static final LocalDate FERIE = LocalDate.of(2026, 7, 14);

    /**
     * {@code ConstraintVerifier} asserts, it does not measure:
     * {@code penalizesBy(0)} fails exactly when the constraint penalises, which
     * is the information wanted here.
     */
    private boolean penalise(String contrainte, PosteAffectation poste, ParametresLegaux parametres) {
        try {
            verify(contrainte).given(poste, poste.getAnimateur(), parametres).penalizesBy(0);
            return false;
        } catch (AssertionError _) {
            return true;
        }
    }

    private boolean atLeastOneConstraintPenalizes(PosteAffectation poste, ParametresLegaux parametres) {
        return CONTRAINTES_REFLETEES.stream().anyMatch(contrainte -> penalise(contrainte, poste, parametres));
    }

    private static ParametresLegaux parametres(int dureePauseMinutes) {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setDureePauseMinutes(dureePauseMinutes);
        return parametres;
    }

    /**
     * The floor, the default, and a duration no default ever produces — the
     * third is what makes this a property rather than a coincidence: a filter
     * reading a constant while the rules read the edition would pass on 20 and
     * 30 and fail here (issue #32, critère 6).
     */
    private static final int[] DUREES_DE_PAUSE = {20, 30, 45};

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

    /**
     * The property: the filter never hides an assignment the score would
     * accept — at every break duration an edition may grant, since the filter
     * and the rules both deduct it from a minor's day and a filter reading a
     * different number from the rules would reject seats the score accepts.
     */
    @Test
    void leFiltreNeRejetteJamaisUneAffectationQueLesContraintesAcceptent() {
        List<String> rejetsInjustifies = new ArrayList<>();
        for (int dureePause : DUREES_DE_PAUSE) {
            ParametresLegaux parametres = parametres(dureePause);
            for (PosteAffectation cas : situation()) {
                if (EligibleAnimateurMoveFilter.isEligible(cas, cas.getAnimateur(), parametres)) {
                    continue;
                }
                if (!atLeastOneConstraintPenalizes(cas, parametres)) {
                    rejetsInjustifies.add(cas.getAnimateur().getId() + " sur "
                            + cas.getStand().getId()
                            + " au créneau " + cas.getCreneau().getHeureDebut() + "–"
                            + cas.getCreneau().getHeureFin() + " le "
                            + cas.getCreneau().getDate()
                            + " (pause de " + dureePause + " min)");
                }
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
                        poste(standWithStrategy("S"), matin("C", 1, D1), null), null, parametres(30)))
                .isTrue();
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
                        poste(standWithStrategy("S"), matin("C1", 1, D1), indisponible), indisponible, parametres(30)))
                .isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                        poste(stand("S-MAJ", true, "STRATEGIE"), matin("C2", 1, D1), mineur), mineur, parametres(30)))
                .isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                        poste(standWithStrategy("S"), nuit("C3", 1, D1), mineur), mineur, parametres(30)))
                .isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                        poste(standWithStrategy("S"), longDay("C4", 1, D1), mineur), mineur, parametres(30)))
                .isFalse();
        assertThat(EligibleAnimateurMoveFilter.isEligible(
                        poste(standWithStrategy("S"), matin("C5", 7, FERIE), mineur), mineur, parametres(30)))
                .isFalse();
    }
}
