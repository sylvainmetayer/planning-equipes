package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
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
        Referentiel referenceDataService = new ReferentielVide();
        return new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
    }

    @Test
    void scenarioSansSectionNeDefinitAucunParametre() {
        PlanningService service = service();

        assertThat(service.chargerSectionsScenario("scenario.yml").parametresLegaux()).isEmpty();
        assertThat(service.chargerSectionsScenario("scenario.yml").parametresDecoupage()).isEmpty();
        assertThat(service.chargerSectionsScenario("scenario.yml").parametresSolveur()).isEmpty();
        assertThat(service.chargerSectionsScenario("scenario.yml").decoupageAuto()).isFalse();
    }

    @Test
    void scenarioAvecSectionPartielleNeSurchargeQueLesChampsNommes() {
        PlanningService service = service();

        ParametresLegaux legaux = service.chargerSectionsScenario("scenario-parametres-optionnels.yaml").parametresLegaux()
                .orElseThrow();
        assertThat(legaux.getReposQuotidienMinimalMinutes()).isEqualTo(500);
        // Champs non mentionnés dans le YAML : valeurs par défaut de la classe,
        // pas celles de la base (ici absente).
        assertThat(legaux.getDureeHebdomadaireMaxMinutes())
                .isEqualTo(ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT);
        assertThat(legaux.getPauseMinimaleEntreVacationsMinutes())
                .isEqualTo(ParametresLegaux.PAUSE_MINIMALE_ENTRE_VACATIONS_MINUTES_PAR_DEFAUT);

        ParametresDecoupage decoupage = service
                .chargerSectionsScenario("scenario-parametres-optionnels.yaml").parametresDecoupage().orElseThrow();
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

        ParametresSolveur solveur = service.chargerSectionsScenario("scenario-parametres-optionnels.yaml").parametresSolveur()
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
    void scenarioAvecDecoupageAutoEstDetecte() {
        PlanningService service = service();

        // Les anciens champs groupeSourceNom/groupeCibleNom du fichier sont
        // acceptés et ignorés (issue #172) : seule la présence de la section compte.
        assertThat(service.chargerSectionsScenario("scenario-decoupage-auto.yaml").decoupageAuto()).isTrue();
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
            assertThat(service.chargerSectionsScenario(nom).parametresLegaux().isPresent())
                    .isEqualTo(service.chargerSectionsScenario(PlanningService.DEFAULT_SCENARIO).parametresLegaux().isPresent());
            assertThat(service.chargerSectionsScenario(nom).parametresDecoupage()).isNotNull();
            assertThat(service.chargerSectionsScenario(nom).parametresSolveur()).isNotNull();
            assertThat(service.chargerSectionsScenario(nom).decoupageAuto()).isEqualTo(
                    service.chargerSectionsScenario(PlanningService.DEFAULT_SCENARIO).decoupageAuto());
            assertThat(service.chargerSectionsScenario(nom).typologies())
                    .isEqualTo(service.chargerSectionsScenario(PlanningService.DEFAULT_SCENARIO).typologies());
        }
    }

    @Test
    void nomDeScenarioAvecComposantDeCheminEstRejete() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.chargerSectionsScenario("../application.properties"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nom de scénario invalide");
    }
}
