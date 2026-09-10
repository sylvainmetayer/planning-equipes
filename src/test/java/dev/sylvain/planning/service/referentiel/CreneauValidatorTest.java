package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The rule is a pure function: an accepted timeslot is a call that returns.
 * What matters here is as much what it refuses as what it must keep letting
 * through.
 */
class CreneauValidatorTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);

    private static Creneau creneau(LocalDate date, LocalTime debut, LocalTime fin) {
        return new Creneau(1L, 1, date, debut, fin);
    }

    @Test
    void uneHeureDeFinManquanteEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CreneauValidator.check(creneau(JOUR, LocalTime.of(18, 0), null)))
                .withMessageContaining("heure de fin");
    }

    @Test
    void uneHeureDeDebutManquanteEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CreneauValidator.check(creneau(JOUR, null, LocalTime.of(22, 0))));
    }

    @Test
    void uneDateManquanteEstRefusee() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CreneauValidator.check(creneau(null, LocalTime.of(18, 0), LocalTime.of(22, 0))));
    }

    /**
     * The refusal is a {@link BusinessError.Invalid} — a 400 — and not the
     * {@link IllegalStateException} the {@code NOT NULL} column used to raise,
     * which came back as a 500 and a Sentry alert for a bad request.
     */
    @Test
    void leRefusEstUneErreurMetierEtNonUnBug() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CreneauValidator.check(null))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    /**
     * The case the validation must never catch: 20:00→00:00 is a four-hour
     * night timeslot, the only shape that reads a stand window dated the next
     * day.
     */
    @Test
    void unCreneauDeNuitEstAccepte() {
        Creneau nuit = creneau(JOUR, LocalTime.of(20, 0), LocalTime.MIDNIGHT);

        assertThatCode(() -> CreneauValidator.check(nuit)).doesNotThrowAnyException();
        assertThatCode(() -> CreneauValidator.check(creneau(JOUR, LocalTime.of(22, 0), LocalTime.of(2, 0))))
                .doesNotThrowAnyException();
    }

    @Test
    void unCreneauOrdinaireEstAccepte() {
        assertThatCode(() -> CreneauValidator.check(creneau(JOUR, LocalTime.of(9, 0), LocalTime.of(18, 0))))
                .doesNotThrowAnyException();
    }
}
