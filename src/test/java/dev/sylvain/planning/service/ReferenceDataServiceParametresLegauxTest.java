package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresLegaux;

/**
 * Constat C1 de l'audit de conformité RH : `updateParametresLegaux` ne
 * vérifiait que la positivité, si bien qu'un administrateur pouvait
 * enregistrer 100 h/semaine sans le moindre avertissement — et le solveur
 * produisait alors un planning « valide » (score dur à zéro) manifestement
 * illégal.
 *
 * <p>Les cas rejetés le sont <b>avant</b> toute écriture, donc le service peut
 * être construit sans dépôt (voir son javadoc).</p>
 */
class ReferenceDataServiceParametresLegauxTest {

    private final ReferenceDataService service = new ReferenceDataService();

    @Test
    void plusDe48HeuresPourUnMajeurEstRefuse() {
        // Art. L3121-20, disposition d'ordre public.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresLegaux(new ParametresLegaux(100 * 60, 35 * 60)))
                .withMessageContaining("L3121-20");
    }

    @Test
    void plusDe35HeuresPourUnMineurEstRefuse() {
        // Art. L3162-1.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresLegaux(new ParametresLegaux(48 * 60, 40 * 60)))
                .withMessageContaining("L3162-1");
    }

    @Test
    void uneValeurNulleOuNegativeEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresLegaux(new ParametresLegaux(0, 35 * 60)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateParametresLegaux(new ParametresLegaux(48 * 60, -1)));
    }

    @Test
    void uneValeurInferieureAuPlafondLegalResteLibre() {
        // Plus protecteur que la loi : rien ne doit s'y opposer. La validation
        // passe et l'écriture échoue faute de dépôt, ce qui prouve qu'on est
        // allé jusque-là.
        assertThatCode(() -> service.updateParametresLegaux(new ParametresLegaux(35 * 60, 20 * 60)))
                .isNotInstanceOf(IllegalArgumentException.class);
    }
}
