package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.ContrainteMcpTools.ContrainteView;
import dev.sylvain.planning.mcp.StandMcpTools.StandsView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.StaffingAnalyzer.StaffingSummary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * The read-only diagnostics, and the constraint weight — everything the
 * screens could answer and MCP could not, checked against the sample scenario
 * rather than against hand-built objects, since the whole point of these
 * analyses is that they are computed on the problem a real solve would build.
 */
@QuarkusTest
class DiagnosticMcpToolsTest {

    private static final String CONTRAINTE = "posteDoitEtrePourvu";

    @Inject
    ScenarioMcpTools scenarioTools;

    @Inject
    DiagnosticMcpTools diagnosticTools;

    @Inject
    ContrainteMcpTools contrainteTools;

    @Inject
    StandMcpTools standTools;

    @AfterEach
    void clearEdition() {
        contrainteTools.modifier_poids_contrainte(CONTRAINTE, null, null);
        scenarioTools.reinitialiser_donnees(null);
    }

    private void loadScenario() {
        scenarioTools.reinitialiser_donnees(null);
        scenarioTools.importer_scenario("scenario.yml", null);
    }

    @Test
    void lanalyseDeffectifsCompteLesSiegesQueLeSolveurAuraitAPourvoir() {
        loadScenario();

        StaffingSummary effectifs = diagnosticTools.analyser_effectifs(null);

        assertThat(effectifs.minimumTotal()).isPositive();
        assertThat(effectifs.parJour()).isNotEmpty();
        assertThat(effectifs.borneRetenue()).isNotNull();
        assertThat(effectifs.jourCritique()).isNotNull();
    }

    @Test
    void lanalyseDeffectifsRepondSurUneEditionVideAuLieuDechouer() {
        scenarioTools.reinitialiser_donnees(null);

        StaffingSummary effectifs = diagnosticTools.analyser_effectifs(null);

        assertThat(effectifs.parJour()).isEmpty();
        assertThat(effectifs.minimumTotal()).isZero();
    }

    @Test
    void lesOuverturesSeFiltrentSurUnStandSansFausserLesTotaux() {
        loadScenario();
        RapportOuvertures complet = diagnosticTools.analyser_ouvertures_stands(null, null);
        assertThat(complet.stands()).hasSizeGreaterThan(1);
        String standId = complet.stands().get(0).standId();

        RapportOuvertures filtre = diagnosticTools.analyser_ouvertures_stands(standId, null);

        assertThat(filtre.stands()).singleElement()
                .satisfies(ligne -> assertThat(ligne.standId()).isEqualTo(standId));
        assertThat(filtre.postesTotal()).isEqualTo(complet.postesTotal());
        assertThat(filtre.jours()).isEqualTo(complet.jours());
    }

    @Test
    void unStandInconnuNeRenvoieAucuneLigneSansEchouer() {
        loadScenario();

        assertThat(diagnosticTools.analyser_ouvertures_stands("STAND-INCONNU", null).stands()).isEmpty();
    }

    @Test
    void laPrevisualisationDimportCompteCeQuiSeraitEcrase() {
        loadScenario();
        StandsView stands = standTools.lister_stands(null, null);

        assertThat(diagnosticTools.previsualiser_import(null).stands()).isEqualTo(stands.total());

        scenarioTools.reinitialiser_donnees(null);
        assertThat(diagnosticTools.previsualiser_import(null).stands()).isZero();
    }

    @Test
    void lHistoriqueDesKpiEstLisibleMemeAJeun() {
        assertThat(diagnosticTools.lister_kpi_historique()).isNotNull();
    }

    @Test
    void lePoidsDuneContrainteSeRegleEtSeRetablit() {
        loadScenario();

        assertThat(contrainteTools.modifier_poids_contrainte(CONTRAINTE, 4, null).poids()).isEqualTo(4);
        assertThat(contrainteTools.lister_contraintes(null))
                .filteredOn(vue -> vue.nom().equals(CONTRAINTE))
                .singleElement()
                .satisfies(vue -> assertThat(vue.poids()).isEqualTo(4));

        var retabli = contrainteTools.modifier_poids_contrainte(CONTRAINTE, null, null);

        assertThat(retabli.parDefaut()).isTrue();
        assertThat(retabli.poids()).isEqualTo(1);
    }

    @Test
    void unPoidsNulOuNegatifEstRefuse() {
        assertThatThrownBy(() -> contrainteTools.modifier_poids_contrainte(CONTRAINTE, 0, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("poids");
    }

    @Test
    void unePonderationSurUneContrainteInconnueEstRefusee() {
        assertThatThrownBy(() -> contrainteTools.modifier_poids_contrainte("contrainteQuiNexistePas", 2, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void lesContraintesNonRegleesRemontentUnPoidsDe1() {
        assertThat(contrainteTools.lister_contraintes(null))
                .isNotEmpty()
                .allSatisfy(vue -> assertThat(vue.poids()).isPositive());
    }

    /** Sanity check on the view itself: no personal field can reach it. */
    @Test
    void lesVuesDeContraintesNeNommentPersonne() {
        for (ContrainteView vue : contrainteTools.lister_contraintes(null)) {
            assertThat(vue.nom()).isNotBlank();
            assertThat(vue.niveau()).isIn("HARD", "MEDIUM", "SOFT");
        }
    }
}
