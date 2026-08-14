package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.mcp.AnimateurMcpTools.AnimateurView;

/**
 * Privacy regression test (issue #107): {@link AnimateurView} must never
 * carry nom/prénom/date de naissance — its record shape already enforces
 * that structurally — and the majeur/mineur status it derives from the
 * (unexposed) birth date must be correct.
 */
class AnimateurMcpToolsTest {

    @Test
    void deriveStatutMineurQuandMoinsDe18AnsALaDateDeReference() {
        Animateur animateur = new Animateur("A-1", "Ada", "Lovelace", LocalDate.of(2010, 6, 1), false);
        animateur.setCompetences(Map.of("cirque", NiveauCompetence.AUTONOME));
        animateur.setSouhaits(Set.of("cirque"));

        AnimateurView view = AnimateurMcpTools.toView(animateur, LocalDate.of(2026, 8, 14));

        assertThat(view.id()).isEqualTo("A-1");
        assertThat(view.statut()).isEqualTo("mineur");
        assertThat(view.manager()).isFalse();
        assertThat(view.competences()).containsEntry("cirque", NiveauCompetence.AUTONOME);
        assertThat(view.souhaits()).containsExactly("cirque");
    }

    @Test
    void deriveStatutMajeurQuand18AnsOuPlusALaDateDeReference() {
        Animateur animateur = new Animateur("A-2", "Grace", "Hopper", LocalDate.of(2000, 1, 1), true);

        AnimateurView view = AnimateurMcpTools.toView(animateur, LocalDate.of(2026, 8, 14));

        assertThat(view.statut()).isEqualTo("majeur");
        assertThat(view.manager()).isTrue();
    }

    @Test
    void neRenvoieAucuneDonneePersonnelleIdentifiante() {
        // AnimateurView n'a structurellement aucun accesseur nom/prenom/dateNaissance :
        // toute régression qui en ajouterait un ferait échouer cette liste de composants.
        assertThat(AnimateurView.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .containsExactlyInAnyOrder("id", "statut", "manager", "competences", "souhaits", "joursindisponibles");
    }
}
