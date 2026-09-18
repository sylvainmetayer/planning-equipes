package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsigneService#checkRepas}: a restated meal window must hold the
 * break it exists for. The solver drops a window shorter than its break as
 * one nobody could honour, so accepting it would leave the date with no rule
 * at all instead of a tighter one.
 */
class ConsigneCheckRepasTest {

    private static ConsigneEdition.RepasConsigne soir(int debut, int fin, Integer coupure) {
        return new ConsigneEdition.RepasConsigne(
                null, null, LocalTime.of(debut, 0), LocalTime.of(fin, 0), coupure, "les équipes ont mangé");
    }

    @Test
    void aWindowShorterThanTheEditionBreakIsRefused() {
        assertThatThrownBy(() -> ConsigneService.checkRepas(soir(19, 20, null), 90, "La consigne"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("plus courte que la coupure de 90 min");
    }

    @Test
    void theBreakRestatedOnTheConsigneIsTheOneTheWindowIsJudgedAgainst() {
        assertThatCode(() -> ConsigneService.checkRepas(soir(19, 20, 60), 90, "La consigne"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConsigneService.checkRepas(soir(19, 20, 61), 30, "Le préréglage"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("61 min");
    }

    @Test
    void aWindowExactlyAsLongAsTheBreakHoldsIt() {
        assertThatCode(() -> ConsigneService.checkRepas(soir(18, 19, null), 60, "La consigne"))
                .doesNotThrowAnyException();
    }

    @Test
    void halfAWindowIsRefusedBeforeItsLengthIsRead() {
        ConsigneEdition.RepasConsigne demi =
                new ConsigneEdition.RepasConsigne(LocalTime.of(12, 0), null, null, null, null, "x");
        assertThatThrownBy(() -> ConsigneService.checkRepas(demi, 60, "La consigne"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("deux bornes");
    }
}
