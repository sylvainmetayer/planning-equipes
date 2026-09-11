package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure half of the competences grid: how a cell reads, how the CSV is written, how a fiche is copied. */
class GrilleCompetencesTest {

    /* -------------------------------- cells -------------------------------- */

    @Test
    void aBlankCellSaysNothingRatherThanNoAppreciation() {
        GrilleCompetences.CelluleCompetence lue = GrilleCompetences.cellule("   ");
        assertThat(lue.vide()).isTrue();
        assertThat(lue.lisible()).isTrue();
        assertThat(lue.niveau()).isNull();
        assertThat(GrilleCompetences.cellule(null).vide()).isTrue();
    }

    @Test
    void aLevelReadsInAnyCaseWithOrWithoutAccents() {
        assertThat(GrilleCompetences.cellule("référent").niveau()).isEqualTo(NiveauCompetence.REFERENT);
        assertThat(GrilleCompetences.cellule("Debutant").niveau()).isEqualTo(NiveauCompetence.DEBUTANT);
        assertThat(GrilleCompetences.cellule(" AUTONOME ").niveau()).isEqualTo(NiveauCompetence.AUTONOME);
    }

    /** The digits are the keys of the screen: 1, 2, 3 in the order of the enum. */
    @Test
    void theKeyboardDigitsReadAsLevels() {
        assertThat(GrilleCompetences.level("1")).contains(NiveauCompetence.DEBUTANT);
        assertThat(GrilleCompetences.level("2")).contains(NiveauCompetence.AUTONOME);
        assertThat(GrilleCompetences.level("3")).contains(NiveauCompetence.REFERENT);
        assertThat(GrilleCompetences.level("0")).isEmpty();
        assertThat(GrilleCompetences.level("4")).isEmpty();
    }

    /** Neither "0" nor a dash removes anything: the file never removes, and a word that is no level is refused. */
    @Test
    void anythingElseIsUnreadable() {
        for (String texte : List.of("expert", "0", "-", "oui", "12")) {
            GrilleCompetences.CelluleCompetence lue = GrilleCompetences.cellule(texte);
            assertThat(lue.lisible()).as(texte).isFalse();
            assertThat(lue.vide()).as(texte).isFalse();
        }
    }

    /* --------------------------------- CSV --------------------------------- */

    @Test
    void theCsvCarriesIdsOnlyOneLevelNamePerCell() {
        Animateur alice = animateur("A1", Map.of("jeux", NiveauCompetence.REFERENT));
        Animateur bruno = animateur("B2", Map.of());
        bruno.setCompetences(null);

        String csv = GrilleCompetences.csv(List.of(alice, bruno), List.of("jeux", "ateliers"));

        assertThat(csv).isEqualTo("animateur;jeux;ateliers\nA1;REFERENT;\nB2;;\n");
        assertThat(csv).doesNotContain("Alice").doesNotContain("Martin");
    }

    @Test
    void aFieldCarryingTheSeparatorIsQuoted() {
        Animateur animateur = animateur("A;1", Map.of("jeux \"de rôle\"", NiveauCompetence.DEBUTANT));

        String csv = GrilleCompetences.csv(List.of(animateur), List.of("jeux \"de rôle\""));

        assertThat(csv).isEqualTo("animateur;\"jeux \"\"de rôle\"\"\"\n\"A;1\";DEBUTANT\n");
    }

    /* --------------------------------- copy -------------------------------- */

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
