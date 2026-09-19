package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.publication.PlanPublicationService.DestinatairePublication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The review table as a file (issue #503): one line per person, read in a
 * spreadsheet away from the screen. Pure — the generator takes the preview it
 * is given and needs no container.
 */
class PlanPublicationCsvTest {

    private static ApercuPublication apercu(DestinatairePublication... destinataires) {
        return new ApercuPublication(
                false, false, false, Instant.EPOCH, destinataires.length, 0, List.of(destinataires));
    }

    @Test
    void theHeaderNamesTheFieldsOfTheApiRatherThanFrenchLabels() {
        assertThat(PlanPublicationService.generateCsv(apercu()))
                .isEqualTo("animateurId;nomAffiche;email;premiereDiffusion;reporte;ajouts;retraits;deplacements;"
                        + "mineur;confirmation;changements;demandes\n");
    }

    @Test
    void onePersonIsOneLineWithTheirSentencesInOneCell() {
        String csv = PlanPublicationService.generateCsv(apercu(new DestinatairePublication(
                "a1",
                "Camille Durand",
                "camille@example.org",
                false,
                List.of(
                        "samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h",
                        "dimanche 12/07 : Kubb 9h-12h (nouveau)"),
                List.of(),
                1,
                0,
                1,
                false,
                true,
                "CONFIRME",
                Instant.EPOCH)));

        assertThat(csv.split("\n")[1])
                .isEqualTo("a1;Camille Durand;camille@example.org;false;true;1;0;1;false;CONFIRME;"
                        + "samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h | dimanche 12/07 : Kubb 9h-12h "
                        + "(nouveau);");
    }

    /**
     * The separator is the semicolon the spreadsheets of this project expect,
     * so a stand whose name carries one has to be quoted — otherwise one
     * person's line silently gains a column and the file stops being sortable.
     */
    @Test
    void aValueCarryingTheSeparatorIsQuoted() {
        String csv = PlanPublicationService.generateCsv(apercu(new DestinatairePublication(
                "a2", "Roy; Dominique", null, true, List.of(), List.of(), 0, 0, 0, false, false, null, null)));

        assertThat(csv.split("\n")[1]).isEqualTo("a2;\"Roy; Dominique\";;true;false;0;0;0;false;;;");
    }
}
