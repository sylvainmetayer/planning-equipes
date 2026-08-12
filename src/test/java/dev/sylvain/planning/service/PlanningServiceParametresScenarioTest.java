package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DecoupageAutoConfig;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;

/**
 * Exercises the optional {@code parametresLegaux:} / {@code parametresDecoupage:}
 * / {@code parametresSolveur:} scenario sections: absent, a scenario keeps
 * depending on whatever is currently configured (unchanged behavior);
 * present, only the fields it names are overridden, everything else falls
 * back to the domain class's own defaults rather than to the (here DB-less)
 * ReferenceDataService.
 */
class PlanningServiceParametresScenarioTest {

    private static PlanningService service() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        return new PlanningService(3L, 2L, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
    }

    @Test
    void scenarioSansSectionNeDefinitAucunParametre() {
        PlanningService service = service();

        assertThat(service.chargerParametresLegauxScenario("scenario.yml")).isEmpty();
        assertThat(service.chargerParametresDecoupageScenario("scenario.yml")).isEmpty();
        assertThat(service.chargerParametresSolveurScenario("scenario.yml")).isEmpty();
        assertThat(service.chargerDecoupageAutoScenario("scenario.yml")).isEmpty();
    }

    @Test
    void scenarioAvecSectionPartielleNeSurchargeQueLesChampsNommes() {
        PlanningService service = service();

        ParametresLegaux legaux = service.chargerParametresLegauxScenario("scenario-parametres-optionnels.yaml")
                .orElseThrow();
        assertThat(legaux.getReposQuotidienMinimalMinutes()).isEqualTo(500);
        // Champs non mentionnés dans le YAML : valeurs par défaut de la classe,
        // pas celles de la base (ici absente).
        assertThat(legaux.getDureeHebdomadaireMaxMinutes())
                .isEqualTo(ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT);
        assertThat(legaux.getPauseMinimaleEntreVacationsMinutes())
                .isEqualTo(ParametresLegaux.PAUSE_MINIMALE_ENTRE_VACATIONS_MINUTES_PAR_DEFAUT);

        ParametresDecoupage decoupage = service
                .chargerParametresDecoupageScenario("scenario-parametres-optionnels.yaml").orElseThrow();
        assertThat(decoupage.getDureeVacationMinMinutes()).isEqualTo(250);
        assertThat(decoupage.getStrategieCouverturePendantPause())
                .isEqualTo(ParametresDecoupage.StrategieCouverturePendantPause.RELEVE);
        assertThat(decoupage.getDureeVacationMaxMinutes())
                .isEqualTo(ParametresDecoupage.DUREE_VACATION_MAX_MINUTES_PAR_DEFAUT);
        // Regression: an unquoted HH:MM:SS scalar is read by SnakeYAML as a
        // sexagesimal Number (43830 = 12*3600 + 30*60), not a String — a naive
        // (String) cast throws ClassCastException instead of parsing it.
        assertThat(decoupage.getFenetreRepasMidiDebut()).isEqualTo(LocalTime.of(12, 30));

        ParametresSolveur solveur = service.chargerParametresSolveurScenario("scenario-parametres-optionnels.yaml")
                .orElseThrow();
        assertThat(solveur.getDureeResolutionSecondes()).isEqualTo(400);
    }

    @Test
    void construireExempleAppliqueLesParametresLegauxDuScenarioQuandPresents() {
        PlanningService service = service();

        PlanningFestival festival = service.construireExemple("scenario-parametres-optionnels.yaml");

        assertThat(festival.getParametresLegaux()).hasSize(1);
        assertThat(festival.getParametresLegaux().get(0).getReposQuotidienMinimalMinutes()).isEqualTo(500);
    }

    @Test
    void scenarioAvecDecoupageAutoExposeLesNomsDeGroupes() {
        PlanningService service = service();

        DecoupageAutoConfig decoupageAuto = service.chargerDecoupageAutoScenario("scenario-decoupage-auto.yaml")
                .orElseThrow();

        assertThat(decoupageAuto.groupeSourceNom()).isEqualTo("Amplitudes import auto");
        assertThat(decoupageAuto.groupeCibleNom()).isEqualTo("Vacations import auto");
    }
}
