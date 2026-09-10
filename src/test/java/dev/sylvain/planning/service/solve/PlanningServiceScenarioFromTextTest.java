package dev.sylvain.planning.service.solve;

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

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.referentiel.ReferenceData;

/**
 * {@link PlanningService#buildFromScenarioText}: the "Importer un
 * file" button on the Scénarios page uploads a scenario YAML file (same
 * shape as {@code src/main/resources/scenarios/*.yaml}) instead of naming a
 * bundled one — this must parse an uploaded file's raw text into the exact
 * same result {@code buildExample}/{@code chargerParametresXScenario}
 * produce for a classpath scenario, and turn a malformed file into a
 * readable {@link IllegalArgumentException} rather than a raw parser
 * exception.
 */
class PlanningServiceScenarioFromTextTest {

    private static PlanningService service() {
        ReferenceData referenceDataService = new EmptyReferenceData();
        return new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
    }

    private static String scenarioYamlText(String fileName) {
        try (InputStream inputStream = PlanningServiceScenarioFromTextTest.class.getClassLoader()
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

        PlanningEvenement depuisTexte = service.buildFromScenarioText(yaml).planning();
        PlanningEvenement depuisNom = service.buildExample("scenario.yml");

        assertThat(depuisTexte.getAnimateurs()).hasSameSizeAs(depuisNom.getAnimateurs());
        assertThat(depuisTexte.getPostes()).hasSameSizeAs(depuisNom.getPostes());
        assertThat(depuisTexte.getDateDebutFestival()).isEqualTo(depuisNom.getDateDebutFestival());
    }

    @Test
    void appliqueLesParametresOptionnelsDuFichierQuandPresents() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-parametres-optionnels.yaml");

        ScenarioYamlReader.ScenarioImporte importe = service.buildFromScenarioText(yaml);

        assertThat(importe.sections().parametresLegaux()).isPresent();
        assertThat(importe.sections().parametresLegaux().orElseThrow().getReposQuotidienMinimalMinutes()).isEqualTo(500);
        assertThat(importe.sections().parametresDecoupage()).isPresent();
        assertThat(importe.sections().parametresSolveur()).isPresent();
        assertThat(importe.sections().parametresSolveur().orElseThrow().dureeResolutionSecondes()).isEqualTo(400);
        assertThat(importe.sections().decoupageAuto()).isFalse();
    }

    /**
     * The tuning of the catalogue travels with the scenario: which rules are
     * off, and what the others weigh. Without it, a file exported from an
     * edition that had disabled a rule re-imported elsewhere as if nothing had
     * happened, and the "same" scenario solved a different problem.
     */
    @Test
    void appliqueLaSectionContraintesDuFichierQuandPresente() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-contraintes.yaml");

        ScenarioYamlReader.ScenarioSections sections = service.buildFromScenarioText(yaml).sections();

