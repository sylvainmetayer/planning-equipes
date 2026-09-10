package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningWhatIf.EchangeSimulation;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.referentiel.ReferenceData;

/**
 * Simulation of a swap request (issue #165) straight on
 * {@code PlanningService.simulateEchange}: no database, no Quarkus context — a
 * solved planning built by hand, and the real {@code SolutionManager} for the
 * score analysis.
 */
class PlanningServiceEchangeTest {

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);

    private static final PlanningService planningService;
    static {
        ReferenceData referenceDataService = new EmptyReferenceData();
        planningService = new PlanningService(3L, 2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceDataService, new FeasibilityAnalyzer(), null, null, ConfigProvider.getConfig());
    }

    private final Stand standS1 = new Stand("S1", "Stand 1", Set.of("STRATEGIE"), 1, 1, false);
    private final Stand standS2 = new Stand("S2", "Stand 2", Set.of("STRATEGIE"), 1, 1, false);
    private final Creneau creneau = new Creneau(1L, 1, J1, LocalTime.of(9, 0), LocalTime.of(13, 0));

    private static Animateur competent(String id) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(2000, 1, 1), false);
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
        return animateur;
    }

    private final Animateur alice = competent("A1");
    private final Animateur bruno = competent("A2");

    private static PlanningEvenement planning(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return new PlanningEvenement(J1, animateurs, postes);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    /** When the target also holds a seat on the timeslot, the swap is a crossed one. */
    @Test
    void uneCibleDejaEnPosteSurLeCreneauDonneUnEchangeCroise() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PosteAffectation posteBruno = poste("P2", standS2, creneau, bruno);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice, posteBruno));

        EchangeSimulation simulation = planningService.simulateEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(simulation.echangeCroise()).isTrue();
        assertThat(simulation.posteDemandeurId()).isEqualTo("P1");
        assertThat(simulation.posteCibleId()).isEqualTo("P2");
        assertThat(simulation.standCibleId()).isEqualTo("S2");
        assertThat(simulation.casseContrainteDure()).isFalse();
        assertThat(simulation.nouvellesViolationsDures()).isEmpty();
    }

    /** A target free on the timeslot simply takes over the requester's seat. */
    @Test
    void uneCibleLibreSurLeCreneauDonneUneRepriseSimple() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice));

        EchangeSimulation simulation = planningService.simulateEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(simulation.echangeCroise()).isFalse();
        assertThat(simulation.posteCibleId()).isNull();
        assertThat(simulation.standCibleId()).isNull();
        assertThat(simulation.casseContrainteDure()).isFalse();
    }

    /**
     * The prevalidation verdict is global: giving the seat to a target
     * unavailable that day breaks {@code animateurDisponible}, and the violation
     * comes back with its business description from the catalog — not a raw
     * technical name.
     */
    @Test
    void uneCibleIndisponibleCeJourLaCasseUneContrainteDure() {
        bruno.setJoursIndisponibles(Set.of(J1));
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice));

        EchangeSimulation simulation = planningService.simulateEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(simulation.casseContrainteDure()).isTrue();
        assertThat(simulation.scoreApres().hardScore()).isLessThan(simulation.scoreAvant().hardScore());
        assertThat(simulation.nouvellesViolationsDures())
                .anySatisfy(violation -> {
                    assertThat(violation.name()).isEqualTo("animateurDisponible");
                    assertThat(violation.description()).contains("indisponible");
                    assertThat(violation.matchesSupplementaires()).isEqualTo(1);
                });
    }

    /**
     * The simulation is a temporary mutation: whatever the verdict, the planning
     * comes back out with its original occupants.
     */
    @Test
    void laSimulationLaisseLePlanningInchange() {
        bruno.setJoursIndisponibles(Set.of(J1));
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PosteAffectation posteBruno = poste("P2", standS2, creneau, bruno);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice, posteBruno));

        planningService.simulateEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(posteAlice.getAnimateur()).isSameAs(alice);
        assertThat(posteBruno.getAnimateur()).isSameAs(bruno);
    }

    /** The seat named must be the requester's: stand and timeslot both count. */
    @Test
    void unPosteQuiNAppartientPasAuDemandeurEstRejete() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice));

        assertThatThrownBy(() -> planningService.simulateEchange(solved, "A1", "A2", 1L, "S2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aucun poste");
        assertThatThrownBy(() -> planningService.simulateEchange(solved, "A2", "A1", 1L, "S1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aucun poste");
    }

    @Test
    void uneCibleInconnueEstRejetee() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningEvenement solved = planning(List.of(alice, bruno), List.of(posteAlice));

        assertThatThrownBy(() -> planningService.simulateEchange(solved, "A1", "FANTOME", 1L, "S1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Animateur inconnu");
    }
}
