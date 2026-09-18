package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition.Fenetre;
import dev.sylvain.planning.domain.ConsigneEdition.Ouverture;
import dev.sylvain.planning.service.consigne.ConsigneService.Demande;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** The compact arguments of the consigne tools, read: an end written {@code 00:00} is the day's end, stored open. */
class ConsigneMcpToolsParsingTest {

    @Test
    void midnightAsAnEndIsReadAsAnOpenEndEverywhere() {
        Demande demande = ConsigneMcpTools.demande(
                "2027-02-03",
                "12:00",
                "00:00",
                "Arrêté",
                "18:00-00:00,08:00-10:00",
                "BOURSE=18:00-00:00*3",
                null,
                null);

        assertThat(demande.fermetureFin()).isNull();
        assertThat(demande.fenetres())
                .containsExactly(
                        new Fenetre(LocalTime.of(18, 0), null), new Fenetre(LocalTime.of(8, 0), LocalTime.of(10, 0)));
        assertThat(demande.ouvertures()).containsExactly(new Ouverture("BOURSE", LocalTime.of(18, 0), null, 3));
    }

    @Test
    void anOmittedEndReadsTheSame() {
        assertThat(ConsigneMcpTools.dayWindows("20:00-")).containsExactly(new Fenetre(LocalTime.of(20, 0), null));
        assertThat(ConsigneMcpTools.endOrOpen(" ")).isNull();
        assertThat(ConsigneMcpTools.endOrOpen("18:30")).isEqualTo(LocalTime.of(18, 30));
    }
}
