package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.ConsigneEspaceView;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.PosteAnimateurView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The consignes the espace shows a person: those on the days they hold a
 * seat, and those on their rest days — a day the band emptied for them is a
 * rest day on screen, and the motif is what says it was decided, not planned.
 */
class EspaceAnimateurConsignesTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 2, 1);
    private static final LocalDate MARDI = LUNDI.plusDays(1);
    private static final LocalDate MERCREDI = LUNDI.plusDays(2);

    @Test
    void aRestDayUnderConsigneIsShownWithItsMotif() {
        Map<LocalDate, ConsigneEdition> consignes =
                Map.of(MARDI, consigne(MARDI, "Arrêté préfectoral canicule"), MERCREDI, consigne(MERCREDI, "Orage"));

        List<ConsigneEspaceView> vues =
                EspaceAnimateurService.consignesOf(List.of(poste(LUNDI), poste(MERCREDI)), List.of(MARDI), consignes);

        assertThat(vues).extracting(ConsigneEspaceView::date).containsExactly(MARDI, MERCREDI);
        assertThat(vues.get(0).motif()).isEqualTo("Arrêté préfectoral canicule");
    }

    @Test
    void aDayOutsideThePersonsPlanningIsNotShown() {
        Map<LocalDate, ConsigneEdition> consignes = Map.of(MERCREDI, consigne(MERCREDI, "Orage"));

        assertThat(EspaceAnimateurService.consignesOf(List.of(poste(LUNDI)), List.of(MARDI), consignes))
                .isEmpty();
        assertThat(EspaceAnimateurService.consignesOf(List.of(poste(LUNDI)), List.of(MARDI), Map.of()))
                .isEmpty();
    }

    private static ConsigneEdition consigne(LocalDate date, String motif) {
        return new ConsigneEdition(
                date,
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                motif,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    private static PosteAnimateurView poste(LocalDate date) {
        return new PosteAnimateurView(
                1L, date, LocalTime.of(9, 0), LocalTime.of(13, 0), "S", "Stand", List.of(), null, null, null);
    }
}
