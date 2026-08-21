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
 * Le filtre de move n'était couvert par aucun test, alors que c'est le seul
 * endroit du solveur où une erreur ne produit ni violation ni exception : il
 * décide ce que la recherche locale a le droit de <b>proposer</b>. Trop
 * permissif, il ne coûte que du temps de calcul (le score rattrape) ; trop
 * strict, il rend invisibles des affectations pourtant légales et le score
 * plafonne au-dessus de zéro hard sans que rien ne le signale.
 *
 * <p>La propriété testée ici est donc dissymétrique et c'est voulu : <b>tout ce
 * que le filtre rejette doit être pénalisé par au moins une des contraintes
 * qu'il prétend refléter</b>. L'inverse n'est pas exigé — le filtre ne
 * reflète les contraintes agrégées (cumuls quotidiens, repos, pauses) que dans
 * leur forme mono-créneau, et laisse volontairement passer ce que seul le
 * score peut trancher.</p>
 */
class EligibleAnimateurMoveFilterTest extends ConstraintTestBase {

    /** Les six contraintes que le filtre déclare refléter, dans l'ordre de sa javadoc. */
    private static final List<String> CONTRAINTES_REFLETEES = List.of(
            "animateurDisponible",
            "standReserveAuxMajeurs",
            "travailInterditJourFerieMineur",
            "travailDeNuitInterditPourMineur",
            "dureeQuotidienneMaxMineur",
            "travailContinuMaxMineur");

    /** 14 juillet 2026 — férié, et dans la même semaine ISO que D1..D5. */
    private static final LocalDate FERIE = LocalDate.of(2026, 7, 14);

    /**
     * {@code ConstraintVerifier} affirme, il ne mesure pas : {@code penalizesBy(0)}
     * échoue exactement quand la contrainte pénalise, ce qui est l'information
     * cherchée ici.
     */
    private boolean penalise(String contrainte, PosteAffectation poste) {
        try {
            verify(contrainte).given(poste, poste.getAnimateur()).penalizesBy(0);
            return false;
        } catch (AssertionError pénalisé) {
            return true;
        }
    }

    private boolean auMoinsUneContraintePenalise(PosteAffectation poste) {
        return CONTRAINTES_REFLETEES.stream().anyMatch(contrainte -> penalise(contrainte, poste));
    }

    /** Toutes les combinaisons (animateur × stand × créneau) que le filtre distingue. */
    private List<PosteAffectation> casDeFigure() {
        Animateur majeur = majeurReferent("MAJ");
        Animateur mineur = mineurDebutant("MIN");
        Animateur mineurJeune = mineurMoinsDe16Debutant("MIN14");
        Animateur indisponible = majeurReferent("INDISPO");
        indisponible.setJoursIndisponibles(java.util.Set.of(D1));

        List<Stand> stands = List.of(standStrategie("STAND-OUVERT"), stand("STAND-MAJ", true, "STRATEGIE"));
        List<Creneau> creneaux = List.of(
                matin("F-MATIN", 1, D1),
                apresMidi("F-AM", 1, D1),
                nuit("F-NUIT", 1, D1),
                journeeLongue("F-LONGUE", 1, D1),
                creneau("F-7H30", 1, D1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(16, 30)),
                matin("F-FERIE", 7, FERIE),
                journeeLongue("F-FERIE-LONGUE", 7, FERIE));

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

    /** La propriété : le filtre ne cache jamais une affectation que le score accepterait. */
    @Test
    void leFiltreNeRejetteJamaisUneAffectationQueLesContraintesAcceptent() {
        List<String> rejetsInjustifies = new ArrayList<>();
        for (PosteAffectation cas : casDeFigure()) {
            if (EligibleAnimateurMoveFilter.estEligible(cas, cas.getAnimateur())) {
                continue;
            }
            if (!auMoinsUneContraintePenalise(cas)) {
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

    /** Un animateur nul est une désaffectation, pas une affectation à filtrer. */
    @Test
    void unPosteNonPourvuResteToujoursProposable() {
        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(standStrategie("S"), matin("C", 1, D1), null), null)).isTrue();
    }

    /**
     * Le pendant du test de propriété : le filtre sert vraiment à quelque chose.
     * Sans ces rejets il n'aurait aucune raison d'exister, et un test qui passe
     * sur un filtre devenu passe-plat ne prouverait rien.
     */
    @Test
    void leFiltreEcarteBienLesAffectationsIllegales() {
        Animateur mineur = mineurDebutant("MIN");
        Animateur indisponible = majeurReferent("INDISPO");
        indisponible.setJoursIndisponibles(java.util.Set.of(D1));

        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(standStrategie("S"), matin("C1", 1, D1), indisponible), indisponible)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(stand("S-MAJ", true, "STRATEGIE"), matin("C2", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(standStrategie("S"), nuit("C3", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(standStrategie("S"), journeeLongue("C4", 1, D1), mineur), mineur)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.estEligible(
                poste(standStrategie("S"), matin("C5", 7, FERIE), mineur), mineur)).isFalse();
    }
}
