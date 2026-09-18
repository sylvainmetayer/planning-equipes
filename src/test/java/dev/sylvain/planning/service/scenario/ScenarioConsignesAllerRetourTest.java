package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.consigne.ConsigneService.VacationRef;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@code consignes:} and {@code prereglagesConsigne:} sections (ADR
 * 0043), written by the export and read by the import — the same joint
 * {@code ScenarioJourneesTypesAllerRetourTest} holds for the day templates.
 *
 * <p>The one thing that cannot travel as-is is the list of créneaux a
 * consigne added: the file carries no créneau id, since the import re-numbers
 * the {@code creneaux:} section. They are written by day and hours and come
 * back as such, for the import to tie to the créneaux it has just written.</p>
 */
class ScenarioConsignesAllerRetourTest {

    private static final LocalDate JOUR = LocalDate.of(2027, 7, 12);

    private static final ConsigneEdition.RepasConsigne REPAS = new ConsigneEdition.RepasConsigne(
            null, null, LocalTime.of(18, 0), LocalTime.of(22, 0), null, "Repas pris pendant la bande fermée");

    @Test
    void theSectionsWrittenByTheExportAreReadBackByTheImport() {
        Creneau apresMidi = creneau(1L, LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau soir = creneau(2L, LocalTime.of(18, 0), LocalTime.of(20, 0));
        ConsigneEdition consigne = new ConsigneEdition(
                JOUR,
                LocalTime.of(12, 0),
                LocalTime.of(16, 0),
                "Arrêté préfectoral canicule",
                "Plan canicule",
                List.of(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), LocalTime.of(20, 0))),
                List.of(new ConsigneEdition.Ouverture("S1", LocalTime.of(18, 0), LocalTime.of(20, 0), 2)),
                List.of(2L),
                Instant.EPOCH,
                Instant.EPOCH,
                REPAS);
        PrereglageConsigne prereglage = new PrereglageConsigne(
                "p-1",
                "Plan canicule",
                LocalTime.of(12, 0),
                LocalTime.of(16, 0),
                "Arrêté préfectoral canicule",
                List.of(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), null)),
                Instant.EPOCH,
                REPAS);

        String yaml = export(List.of(apresMidi, soir), List.of(consigne), List.of(prereglage));

        assertThat(yaml)
                .contains("prereglagesConsigne:")
                .contains("consignes:")
                .contains("creneauxAjoutes:")
                .contains("justification: Repas pris pendant la bande fermée")
                // The added créneau travels by its key, never by the id 2.
                .doesNotContain("creneauxAjoutes:\n  - '2'")
                .doesNotContain("creneauxAjoutes:\n  - 2\n");
        assertThat(ScenarioValidator.validate(yaml)).isEmpty();

        ScenarioYamlReader.ScenarioSections relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new)
                .sections();

        List<ScenarioYamlReader.ConsigneScenario> consignes = relu.consignes().orElseThrow();
        assertThat(consignes).hasSize(1);
        ConsigneEdition lue = consignes.getFirst().consigne();
        assertThat(lue.date()).isEqualTo(JOUR);
        assertThat(lue.bande()).isEqualTo(new ConsigneEdition.Fenetre(LocalTime.of(12, 0), LocalTime.of(16, 0)));
        assertThat(lue.motif()).isEqualTo("Arrêté préfectoral canicule");
        assertThat(lue.prereglage()).isEqualTo("Plan canicule");
        assertThat(lue.fenetres())
                .containsExactly(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), LocalTime.of(20, 0)));
        assertThat(lue.ouvertures())
                .containsExactly(new ConsigneEdition.Ouverture("S1", LocalTime.of(18, 0), LocalTime.of(20, 0), 2));
        assertThat(lue.repas()).isEqualTo(REPAS);
        // Empty on the consigne itself: the ids are the import's to assign.
        assertThat(lue.creneauxAjoutes()).isEmpty();
        assertThat(consignes.getFirst().creneauxAjoutes())
                .containsExactly(new VacationRef(JOUR, LocalTime.of(18, 0), LocalTime.of(20, 0)));

        List<PrereglageConsigne> prereglages = relu.prereglagesConsigne().orElseThrow();
        assertThat(prereglages).hasSize(1);
        assertThat(prereglages.getFirst().id()).isEqualTo("p-1");
        assertThat(prereglages.getFirst().nom()).isEqualTo("Plan canicule");
        assertThat(prereglages.getFirst().fenetres())
                .containsExactly(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), null));
        assertThat(prereglages.getFirst().repas()).isEqualTo(REPAS);
    }

    @Test
    void aFileWithoutTheSectionsHasNone() {
        String yaml = ScenarioYamlWriter.buildScenarioYaml(
                List.of(animateur()),
                List.of(stand()),
                List.of(creneau(1L, LocalTime.of(9, 0), LocalTime.of(12, 0))),
                List.of());

        assertThat(yaml).doesNotContain("consignes").doesNotContain("prereglagesConsigne");
        ScenarioYamlReader.ScenarioSections relu = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new)
                .sections();
        assertThat(relu.consignes()).isEmpty();
        assertThat(relu.prereglagesConsigne()).isEmpty();
    }

    /** A consigne that restates nothing about meals is written without the block, and read back without it. */
    @Test
    void aConsigneUnderTheEditionsMealWindowsCarriesNoRepasBlock() {
        Creneau apresMidi = creneau(1L, LocalTime.of(14, 0), LocalTime.of(18, 0));
        ConsigneEdition consigne = new ConsigneEdition(
                JOUR, LocalTime.of(12, 0), null, "Orage", null, List.of(), List.of(), List.of(), null, null, null);

        String yaml = export(List.of(apresMidi), List.of(consigne), List.of());

        assertThat(yaml).doesNotContain("repas:").doesNotContain("fermetureFin");
        ConsigneEdition lue = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new)
                .sections()
                .consignes()
                .orElseThrow()
                .getFirst()
                .consigne();
        assertThat(lue.repas()).isNull();
        assertThat(lue.fermetureFin()).isNull();
        assertThat(lue.prereglage()).isNull();
    }

    /** An added créneau the export does not carry is dropped, as an ad hoc constraint's scope is — never written blind. */
    @Test
    void anAddedCreneauTheExportDoesNotCarryIsDropped() {
        Creneau apresMidi = creneau(1L, LocalTime.of(14, 0), LocalTime.of(18, 0));
        ConsigneEdition consigne = new ConsigneEdition(
                JOUR, LocalTime.of(12, 0), null, "Orage", null, List.of(), List.of(), List.of(99L), null, null, null);

        String yaml = export(List.of(apresMidi), List.of(consigne), List.of());

        assertThat(yaml).contains("creneauxAjoutes: []").doesNotContain("heureDebut: '18:00'");
    }

    @Test
    void anOpeningOnAStandTheFileDoesNotDeclareIsRefused() {
        String yaml = MINIMAL + """
                consignes:
                  - date: 2027-07-12
                    fermetureDebut: "12:00"
                    motif: Orage
                    ouvertures:
                      - standId: S9
                        debut: "18:00"
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("S9")
                .hasMessageContaining("stands");
    }

    @Test
    void anAddedCreneauTheFileDoesNotListIsRefused() {
        String yaml = MINIMAL + """
                consignes:
                  - date: 2027-07-12
                    fermetureDebut: "12:00"
                    motif: Orage
                    creneauxAjoutes:
                      - date: 2027-07-12
                        heureDebut: "18:00"
                        heureFin: "20:00"
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("18:00-20:00")
                .hasMessageContaining("creneaux");
    }

    @Test
    void aConsigneOnADateWithoutCreneauIsRefused() {
        String yaml = MINIMAL + """
                consignes:
                  - date: 2027-07-13
                    fermetureDebut: "12:00"
                    motif: Orage
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("2027-07-13");
    }

    @Test
    void restatedMealWindowsWithoutAReasonAreRefused() {
        String yaml = MINIMAL + """
                prereglagesConsigne:
                  - nom: Plan canicule
                    fermetureDebut: "12:00"
                    motif: Orage
                    repas:
                      soirDebut: "18:00"
                      soirFin: "22:00"
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("justification");
    }

    @Test
    void aBandEndingBeforeItStartsIsRefused() {
        String yaml = MINIMAL + """
                consignes:
                  - date: 2027-07-12
                    fermetureDebut: "16:00"
                    fermetureFin: "12:00"
                    motif: Orage
                """;

        assertThatThrownBy(() -> ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("bande");
    }

    /** A preset written by hand needs no id: the import draws one. */
    @Test
    void aPresetWrittenWithoutAnIdReadsBackWithoutOne() {
        String yaml = MINIMAL + """
                prereglagesConsigne:
                  - nom: Plan canicule
                    fermetureDebut: "12:00"
                    fermetureFin: "18:00"
                    motif: Arrêté préfectoral
                    fenetres:
                      - debut: "18:00"
                        fin: "22:00"
                """;

        List<PrereglageConsigne> prereglages = ScenarioYamlReader.buildFromScenarioText(yaml, ParametresLegaux::new)
                .sections()
                .prereglagesConsigne()
                .orElseThrow();

        assertThat(prereglages).hasSize(1);
        assertThat(prereglages.getFirst().id()).isNull();
        assertThat(prereglages.getFirst().nom()).isEqualTo("Plan canicule");
        assertThat(prereglages.getFirst().fenetres())
                .containsExactly(new ConsigneEdition.Fenetre(LocalTime.of(18, 0), LocalTime.of(22, 0)));
    }

    private static final String MINIMAL = """
            festival:
              dateDebut: 2027-07-12
            creneaux:
              - id: J1
                date: 2027-07-12
                heureDebut: "09:00"
                heureFin: "13:00"
            stands:
              - id: S1
                nom: Stand 1
                typologiesProposees: [STRATEGIE]
                effectifMin: 1
                effectifMax: 2
            animateurs:
              - id: A1
                prenom: Alice
                nom: Durand
                dateNaissance: 2000-01-01
                competences:
                  STRATEGIE: AUTONOME
            """;

    private static String export(
            List<Creneau> creneaux, List<ConsigneEdition> consignes, List<PrereglageConsigne> prereglages) {
        return ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                List.of(animateur()),
                List.of(stand()),
                creneaux,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                consignes,
                prereglages));
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, JOUR, debut, fin);
    }

    private static Stand stand() {
        Stand stand = new Stand();
        stand.setId("S1");
        stand.setNom("Stand");
        stand.setEffectifMin(1);
        stand.setEffectifMax(2);
        stand.setTypologiesProposees(Set.of("JEU"));
        return stand;
    }

    private static Animateur animateur() {
        Animateur animateur = new Animateur();
        animateur.setId("A1");
        animateur.setPrenom("Alice");
        animateur.setNom("Martin");
        animateur.setDateNaissance(LocalDate.of(1990, 1, 1));
        return animateur;
    }
}
