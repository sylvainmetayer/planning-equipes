package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationsView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The two answers that keep a real plan readable over MCP: a capped listing
 * that says how much it left out, and a whole-plan summary that fits in a
 * conversation.
 */
class PlanningMcpToolsSyntheseTest {

    private static final LocalDate SAMEDI = LocalDate.of(2026, 8, 15);
    private static final LocalDate DIMANCHE = LocalDate.of(2026, 8, 16);

    private static PlanningMcpTools tools(List<PosteAffectation> postes) {
        PlanningEvenement planning = new PlanningEvenement();
        planning.setPostes(postes);
        PlanningPersistenceService persistenceService = new PlanningPersistenceService() {
            @Override
            public PlanningEvenement loadPersistedPlanning() {
                return planning;
            }
        };
        return new PlanningMcpTools(null, null, persistenceService, null, null, null, null, null, null);
    }

    private static PosteAffectation poste(String id, Stand stand, LocalDate date, String animateurId) {
        Creneau creneau = new Creneau((long) date.getDayOfMonth(), 1, date, LocalTime.of(10, 0), LocalTime.of(14, 0));
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        if (animateurId != null) {
            poste.setAnimateur(new Animateur(animateurId, null, null, null, false));
        }
        return poste;
    }

    private static Stand stand(String id, String nom) {
        return new Stand(id, nom, Set.of(), 1, 2, false);
    }

    @Test
    void theSummaryCountsTheFilledPostesPerStandAndPerDay() {
        Stand echecs = stand("S1", "Échecs");
        Stand tir = stand("S2", "Tir à l'arc");
        PlanningMcpTools tools = tools(List.of(
                poste("P1", echecs, SAMEDI, "A1"),
                poste("P2", echecs, SAMEDI, null),
                poste("P3", tir, SAMEDI, "A2"),
                poste("P4", tir, DIMANCHE, "A1")));

        var synthese = tools.summarizeAffectations(null);

        assertThat(synthese.postesTotal()).isEqualTo(4);
        assertThat(synthese.postesPourvus()).isEqualTo(3);
        assertThat(synthese.postesNonPourvus()).isEqualTo(1);
        assertThat(synthese.animateursAffectes()).isEqualTo(2);
        assertThat(synthese.parStand())
                .extracting(ligne -> ligne.standId() + ":" + ligne.postes() + "/" + ligne.pourvus())
                .containsExactlyInAnyOrder("S1:2/1", "S2:2/2");
        assertThat(synthese.parJour())
                .extracting(ligne -> ligne.date() + ":" + ligne.nonPourvus())
                .containsExactly(SAMEDI + ":1", DIMANCHE + ":0");
    }

    @Test
    void theSummaryNamesTheStandsButNeverTheAnimateurs() {
        PlanningMcpTools tools = tools(List.of(poste("P1", stand("S1", "Échecs"), SAMEDI, "A1")));

        var synthese = tools.summarizeAffectations(null);

        assertThat(synthese.parStand())
                .singleElement()
                .satisfies(ligne -> assertThat(ligne.standNom()).isEqualTo("Échecs"));
        assertThat(synthese.animateursAffectes()).isEqualTo(1);
    }

    @Test
    void anEmptyPlanningGivesAZeroSummary() {
        var synthese = tools(List.of()).summarizeAffectations(null);

        assertThat(synthese.postesTotal()).isZero();
        assertThat(synthese.parStand()).isEmpty();
        assertThat(synthese.parJour()).isEmpty();
    }

    @Test
    void listingTheAffectationsCapsButAnnouncesTheTotal() {
        Stand echecs = stand("S1", "Échecs");
        List<PosteAffectation> postes = new ArrayList<>(IntStream.range(0, 500)
                .mapToObj(index -> poste("P" + index, echecs, SAMEDI, "A" + index))
                .toList());

        AffectationsView vue = tools(postes).listAffectations(null, null, null, null, null, null);

        assertThat(vue.affectations()).hasSize(PlanningMcpTools.LIMITE_AFFECTATIONS_DEFAUT);
        assertThat(vue.total()).isEqualTo(500);
    }

    @Test
    void theTotalCountsTheFilteredPostesNotAllPostes() {
        PlanningMcpTools tools = tools(List.of(
                poste("P1", stand("S1", "Échecs"), SAMEDI, "A1"),
                poste("P2", stand("S2", "Tir à l'arc"), SAMEDI, null)));

        assertThat(tools.listAffectations("S1", null, null, null, null, null).total())
                .isEqualTo(1);
        assertThat(tools.listAffectations(null, null, null, true, null, null).total())
                .isEqualTo(1);
    }

    @Test
    void aNonPositiveLimitIsRefused() {
        PlanningMcpTools tools = tools(List.of(poste("P1", stand("S1", "Échecs"), SAMEDI, "A1")));

        assertThatThrownBy(() -> tools.listAffectations(null, null, null, null, -1, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("limite");
    }
}
