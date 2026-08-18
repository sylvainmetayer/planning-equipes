package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
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
        return new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
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
        // Découpage par familles décalées : le paramétrage qui rend un scénario
        // dense faisable (voir docs/optimisation-solveur.md) doit pouvoir être
        // épinglé dans le scénario lui-même, pas seulement réglé à la main
        // après import.
        assertThat(decoupage.getNombreFamillesDecalage()).isEqualTo(3);
        assertThat(decoupage.getDureeDecalageMaxMinutes()).isEqualTo(75);

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

    /**
     * Un nom absent ou vide (import sans scénario sélectionné) doit retomber
     * sur le scénario par défaut, comme {@code construireExemple} : concaténé
     * tel quel il visait {@code scenarios/null}, ou pire le dossier
     * {@code scenarios/} lui-même, dont le listing se parse en simple chaîne
     * YAML et cassait l'import en ClassCastException.
     */
    @Test
    void nomDeScenarioAbsentOuVideRetombeSurLeScenarioParDefaut() {
        PlanningService service = service();

        for (String nom : new String[] { null, "", "   " }) {
            assertThat(service.chargerParametresLegauxScenario(nom).isPresent())
                    .isEqualTo(service.chargerParametresLegauxScenario(PlanningService.DEFAULT_SCENARIO).isPresent());
            assertThat(service.chargerParametresDecoupageScenario(nom)).isNotNull();
            assertThat(service.chargerParametresSolveurScenario(nom)).isNotNull();
            assertThat(service.chargerDecoupageAutoScenario(nom)).isNotNull();
            assertThat(service.chargerTypologiesScenario(nom))
                    .isEqualTo(service.chargerTypologiesScenario(PlanningService.DEFAULT_SCENARIO));
        }
    }

    @Test
    void nomDeScenarioAvecComposantDeCheminEstRejete() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.chargerParametresLegauxScenario("../application.properties"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nom de scénario invalide");
    }
}
