package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An edition solved and published with the run of days at eight, then
 * tightened to six with the hard rule on — the path of ADR 0050, on
 * {@code festival-realiste-canicule.yaml}.
 *
 * <p>The published plan breaks the new ceiling for dozens of people, so the
 * next solve must move published seats to become feasible again. It runs in
 * two stages: feasibility with the stability rule suspended, then polishing
 * with it restored. What this holds is the contract of those two stages, not a
 * convergence time: the solve ends at zero hard, the report is there, and the
 * second stage gives published seats back to their holders — the final plan
 * changes fewer of them than the first stage left changed.</p>
 *
 * <p>Tagged {@code scenario-lent}: two full solves of a 153-animateur edition.
 * See AGENTS.md's "Costly test jobs".</p>
 */
@Tag("scenario-lent")
class PlanningServiceScenarioPublishedRunCapTest {

    private static final String SCENARIO = "festival-realiste-canicule.yaml";

    /** Safety ceiling of the first solve, the one that is published; see the canicule test. */
    private static final long PUBLISHED_SOLVE_LIMIT_SECONDS = 900L;

    /** Budget of the solve under test, shared by its two stages. */
    private static final long BUDGET_SECONDS = 120L;

    /** The edition's quality parameters and toggles, which the test changes between the two solves. */
    private static final class TightenableReferenceData extends EmptyReferenceData {
        private int consecutiveDaysMax = 8;
        private boolean hardRunRule;

        @Override
        public ParametresQualite getParametresQualite() {
            ParametresQualite defaults = new ParametresQualite();
            return new ParametresQualite(
                    defaults.maxEmplacementsDistinctsParJour(),
                    defaults.heureServiceTardif(),
                    defaults.heureServiceMatinal(),
                    defaults.reposSouhaiteApresServiceTardifMinutes(),
                    defaults.typologiesDistinctesMax(),
                    consecutiveDaysMax);
        }

        @Override
        public Map<String, Boolean> getEtatsContraintes() {
            return hardRunRule ? Map.of("maxJoursConsecutifsTravaillesDur", true) : Map.of();
        }
    }

    private final List<PlanSnapshotService.AffectationSnapshot> publication = new ArrayList<>();

    @Test
    void tighteningAfterPublicationReachesFeasibilityThenGivesSeatsBack() throws IOException {
        TightenableReferenceData referenceData = new TightenableReferenceData();
        PlanSnapshotService snapshots = new PlanSnapshotService() {
            @Override
            public SnapshotDetail loadLastPublication() {
                return publication.isEmpty() ? null : new SnapshotDetail(null, publication);
            }
        };
        PlanningService planningService = new PlanningService(
                BUDGET_SECONDS,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                new FeasibilityAnalyzer(),
                null,
                snapshots,
                ConfigProvider.getConfig());

        PlanningEvenement published =
                planningService.solveUntilFeasible(build(planningService), PUBLISHED_SOLVE_LIMIT_SECONDS);
        assertThat(published.getScore().hardScore()).isZero();
        Map<String, String> holders = publish(published);

        referenceData.consecutiveDaysMax = 6;
        referenceData.hardRunRule = true;
        SolveRunner.Solved solved =
                planningService.solveReporting(startingFrom(planningService, holders), BUDGET_SECONDS, null);

        FeasibilityFirstReport report = solved.feasibilityFirst();
        assertThat(report)
                .as("a published plan under the hard rule is solved in two stages")
                .isNotNull();
        assertThat(report.feasibilityReached()).isTrue();
        assertThat(solved.planning().getScore().hardScore()).isZero();
        assertThat(report.publishedSeatsChangedAfterFeasibility())
                .as("tightening to six moves published seats")
                .isPositive();
        assertThat(report.publishedSeatsChanged())
                .as("the second stage gives published seats back")
                .isLessThan(report.publishedSeatsChangedAfterFeasibility());
    }

    /** Records the plan as the last publication, as {@code PublicationService} would. */
    private Map<String, String> publish(PlanningEvenement plan) {
        Map<String, String> holders = new HashMap<>();
        for (PosteAffectation poste : plan.getPostes()) {
            if (poste.getAnimateur() == null) {
                continue;
            }
            holders.put(poste.getId(), poste.getAnimateur().getId());
            publication.add(new PlanSnapshotService.AffectationSnapshot(
                    poste.getId(),
                    poste.getStand().getId(),
                    String.valueOf(poste.getCreneau().getId()),
                    poste.getCreneau().getDate().toString(),
                    poste.heureDebutEffectif().toString(),
                    poste.heureFinEffectif().toString(),
                    poste.getAnimateur().getId(),
                    null,
                    null));
        }
        return holders;
    }

    /** The same edition, starting from the published plan, as a solve from the saved plan does. */
    private static PlanningEvenement startingFrom(PlanningService planningService, Map<String, String> holders)
            throws IOException {
        PlanningEvenement problem = build(planningService);
        Map<String, Animateur> byId = new HashMap<>();
        problem.getAnimateurs().forEach(animateur -> byId.put(animateur.getId(), animateur));
        for (PosteAffectation poste : problem.getPostes()) {
            String holder = holders.get(poste.getId());
            poste.setAnimateur(holder == null ? null : byId.get(holder));
        }
        return problem;
    }

    /** Built as {@code PlanningServiceScenarioFestivalRealisteTest} builds it, for the same reasons. */
    private static PlanningEvenement build(PlanningService planningService) throws IOException {
        ScenarioYamlReader.ReferenceScenario reference = planningService.loadReferenceScenario(SCENARIO);
        ParametresLegaux parametresLegaux = planningService
                .loadScenarioSections(SCENARIO)
                .parametresLegaux()
                .orElseGet(ParametresLegaux::new);
        List<Creneau> vacations = List.copyOf(reference.creneauxParId().values());
        List<Stand> stands = List.copyOf(reference.standsById().values());
        HoraireStandResolver.apply(stands, vacations);
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, vacations);
        PlanningEvenement problem = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes);
        problem.setFenetresRepas(FenetreRepas.from(parametresLegaux));
        problem.setParametresLegaux(List.of(parametresLegaux));
        return problem;
    }
}
