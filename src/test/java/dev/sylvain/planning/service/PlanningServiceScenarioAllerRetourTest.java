package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * The joint between the two halves of the scenario format: what the export
 * writes must be what the import reads.
 *
 * <p>Both halves were already tested, and both passed. The export was checked
 * against what it emits; the import against the fixtures of
 * {@code src/main/resources/scenarios/} — <b>hand-written files, with textual
 * ids</b> such as {@code J1-MATIN}. Nobody ever fed the import a file the
 * application itself had produced, and that file happened to be unreadable:
 * the export wrote the créneau's database id as the {@code Long} it is, so the
 * YAML carried a number, and the loader's cast to {@code String} threw.</p>
 *
 * <p>Which is why this class asserts the round trip rather than one more
 * property of either side. The one-line fix is not the interesting part — the
 * missing test is: without it, the next field added to the format can diverge
 * exactly the same way, silently, and the suite will stay green.</p>
 */
class PlanningServiceScenarioAllerRetourTest {

    private static PlanningService service() {
        return new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(), new FeasibilityAnalyzer(), ConfigProvider.getConfig());
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, LocalDate.of(2026, 7, 8), debut, fin);
    }

    private static Stand stand(String id) {
        Stand stand = new Stand();
        stand.setId(id);
        stand.setNom("Stand " + id);
        stand.setEffectifMin(1);
        stand.setEffectifMax(2);
        stand.setTypologiesProposees(Set.of("STRATEGIE"));
        return stand;
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        PosteAffectation poste = new PosteAffectation();
        poste.setId(id);
        poste.setStand(stand);
        poste.setCreneau(creneau);
        return poste;
    }

    private static Animateur animateur(String id) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setPrenom("Alice");
        animateur.setNom("Referente");
        animateur.setDateNaissance(LocalDate.of(2002, 7, 19));
        animateur.setCompetences(Map.of("STRATEGIE", NiveauCompetence.REFERENT));
        return animateur;
    }

    /**
     * The exact case that used to fail: a créneau whose id is a database
     * {@code Long}, exported and read back.
     */
    @Test
    void unScenarioExporteSeReimporte() {
        Creneau creneau = creneau(1L, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Stand stand = stand("STAND-A");
        Animateur animateur = animateur("A1");
        PosteAffectation poste = poste("P1", stand, creneau);

        String yaml = PlanningService.buildScenarioYaml(
                List.of(animateur), List.of(stand), List.of(creneau), List.of(poste));

        PlanningEvenement relu = service().buildFromScenarioText(yaml).planning();

        assertThat(relu.getAnimateurs()).extracting(Animateur::getId).containsExactly("A1");
        assertThat(relu.getPostes()).hasSize(1);
        assertThat(relu.getDateDebutFestival()).isEqualTo(LocalDate.of(2026, 7, 8));
    }

    /**
     * A file that declares no seat must come back with none: {@code postes: []}
     * says "no seats", not "generate them for me" — the generation only kicks
     * in when the section is absent altogether.
     */
    @Test
    void unExportSansPosteSeRelitSansPoste() {
        String yaml = PlanningService.buildScenarioYaml(
                List.of(animateur("A1")), List.of(stand("STAND-A")),
                List.of(creneau(1L, LocalTime.of(9, 0), LocalTime.of(13, 0))), List.of());

        PlanningEvenement relu = service().buildFromScenarioText(yaml).planning();

        assertThat(relu.getPostes()).isEmpty();
        assertThat(relu.getAnimateurs()).extracting(Animateur::getId).containsExactly("A1");
    }

    /**
     * The postes section carries the same id a second time, as
     * {@code creneauId}: a poste whose créneau cannot be resolved would come
     * back detached, which is a silent loss rather than a crash.
     */
    @Test
    void lesPostesExportesRetrouventLeurCreneauEtLeurStand() {
        Creneau creneau = creneau(7L, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Stand stand = stand("STAND-A");

        String yaml = PlanningService.buildScenarioYaml(
                List.of(animateur("A1")), List.of(stand), List.of(creneau),
                List.of(poste("P1", stand, creneau)));

        PlanningEvenement relu = service().buildFromScenarioText(yaml).planning();

        assertThat(relu.getPostes()).allSatisfy(p -> {
            assertThat(p.getCreneau()).as("le créneau du poste").isNotNull();
            assertThat(p.getStand()).as("le stand du poste").isNotNull();
        });
        assertThat(relu.getPostes())
                .extracting(p -> p.getCreneau().getHeureDebut())
                .containsOnly(LocalTime.of(9, 0));
    }

    /**
     * The exported file must satisfy the schema it is published with: ids are
     * declared {@code string} in {@code docs/schema/scenario-schema.json}, and
     * an export that emits numbers there is wrong even where a tolerant loader
     * would cope.
     */
    @Test
    void lesIdentifiantsSontExportesEnChaineCommeLeSchemaLeDeclare() {
        Creneau creneau = creneau(1L, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Stand stand = stand("STAND-A");

        String yaml = PlanningService.buildScenarioYaml(
                List.of(animateur("A1")), List.of(stand), List.of(creneau),
                List.of(poste("P1", stand, creneau)));

        assertThat(yaml).contains("id: '1'").doesNotContain("id: 1\n");
        assertThat(yaml).contains("creneauId: '1'");
    }

    /**
     * A midnight-to-noon window is where YAML 1.1 bites: an unquoted
     * {@code 13:00} resolves to the sexagesimal integer 780, not to a string.
     * The reader accepts both forms, so an hour that the dumper chose not to
     * quote still comes back as the same time.
     */
    @Test
    void lesHorairesSurviventQuelQueSoitLeTypeQueYamlLeurDonne() {
        Creneau matin = creneau(1L, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Stand stand = stand("STAND-A");

        String yaml = PlanningService.buildScenarioYaml(
                List.of(animateur("A1")), List.of(stand), List.of(matin),
                List.of(poste("P1", stand, matin)));

        PlanningEvenement relu = service().buildFromScenarioText(yaml).planning();

        assertThat(relu.getPostes())
                .extracting(p -> p.getCreneau().getHeureDebut(), p -> p.getCreneau().getHeureFin())
                .containsOnly(org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 0), LocalTime.of(13, 0)));
    }

    /**
     * The tolerance kept for the files already exported before the fix: they
     * carry numeric ids, and they must stay readable rather than becoming
     * scrap.
     */
    @Test
    void unFichierAncienAIdentifiantsNumeriquesResteLisible() {
        String yaml = """
                festival:
                  dateDebut: '2026-07-08'
                creneaux:
                - id: 1
                  jour: 1
                  date: '2026-07-08'
                  heureDebut: '09:00'
                  heureFin: '13:00'
                stands:
                - id: STAND-A
                  nom: Stand A
                  typologiesProposees:
                  - STRATEGIE
                  effectifMin: 1
                  effectifMax: 1
                  reserveMajeurs: false
                animateurs:
                - id: A1
                  prenom: Alice
                  nom: Referente
                  dateNaissance: '2002-07-19'
                  manager: false
                  competences:
                    STRATEGIE: REFERENT
                  joursIndisponibles: []
                postes:
                - id: P1
                  standId: STAND-A
                  creneauId: 1
                  animateurId: null
                """;

        PlanningEvenement relu = service().buildFromScenarioText(yaml).planning();

        assertThat(relu.getPostes()).hasSize(1);
        assertThat(relu.getPostes().getFirst().getCreneau()).isNotNull();
        assertThat(relu.getPostes().getFirst().getStand().getId()).isEqualTo("STAND-A");
    }
}
