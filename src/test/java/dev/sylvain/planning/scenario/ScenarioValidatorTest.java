package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.scenario.ScenariosLivres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@link ScenarioValidator#validate(String)}, the structural gate the
 * "Valider un scénario" screen and the {@code -Dexec.mainClass=…ScenarioValidator}
 * CLI both run before anything is imported.
 *
 * <p>Also pins the scenarios actually shipped in {@code src/main/resources/scenarios}:
 * they are the fixtures every demo and several tests import, and a DTO change
 * that quietly invalidates one of them should break the build here rather than
 * at someone's first import.
 */
class ScenarioValidatorTest {

    private static final String MINIMAL = """
            festival:
              dateDebut: 2026-07-08
            creneaux:
              - id: J1
                date: 2026-07-08
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

    @Test
    void unScenarioMinimalBienFormeNeRemonteAucuneErreur() {
        assertThat(ScenarioValidator.validate(MINIMAL)).isEmpty();
    }

    /** Issue #343: a stand without any typologie is refused, and the message says which entry. */
    @Test
    void aStandWithoutTypologieIsReportedByItsEntry() {
        String sansTypologie = MINIMAL.replace("typologiesProposees: [STRATEGIE]", "typologiesProposees: []");

        List<String> erreurs = ScenarioValidator.validate(sansTypologie);

        assertThat(erreurs)
                .singleElement()
                .asString()
                .startsWith("stands[0].typologiesProposees:")
                .contains("au moins une typologie");
    }

    @Test
    void aMissingMandatorySectionIsReported() {
        String withoutStands = MINIMAL.replaceAll("(?s)stands:.*?animateurs:", "animateurs:");

        List<String> erreurs = ScenarioValidator.validate(withoutStands);

        assertThat(erreurs).isNotEmpty().anySatisfy(erreur -> assertThat(erreur).startsWith("stands:"));
    }

    @Test
    void unIdentifiantVideEstSignale() {
        List<String> erreurs = ScenarioValidator.validate(MINIMAL.replace("id: S1", "id: \"\""));

        assertThat(erreurs).anySatisfy(erreur -> assertThat(erreur).contains("stands[0].id"));
    }

    /**
     * {@code effectifMin} is {@code @PositiveOrZero}: zero is legitimate (a
     * stand open with no mandatory seat), a negative effectif is not.
     */
    @Test
    void unEffectifNegatifEstSignaleMaisPasUnEffectifNul() {
        assertThat(ScenarioValidator.validate(MINIMAL.replace("effectifMin: 1", "effectifMin: 0")))
                .isEmpty();

        assertThat(ScenarioValidator.validate(MINIMAL.replace("effectifMin: 1", "effectifMin: -1")))
                .anySatisfy(erreur -> assertThat(erreur).contains("stands[0].effectifMin"));
    }

    /**
     * The two effectifs are boxed on purpose: a primitive read an absent key as
     * 0, and a stand nobody sized was staffed with one seat, silently, all
     * festival long. Absent is a violation, named by its entry.
     */
    @Test
    void unStandSansEffectifEstSignale() {
        assertThat(ScenarioValidator.validate(MINIMAL.replace("    effectifMin: 1\n", "")))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("stands[0].effectifMin:"));

        assertThat(ScenarioValidator.validate(MINIMAL.replace("    effectifMax: 2\n", "")))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("stands[0].effectifMax:"));
    }

    /**
     * A duration the domain refuses is caught here too, before import: the
     * scenario sections are validated recursively via {@code @Valid}.
     */
    @Test
    void unParametreLegalNegatifEstSignale() {
        String withLegaux = MINIMAL + """
                parametresLegaux:
                  dureeVacationMaxMinutes: 0
                  reposQuotidienMinimalMinutes: -5
                """;

        List<String> erreurs = ScenarioValidator.validate(withLegaux);

        assertThat(erreurs)
                .anySatisfy(erreur -> assertThat(erreur).contains("parametresLegaux.dureeVacationMaxMinutes"))
                .anySatisfy(erreur -> assertThat(erreur).contains("parametresLegaux.reposQuotidienMinimalMinutes"));
    }

    /**
     * A retired section is refused at binding, by name — like any key the
     * application does not know, and for a sharper reason: accepting
     * {@code decoupageAuto} and ignoring it would import a grid of day-long
     * opening amplitudes as day-long vacations, without a word.
     */
    @Test
    void uneSectionDuDecoupageRetireEstRefuseeParSonNom() {
        assertThatThrownBy(() -> ScenarioValidator.validate(
                        MINIMAL + "parametresDecoupage:\n  dureeVacationCibleMinutes: 240\n"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("parametresDecoupage")
                .hasMessageContaining("journeesTypes");

        assertThatThrownBy(() -> ScenarioValidator.validate(MINIMAL + "decoupageAuto: {}\n"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("decoupageAuto")
                .hasMessageContaining("couverturePause");
    }

    /**
     * Malformed YAML fails loudly at parsing, it is not reported as a
     * violation — a violation says "this field is wrong", and there is no
     * field yet.
     */
    @Test
    void unYamlSyntaxiquementInvalideLeveUneErreurDeLecture() {
        assertThatThrownBy(() -> ScenarioValidator.validate("festival: [unclosed"))
                .isInstanceOf(ScenarioFormatException.class)
                .hasMessageContaining("YAML invalide");
    }

    @ParameterizedTest
    @MethodSource("scenariosLivres")
    void lesScenariosLivresRestentValides(Path scenario) throws IOException {
        assertThat(ScenarioValidator.validate(Files.readString(scenario)))
                .as("%s", scenario.getFileName())
                .isEmpty();
    }

    private static Stream<Path> scenariosLivres() throws IOException {
        return ScenariosLivres.all().stream();
    }
    /**
     * The consigne sections (ADR 0043) are checked like the rest: a band that
     * starts, a motif, and a reason as soon as the meal windows are restated.
     * What only the whole file can tell — a stand or a créneau the section
     * names — is the import's to refuse, like every other cross-reference.
     */
    @Test
    void aConsigneWithoutABandStartOrAMotifIsReported() {
        String consigne = """
                consignes:
                  - date: 2026-07-08
                    fermetureDebut: "12:00"
                    motif: Orage
                    repas:
                      soirDebut: "18:00"
                      soirFin: "22:00"
                      justification: Repas pris pendant la bande
                """;
        assertThat(ScenarioValidator.validate(MINIMAL + consigne)).isEmpty();

        assertThat(ScenarioValidator.validate(MINIMAL + consigne.replace("    fermetureDebut: \"12:00\"\n", "")))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("consignes[0].fermetureDebut:"));
        assertThat(ScenarioValidator.validate(MINIMAL + consigne.replace("    motif: Orage\n", "")))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("consignes[0].motif:"));
        assertThat(ScenarioValidator.validate(
                        MINIMAL + consigne.replace("      justification: Repas pris pendant la bande\n", "")))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("consignes[0].repas.justification:"));
    }

    @Test
    void aPresetWithoutANameIsReported() {
        String prereglage = """
                prereglagesConsigne:
                  - fermetureDebut: "12:00"
                    motif: Orage
                """;
        assertThat(ScenarioValidator.validate(MINIMAL + prereglage))
                .anySatisfy(erreur -> assertThat(erreur).startsWith("prereglagesConsigne[0].nom:"));
    }
}
