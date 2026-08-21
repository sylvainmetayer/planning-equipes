package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The typed reads over the object graph SnakeYAML returns. Two invariants
 * only, but they are the ones that justify the existence of the class: a
 * missing section is not an empty one, and a malformed section must name the
 * offending key rather than two Java classes.
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
     * {@code null} and not an empty value: the format tells "the key is not
     * there" from "the key is there and empty" — a file with no {@code postes:}
     * section gets its seats generated, a file with an empty section has
     * none.
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
