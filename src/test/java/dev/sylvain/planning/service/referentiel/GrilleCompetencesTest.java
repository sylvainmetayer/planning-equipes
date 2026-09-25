package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure half of the competences grid: how a fiche is copied with its appreciations replaced. */
class GrilleCompetencesTest {

    @Test
    void theCopyReplacesTheAppreciationsAndCarriesEverythingElse() {
        Animateur source = animateur("A1", Map.of("jeux", NiveauCompetence.DEBUTANT));
        source.setEmail("a1@example.org");
        source.setSouhaits(new HashSet<>(Set.of("ateliers")));
        source.setJoursIndisponibles(new HashSet<>(Set.of(LocalDate.of(2030, 7, 18))));
        source.setModifieLe(Instant.parse("2030-01-01T00:00:00Z"));
        Instant lu = Instant.parse("2030-02-02T00:00:00Z");

        Animateur copie = GrilleCompetences.withCompetences(source, Map.of("ateliers", NiveauCompetence.REFERENT), lu);

        assertThat(copie.getCompetences()).containsExactlyEntriesOf(Map.of("ateliers", NiveauCompetence.REFERENT));
        assertThat(copie.getModifieLe()).isEqualTo(lu);
        assertThat(copie.getPrenom()).isEqualTo("Alice");
        assertThat(copie.getNom()).isEqualTo("Martin");
        assertThat(copie.getDateNaissance()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(copie.getEmail()).isEqualTo("a1@example.org");
        assertThat(copie.getSouhaits()).containsExactly("ateliers");
        assertThat(copie.getJoursIndisponibles()).containsExactly(LocalDate.of(2030, 7, 18));
        // The source is what the caller read: a refused write must leave it as it was.
        assertThat(source.getCompetences()).containsExactlyEntriesOf(Map.of("jeux", NiveauCompetence.DEBUTANT));
        assertThat(source.getModifieLe()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void aNullMapCopiesAsNoAppreciationAtAll() {
        Animateur copie = GrilleCompetences.withCompetences(animateur("A1", Map.of()), null, null);
        assertThat(copie.getCompetences()).isEmpty();
        assertThat(copie.getModifieLe()).isNull();
    }

    private static Animateur animateur(String id, Map<String, NiveauCompetence> competences) {
        Animateur animateur = new Animateur(id, "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(new LinkedHashMap<>(competences));
        return animateur;
    }
}
