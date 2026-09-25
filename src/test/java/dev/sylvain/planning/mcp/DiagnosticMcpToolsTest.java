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
import dev.sylvain.planning.service.analyse.MargeAnalyzer;
import dev.sylvain.planning.service.analyse.MarginReading;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.CompetenceStaffing;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.analyse.TensionAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
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

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @AfterEach
    void clearEdition() {
        contrainteTools.updateContrainteWeight(CONTRAINTE, null, null);
        scenarioTools.resetData(null);
    }

    private void loadScenario() {
        scenarioTools.resetData(null);
        scenarioTools.importScenario("scenario.yml", null);
    }

    @Test
    void theStaffingAnalysisCountsTheSeatsTheSolverWouldFill() {
        loadScenario();

        StaffingSummary effectifs = diagnosticTools.analyzeEffectifs(null);

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

        CompetenceStaffing competence = diagnosticTools.analyzeEffectifs(null).parCompetence();

        // The lines carry typologie ids, which are generated (ADR 0050): the
        // scenario's references are the codes of the rows they landed on.
        assertThat(competence.animateursTotal()).isPositive();
        assertThat(competence.parTypologie())
                .extracting(TypologieStaffing::typologie)
                .containsExactlyInAnyOrder(typologieIdByCode("STRATEGIE"), typologieIdByCode("HOMME_JEU"));
        assertThat(competence.parTypologie())
                .allSatisfy(ligne -> assertThat(ligne.minimumTotal()).isPositive());
        assertThat(competence.siegesNonAttribues()).isZero();
    }

    private String typologieIdByCode(String code) {
        return referenceDataService.listTypologies().stream()
                .filter(typologie -> code.equals(typologie.code()))
                .map(TypologieItem::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No typologie of code " + code));
    }

    @Test
    void withStandsButNoAnimateurYetTheBoundsAreProvenAndOnlyTheComparisonIsMissing() {
        // The real path of a fresh edition: stands and créneaux imported, staff
        // list not filled in yet. The seats depend on the stands and the
        // créneaux only, so the bounds are the same as with the full roster
        // (issue #416) — and it is animateursTotal, not an empty category
        // list, that says the comparison is missing.
        loadScenario();
        StaffingSummary avecAnimateurs = diagnosticTools.analyzeEffectifs(null);
        referenceDataService.listAnimateurs().stream()
                .map(Animateur::getId)
                .forEach(referenceDataService::deleteAnimateur);

        StaffingSummary effectifs = diagnosticTools.analyzeEffectifs(null);

        assertThat(effectifs.parJour()).isNotEmpty();
        assertThat(effectifs.minimumTotal()).isPositive().isEqualTo(avecAnimateurs.minimumTotal());
        assertThat(effectifs.referentielsManquants()).containsExactly(ReferentielManquant.ANIMATEURS);
        assertThat(effectifs.parCompetence().animateursTotal()).isZero();
        assertThat(effectifs.parCompetence().parTypologie()).isNotEmpty();
        assertThat(effectifs.parCompetence().manqueTotal()).isZero();
    }

    @Test
    void theStaffingAnalysisAnswersOnAnEmptyEditionAndNamesEverythingMissing() {
        scenarioTools.resetData(null);

        StaffingSummary effectifs = diagnosticTools.analyzeEffectifs(null);

        assertThat(effectifs.parJour()).isEmpty();
        assertThat(effectifs.minimumTotal()).isZero();
        assertThat(effectifs.referentielsManquants())
                .containsExactly(
                        ReferentielManquant.STANDS, ReferentielManquant.CRENEAUX, ReferentielManquant.ANIMATEURS);
    }

    @Test
    void openingsFilterOnAStandWithoutSkewingTheTotals() {
        loadScenario();
        RapportOuvertures complet = diagnosticTools.analyzeStandOpenings(null, null);
        assertThat(complet.stands()).hasSizeGreaterThan(1);
        String standId = complet.stands().get(0).standId();

        RapportOuvertures filtre = diagnosticTools.analyzeStandOpenings(standId, null);

        assertThat(filtre.stands())
                .singleElement()
                .satisfies(ligne -> assertThat(ligne.standId()).isEqualTo(standId));
        assertThat(filtre.postesTotal()).isEqualTo(complet.postesTotal());
        assertThat(filtre.jours()).isEqualTo(complet.jours());
    }

    @Test
    void anUnknownStandReturnsNoRowWithoutFailing() {
        loadScenario();

        assertThat(diagnosticTools.analyzeStandOpenings("STAND-INCONNU", null).stands())
                .isEmpty();
    }

    @Test
    void theImportPreviewCountsWhatWouldBeOverwritten() {
        loadScenario();
        StandsView stands = standTools.listStands(null, null);

        assertThat(diagnosticTools.previewImport(null).stands()).isEqualTo(stands.total());

        scenarioTools.resetData(null);
        assertThat(diagnosticTools.previewImport(null).stands()).isZero();
    }

    @Test
    void theKpiHistoryIsReadableEvenWhenEmpty() {
        assertThat(diagnosticTools.listKpiHistory()).isNotNull();
    }

    @Test
    void aContrainteWeightIsSetAndRestored() {
        loadScenario();

        assertThat(contrainteTools.updateContrainteWeight(CONTRAINTE, 4, null).poids())
                .isEqualTo(4);
        assertThat(contrainteTools.listContraintes(null))
                .filteredOn(vue -> vue.nom().equals(CONTRAINTE))
                .singleElement()
                .satisfies(vue -> assertThat(vue.poids()).isEqualTo(4));

        var retabli = contrainteTools.updateContrainteWeight(CONTRAINTE, null, null);

        assertThat(retabli.parDefaut()).isTrue();
        assertThat(retabli.poids()).isEqualTo(1);
    }

    @Test
    void aZeroOrNegativeWeightIsRefused() {
        assertThatThrownBy(() -> contrainteTools.updateContrainteWeight(CONTRAINTE, 0, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("poids");
    }

    @Test
    void aWeightOnAnUnknownContrainteIsRefused() {
        assertThatThrownBy(() -> contrainteTools.updateContrainteWeight("contrainteQuiNexistePas", 2, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class);
    }

    @Test
    void untunedContraintesReportAWeightOf1() {
        assertThat(contrainteTools.listContraintes(null))
                .isNotEmpty()
                .allSatisfy(vue -> assertThat(vue.poids()).isPositive());
    }

    /** Sanity check on the view itself: no personal field can reach it. */
    @Test
    void theContrainteViewsNameNobody() {
        for (ContrainteView vue : contrainteTools.listContraintes(null)) {
            assertThat(vue.nom()).isNotBlank();
            assertThat(vue.niveau()).isIn("HARD", "MEDIUM", "SOFT");
        }
    }

    @Test
    void thePauseAnalysisPlacesEachPauseNamesNobodyAndFilters() {
        scenarioTools.resetData(null);
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
        PausesView tout = diagnosticTools.analyzePauses(null, null, null, null);
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

        PausesView sansRelais = diagnosticTools.analyzePauses(null, null, true, null);
        assertThat(sansRelais.journees())
                .extracting(DiagnosticMcpTools.JourneePausesView::animateurId)
                .containsExactly("PAUSE-MCP-C");
        assertThat(sansRelais.pausesDues()).isEqualTo(1);

        PausesView surLeDuo = diagnosticTools.analyzePauses("2026-07-11", "PAUSE-MCP-S1", null, null);
        assertThat(surLeDuo.journees()).hasSize(2);
        assertThat(diagnosticTools.analyzePauses("2026-07-12", null, null, null).journees())
                .isEmpty();
    }

    /**
     * The break the organisation retained has to be settable from an assistant,
     * not only from the Paramètres screen, the REST route or a YAML (issue #31)
     * — and the ordre public floor has to hold on that path too: 20 minutes
     * (L3121-16). There is one duration now, not one per age bracket
     * (ADR 0048); the minors' thirty are applied when the break is read, not
     * stored.
     */
    @Test
    void thePauseDurationIsSetOverMcpAndKeepsItsFloor() {
        loadScenario();

        ParametresMcpTools.ParametresLegauxView ecrit = parametresTools.updateParametresLegaux(
                null, null, null, null, 45, null, null, null, null, null, null, null);

        assertThat(ecrit.dureePauseMinutes()).isEqualTo(45);
        assertThat(parametresTools.getParametresLegaux(null).dureePauseMinutes())
                .isEqualTo(45);

        assertThatThrownBy(() -> parametresTools.updateParametresLegaux(
                        null, null, null, null, 19, null, null, null, null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("L3121-16");
    }

    /**
     * The checklist crosses to MCP as codes and ids: never the sentence — a
     * warning on an animateur dates their majority — nor the date.
     */
    @Test
    void theTensionModeCrossesTheMarginWithTheFragilityByIdOnly() throws Exception {
        loadScenario();

        MarginReading lecture = diagnosticTools.analyzeMargin("tension", null);

        assertThat(lecture).isInstanceOf(TensionAnalyzer.RapportTension.class);
        String json = objectMapper.writeValueAsString(lecture);
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            assertThat(json).doesNotContain(animateur.nomAffiche());
        }
        assertThat(diagnosticTools.analyzeMargin("apres", null)).isInstanceOf(MargeAnalyzer.RapportMarge.class);
    }

    @Test
    void theTrainingPlanNamesItsCandidatesByIdOnly() throws Exception {
        loadScenario();

        DiagnosticMcpTools.PlanFormationView plan = diagnosticTools.suggestTrainingPlan(null);
        String json = objectMapper.writeValueAsString(plan);

        assertThat(json).doesNotContain("\"nom\"");
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            assertThat(json).doesNotContain(animateur.nomAffiche());
        }
        assertThat(plan.typologies())
                .allSatisfy(ligne -> assertThat(ligne.candidats())
                        .allSatisfy(candidat -> assertThat(candidat.niveau())
                                .isIn(
                                        dev.sylvain.planning.domain.NiveauCompetence.DEBUTANT,
                                        dev.sylvain.planning.domain.NiveauCompetence.AUTONOME)));
    }

    @Test
    void theCoherenceChecklistCrossesAsCodesAndIdsOnly() throws Exception {
        loadScenario();
        referenceDataService.listAnimateurs().stream().findFirst().ifPresent(animateur -> {
            animateur.setDateNaissance(java.time.LocalDate.now().minusYears(15));
            referenceDataService.updateAnimateur(animateur.getId(), animateur);
        });

        DiagnosticMcpTools.CoherenceListView vue = diagnosticTools.listReferenceDataAnomalies(null);

        assertThat(vue.anomalies()).hasSize(vue.bloquants() + vue.aVerifier() + vue.informations());
        assertThat(vue.familles()).hasSize(5);
        String json = objectMapper.writeValueAsString(vue);
        assertThat(json)
                .doesNotContain("\"message\"")
                .doesNotContain("\"date\"")
                .doesNotContain("mineur du");
    }
}
