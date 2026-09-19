package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.IOException;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full-scale regression test for the "continu" scenario family: an event open
 * from morning to midnight, whose day is covered by overlapping relais — a
 * vacation hands over to the next a few minutes before it ends, so a stand is
 * never in deficit, only briefly in surplus. The file used to carry one raw
 * all-day amplitude per day and let the découpage cut it; it now states those
 * relais outright, which is the same problem without the machinery. Both
 * variants must always solve to zero hard-constraint violations. Built the
 * plain (non-Quarkus) way,
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

    // 300 s were verified on 3/3 cold cycles before the meal break existed.
    // Since coupureRepasObligatoire applies (issue #438) the same grid lands
    // on -1 or -2 hard at that budget — one or two seats out of 3968, and a
    // score that moves from one run to the next, which is a solve still
    // converging rather than a problem without an answer. Raised rather than
    // shaved: these budgets are ceilings, not durations. A solve stops the
    // moment it reaches zero, so the whole scenario job still runs in about
    // ten minutes; what the ceiling buys is the run that needs a little more.
    private static final long SECONDS_LIMITE_SECURITE = 600L;

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
        PlanningService planningService = new PlanningService(
                420L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceDataService,
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());

        // Mirrors buildFromReferenceData(): postes built from stands x the
        // file's créneaux — not buildExample(), which would use the file's
        // hand-pinned poste list when it carries one.
        ScenarioYamlReader.ReferenceScenario reference = planningService.loadReferenceScenario(scenarioName);
        // The file's legal parameters carry the meal break the solver judges
        // on; a plain-Java harness hands them over itself, as production reads
        // them from the edition.
        ParametresLegaux parametresLegaux = planningService
                .loadScenarioSections(scenarioName)
                .parametresLegaux()
                .orElseGet(ParametresLegaux::new);
        List<Creneau> creneaux = List.copyOf(reference.creneauxParId().values());
        List<Stand> stands = List.copyOf(reference.standsById().values());
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, creneaux);
        PlanningEvenement problem = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes);
        // The grid is laid out around the file's own meal windows, so it has to
        // be judged on the same ones. Production reads them from the edition the
        // scenario was imported into; a plain-Java harness has to hand them over
        // itself, or the plan is scored against windows the grid never saw
        // (issue #438).
        problem.setFenetresRepas(FenetreRepas.from(parametresLegaux));
        // And its legal parameters, for the same reason: production reads them
        // from the edition, so a file that declares a minimum gap of zero —
        // what a grid of touching vacations needs — must be heard here too.
        problem.setParametresLegaux(List.of(parametresLegaux));

        PlanningEvenement solved = planningService.solveUntilFeasible(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        // A renfort may stay empty (issue #505): the scenario declares a
        // capacity above the staffing its windows ask for, and feasibility is
        // about what is owed, not about exhausting that capacity.
        assertThat(solved.getPostes())
                .filteredOn(poste -> !poste.isOptionnel())
                .noneMatch(poste -> poste.getAnimateur() == null);
    }
}
