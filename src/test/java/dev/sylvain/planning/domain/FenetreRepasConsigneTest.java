package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The meal windows a consigne restates (issue #4) become dated facts, and the
 * edition's own fact steps aside on that date only.
 */
class FenetreRepasConsigneTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 9, 22);

    private static ParametresLegaux parametres() {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setCoupureRepasMidiDebut(LocalTime.of(12, 0));
        parametres.setCoupureRepasMidiFin(LocalTime.of(14, 0));
        parametres.setCoupureRepasSoirDebut(LocalTime.of(19, 0));
        parametres.setCoupureRepasSoirFin(LocalTime.of(21, 0));
        parametres.setCoupureRepasMinutes(60);
        return parametres;
    }

    private static ConsigneEdition consigne(ConsigneEdition.RepasConsigne repas) {
        return new ConsigneEdition(
                JOUR,
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                "arrêté",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                repas);
    }

    @Test
    void sansSurchargeLesFenetresDeLEditionRestentSeulesEtSansExclusion() {
        List<FenetreRepas> fenetres = FenetreRepas.from(parametres(), List.of(consigne(null)));

        assertThat(fenetres).hasSize(2);
        assertThat(fenetres)
                .allMatch(fenetre ->
                        fenetre.date() == null && fenetre.datesExclues().isEmpty());
        assertThat(fenetres).allMatch(fenetre -> fenetre.appliesTo(JOUR));
    }

    @Test
    void leSoirSurchargeVautSurSaDateEtLEditionAilleurs() {
        ConsigneEdition.RepasConsigne repas = new ConsigneEdition.RepasConsigne(
                null, null, LocalTime.of(18, 0), LocalTime.of(22, 0), null, "les équipes mangent pendant la bande");

        List<FenetreRepas> fenetres = FenetreRepas.from(parametres(), List.of(consigne(repas)));

        assertThat(fenetres).hasSize(3);
        FenetreRepas midi = fenetres.get(0);
        FenetreRepas soirEdition = fenetres.get(1);
        FenetreRepas soirConsigne = fenetres.get(2);
        assertThat(midi.libelle()).isEqualTo(FenetreRepas.MIDI);
        assertThat(midi.appliesTo(JOUR)).isTrue();
        assertThat(soirEdition.debut()).isEqualTo(LocalTime.of(19, 0));
        assertThat(soirEdition.appliesTo(JOUR)).isFalse();
        assertThat(soirEdition.appliesTo(JOUR.plusDays(1))).isTrue();
        assertThat(soirConsigne.date()).isEqualTo(JOUR);
        assertThat(soirConsigne.debut()).isEqualTo(LocalTime.of(18, 0));
        assertThat(soirConsigne.fin()).isEqualTo(LocalTime.of(22, 0));
        assertThat(soirConsigne.dureeMinutes()).isEqualTo(60);
        assertThat(soirConsigne.appliesTo(JOUR)).isTrue();
        assertThat(soirConsigne.appliesTo(JOUR.plusDays(1))).isFalse();
    }

    @Test
    void laDureeSeuleSurchargeLesDeuxFenetresAvecLesHeuresDeLEdition() {
        ConsigneEdition.RepasConsigne repas =
                new ConsigneEdition.RepasConsigne(null, null, null, null, 30, "service allégé");

        List<FenetreRepas> fenetres = FenetreRepas.from(parametres(), List.of(consigne(repas)));

        assertThat(fenetres).hasSize(4);
        assertThat(fenetres.stream().filter(f -> f.date() != null))
                .allMatch(f -> f.dureeMinutes() == 30)
                .extracting(FenetreRepas::debut)
                .containsExactlyInAnyOrder(LocalTime.of(12, 0), LocalTime.of(19, 0));
    }
}
