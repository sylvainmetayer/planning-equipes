package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * How an édition names itself on the documents it produces (issue #608).
 *
 * <p>The label exists to tell two éditions apart for somebody holding two
 * espace links; every case here is one of the ways two éditions differ.</p>
 */
class EtiquetteEditionTest {

    private static EtiquetteEdition edition(String debut, String fin) {
        return new EtiquetteEdition("Festival 26", LocalDate.parse(debut), LocalDate.parse(fin));
    }

    @Test
    void namesTheEditionAndSpellsOutItsSpan() {
        assertThat(edition("2026-02-01", "2026-02-16").libelle()).isEqualTo("Festival 26 — du 1er au 16 février 2026");
    }

    @Test
    void namesBothMonthsWhenTheEventStraddlesOne() {
        assertThat(edition("2026-01-28", "2026-02-03").periode()).isEqualTo("du 28 janvier au 3 février 2026");
    }

    /** The year is the whole point: a New Year's event must not read as one year. */
    @Test
    void repeatsTheYearOnBothSidesWhenTheEventStraddlesOne() {
        assertThat(edition("2025-12-28", "2026-01-03").periode()).isEqualTo("du 28 décembre 2025 au 3 janvier 2026");
    }

    @Test
    void saysOneDayAsOneDay() {
        assertThat(edition("2026-02-14", "2026-02-14").periode()).isEqualTo("le 14 février 2026");
    }

    /**
     * An édition with no créneau has no span at all — the domain derives the
     * bounds from the créneaux and stores nothing (`docs/domaine.md`). Its name
     * is still worth printing.
     */
    @Test
    void keepsTheNameWhenTheEditionHasNoCreneauToDeriveASpanFrom() {
        EtiquetteEdition sansDates = new EtiquetteEdition("Festival 26", null, null);

        assertThat(sansDates.periode()).isNull();
        assertThat(sansDates.libelle()).isEqualTo("Festival 26");
        assertThat(sansDates.isEmpty()).isFalse();
    }

    /** An édition that cannot be read at all says nothing rather than something wrong. */
    @Test
    void saysNothingWhenThereIsNothingToSay() {
        assertThat(EtiquetteEdition.INCONNUE.libelle()).isNull();
        assertThat(EtiquetteEdition.INCONNUE.isEmpty()).isTrue();
    }

    /** A span without a name is still an answer: it dates the document. */
    @Test
    void fallsBackOnTheSpanAloneWhenTheEditionHasNoName() {
        EtiquetteEdition anonyme =
                new EtiquetteEdition(null, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-16"));

        assertThat(anonyme.libelle()).isEqualTo("du 1er au 16 février 2026");
    }
}