        assertThat(sections.contraintes()).isPresent();
        assertThat(sections.contraintes().orElseThrow().desactivees())
                .containsExactly("eviterRoulementStandsPremium");
        assertThat(sections.contraintes().orElseThrow().poids())
                .containsEntry("equilibrerCharge", 7)
                .containsEntry("maxJoursConsecutifsTravailles", 3);
    }

    /**
     * A name absent from the catalogue is refused rather than ignored: it is
     * either a typo or a file written against another version of the
     * catalogue, and dropping it silently would leave the operator convinced a
     * rule was switched off when it never was.
     */
    @Test
    void refuseUneContrainteInconnueDansLaSectionContraintes() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-contraintes.yaml")
                .replace("eviterRoulementStandsPremium", "reglePasDansLeCatalogue");

        assertThatThrownBy(() -> service.buildFromScenarioText(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reglePasDansLeCatalogue");
    }

    /**
     * The hand-entered constraints come from the file when it carries them —
     * not from the database — so re-importing a scenario reproduces exactly
     * the problem it describes instead of merging somebody else's.
     */
    @Test
    void chargeLesContraintesAdHocDuFichier() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-contraintes.yaml");

        PlanningEvenement planning = service.buildFromScenarioText(yaml).planning();

        assertThat(planning.getContraintesAdHoc()).hasSize(2);
        ContrainteAdHoc incompatibilite = planning.getContraintesAdHoc().get(0);
        assertThat(incompatibilite.getId()).isEqualTo("INCOMPAT-1");
        assertThat(incompatibilite.getType()).isEqualTo(TypeContrainteAdHoc.INCOMPATIBILITE);
        assertThat(incompatibilite.getAnimateursConcernes()).extracting(Animateur::getId)
                .containsExactly("A1", "A2");
        assertThat(incompatibilite.getCreneau()).isNull();

        ContrainteAdHoc indisponibilite = planning.getContraintesAdHoc().get(1);
        assertThat(indisponibilite.getStand().getId()).isEqualTo("STAND-STRAT");
        // Resolved against the file's own créneau id, so the import can remap
        // it to the database id the créneau gets on the way in.
        assertThat(indisponibilite.getCreneau()).isNotNull();
        assertThat(indisponibilite.getRaison()).isEqualTo("Formation");
    }

    @Test
    void refuseUneContrainteAdHocVisantUnAnimateurAbsent() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-contraintes.yaml").replace("      - A2\n    raison: Ne", "      - A9\n    raison: Ne");

        assertThatThrownBy(() -> service.buildFromScenarioText(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A9");
    }

    @Test
    void appliqueLaSectionDecoupageAutoDuFichierQuandPresente() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-decoupage-auto.yaml");

        ScenarioYamlReader.ScenarioImporte importe = service.buildFromScenarioText(yaml);

        assertThat(importe.sections().decoupageAuto()).isTrue();
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
                        effectif: 3

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

        PlanningEvenement planning = service.buildFromScenarioText(yaml).planning();
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
        // An opening names its own headcount, like a rule's window does; absent, it
        // falls back on the stand's minimum, and it must survive the round trip.
        assertThat(stand.getOuvertures().get(0).getEffectif()).isEqualTo(3);
    }

    @Test
    void defautNiveauEffortNormalQuandAbsentDuScenario() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario.yml");

        PlanningEvenement planning = service.buildFromScenarioText(yaml).planning();

        assertThat(planning.getPostes()).isNotEmpty();
        assertThat(planning.getPostes().get(0).getStand().getNiveauEffort()).isEqualTo(NiveauEffort.NORMAL);
    }

    @Test
    void genereLesPostesQuandLaSectionEstAbsenteDuFichier() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-sans-postes.yaml");

        PlanningEvenement planning = service.buildFromScenarioText(yaml).planning();

        // 2 timeslots x (STAND-A effectifMin 2 + STAND-B effectifMin 1) = 6.
        assertThat(planning.getPostes()).hasSize(6);
        assertThat(planning.getPostes()).filteredOn(poste -> "STAND-A".equals(poste.getStand().getId())).hasSize(4);
        assertThat(planning.getPostes()).filteredOn(poste -> "STAND-B".equals(poste.getStand().getId())).hasSize(2);
    }

    @Test
    void rejetteUnFichierVideAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.buildFromScenarioText(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vide");
        assertThatThrownBy(() -> service.buildFromScenarioText(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejetteUnYamlMalformeAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.buildFromScenarioText("festival: [unclosed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("YAML invalide");
    }

    @Test
    void rejetteUnScenarioAvecUneSectionManquanteAvecUnMessageLisible() {
        PlanningService service = service();

        assertThatThrownBy(() -> service.buildFromScenarioText("festival:\n  dateDebut: 2026-07-01\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Scénario invalide");
    }
    /**
     * A scenario stating its schedule as recurring {@code horaires:} rules rather
     * than as one dated window per event day. Everything downstream — which
     * segments of a créneau a stand is open for, hence which postes exist —
     * has to come out exactly as if the windows had been written out by hand.
     */
    @Test
    void etendLesHorairesRecurrentsALImport() {
        PlanningService service = service();
        String yaml = scenarioYamlText("scenario-horaires-recurrents.yaml");

        PlanningEvenement planning = service.buildFromScenarioText(yaml).planning();

        // BOURSE: "10:00-12:00 then 14:00 until closing time, every day" opens
        // each of the five timeslots entirely — the open-ended window adapts to
        // the day closing at 20:00 as well as to the one closing at midnight.
        assertThat(postesOf(planning, "BOURSE")).hasSize(5);
        assertThat(postesOf(planning, "BOURSE"))
                .allSatisfy(poste -> assertThat(poste.getHeureFinEffective()).isNull());

        // PODIUM: open in the afternoon by the rule, except on the 9th where a
        // dated exception closes it — and that one wins, hence no seat that day.
        assertThat(postesOf(planning, "PODIUM")).hasSize(2);
        assertThat(postesOf(planning, "PODIUM"))
                .extracting(poste -> poste.getCreneau().getDate())
                .containsExactlyInAnyOrder(LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 10));

        // MEDIATHEQUE: closed 10:00-12:00 on Wednesdays only. 8 July is a
        // Wednesday, the 9th a Thursday: the morning timeslot disappears on the
        // first day and survives on the second.
        assertThat(creneauxOf(planning, "MEDIATHEQUE"))
                .doesNotContain(entree(LocalDate.of(2026, 7, 8), LocalTime.of(10, 0)))
                .contains(entree(LocalDate.of(2026, 7, 9), LocalTime.of(10, 0)));
        assertThat(postesOf(planning, "MEDIATHEQUE")).hasSize(4);
    }

    private static java.util.List<PosteAffectation> postesOf(PlanningEvenement planning, String standId) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getStand().getId().equals(standId))
                .toList();
    }

    private static java.util.List<String> creneauxOf(PlanningEvenement planning, String standId) {
        return postesOf(planning, standId).stream()
                .map(poste -> entree(poste.getCreneau().getDate(), poste.getCreneau().getHeureDebut()))
                .toList();
    }

    private static String entree(LocalDate date, LocalTime heureDebut) {
        return date + " " + heureDebut;
    }
}
