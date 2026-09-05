package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.service.GrilleCsv.Matrice;

/** {@link GrilleCsv}: the stand matrix as a spreadsheet writes it, read back column by column. */
class GrilleCsvTest {

    @Test
    void litDeuxLignesDEnTeteAvecLesDatesFusionneesRemplies() {
        Matrice matrice = GrilleCsv.parse("""
                stand;2026-07-08;;2026-07-09
                ;10:00-12:00;14:00-20:00;10:00-12:00
                BOURSE;2;4;2
                JEUX;1;;-
                """);

        assertThat(matrice.separator()).isEqualTo(";");
        assertThat(matrice.colonnes()).hasSize(3);
        assertThat(matrice.colonnes().get(1).date()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(matrice.colonnes().get(1).heureDebut()).isEqualTo(LocalTime.of(14, 0));
        assertThat(matrice.colonnes().get(2).date()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(matrice.colonnes()).allMatch(GrilleCsv.Colonne::namesCreneau);
        assertThat(matrice.lignes()).hasSize(2);
        assertThat(matrice.lignes().get(0).stand()).isEqualTo("BOURSE");
        assertThat(matrice.lignes().get(0).line()).isEqualTo(3);
        assertThat(matrice.lignes().get(1).cellules()).containsExactly("1", "", "-");
    }

    @Test
    void litUneSeuleLigneDEnTeteDateEtBande() {
        Matrice matrice = GrilleCsv.parse("stand,2026-07-08 10:00-12:00,08/07/2026 14h-20h,note\nBOURSE,2,4,x\n");

        assertThat(matrice.colonnes()).hasSize(3);
        assertThat(matrice.colonnes().get(0).namesCreneau()).isTrue();
        assertThat(matrice.colonnes().get(1).date()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(matrice.colonnes().get(1).heureFin()).isEqualTo(LocalTime.of(20, 0));
        assertThat(matrice.colonnes().get(2).namesCreneau()).isFalse();
        assertThat(matrice.colonnes().get(2).libelle()).isEqualTo("note");
    }

    @Test
    void litLesHeuresEcritesALaMainEtMinuitCommeFin() {
        assertThat(GrilleCsv.bande("10h-12h30")).containsExactly(LocalTime.of(10, 0), LocalTime.of(12, 30));
        assertThat(GrilleCsv.bande("20:00 → 24:00")).containsExactly(LocalTime.of(20, 0), LocalTime.MIDNIGHT);
        assertThat(GrilleCsv.bande("20:00-00:00")).containsExactly(LocalTime.of(20, 0), LocalTime.MIDNIGHT);
        assertThat(GrilleCsv.bande("matin")).isNull();
        assertThat(GrilleCsv.bande("25:00-26:00")).isNull();
        // A single-digit minute is ambiguous — 9:05 or 9:50 — and a band read
        // wrong lands the column on the wrong créneau: refused, never completed.
        assertThat(GrilleCsv.heure("9.5")).isNull();
        assertThat(GrilleCsv.heure("9.05")).isEqualTo(LocalTime.of(9, 5));
        assertThat(GrilleCsv.bande("9:5-12:00")).isNull();
    }

    @Test
    void litUneCaseCommeUnEffectifOuUneFermeture() {
        assertThat(GrilleCsv.cellule("3")).isEqualTo(new GrilleCsv.CelluleLue(3, true));
        assertThat(GrilleCsv.cellule(" 12 ")).isEqualTo(new GrilleCsv.CelluleLue(12, true));
        assertThat(GrilleCsv.cellule("")).isEqualTo(new GrilleCsv.CelluleLue(null, true));
        assertThat(GrilleCsv.cellule("-")).isEqualTo(new GrilleCsv.CelluleLue(null, true));
        assertThat(GrilleCsv.cellule("0")).isEqualTo(new GrilleCsv.CelluleLue(null, true));
        assertThat(GrilleCsv.cellule("x")).isEqualTo(new GrilleCsv.CelluleLue(null, false));
        assertThat(GrilleCsv.cellule("2,5")).isEqualTo(new GrilleCsv.CelluleLue(null, false));
    }

    @Test
    void ignoreLesLignesVidesEtGardeLeNumeroDeLigneDuFichier() {
        Matrice matrice = GrilleCsv.parse("stand\t2026-07-08 10:00-12:00\n\nBOURSE\t2\n\n");

        assertThat(matrice.separator()).isEqualTo("\t");
        assertThat(matrice.lignes()).singleElement().satisfies(ligne -> assertThat(ligne.line()).isEqualTo(3));
    }
}
