package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@link ScenarioValidator#valider(String)}, the structural gate the
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
    void unScenarioMinimalBienFormeNeRemonteAucuneErreur() throws IOException {
        assertThat(ScenarioValidator.valider(MINIMAL)).isEmpty();
    }

    @Test
    void uneSectionObligatoireAbsenteEstSignalee() throws IOException {
        String sansStands = MINIMAL.replaceAll("(?s)stands:.*?animateurs:", "animateurs:");

        List<String> erreurs = ScenarioValidator.valider(sansStands);

        assertThat(erreurs).isNotEmpty();
        assertThat(erreurs).anySatisfy(erreur -> assertThat(erreur).startsWith("stands:"));
    }

    @Test
    void unIdentifiantVideEstSignale() throws IOException {
        List<String> erreurs = ScenarioValidator.valider(MINIMAL.replace("id: S1", "id: \"\""));

        assertThat(erreurs).anySatisfy(erreur -> assertThat(erreur).contains("stands[0].id"));
    }

    /**
     * {@code effectifMin} is {@code @PositiveOrZero}: zero is legitimate (a
     * stand open with no mandatory seat), a negative effectif is not.
     */
    @Test
    void unEffectifNegatifEstSignaleMaisPasUnEffectifNul() throws IOException {
        assertThat(ScenarioValidator.valider(MINIMAL.replace("effectifMin: 1", "effectifMin: 0"))).isEmpty();

        assertThat(ScenarioValidator.valider(MINIMAL.replace("effectifMin: 1", "effectifMin: -1")))
                .anySatisfy(erreur -> assertThat(erreur).contains("stands[0].effectifMin"));
    }

    /**
     * A duration the domain refuses is caught here too, before import: the
     * scenario sections are validated recursively via {@code @Valid}.
     */
    @Test
    void unParametreDeDecoupageNegatifEstSignale() throws IOException {
        String avecDecoupage = MINIMAL + """
                parametresDecoupage:
                  nombreFamillesDecalage: 0
                  dureeDecalageMaxMinutes: -5
                """;

        List<String> erreurs = ScenarioValidator.valider(avecDecoupage);

        assertThat(erreurs)
                .anySatisfy(erreur -> assertThat(erreur).contains("parametresDecoupage.nombreFamillesDecalage"))
                .anySatisfy(erreur -> assertThat(erreur).contains("parametresDecoupage.dureeDecalageMaxMinutes"));
    }

    /** Malformed YAML fails loudly at parsing, it is not reported as a violation. */
    @Test
    void unYamlSyntaxiquementInvalideLeveUneErreurDeLecture() {
        assertThatThrownBy(() -> ScenarioValidator.valider("festival: [unclosed"))
                .isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @MethodSource("scenariosLivres")
    void lesScenariosLivresRestentValides(Path scenario) throws IOException {
        assertThat(ScenarioValidator.valider(Files.readString(scenario)))
                .as("%s", scenario.getFileName())
                .isEmpty();
    }

    private static Stream<Path> scenariosLivres() throws IOException {
        Path dossier = Path.of("src", "main", "resources", "scenarios");
        try (Stream<Path> fichiers = Files.list(dossier)) {
            return fichiers
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".yaml")
                            || f.getFileName().toString().endsWith(".yml"))
                    // The reel-*.yaml scenarios are gitignored (real personal
                    // data): present only on the machine of whoever produced
                    // them, never in the repository nor in CI.
                    .filter(f -> !f.getFileName().toString().startsWith("reel-"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }
}
