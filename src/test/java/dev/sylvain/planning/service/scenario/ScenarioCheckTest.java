package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What the « Valider un scénario » report says, without a database: the stages of an import that write nothing. */
class ScenarioCheckTest {

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
    void aWellFormedFileIsValidWithoutWarning() {
        ScenarioCheck.Report report = ScenarioCheck.check(MINIMAL);

        assertThat(report.erreurs()).isEmpty();
        assertThat(report.avertissements()).isEmpty();
    }

    /** A seat naming a stand the file does not define: the import refused it, the report now says so too. */
    @Test
    void aBrokenReferenceIsAnErrorAsAtTheImport() {
        ScenarioCheck.Report report = ScenarioCheck.check(MINIMAL + """
                postes:
                  - id: P1
                    standId: S-ABSENT
                    creneauId: J1
                """);

        assertThat(report.erreurs()).singleElement().asString().contains("S-ABSENT");
    }

    /** A file that stops before its last mandatory sections: refused, and said likely cut. */
    @Test
    void aFileStoppingBeforeItsMandatorySectionsIsSaidTruncated() {
        ScenarioCheck.Report report = ScenarioCheck.check(MINIMAL.substring(0, MINIMAL.indexOf("stands:")));

        assertThat(report.erreurs())
                .contains("stands: obligatoire, absent du fichier.", "animateurs: obligatoire, absent du fichier.");
        assertThat(report.avertissements())
                .singleElement()
                .asString()
                .contains("après la section « creneaux »", "« stands », « animateurs »", "tronqué");
    }

    @Test
    void anEmptyFileSaysSo() {
        assertThat(ScenarioCheck.check("  ").erreurs()).containsExactly("Le fichier est vide.");
    }

    /** What the screen calls valid, the import accepts: every shipped scenario passes the whole report. */
    @Test
    void everyBundledScenarioPassesTheWholeCheck() throws IOException {
        for (Path scenario : ScenariosLivres.all()) {
            assertThat(ScenarioCheck.check(Files.readString(scenario)).erreurs())
                    .as(scenario.getFileName().toString())
                    .isEmpty();
        }
    }
}
