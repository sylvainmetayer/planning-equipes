package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;

/**
 * Constat C1 de l'audit de conformité RH : `updateParametresLegaux` ne
 * vérifiait que la positivité, si bien qu'un administrateur pouvait
 * enregistrer 100 h/semaine sans le moindre avertissement — et le solveur
 * produisait alors un planning « valide » (score dur à zéro) manifestement
 * illégal.
 *
 * <p>Les règles sont des fonctions pures : une valeur acceptée est un appel
 * qui rend la main. Ces tests lisaient auparavant un
 * {@code NullPointerException} — le service était construit sans dépôt — comme
 * preuve que la validation était passée.</p>
 */
class ValidationParametresTest {

    @Test
    void plusDe48HeuresPourUnMajeurEstRefuse() {
        // Art. L3121-20, disposition d'ordre public.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierLegaux(new ParametresLegaux(100 * 60, 35 * 60)))
                .withMessageContaining("L3121-20");
    }

    @Test
    void plusDe35HeuresPourUnMineurEstRefuse() {
        // Art. L3162-1.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierLegaux(new ParametresLegaux(48 * 60, 40 * 60)))
                .withMessageContaining("L3162-1");
    }

    @Test
    void uneValeurLegaleNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierLegaux(new ParametresLegaux(0, 35 * 60)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierLegaux(new ParametresLegaux(48 * 60, -1)));
    }

    @Test
    void uneValeurInferieureAuPlafondLegalResteLibre() {
        // Plus protecteur que la loi : rien ne doit s'y opposer.
        assertThatCode(() -> ValidationParametres.verifierLegaux(new ParametresLegaux(35 * 60, 20 * 60)))
                .doesNotThrowAnyException();
    }

    /**
     * La durée de résolution vivait dans le localStorage, ce qui la rendait
     * incohérente d'un navigateur à l'autre ; elle est désormais persistée
     * côté serveur, avec la même forme de CRUD que les autres paramètres
     * réglables.
     */
    @Test
    void uneDureeDeResolutionNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierSolveur(new ParametresSolveur(0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValidationParametres.verifierSolveur(new ParametresSolveur(-1)));
    }

    @Test
    void uneDureeDeResolutionPositiveEstAcceptee() {
        assertThatCode(() -> ValidationParametres.verifierSolveur(new ParametresSolveur(120)))
                .doesNotThrowAnyException();
    }
}
