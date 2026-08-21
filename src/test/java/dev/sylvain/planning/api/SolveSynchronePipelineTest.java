package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.KpiHistoriqueService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Le solve synchrone (`POST /api/solve`) doit suivre exactement le même chemin
 * que les solves asynchrones.
 *
 * <p>Il ne le suivait pas : le pipeline était écrit quatre fois, et cette
 * copie-là s'arrêtait après « persister ». L'écran Contraintes restait donc sur
 * l'analyse du solve <b>précédent</b> — il affichait des violations qui ne
 * décrivaient plus le plan persisté — et aucune ligne de KPI n'était écrite.
 * Rien ne levait, rien ne se voyait dans les logs : le seul symptôme était un
 * écran qui mentait.</p>
 *
 * <p>Ce test tient les deux bouts que {@code ResolutionPipeline} garantit
 * désormais pour ses quatre appelants.</p>
 */
@QuarkusTest
class SolveSynchronePipelineTest {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    KpiHistoriqueService kpiHistorique;

    @Test
    void unSolveSynchroneAlimenteLEcranContraintesEtLHistoriqueKpi() {
        // Une analyse d'un autre plan est en place : c'est elle qui restait
        // affichée après un solve synchrone.
        analysisStore.effacer();
        int kpiAvant = kpiHistorique.lister().size();

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

        assertThat(kpiHistorique.lister())
                .as("un solve terminé écrit sa ligne de KPI, quel que soit le chemin qui l'a lancé")
                .hasSizeGreaterThan(kpiAvant);
    }
}
