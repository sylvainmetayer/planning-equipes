package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Les lectures typées du graphe d'objets rendu par SnakeYAML. Deux invariants
 * seulement, mais ce sont ceux qui justifient l'existence de la classe :
 * l'absence d'une section n'est pas son vide, et une section mal formée doit
 * nommer la clé fautive plutôt que deux classes Java.
 */
class YamlSectionsTest {

    private static final Map<String, Object> DOCUMENT = Map.of(
            "parametresLegaux", Map.of("dureeHebdomadaireMaxMinutes", 2400),
            "stands", List.of(Map.of("id", "S1")),
            "typologiesProposees", List.of("STRATEGIE", "ADRESSE"),
            "joursIndisponibles", List.of("2026-08-14"),
            "festival", "pas-un-bloc",
            "creneaux", "pas-une-liste");

    @Test
    void lesSectionsBienFormeesSontRenduesTelleQuelles() {
        assertThat(YamlSections.objet(DOCUMENT, "parametresLegaux"))
                .containsEntry("dureeHebdomadaireMaxMinutes", 2400);
        assertThat(YamlSections.objets(DOCUMENT, "stands")).singleElement()
                .satisfies(stand -> assertThat(stand).containsEntry("id", "S1"));
        assertThat(YamlSections.chaines(DOCUMENT, "typologiesProposees"))
                .containsExactly("STRATEGIE", "ADRESSE");
        assertThat(YamlSections.valeurs(DOCUMENT, "joursIndisponibles"))
                .containsExactly("2026-08-14");
    }

    /**
     * {@code null} et non une valeur vide : le format distingue « la clé n'est
     * pas là » de « la clé est là et vide » — un fichier sans section
     * {@code postes:} voit ses postes générés, un fichier avec une section vide
     * n'en a aucun.
     */
    @Test
    void uneSectionAbsenteEstNulleEtNonVide() {
        assertThat(YamlSections.objet(DOCUMENT, "absente")).isNull();
        assertThat(YamlSections.objets(DOCUMENT, "absente")).isNull();
        assertThat(YamlSections.chaines(DOCUMENT, "absente")).isNull();
        assertThat(YamlSections.valeurs(DOCUMENT, "absente")).isNull();
    }

    @Test
    void uneSectionMalFormeeNommeLaCleFautive() {
        assertThatThrownBy(() -> YamlSections.objet(DOCUMENT, "festival"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("festival")
                .hasMessageContaining("un bloc de champs");

        assertThatThrownBy(() -> YamlSections.objets(DOCUMENT, "creneaux"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creneaux")
                .hasMessageContaining("une liste");
    }
}
