package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.SplitSeatFixture;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.edition.EtiquetteEdition;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * The documents of a plan holding a seat split on the day and a narrowed one
 * (ADR 0066, {@link SplitSeatFixture}): each person's part on its own window,
 * one place counted once, the teammates who were really there.
 */
class SplitSeatExportsTest {

    private static final EtiquetteEdition EDITION =
            new EtiquetteEdition("Édition de test", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-12"));

    private static final ExportProvenance PROVENANCE = new ExportProvenance() {
        @Override
        public Provenance courante() {
            return new Provenance(EDITION, Instant.parse("2026-07-11T07:30:00Z"), Nature.RESOLUTION);
        }

        @Override
        public Provenance publiee() {
            return new Provenance(EDITION, Instant.parse("2026-07-10T17:00:00Z"), Nature.PUBLICATION);
        }
    };

    private static final TypologieLibelles TYPOLOGIES =
            () -> Map.of("STRATEGIE", "Jeux de stratégie", "AMBIANCE", "Jeux d'ambiance");

    private final PlanningExportService service = new PlanningExportService(
            new ApplicationLinks(Optional.empty()),
            new AnimateurPlanningPdf(new PdfTheme(), TYPOLOGIES),
            new AnimateurFeuillePdf(new PdfTheme(), TYPOLOGIES),
            new GlobalPlanningPdf(new PdfTheme(), TYPOLOGIES),
            new PlanningIcs(),
            PROVENANCE,
            null);

    private final SplitSeatFixture fixture = new SplitSeatFixture();
    private final PlanningEvenement plan = fixture.plan();

    @Test
    void theTeammatesAreThoseOnTheStandAtACommonMoment() {
        assertThat(PlanningExportService.teammatesByPoste(plan, "A-ADA"))
                .containsExactly(entry("p1", List.of("Cyd Charisse")));
        assertThat(PlanningExportService.teammatesByPoste(plan, "A-BOB"))
                .containsExactly(entry("p1~0920", List.of("Cyd Charisse")));
        assertThat(PlanningExportService.teammatesByPoste(plan, "A-CYD"))
                .containsExactly(entry("p2", List.of("Ada Lovelace", "Bob Kahn")));
    }

    @Test
    void theCalendarCarriesEachPartOnItsOwnWindow() {
        assertThat(ics("A-ADA"))
                .contains("DTSTART;TZID=Europe/Paris:20260711T090000")
                .contains("DTEND;TZID=Europe/Paris:20260711T092000");
        assertThat(ics("A-BOB"))
                .contains("DTSTART;TZID=Europe/Paris:20260711T092000")
                .contains("DTEND;TZID=Europe/Paris:20260711T120000");
    }

    /** The rest of a 22:00-02:00 night split at 01:00 is at 01:00 the next day, never 21 hours early. */
    @Test
    void theRestOfANightSplitAfterMidnightFallsOnTheNextDay() {
        Creneau nightSlot = new Creneau(2L, 1, SplitSeatFixture.SATURDAY, LocalTime.of(22, 0), LocalTime.of(2, 0));
        PosteAffectation origin = new PosteAffectation("n1", fixture.circus, nightSlot);
        origin.setAnimateur(fixture.ada);
        origin.setHeureFinEffective(LocalTime.of(1, 0));
        PosteAffectation rest = new PosteAffectation("n1~0100", fixture.circus, nightSlot);
        rest.setAnimateur(fixture.bob);
        rest.setHeureDebutEffective(LocalTime.of(1, 0));
        rest.setSuiteDe("n1");
        PlanningEvenement night = new PlanningEvenement();
        night.setAnimateurs(List.of(fixture.ada, fixture.bob));
        night.setPostes(List.of(origin, rest));

        assertThat(new PlanningIcs().exportAnimateurIcs(night, "A-ADA", List.<PauseAnalyzer.PauseAnimateurView>of()))
                .contains("DTSTART;TZID=Europe/Paris:20260711T220000")
                .contains("DTEND;TZID=Europe/Paris:20260712T010000");
        assertThat(new PlanningIcs().exportAnimateurIcs(night, "A-BOB", List.<PauseAnalyzer.PauseAnimateurView>of()))
                .contains("DTSTART;TZID=Europe/Paris:20260712T010000")
                .contains("DTEND;TZID=Europe/Paris:20260712T020000");
    }

    @Test
    void theOverviewCountsThreeSeatsOneEmptyAndSixHoursWorked() throws IOException {
        String text = firstPage(service.exportGlobalPdf(plan));

        assertThat(text).containsPattern("\\b3\\s+sièges, 1 non pourvu").containsPattern("\\b6 h\\s+travaillées");
    }

    private String ics(String animateurId) {
        return new PlanningIcs().exportAnimateurIcs(plan, animateurId, List.<PauseAnalyzer.PauseAnimateurView>of());
    }

    private static String firstPage(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }
}
