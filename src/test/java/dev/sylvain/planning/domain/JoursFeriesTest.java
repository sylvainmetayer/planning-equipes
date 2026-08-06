package dev.sylvain.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Covers the public-holiday calendar of art. L3133-1, on which
 * {@code travailInterditJourFerieMineur} (art. L3164-6) rests.
 */
class JoursFeriesTest {

    @Test
    void paquesEstCalculeCorrectement() {
        // Dates de référence du comput grégorien.
        assertThat(JoursFeries.paques(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(JoursFeries.paques(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(JoursFeries.paques(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    void lesOnzeJoursFeriesDeLArticleL3133SontPresents() {
        assertThat(JoursFeries.joursFeries(2026)).hasSize(11).contains(
                LocalDate.of(2026, 1, 1),    // Jour de l'an
                LocalDate.of(2026, 4, 6),    // Lundi de Pâques
                LocalDate.of(2026, 5, 1),    // Fête du Travail
                LocalDate.of(2026, 5, 8),    // Victoire 1945
                LocalDate.of(2026, 5, 14),   // Ascension (Pâques + 39)
                LocalDate.of(2026, 5, 25),   // Lundi de Pentecôte (Pâques + 50)
                LocalDate.of(2026, 7, 14),   // Fête nationale
                LocalDate.of(2026, 8, 15),   // Assomption
                LocalDate.of(2026, 11, 1),   // Toussaint
                LocalDate.of(2026, 11, 11),  // Armistice 1918
                LocalDate.of(2026, 12, 25)); // Noël
    }

    @Test
    void leQuatorzeJuilletEstFerie() {
        assertThat(JoursFeries.estFerieEnFrance(LocalDate.of(2026, 7, 14))).isTrue();
    }

    @Test
    void unJourOrdinaireNEstPasFerie() {
        assertThat(JoursFeries.estFerieEnFrance(LocalDate.of(2026, 7, 15))).isFalse();
        assertThat(JoursFeries.estFerieEnFrance(null)).isFalse();
    }

    @Test
    void lesJoursFeriesDAlsaceMoselleNeSontPasRetenus() {
        // Vendredi saint et 26 décembre : fériés uniquement en Alsace-Moselle,
        // hors du modèle tant qu'aucune notion de région n'existe.
        assertThat(JoursFeries.estFerieEnFrance(LocalDate.of(2026, 4, 3))).isFalse();
        assertThat(JoursFeries.estFerieEnFrance(LocalDate.of(2026, 12, 26))).isFalse();
    }
}
