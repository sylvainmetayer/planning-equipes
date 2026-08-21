package dev.sylvain.planning.service;

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
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningService.EchangeSimulation;

/**
 * Simulation d'une demande d'échange (issue #165) sur
 * {@code PlanningService.simulerEchange} directement : pas de base, pas de
 * contexte Quarkus — un planning résolu construit à la main, et le
 * {@code SolutionManager} réel pour l'analyse des scores.
 */
class PlanningServiceEchangeTest {

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);

    private static final PlanningService planningService;
    static {
        Referentiel referenceDataService = new ReferentielVide();
        planningService = new PlanningService(3L, 2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceDataService, new FeasibilityAnalyzer(), ConfigProvider.getConfig());
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

    private static PlanningFestival planning(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return new PlanningFestival(J1, animateurs, postes);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    /** Quand la cible tient aussi un siège sur le créneau, l'échange est croisé. */
    @Test
    void uneCibleDejaEnPosteSurLeCreneauDonneUnEchangeCroise() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PosteAffectation posteBruno = poste("P2", standS2, creneau, bruno);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice, posteBruno));

        EchangeSimulation simulation = planningService.simulerEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(simulation.echangeCroise()).isTrue();
        assertThat(simulation.posteDemandeurId()).isEqualTo("P1");
        assertThat(simulation.posteCibleId()).isEqualTo("P2");
        assertThat(simulation.standCibleId()).isEqualTo("S2");
        assertThat(simulation.casseContrainteDure()).isFalse();
        assertThat(simulation.nouvellesViolationsDures()).isEmpty();
    }

    /** Une cible libre sur le créneau reprend simplement le siège du demandeur. */
    @Test
    void uneCibleLibreSurLeCreneauDonneUneRepriseSimple() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice));

        EchangeSimulation simulation = planningService.simulerEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(simulation.echangeCroise()).isFalse();
        assertThat(simulation.posteCibleId()).isNull();
        assertThat(simulation.standCibleId()).isNull();
        assertThat(simulation.casseContrainteDure()).isFalse();
    }

    /**
     * Le verdict de prévalidation est global : donner le siège à une cible
     * indisponible ce jour-là casse {@code animateurDisponible}, et la
     * violation revient avec sa description métier du catalogue — pas un nom
     * technique brut.
     */
    @Test
    void uneCibleIndisponibleCeJourLaCasseUneContrainteDure() {
        bruno.setJoursIndisponibles(Set.of(J1));
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice));

        EchangeSimulation simulation = planningService.simulerEchange(solved, "A1", "A2", 1L, "S1");

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
     * La simulation est une mutation temporaire : quel que soit le verdict, le
     * planning ressort avec ses occupants d'origine.
     */
    @Test
    void laSimulationLaisseLePlanningInchange() {
        bruno.setJoursIndisponibles(Set.of(J1));
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PosteAffectation posteBruno = poste("P2", standS2, creneau, bruno);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice, posteBruno));

        planningService.simulerEchange(solved, "A1", "A2", 1L, "S1");

        assertThat(posteAlice.getAnimateur()).isSameAs(alice);
        assertThat(posteBruno.getAnimateur()).isSameAs(bruno);
    }

    /** Le poste désigné doit être celui du demandeur : stand et créneau comptent. */
    @Test
    void unPosteQuiNAppartientPasAuDemandeurEstRejete() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice));

        assertThatThrownBy(() -> planningService.simulerEchange(solved, "A1", "A2", 1L, "S2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aucun poste");
        assertThatThrownBy(() -> planningService.simulerEchange(solved, "A2", "A1", 1L, "S1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aucun poste");
    }

    @Test
    void uneCibleInconnueEstRejetee() {
        PosteAffectation posteAlice = poste("P1", standS1, creneau, alice);
        PlanningFestival solved = planning(List.of(alice, bruno), List.of(posteAlice));

        assertThatThrownBy(() -> planningService.simulerEchange(solved, "A1", "FANTOME", 1L, "S1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Animateur inconnu");
    }
}
