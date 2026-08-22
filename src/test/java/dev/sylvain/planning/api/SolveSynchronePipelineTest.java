package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.KpiHistoriqueService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * The synchronous solve (`POST /api/solve`) must follow exactly the same path
 * as the asynchronous ones.
 *
 * <p>It did not: the pipeline was written four times, and that copy stopped
 * after "persist". The Contraintes screen therefore stayed on the analysis of
 * the <b>previous</b> solve — it displayed violations that no longer described
 * the persisted plan — and no KPI row was written. Nothing threw, nothing
 * showed in the logs: the only symptom was a screen that lied.</p>
 *
 * <p>This test holds the two ends {@code SolvePipeline} now guarantees for
 * its four callers.</p>
 */
@QuarkusTest
class SolveSynchronePipelineTest {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    KpiHistoriqueService kpiHistorique;

    @Test
    void unSolveSynchroneAlimenteLEcranContraintesEtLHistoriqueKpi() {
        // An analysis of another plan is in place: that is the one that stayed
        // on screen after a synchronous solve.
        analysisStore.clear();
        int kpiBefore = kpiHistorique.list().size();

        String probleme = given()
                .when().get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract().asString();

        given()
                .contentType("application/json")
                .body(probleme)
                .when().post("/api/solve?seconds=1")
                .then()
                .statusCode(200);

        ConstraintAnalysisStore.StoredAnalysis analyse = analysisStore.latest();
        assertThat(analyse)
                .as("l'écran Contraintes doit décrire le plan que ce solve vient de persister")
                .isNotNull();
        assertThat(analyse.diagnostic()).isNotNull();
        assertThat(analyse.diagnostic().score()).isNotBlank();

        assertThat(kpiHistorique.list())
                .as("un solve terminé écrit sa ligne de KPI, quel que soit le chemin qui l'a lancé")
                .hasSizeGreaterThan(kpiBefore);
    }
}
