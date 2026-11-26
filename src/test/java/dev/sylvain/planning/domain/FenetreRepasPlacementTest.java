package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The edition's placement setting applied to the meal windows (ADR 0123). */
class FenetreRepasPlacementTest {

    private static final FenetreRepas MIDI =
            new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60, true);
    private static final FenetreRepas SOIR =
            new FenetreRepas(FenetreRepas.SOIR, LocalTime.of(19, 0), LocalTime.of(21, 0), 60, false);

    @Test
    void theShippedPlacementLeavesEveryWindowAsItIs() {
        List<FenetreRepas> fenetres = List.of(MIDI, SOIR);

        assertThat(FenetreRepas.withPlacement(fenetres, PlacementRepas.FIN_MIDI_DEBUT_SOIR))
                .isEqualTo(fenetres);
    }

    @Test
    void theStartAndEndPlacementsSetOneDirectionForBothWindows() {
        assertThat(FenetreRepas.withPlacement(List.of(MIDI, SOIR), PlacementRepas.DEBUT))
                .allSatisfy(fenetre -> assertThat(fenetre.auPlusTard()).isFalse());
        assertThat(FenetreRepas.withPlacement(List.of(MIDI, SOIR), PlacementRepas.FIN))
                .allSatisfy(fenetre -> assertThat(fenetre.auPlusTard()).isTrue());
    }

    @Test
    void noPlacementMarksTheWindowAndKeepsItsHoursAndDates() {
        FenetreRepas sans = MIDI.withPlacement(PlacementRepas.AUCUN);

        assertThat(sans.sansPlacement()).isTrue();
        assertThat(sans)
                .usingRecursiveComparison()
                .ignoringFields("sansPlacement")
                .isEqualTo(MIDI);
        assertThat(MIDI.sansPlacement()).isFalse();
    }

    @Test
    void aNewQualityRecordShipsTheDefaultPlacement() {
        assertThat(new ParametresQualite().placementRepas()).isEqualTo(PlacementRepas.FIN_MIDI_DEBUT_SOIR);
        assertThat(new ParametresQualite()
                        .withPlacementRepas(PlacementRepas.AUCUN)
                        .placementRepas())
                .isEqualTo(PlacementRepas.AUCUN);
    }
}
