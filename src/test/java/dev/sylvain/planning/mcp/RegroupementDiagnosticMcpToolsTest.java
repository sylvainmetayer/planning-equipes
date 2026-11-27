package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.DiagnosticMcpTools.BesoinView;
import dev.sylvain.planning.mcp.DiagnosticMcpTools.RepasPausesView;
import dev.sylvain.planning.mcp.DiagnosticMcpTools.RisquesView;
import dev.sylvain.planning.service.analyse.MargeAnalyzer;
import dev.sylvain.planning.service.analyse.TensionAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tools 4.0 groups for the diagnosis and its reports (ADR 0127), each
 * called once as it should be and once as it must be refused, on the shipped
 * scenario and a two-seat plan persisted by hand.
 */
@QuarkusTest
class RegroupementDiagnosticMcpToolsTest {

    @Inject
    ScenarioMcpTools scenarioTools;

    @Inject
    DiagnosticMcpTools diagnosticTools;

    @Inject
    ChangementsJourneeMcpTools changementsTools;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ObjectMapper objectMapper;

    @AfterEach
    void clearEdition() {
        scenarioTools.resetData("E1");
    }

    /** The shipped scenario, two of its seats held: enough for every section to have something to read. */
    private Creneau loadScenarioWithAPlan() {
        scenarioTools.resetData("E1");
        scenarioTools.importScenario("scenario.yml", "E1");
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listStands();
        Creneau creneau = referenceDataService.listCreneaux().getFirst();
        PosteAffectation premier = new PosteAffectation("GROUPE-P1", stands.get(0), creneau);
        premier.setAnimateur(animateurs.get(0));
        PosteAffectation second = new PosteAffectation("GROUPE-P2", stands.get(stands.size() - 1), creneau);
        second.setAnimateur(animateurs.get(1));
        persistence.persist(new PlanningEvenement(creneau.getDate(), animateurs, List.of(premier, second)));
        return creneau;
    }

    @Test
    void theNeedIsReadSectionBySectionTheOthersLeftOut() {
        loadScenarioWithAPlan();

        BesoinView borne = diagnosticTools.consultNeed(null, null, "E1");
        assertThat(borne.section()).isEqualTo("borne");
        assertThat(borne.borne().minimumTotal()).isPositive();
        assertThat(borne.marge()).isNull();
        assertThat(borne.faisabilite()).isNull();

        BesoinView marge = diagnosticTools.consultNeed("marge", "apres", "E1");
        assertThat(marge.marge()).isInstanceOf(MargeAnalyzer.RapportMarge.class);
        assertThat(marge.borne()).isNull();

        assertThat(diagnosticTools.consultNeed("volumetrie", null, "E1").volumetrie())
                .isEqualTo(diagnosticTools.volumes("E1"));
        assertThat(diagnosticTools.consultNeed("typologie", null, "E1").typologie())
                .isNotNull();
        assertThat(diagnosticTools.consultNeed("faisabilite", null, "E1").faisabilite())
                .isNotNull();
    }

    @Test
    void theNeedRefusesAModeOutsideTheMarginAndTheTensionWhichIsARisk() {
        loadScenarioWithAPlan();

        assertThatThrownBy(() -> diagnosticTools.consultNeed("borne", "apres", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("section marge");
        assertThatThrownBy(() -> diagnosticTools.consultNeed("marge", "tension", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("tension");
    }

    /** The fragility report names people in its lines and in its sentence: neither crosses. */
    @Test
    void theRisksReadTheFragilityByIdWithoutANameNorTheSentence() throws Exception {
        loadScenarioWithAPlan();

        RisquesView fragilite = diagnosticTools.consultRisks(null, "E1");

        assertThat(fragilite.section()).isEqualTo("fragilite");
        assertThat(fragilite.fragilite().groupesAnalyses()).isPositive();
        assertThat(fragilite.fragilite().animateurs()).isNotEmpty();
        String json = objectMapper.writeValueAsString(fragilite);
        assertThat(json).doesNotContain("\"nom\"").doesNotContain("\"message\"");
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            assertThat(json).doesNotContain(animateur.nomAffiche());
        }
        assertThat(diagnosticTools.consultRisks("former", "E1").former()).isNotNull();
        assertThat(diagnosticTools.consultRisks("tension", "E1").tension())
                .isInstanceOf(TensionAnalyzer.RapportTension.class);
    }

    @Test
    void theRisksRefuseAnUnknownSection() {
        assertThatThrownBy(() -> diagnosticTools.consultRisks("marge", "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("fragilite, former, tension");
    }

    @Test
    void theMealsAndBreaksReportReadsTheBreaksOrTheWalks() {
        loadScenarioWithAPlan();

        RepasPausesView pauses = diagnosticTools.consultMealsAndBreaks(null, null, null, null, "E1");
        assertThat(pauses.section()).isEqualTo("pauses");
        assertThat(pauses.pauses()).isNotNull();
        assertThat(pauses.enchainements()).isNull();

        RepasPausesView marches = diagnosticTools.consultMealsAndBreaks("enchainements", null, null, null, "E1");
        assertThat(marches.enchainements()).isNotNull();
        assertThat(marches.pauses()).isNull();
    }

    @Test
    void theWalksRefuseTheFiltersOfTheBreaks() {
        assertThatThrownBy(() -> diagnosticTools.consultMealsAndBreaks("enchainements", null, "S1", null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("section pauses");
    }

    @Test
    void aDayIsComparedToItsReference() {
        Creneau creneau = loadScenarioWithAPlan();

        var changements = changementsTools.compareDay(creneau.getDate().toString(), "resolution", "E1");

        assertThat(changements.jour()).isEqualTo(creneau.getDate());
        assertThat(changements.reference()).hasToString("RESOLUTION");
    }

    @Test
    void aDayThatIsNotADateIsRefused() {
        assertThatThrownBy(() -> changementsTools.compareDay("demain", null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("date invalide");
    }
}
