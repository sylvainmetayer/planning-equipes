package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.analyse.ScoreReading.ReadingSubject;
import dev.sylvain.planning.service.analyse.ScoreReading.ReadingTone;
import dev.sylvain.planning.service.analyse.ScoreReading.ScoreSentence;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reading is a way in to the diagnostic, never a reason to lose it: a
 * template that trips must leave the diagnostic — hence the end of the solve —
 * standing, with no sentence rather than an error.
 */
class PlanningDiagnosticReadingTest {

    @Test
    void aReadingThatFailsLeavesNoSentenceInsteadOfFailingTheDiagnostic() {
        List<ScoreSentence> sentences = PlanningDiagnosticService.readingOrNothing(() -> {
            throw new StringIndexOutOfBoundsException("charAt(0) on an empty label");
        });

        assertThat(sentences).isEmpty();
    }

    @Test
    void aReadingThatWorksGoesThroughUntouched() {
        ScoreSentence verdict = new ScoreSentence(
                ReadingSubject.VERDICT,
                ReadingTone.OK,
                "Le planning respecte toutes les règles impératives.",
                List.of());

        assertThat(PlanningDiagnosticService.readingOrNothing(() -> List.of(verdict)))
                .containsExactly(verdict);
        assertThat(PlanningDiagnosticService.<ScoreSentence>readingOrNothing(() -> null))
                .isEmpty();
    }
}
