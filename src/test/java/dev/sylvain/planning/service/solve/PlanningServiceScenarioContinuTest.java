package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.ReferenceData;

/**
 * Full-scale regression test for the "continu" scenario family: unlike
 * {@code scenario-complet.yaml} (already hand-split into per-shift créneaux),
 * {@code scenario-continu.yaml} carries one raw all-day créneau per day and
 * only becomes a real staffing problem after découpage ({@link
 * VacationGeneratorService}) splits each day into overlapping relais — the
 * exact "continu scindé" pipeline exercised manually against the dev
 * database while investigating issue #60's follow-up (reset DB -> load
 * scenario continu -> découpage -> solve). Both variants must always solve
 * to zero hard-constraint violations. Built the plain (non-Quarkus) way,
 * like {@link PlanningServiceScenarioCompletTest}, so it needs no database
 * and can give the solver the time this scenario size actually takes,
 * unconstrained by the %test profile's short solver budget.
 *
 * <p>Tagged {@code scenario-lent} (~75s for both tests): excluded from the
 * default {@code ./mvnw test}/CI run (see the {@code scenario-tests} Maven
 * profile in {@code pom.xml}) and only run with
 * {@code ./mvnw test -Pscenario-tests}. Run it in the background (not a
 * blocking foreground wait) when triggered from an agent session — see
 * AGENTS.md's "Costly test jobs" section.</p>
 */
@Tag("scenario-lent")
class PlanningServiceScenarioContinuTest {

    // Empirically verified: 3/3 independent cold reset/import/découpage/solve
    // cycles against the live dev database reached 0 hard within 300s
    // (deterministic seed=0); 120s and 180s were not enough. Keep this
    // guardrail generous rather than shaving it to the observed minimum, the
    // same reasoning as PlanningServiceScenarioCompletTest's own budget.
    private static final long SECONDS_LIMITE_SECURITE = 300L;

    @Test
    void scenarioContinuNeViolateAucuneContrainteHard() throws IOException {
        assertScenarioContinuSplitWithoutHard("scenario-continu.yaml");
    }

    @Test
    void scenarioContinuAvecCoupureNeViolateAucuneContrainteHard() throws IOException {
        assertScenarioContinuSplitWithoutHard("scenario-continu-avec-coupure.yaml");
    }

    private void assertScenarioContinuSplitWithoutHard(String scenarioName) throws IOException {
        ReferenceData referenceDataService = new EmptyReferenceData();
        PlanningService planningService = new PlanningService(420L, 0L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());

        // Mirrors buildFromReferenceData(): découpage on the raw
        // créneaux, then postes built from stands x découpé créneaux — not
        // buildExample(), which would instead use the file's raw,
        // undivided one-créneau-per-day amplitudes directly.
        ScenarioYamlReader.ReferenceScenario reference = planningService.loadReferenceScenario(scenarioName);
        ParametresDecoupage parametresDecoupage = planningService.loadScenarioSections(scenarioName).parametresDecoupage()
                .orElseGet(ParametresDecoupage::new);
        List<Creneau> creneauxScindes = VacationGeneratorService.generateVacations(
                List.copyOf(reference.creneauxParId().values()), parametresDecoupage);
        List<Stand> stands = List.copyOf(reference.standsById().values());
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, creneauxScindes);
        PlanningEvenement problem = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes);

        PlanningEvenement solved = planningService.solveUntilFeasible(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
    }
}
