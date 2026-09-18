package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.mcp.ContrainteMcpTools.ContrainteView;
import dev.sylvain.planning.mcp.DiagnosticMcpTools.PausesView;
import dev.sylvain.planning.mcp.StandMcpTools.StandsView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.CompetenceStaffing;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    ReferenceDataService referenceDataService;

    @Inject
    ContrainteMcpTools contrainteTools;

    @Inject
    StandMcpTools standTools;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ParametresMcpTools parametresTools;

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
    void staffingIsAlsoBrokenDownPerGameCategoryOfTheReferential() {
        // Proves the wiring, not the arithmetic: the analyzer only sees the
        // categories and the animateurs the edition actually holds — nothing
        // here is a hard-coded list of typologies.
        loadScenario();

        CompetenceStaffing competence = diagnosticTools.analyser_effectifs(null).parCompetence();

        assertThat(competence.animateursTotal()).isPositive();
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
                .containsExactlyInAnyOrder("STRATEGIE", "HOMME_JEU");
        assertThat(competence.parTypologie())
                .allSatisfy(ligne -> assertThat(ligne.minimumTotal()).isPositive());
        assertThat(competence.siegesNonAttribues()).isZero();
    }

    @Test
    void withStandsButNoAnimateurYetTheBoundsAreProvenAndOnlyTheComparisonIsMissing() {
        // The real path of a fresh edition: stands and créneaux imported, staff
        // list not filled in yet. The seats depend on the stands and the
        // créneaux only, so the bounds are the same as with the full roster
        // (issue #416) — and it is animateursTotal, not an empty category
        // list, that says the comparison is missing.
        loadScenario();
        StaffingSummary avecAnimateurs = diagnosticTools.analyser_effectifs(null);
        referenceDataService.listAnimateurs().stream()
                .map(Animateur::getId)
                .forEach(referenceDataService::deleteAnimateur);

        StaffingSummary effectifs = diagnosticTools.analyser_effectifs(null);

        assertThat(effectifs.parJour()).isNotEmpty();
        assertThat(effectifs.minimumTotal()).isPositive().isEqualTo(avecAnimateurs.minimumTotal());
        assertThat(effectifs.referentielsManquants()).containsExactly(ReferentielManquant.ANIMATEURS);
        assertThat(effectifs.parCompetence().animateursTotal()).isZero();
        assertThat(effectifs.parCompetence().parTypologie()).isNotEmpty();
        assertThat(effectifs.parCompetence().manqueTotal()).isZero();
    }

    @Test
    void theStaffingAnalysisAnswersOnAnEmptyEditionAndNamesEverythingMissing() {
        scenarioTools.reinitialiser_donnees(null);

        StaffingSummary effectifs = diagnosticTools.analyser_effectifs(null);

        assertThat(effectifs.parJour()).isEmpty();
        assertThat(effectifs.minimumTotal()).isZero();
        assertThat(effectifs.referentielsManquants())
                .containsExactly(
                        ReferentielManquant.STANDS, ReferentielManquant.CRENEAUX, ReferentielManquant.ANIMATEURS);
    }

    @Test
    void lesOuverturesSeFiltrentSurUnStandSansFausserLesTotaux() {
        loadScenario();
        RapportOuvertures complet = diagnosticTools.analyser_ouvertures_stands(null, null);
        assertThat(complet.stands()).hasSizeGreaterThan(1);
        String standId = complet.stands().get(0).standId();

        RapportOuvertures filtre = diagnosticTools.analyser_ouvertures_stands(standId, null);

        assertThat(filtre.stands())
                .singleElement()
                .satisfies(ligne -> assertThat(ligne.standId()).isEqualTo(standId));
        assertThat(filtre.postesTotal()).isEqualTo(complet.postesTotal());
        assertThat(filtre.jours()).isEqualTo(complet.jours());
    }

    @Test
    void unStandInconnuNeRenvoieAucuneLigneSansEchouer() {
        loadScenario();

        assertThat(diagnosticTools
                        .analyser_ouvertures_stands("STAND-INCONNU", null)
                        .stands())
                .isEmpty();
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

        assertThat(contrainteTools
                        .modifier_poids_contrainte(CONTRAINTE, 4, null)
                        .poids())
                .isEqualTo(4);
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
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("poids");
    }

    @Test
    void unePonderationSurUneContrainteInconnueEstRefusee() {
        assertThatThrownBy(() -> contrainteTools.modifier_poids_contrainte("contrainteQuiNexistePas", 2, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class);
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

    @Test
    void lAnalyseDesPausesSitueChaquePauseSansNommerPersonneEtSeFiltre() {
        scenarioTools.reinitialiser_donnees(null);
        Animateur alice = new Animateur("PAUSE-MCP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PAUSE-MCP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur seul = new Animateur("PAUSE-MCP-C", "Carole", "Seule", LocalDate.of(1990, 1, 1), false);
        Stand duo = new Stand("PAUSE-MCP-S1", "Duo", Set.of(), 2, 2, false);
        Stand solo = new Stand("PAUSE-MCP-S2", "Solo", Set.of(), 1, 1, false);
        LocalDate jour = LocalDate.of(2026, 7, 11);
        Creneau longue = new Creneau(9601L, 1, jour, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation p1 = new PosteAffectation("PAUSE-MCP-P1", duo, longue);
        p1.setAnimateur(alice);
        PosteAffectation p2 = new PosteAffectation("PAUSE-MCP-P2", duo, longue);
        p2.setAnimateur(bruno);
        PosteAffectation p3 = new PosteAffectation("PAUSE-MCP-P3", solo, longue);
        p3.setAnimateur(seul);
        persistence.persist(new PlanningEvenement(jour, List.of(alice, bruno, seul), List.of(p1, p2, p3)));
        parametresTools.modifier_parametres_legaux(
                null, null, null, null, null, true, null, null, null, null, null, null, null, null, null);

        PausesView tout = diagnosticTools.analyser_pauses(null, null, null, null);
        assertThat(tout.pauseSurPoste()).isTrue();
        assertThat(tout.journeesAnalysees()).isEqualTo(3);
        assertThat(tout.pausesDues()).isEqualTo(3);
        assertThat(tout.relaisManquants()).isEqualTo(1);
        assertThat(tout.journees())
                .extracting(DiagnosticMcpTools.JourneePausesView::animateurId)
                .containsExactly("PAUSE-MCP-A", "PAUSE-MCP-B", "PAUSE-MCP-C");
        DiagnosticMcpTools.PauseDueMcpView pauseAlice =
                tout.journees().getFirst().sequences().getFirst().pausesDues().getFirst();
        assertThat(pauseAlice.heureLimite()).isEqualTo(LocalTime.of(19, 0));
        assertThat(pauseAlice.standId()).isEqualTo("PAUSE-MCP-S1");
        assertThat(pauseAlice.relaisAnimateurIds()).containsExactly("PAUSE-MCP-B");
        // No display name anywhere in the wire shape: ids only.
        assertThat(tout.toString()).doesNotContain("Martin").doesNotContain("Alice");

        PausesView sansRelais = diagnosticTools.analyser_pauses(null, null, true, null);
        assertThat(sansRelais.journees())
                .extracting(DiagnosticMcpTools.JourneePausesView::animateurId)
                .containsExactly("PAUSE-MCP-C");
        assertThat(sansRelais.pausesDues()).isEqualTo(1);

        PausesView surLeDuo = diagnosticTools.analyser_pauses("2026-07-11", "PAUSE-MCP-S1", null, null);
        assertThat(surLeDuo.journees()).hasSize(2);
        assertThat(diagnosticTools
                        .analyser_pauses("2026-07-12", null, null, null)
                        .journees())
                .isEmpty();

        parametresTools.modifier_parametres_legaux(
                null, null, null, null, null, false, null, null, null, null, null, null, null, null, null);
        assertThat(diagnosticTools.analyser_pauses(null, null, null, null).pauseSurPoste())
                .isFalse();
    }

    /**
     * The thirty minutes the organisation retained have to be settable from an
     * assistant, not only from the Paramètres screen, the REST route or a YAML
     * (issue #31) — and the ordre public floors have to hold on that path too:
     * 20 minutes for an adult (L3121-16), 30 for a minor (L3162-3).
     */
    @Test
    void lesDureesDePauseSeReglentParMcpEtGardentLeursPlanchers() {
        loadScenario();

        ParametresMcpTools.ParametresLegauxView ecrit = parametresTools.modifier_parametres_legaux(
                null, null, null, null, null, null, 30, 45, null, null, null, null, null, null, null);

        assertThat(ecrit.dureePauseMajeurMinutes()).isEqualTo(30);
        assertThat(ecrit.dureePauseMineurMinutes()).isEqualTo(45);
        assertThat(parametresTools.consulter_parametres_legaux(null).dureePauseMajeurMinutes())
                .isEqualTo(30);

        assertThatThrownBy(() -> parametresTools.modifier_parametres_legaux(
                        null, null, null, null, null, null, 19, null, null, null, null, null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("L3121-16");
        assertThatThrownBy(() -> parametresTools.modifier_parametres_legaux(
                        null, null, null, null, null, null, null, 29, null, null, null, null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("L3162-3");
    }
}
