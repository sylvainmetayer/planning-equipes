package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Privacy regression test (issue #107) on the one place where an animateur's
 * name could still slip out over MCP: the human-readable violation lines
 * {@code ViolationFormatter} builds for the web UI, which label an animateur
 * as "Prénom Nom (id)".
 */
class AnonymisationViolationsTest {

    @Test
    void remplaceLeNomDeLAnimateurParSonId() {
        assertThat(AnonymisationViolations.anonymiser("Sarah Rousseau (A45)")).isEqualTo("animateur A45");
    }

    @Test
    void anonymiseLAnimateurDansUneLigneDePoste() {
        String ligne = AnonymisationViolations.anonymiser(
                "Stand tir à l'arc — 2026-07-16 12:30-15:30 (Jean-Pierre Le Goff (A7))");

        assertThat(ligne).doesNotContain("Jean-Pierre").doesNotContain("Le Goff");
        assertThat(ligne).isEqualTo("Stand tir à l'arc — 2026-07-16 12:30-15:30 (animateur A7)");
    }

    @Test
    void anonymiseChaqueAnimateurDUneListeDeFaits() {
        assertThat(AnonymisationViolations.anonymiser(
                        List.of("Ada Lovelace (A1) — Alan Turing (A2)", "poste P1 non pourvu")))
                .containsExactly("animateur A1 — animateur A2", "poste P1 non pourvu");
    }

    @Test
    void laisseIntactesLesLignesSansNom() {
        assertThat(AnonymisationViolations.anonymiser("Stand tir à l'arc — 2026-07-16 12:30-15:30"))
                .isEqualTo("Stand tir à l'arc — 2026-07-16 12:30-15:30");
    }

    @Test
    void toleranteAuNull() {
        assertThat(AnonymisationViolations.anonymiser((String) null)).isNull();
        assertThat(AnonymisationViolations.anonymiser((List<String>) null)).isEmpty();
    }
}
