package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The proposed column mapping — a convenience the operator may overrule, so
 * what matters here is that it never quietly maps two fields to one column,
 * and that it survives the way a spreadsheet spells a header.
 */
class AnimateurCsvMappingTest {

    @Test
    void recognisesTheUsualFrenchHeaders() {
        AnimateurCsvMapping mapping = AnimateurCsvMapping.propose(
                List.of("Prénom", "Nom", "Date de naissance", "E-mail", "Compétences",
                        "Jours indisponibles"));

        assertThat(mapping.prenom()).isZero();
        assertThat(mapping.nom()).isEqualTo(1);
        assertThat(mapping.dateNaissance()).isEqualTo(2);
        assertThat(mapping.email()).isEqualTo(3);
        assertThat(mapping.competences()).isEqualTo(4);
        assertThat(mapping.joursIndisponibles()).isEqualTo(5);
    }

    @Test
    void recognisesEnglishHeadersToo() {
        AnimateurCsvMapping mapping =
                AnimateurCsvMapping.propose(List.of("First name", "Last name", "Birth date"));

        assertThat(mapping.prenom()).isZero();
        assertThat(mapping.nom()).isEqualTo(1);
        assertThat(mapping.dateNaissance()).isEqualTo(2);
    }

    /** Two headers a field answers to must not both feed it: the first wins. */
    @Test
    void neverFeedsOneFieldFromTwoColumns() {
        AnimateurCsvMapping mapping = AnimateurCsvMapping.propose(List.of("Nom", "Nom de famille"));

        assertThat(mapping.nom()).isZero();
        assertThat(mapping.prenom()).isNull();
    }

    @Test
    void proposesNothingForHeadersItDoesNotKnow() {
        AnimateurCsvMapping mapping = AnimateurCsvMapping.propose(List.of("colonne A", "colonne B"));

        assertThat(mapping).isEqualTo(AnimateurCsvMapping.empty());
    }

    @Test
    void toleratesEmptyAndDuplicateHeaders() {
        AnimateurCsvMapping mapping = AnimateurCsvMapping.propose(List.of("", "Nom", "", "Nom"));

        assertThat(mapping.nom()).isEqualTo(1);
    }
}
