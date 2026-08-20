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
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Full-scale regression test on the two <b>anonymised real-world</b> fixtures:
 * {@code festival-realiste.yaml} (153 animateurs, 65 stands, 23 emplacements,
 * 20 daily amplitudes) and its {@code -canicule} variant, whose extra evening
 * windows make the same festival a materially harder problem.
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
     * The production budget of the fixtures themselves
     * ({@code parametresSolveur.dureeResolutionSecondes: 1800}). Deliberately
     * not shaved to the observed convergence time: these two are the slowest
     * scenarios in the suite, and a guardrail set to the minimum that happened
     * to work once turns every unrelated solver tuning into a red build.
     */
    private static final long SECONDS_LIMITE_SECURITE = 1800L;

    @Test
    void festivalRealisteNeViolateAucuneContrainteHard() throws IOException {
        assertDecoupeEtResoluSansHard("festival-realiste.yaml");
    }

    @Test
    void festivalRealisteEnCaniculeNeViolateAucuneContrainteHard() throws IOException {
        assertDecoupeEtResoluSansHard("festival-realiste-canicule.yaml");
    }

    /**
     * Mirrors what {@code decoupageAuto: {}} does at import: the file's
     * créneaux are daily amplitudes, sliced into vacations before any poste
     * exists. Going through {@code construireExemple()} instead would solve
     * the raw amplitudes and test a pipeline nobody runs.
     */
    private void assertDecoupeEtResoluSansHard(String scenario) throws IOException {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        PlanningService planningService = new PlanningService(420L, 0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService,
                new FeasibilityAnalyzer(), ConfigProvider.getConfig());

        PlanningService.ReferenceScenario reference = planningService.chargerReferenceScenario(scenario);
        ParametresDecoupage parametresDecoupage = planningService.chargerParametresDecoupageScenario(scenario)
                .orElseGet(ParametresDecoupage::new);
        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.copyOf(reference.creneauxParId().values()), parametresDecoupage);
        List<Stand> stands = List.copyOf(reference.standsParId().values());
        List<PosteAffectation> postes = PlanningService.construirePostes(stands, vacations);
        PlanningFestival problem = new PlanningFestival(reference.dateDebut(), reference.animateurs(), postes);

        PlanningFestival solved = planningService.resoudreJusquaFaisabilite(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
    }
}
