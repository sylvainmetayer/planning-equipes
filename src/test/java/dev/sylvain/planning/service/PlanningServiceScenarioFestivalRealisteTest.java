package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Full-scale regression test on the two <b>anonymised real-world</b> fixtures:
 * {@code festival-realiste.yaml} (153 animateurs, 65 stands, 23 emplacements,
 * 20 daily amplitudes) and its {@code -canicule} variant, whose extra evening
 * windows make the same event a materially harder problem.
 *
 * <h2>What these add over the other scenario-lent tests</h2>
 * <p>{@code scenario-complet} and {@code scenario-continu} are hand-built
 * problems: regular, evenly staffed, with no premium tier and few opening
 * exceptions. These two are the shape a real edition actually takes — 45
 * premium stands, per-stand recurring schedules with midday breaks, an uneven
 * skill matrix — which is where convergence has historically broken without
 * any of the hand-built scenarios noticing.</p>
 *
 * <h2>Why they can be committed</h2>
 * <p>They are derived from files that must never be: only identifiers were
 * rewritten (animateur first/last names, emplacement and stand ids and names,
 * the edition name). Everything the solver reads — dates, hours, headcounts,
 * skills, schedules, parameters — is the original's, and the coordinates are
 * translated in longitude at constant latitude so the distance constraint sees
 * exactly the same values. That is the point: a fixture that converges like
 * its source, or it would not be testing what it claims to.</p>
 *
 * <p>Ids are numbered in order of appearance and section order is preserved,
 * so nothing that depends on ordering can diverge from the original either.</p>
 *
 * <p>Tagged {@code scenario-lent}: excluded from the default
 * {@code ./mvnw test}/CI run (see the {@code scenario-tests} Maven profile in
 * {@code pom.xml}) and only run with {@code ./mvnw test -Pscenario-tests}.
 * Run it in the background when triggered from an agent session — see
 * AGENTS.md's "Costly test jobs" section.</p>
 */
@Tag("scenario-lent")
class PlanningServiceScenarioFestivalRealisteTest {

    /**
     * Safety ceiling, not a target: {@code solveUntilFeasible} returns
     * as soon as the hard score reaches zero, so this only bounds a run that
     * is <em>not</em> converging.
     *
     * <p>Measured convergence on Timefold 2.5, once the stand horaires are
     * resolved (see below): 94 s then 103 s across two runs on the base
     * fixture, 8 s on the canicule variant — which reaches feasibility in the
     * construction heuristic and never enters local search. 900 s therefore
     * leaves roughly an eightfold margin locally, and still several times the
     * CI runner's slower pace (7 700–9 800 move evaluations/s there against
     * 5 400–34 000 here).</p>
     *
     * <p>The figures are the solver's, not a stopwatch's: they come from the
     * {@code Local Search phase (1) ended} line, which is where the hard score
     * reaches zero. They moved with the 2.x migration — 150 s on 1.34 — so a
     * Timefold bump is a reason to read them again rather than trust them.</p>
     *
     * <p>Deliberately not the fixtures' own 1800 s production budget: at that
     * ceiling a genuine convergence regression would burn an hour of the
     * self-hosted runner before turning the build red. Not shaved to the
     * hundred seconds observed either — a guardrail set to the minimum that
     * happened to work once turns every unrelated solver tuning into a false
     * alarm, and the two runs above already differ by 10 %.</p>
     */
    private static final long SECONDS_LIMITE_SECURITE = 900L;

    @Test
    void festivalRealisteNeViolateAucuneContrainteHard() throws IOException {
        assertSlicedAndSolvedWithoutHard("festival-realiste.yaml");
    }

    @Test
    void festivalRealisteEnCaniculeNeViolateAucuneContrainteHard() throws IOException {
        assertSlicedAndSolvedWithoutHard("festival-realiste-canicule.yaml");
    }

    /**
     * Mirrors what {@code decoupageAuto: {}} does at import: the file's
     * créneaux are daily amplitudes, sliced into vacations before any poste
     * exists. Going through {@code buildExample()} instead would solve
     * the raw amplitudes and test a pipeline nobody runs.
     *
     * <p>The {@link HoraireStandResolver} call is not optional decoration, and
     * it is what the sibling scenario tests get away with omitting: they run on
     * scenarios where no stand carries a recurring horaire, so resolving is a
     * no-op there. Here 65 stands do carry them, and skipping the expansion
     * makes {@link Creneau#segmentsOuvertsMinutes} see every stand as open
     * around the clock — 16 924 seats instead of 3 499, a problem five times
     * too large that no roster of 153 animateurs could ever fill. The
     * production path never has this problem because it goes through
     * {@code ReferenceDataService.listSolvedStands()}; a plain-Java test has
     * to do it by hand.</p>
     */
    private void assertSlicedAndSolvedWithoutHard(String scenario) throws IOException {
        ReferenceData referenceDataService = new EmptyReferenceData();
        PlanningService planningService = new PlanningService(420L, 0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService,
                new FeasibilityAnalyzer(), ConfigProvider.getConfig());

        ScenarioYamlReader.ReferenceScenario reference = planningService.loadReferenceScenario(scenario);
        ParametresDecoupage parametresDecoupage = planningService.loadScenarioSections(scenario).parametresDecoupage()
                .orElseGet(ParametresDecoupage::new);
        List<Creneau> vacations = VacationGeneratorService.generateVacations(
                List.copyOf(reference.creneauxParId().values()), parametresDecoupage);
        List<Stand> stands = List.copyOf(reference.standsById().values());
        HoraireStandResolver.apply(stands, vacations);
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, vacations);
        PlanningEvenement problem = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes);

        PlanningEvenement solved = planningService.solveUntilFeasible(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
    }
}
