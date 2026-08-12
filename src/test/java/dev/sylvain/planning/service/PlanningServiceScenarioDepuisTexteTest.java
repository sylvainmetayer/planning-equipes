package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import java.time.LocalDate;
import java.time.LocalTime;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;

/**
 * {@link PlanningService#construireDepuisTexteScenario}: the "Importer un
 * fichier" button on the Scénarios page uploads a scenario YAML file (same
 * shape as {@code src/main/resources/scenarios/*.yaml}) instead of naming a
 * bundled one — this must parse an uploaded file's raw text into the exact
 * same result {@code construireExemple}/{@code chargerParametresXScenario}
 * produce for a classpath scenario, and turn a malformed file into a
 * readable {@link IllegalArgumentException} rather than a raw parser
 * exception.
 */
class PlanningServiceScenarioDepuisTexteTest {

    private static PlanningService service() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        return new PlanningService(3L, 2L, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
    }

    private static String scenarioYamlText(String fileName) {
        try (InputStream inputStream = PlanningServiceScenarioDepuisTexteTest.class.getClassLoader()
                .getResourceAsStream("scenarios/" + fileName)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void construitLeMemePlanningQueConstruireExemple() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario.yml");

        PlanningFestival depuisTexte = service.construireDepuisTexteScenario(yaml).planning();
        PlanningFestival depuisNom = service.construireExemple("scenario.yml");

        assertThat(depuisTexte.getAnimateurs()).hasSameSizeAs(depuisNom.getAnimateurs());
        assertThat(depuisTexte.getPostes()).hasSameSizeAs(depuisNom.getPostes());
        assertThat(depuisTexte.getDateDebutFestival()).isEqualTo(depuisNom.getDateDebutFestival());
    }

    @Test
    void appliqueLesParametresOptionnelsDuFichierQuandPresents() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-parametres-optionnels.yaml");

        PlanningService.ScenarioImporte importe = service.construireDepuisTexteScenario(yaml);

        assertThat(importe.parametresLegaux()).isPresent();
        assertThat(importe.parametresLegaux().orElseThrow().getReposQuotidienMinimalMinutes()).isEqualTo(500);
        assertThat(importe.parametresDecoupage()).isPresent();
        assertThat(importe.parametresSolveur()).isPresent();
        assertThat(importe.parametresSolveur().orElseThrow().getDureeResolutionSecondes()).isEqualTo(400);
        assertThat(importe.decoupageAuto()).isEmpty();
    }

    @Test
    void appliqueLaSectionDecoupageAutoDuFichierQuandPresente() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-decoupage-auto.yaml");

        PlanningService.ScenarioImporte importe = service.construireDepuisTexteScenario(yaml);

        assertThat(importe.decoupageAuto()).isPresent();
        assertThat(importe.decoupageAuto().orElseThrow().groupeSourceNom()).isEqualTo("Amplitudes import auto");
        assertThat(importe.decoupageAuto().orElseThrow().groupeCibleNom()).isEqualTo("Vacations import auto");
    }

    @Test
    void chargeNiveauEffortIndisponibilitesEtOuverturesDuStand() {
        PlanningService service = service();
        String yaml = """
                festival:
                  dateDebut: 2026-08-14

                creneaux:
                  - id: J1-MATIN
                    jour: 1
                    date: 2026-08-14
                    heureDebut: "09:00"
                    heureFin: "13:00"

                stands:
                  - id: HOMME-JEU
                    nom: Homme-jeu
                    typologiesProposees:
                      - HOMME_JEU
                    effectifMin: 1
                    effectifMax: 1
                    reserveMajeurs: false
                    premium: false
                    niveauEffort: EPUISANT
                    indisponibilites:
                      - date: 2026-08-14
                        heureDebut: "14:00"
                        heureFin: "16:00"
                        motif: Pause
                    ouvertures:
                      - date: 2026-08-15
                        heureDebut: "20:00"
                        heureFin: "23:00"
                        motif: null

                animateurs:
                  - id: A1
                    prenom: Alice
                    nom: Referente
                    dateNaissance: 2002-07-19
                    manager: false
                    competences:
                      HOMME_JEU: REFERENT
                    joursIndisponibles: []

                postes:
                  - id: P1
                    standId: HOMME-JEU
                    creneauId: J1-MATIN
                    animateurId: null
                """;

        PlanningFestival planning = service.construireDepuisTexteScenario(yaml).planning();
        Stand stand = planning.getPostes().get(0).getStand();

        assertThat(stand.getNiveauEffort()).isEqualTo(NiveauEffort.EPUISANT);
        assertThat(stand.getIndisponibilites()).hasSize(1);
        assertThat(stand.getIndisponibilites().get(0).getDate()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(stand.getIndisponibilites().get(0).getHeureDebut()).isEqualTo(LocalTime.of(14, 0));
        assertThat(stand.getIndisponibilites().get(0).getHeureFin()).isEqualTo(LocalTime.of(16, 0));
        assertThat(stand.getIndisponibilites().get(0).getMotif()).isEqualTo("Pause");
        assertThat(stand.getOuvertures()).hasSize(1);
        assertThat(stand.getOuvertures().get(0).getDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(stand.getOuvertures().get(0).getHeureDebut()).isEqualTo(LocalTime.of(20, 0));
        assertThat(stand.getOuvertures().get(0).getHeureFin()).isEqualTo(LocalTime.of(23, 0));
    }

    @Test
    void defautNiveauEffortNormalQuandAbsentDuScenario() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario.yml");

        PlanningFestival planning = service.construireDepuisTexteScenario(yaml).planning();

        assertThat(planning.getPostes()).isNotEmpty();
        assertThat(planning.getPostes().get(0).getStand().getNiveauEffort()).isEqualTo(NiveauEffort.NORMAL);
    }

    @Test
    void rejetteUnFichierVideAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vide");
        assertThatThrownBy(() -> service.construireDepuisTexteScenario(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejetteUnYamlMalformeAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario("festival: [unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("YAML invalide");
    }

    @Test
    void rejetteUnScenarioAvecUneSectionManquanteAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.construireDepuisTexteScenario("festival:\n  dateDebut: 2026-07-01\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Scénario invalide");
    }
}
