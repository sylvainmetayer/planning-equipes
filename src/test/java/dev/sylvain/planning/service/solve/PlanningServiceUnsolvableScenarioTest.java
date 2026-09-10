package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.AnomalyType;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * {@code scenarios/scenario-avec-erreur-planning.yaml} is the one shipped scenario
 * that is <b>deliberately unsolvable</b>: a teaching fixture whose six stands
 * each carry one classic opening mistake, so an operator can see what each
 * screen says about it.
 *
 * <p>What this test pins is that intent, and nothing narrower. It must stay
 * true that the file is <em>accepted</em> (a scenario rejected at import
 * demonstrates nothing, it never reaches the solver) and that it can
 * <em>never</em> be solved without a hard violation. It deliberately asserts
 * neither a score nor a violation count: those move with every constraint
 * change, and freezing them would turn a fixture meant to illustrate into a
 * fixture that has to be maintained.</p>
 */
class PlanningServiceUnsolvableScenarioTest {

    private static final String SCENARIO = "scenario-avec-erreur-planning.yaml";

    private static PlanningService service() {
        return new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());
    }

    private static String yaml() {
        try (InputStream inputStream = PlanningServiceUnsolvableScenarioTest.class
                .getClassLoader()
                .getResourceAsStream("scenarios/" + SCENARIO)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stands and créneaux as the screens see them: rules already expanded. */
    private static ScenarioYamlReader.ReferenceScenario reference() throws IOException {
        ScenarioYamlReader.ReferenceScenario reference = service().loadReferenceScenario(SCENARIO);
        HoraireStandResolver.apply(
                reference.standsById().values(), reference.creneauxParId().values());
        return reference;
    }

    @Test
    void theFixtureIsAcceptedByTheImportValidator() throws IOException {
        assertThat(ScenarioValidator.validate(yaml())).isEmpty();

        PlanningEvenement planning = service().buildFromScenarioText(yaml()).planning();

        assertThat(planning.getAnimateurs()).hasSize(5);
        assertThat(planning.getPostes()).isNotEmpty();
    }

    /**
     * The structural reason no solve can succeed: one créneau alone asks for
     * more seats than there are animateurs alive that day, so
     * {@code posteDoitEtrePourvu} (hard) is violated whatever the assignment.
     */
    @Test
    void theFixtureIsUnsolvableByConstructionNotByBadLuck() throws IOException {
        ScenarioYamlReader.ReferenceScenario reference = reference();
        List<Stand> stands = new ArrayList<>(reference.standsById().values());
        List<Creneau> creneaux = new ArrayList<>(reference.creneauxParId().values());

        FeasibilityAnalyzer.FeasibilityReport report =
                new FeasibilityAnalyzer().analyze(reference.animateurs(), stands, creneaux);

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isPositive();
    }

    /**
     * The three anomalies of the "Ouvertures des stands" screen, one stand
     * each, so the demo shows them isolated rather than piled up.
     */
    @Test
    void everyOpeningAnomalyOfTheScreenIsRepresentedOnItsOwnStand() throws IOException {
        ScenarioYamlReader.ReferenceScenario reference = reference();
        RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(
                new ArrayList<>(reference.standsById().values()),
                new ArrayList<>(reference.creneauxParId().values()));

        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.STAND_JAMAIS_OUVERT)
                .singleElement()
                .satisfies(anomalie -> assertThat(anomalie.standId()).isEqualTo("STAND-3"));
        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.SEGMENT_TROP_COURT)
                .singleElement()
                .satisfies(anomalie -> assertThat(anomalie.standId()).isEqualTo("STAND-5"));
        assertThat(rapport.anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.FENETRE_SANS_EFFET)
                .extracting(OuvertureStandsAnalyzer.Anomaly::standId)
                .containsOnly("STAND-1", "STAND-3");
    }

    /**
     * A dated exception replaces the recurring rule of the day it names — the
     * mistake Stand 4 is there to show: its last day loses the morning and the
     * afternoon the rule opened, keeping only the nocturne that was meant to
     * be added to them.
     */
    @Test
    void aDatedExceptionReplacesTheRuleOfItsDayOnStandFour() throws IOException {
        ScenarioYamlReader.ReferenceScenario reference = reference();
        RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(
                new ArrayList<>(reference.standsById().values()),
                new ArrayList<>(reference.creneauxParId().values()));

        OuvertureStandsAnalyzer.LigneStand ligne = rapport.stands().stream()
                .filter(stand -> stand.standId().equals("STAND-4"))
                .findFirst()
                .orElseThrow();

        assertThat(ligne.jours()).hasSize(3);
        assertThat(ligne.jours().get(2).fenetres())
                .singleElement()
                .satisfies(fenetre -> assertThat(fenetre.heureDebut()).isEqualTo(LocalTime.of(18, 0)));
    }

    /**
     * The end of the demo: a real solve, run past the construction heuristic,
     * still cannot reach a feasible planning.
     */
    @Test
    void aRealSolveCannotReachAFeasiblePlanning() {
        PlanningService service = service();
        PlanningEvenement solved =
                service.solve(service.buildFromScenarioText(yaml()).planning(), 3L);

        assertThat(solved.getScore().hardScore()).isNegative();
    }
}
