package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Privacy regression test (issue #107) on the one place where an animateur's
 * name could still slip out over MCP: the human-readable violation lines
 * {@code ViolationFormatter} builds for the web UI, which label an animateur
 * as "Prénom Nom (id)" and an ad hoc constraint as "TYPE id (raison)".
 */
class AnonymisationViolationsTest {

    private static final LocalDate NAISSANCE = LocalDate.of(2000, 1, 1);

    private static final AnonymisationViolations SANS_REFERENTIEL =
            AnonymisationViolations.of(List.of(), List.of(), List.of());

    private static AnonymisationViolations withAnimateurs(Animateur... animateurs) {
        return AnonymisationViolations.of(List.of(animateurs), List.of(), List.of());
    }

    private static Animateur animateur(String id, String prenom, String nom) {
        return new Animateur(id, prenom, nom, NAISSANCE, false);
    }

    @Test
    void replacesTheNameOfAnAnimateurByItsId() {
        assertThat(withAnimateurs(animateur("A45", "Sarah", "Rousseau")).anonymiser("Sarah Rousseau (A45)"))
                .isEqualTo("animateur A45");
    }

    @Test
    void anonymisesTheAnimateurOfASeatLine() {
        String ligne = withAnimateurs(animateur("A7", "Jean-Pierre", "Le Goff"))
                .anonymiser("Stand tir à l'arc — 2026-07-16 12:30-15:30 (Jean-Pierre Le Goff (A7))");

        assertThat(ligne).isEqualTo("Stand tir à l'arc — 2026-07-16 12:30-15:30 (animateur A7)");
    }

    @Test
    void anonymisesEveryAnimateurOfAListOfFacts() {
        assertThat(withAnimateurs(animateur("A1", "Ada", "Lovelace"), animateur("A2", "Alan", "Turing"))
                        .anonymiser(List.of("Ada Lovelace (A1) — Alan Turing (A2)", "poste P1 non pourvu")))
                .containsExactly("animateur A1 — animateur A2", "poste P1 non pourvu");
    }

    /**
     * The shapes the two-word pattern let through verbatim. A fiche imported
     * with a single name, an initial, a digit, an id carrying a space: each is
     * known to the referential, so each is replaced whatever it looks like.
     */
    @Test
    void anonymisesAKnownAnimateurWhateverTheShapeOfItsName() {
        AnonymisationViolations anonymisation = withAnimateurs(
                animateur("A1", "Marie", ""),
                animateur("A2", "Jean", "D."),
                animateur("A3", "Louis", "XIV 2"),
                animateur("Marie Curie", "Marie", "Curie"));

        assertThat(anonymisation.anonymiser(
                        List.of("Marie (A1)", "Jean D. (A2)", "Louis XIV 2 (A3)", "Marie Curie (Marie Curie)")))
                .containsExactly("animateur A1", "animateur A2", "animateur A3", "animateur Marie Curie");
    }

    /** A line outlives its subject: the stored analysis may name an animateur deleted since. */
    @Test
    void anonymisesADeletedAnimateurWithASingleName() {
        assertThat(SANS_REFERENTIEL.anonymiser("Coin jeux — 2026-07-16 10:00-12:00 (Marie (A1))"))
                .isEqualTo("Coin jeux — 2026-07-16 10:00-12:00 (animateur A1)");
        assertThat(SANS_REFERENTIEL.anonymiser("Jean D. (A2) dépasse 8 h")).isEqualTo("animateur A2 dépasse 8 h");
    }

    @Test
    void sparesAKnownStandWhoseNameLooksLikeAnAnimateurLabel() {
        Stand stand = new Stand();
        stand.setId("s1");
        stand.setNom("Coin (extérieur)");
        AnonymisationViolations anonymisation = AnonymisationViolations.of(List.of(), List.of(), List.of(stand));

        assertThat(anonymisation.anonymiser("Coin (extérieur) — 2026-07-16 10:00-12:00"))
                .isEqualTo("Coin (extérieur) — 2026-07-16 10:00-12:00");
    }

    /** The reason is free text: « Marie garde ses enfants » is exactly what must not travel. */
    @Test
    void dropsTheReasonOfAKnownAdHocConstraint() {
        ContrainteAdHoc contrainte = new ContrainteAdHoc("AH1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        contrainte.setRaison("Marie garde ses enfants (mercredi)");
        AnonymisationViolations anonymisation = AnonymisationViolations.of(List.of(), List.of(contrainte), List.of());

        assertThat(anonymisation.anonymiser(
                        "INDISPONIBILITE_FORCEE AH1 (Marie garde ses enfants (mercredi)) — 2026-07-16 10:00-12:00"))
                .isEqualTo("INDISPONIBILITE_FORCEE AH1 — 2026-07-16 10:00-12:00");
    }

    @Test
    void dropsTheReasonOfADeletedAdHocConstraint() {
        assertThat(SANS_REFERENTIEL.anonymiser("AFFECTATION_FORCEE AH9 (rendez-vous médical)"))
                .isEqualTo("AFFECTATION_FORCEE AH9");
    }

    @Test
    void leavesLinesWithoutANameUntouched() {
        assertThat(SANS_REFERENTIEL.anonymiser("Stand tir à l'arc — 2026-07-16 12:30-15:30"))
                .isEqualTo("Stand tir à l'arc — 2026-07-16 12:30-15:30");
    }

    @Test
    void toleratesNull() {
        assertThat(SANS_REFERENTIEL.anonymiser((String) null)).isNull();
        assertThat(SANS_REFERENTIEL.anonymiser((List<String>) null)).isEmpty();
    }
}
