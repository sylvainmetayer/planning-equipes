package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The meal bound of {@code StaffingAnalyzer} against the fixtures that are
 * known to reach zero hard: on those, a floor above the roster would be a
 * false alarm — the very grids the tightened bound of issue #482 must not
 * disturb.
 *
 * <p>No solve here, only the seats: the floor is read the way the Besoin
 * screen reads it, from the seats the file describes and the meal windows its
 * own legal parameters carry. That is what makes this test cheap enough to
 * stay in the default run while {@code PlanningServiceScenario*Test} — the
 * ones that actually prove the zero hard those rosters achieve — stay tagged
 * {@code scenario-lent}.</p>
 */
class ScenarioPlancherRepasTest {

    private final StaffingAnalyzer analyzer = new StaffingAnalyzer();

    /**
     * {@code festival-hivernal} is the anonymised original of the grid issue
     * #482 was reported on, entered in vacations, with the 20:00-21:00 evening
     * window. Its evenings do leave holes — stands close at 19:00 or 20:00 and
     * the late ones reopen at 21:00 — so no seat covers the whole window while
     * running past it, and the floor stays under the 153 animateurs that solve
     * it.
     */
    @Test
    void lePlancherDuFestivalHivernalResteSousSonEffectif() {
        assertPlancherSousEffectif("festival-hivernal");
    }

    /** Same event, sliced by the découpage, with the 17:00-18:00 window it declares. */
    @Test
    void lePlancherDuFestivalRealisteResteSousSonEffectif() {
        assertPlancherSousEffectif("festival-realiste-canicule");
    }

    /** The hand-built performance target: midday rotation 12-13 / 13-14, and a roster that solves it. */
    @Test
    void lePlancherDuScenarioCompletResteSousSonEffectif() {
        assertPlancherSousEffectif("scenario-complet");
    }

    private void assertPlancherSousEffectif(String scenario) {
        ScenarioLadder.Loaded loaded = ScenarioLadder.load(scenario);
        int effectif = loaded.problem().getAnimateurs().size();
        assertThat(effectif).isPositive();

        StaffingSummary summary = analyzer.analyze(
                loaded.problem().getPostes(),
                loaded.problem().getAnimateurs(),
                List.of(),
                parametres(loaded).getDureeHebdomadaireMaxMinutes(),
                parametres(loaded).getPauseMinimaleEntreVacationsMinutes(),
                List.of(),
                fenetres(loaded));

        assertThat(fenetres(loaded)).as("%s declares meal windows", scenario).isNotEmpty();
        assertThat(summary.picRepas())
                .as("%s: meal floor against its %d animateurs", scenario, effectif)
                .isLessThanOrEqualTo(effectif);
    }

    private static ParametresLegaux parametres(ScenarioLadder.Loaded loaded) {
        return loaded.problem().getParametresLegaux().getFirst();
    }

    /** What {@code StaffingService} hands the analyzer: no window when the rule is switched off. */
    private static List<FenetreRepas> fenetres(ScenarioLadder.Loaded loaded) {
        boolean eteinte = loaded.problem().getConstraintsDesactivees().stream()
                .map(ConstraintToggle::getNom)
                .anyMatch("coupureRepasObligatoire"::equals);
        return eteinte ? List.of() : FenetreRepas.from(parametres(loaded));
    }
}
