package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The relay half of the single break rule (ADR 0048), on the shape found on the
 * 2026 edition: a meal-relief seat followed by a full afternoon on a
 * single-seat stand, seven hours alone, a break due at 19:00 that nobody can
 * relay.
 *
 * <p>These cases used to belong to {@code pauseSurPosteSansRelais}, a rule of
 * its own that only spoke when the organiser had ticked « pause prise sur le
 * poste ». There is no tick and no second rule: {@code travailContinuMaxMajeur}
 * asks for a hole <b>or</b> a relay, and charges one hard point per break that
 * gets neither.</p>
 */
class PauseRelayeeTest extends ConstraintTestBase {

    private final Stand seul = standWithStrategy("STAND-SEUL");
    private final Stand releve = standWithStrategy("STAND-RELEVE");
    private final Animateur a1 = majeurAutonome("A1");
    private final Animateur a2 = majeurAutonome("A2");
    private final Creneau treizeQuatorze = creneau("13-14", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
    private final Creneau apresMidi = creneau("14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));

    private static ParametresLegaux parametres() {
        return new ParametresLegaux();
    }

    @Test
    void septHeuresSeulSurSonStandCoutentLaPauseSansRelais() {
        verify("travailContinuMaxMajeur")
                .given(parametres(), poste(releve, treizeQuatorze, a1), poste(seul, apresMidi, a1))
                .penalizesBy(1);
    }

    @Test
    void unCollegueSurLeStandALaSixiemeHeureRelaie() {
        verify("travailContinuMaxMajeur")
                .given(
                        parametres(),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(seul, apresMidi, a2))
                .penalizesBy(0);
    }

    /**
     * The other way out of the same breach: a hole of at least the break splits
     * the stretch, and neither half reaches the cap. Nobody has to relay
     * anything.
     */
    @Test
    void unTrouDeLaDureeDeLaPauseDispenseDeToutRelais() {
        Creneau apresLeTrou = creneau("1430-2030", 1, D1, LocalTime.of(14, 30), LocalTime.of(20, 30));

        verify("travailContinuMaxMajeur")
                .given(parametres(), poste(releve, treizeQuatorze, a1), poste(seul, apresLeTrou, a1))
                .penalizesBy(0);
    }

    private static PosteAffectation passe(PosteAffectation poste) {
        poste.setPasse(true);
        return poste;
    }

    /**
     * The past is frozen (ADR 0044): a break owed inside a stretch already
     * worked is charged to nobody, even when the same day still holds a seat
     * ahead. Its seats are pinned, so a hard écart there could never be
     * repaired — a re-solve started mid-event would never reach zero again.
     *
     * <p>The evening seat is what makes this case worth a test: the rule
     * groups by animateur <em>and day</em>, so the day-level « is anything
     * still ahead » guard answers yes, and only the break's own seat says
     * otherwise.</p>
     */
    @Test
    void unePauseDueDansUneSequencePasseeNEstReprocheeAPersonne() {
        Creneau soiree = creneau("21-23", 1, D1, LocalTime.of(21, 0), LocalTime.of(23, 0));

        verify("travailContinuMaxMajeur")
                .given(
                        parametres(),
                        passe(poste(releve, treizeQuatorze, a1)),
                        passe(poste(seul, apresMidi, a1)),
                        poste(seul, soiree, a1))
                .penalizesBy(0);
    }

    /** The same shape with nothing behind us: the break is owed, and charged. */
    @Test
    void laMemeJourneeEntierementAVenirCouteSaPauseSansRelais() {
        Creneau soiree = creneau("21-23", 1, D1, LocalTime.of(21, 0), LocalTime.of(23, 0));

        verify("travailContinuMaxMajeur")
                .given(
                        parametres(),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(seul, soiree, a1))
                .penalizesBy(1);
    }

    @Test
    void unCollegueSurUnAutreStandNeRelaiePas() {
        verify("travailContinuMaxMajeur")
                .given(
                        parametres(),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(releve, apresMidi, a2))
                .penalizesBy(1);
    }

    @Test
    void sixHeuresExactementNeDoiventRien() {
        verify("travailContinuMaxMajeur")
                .given(parametres(), poste(seul, apresMidi, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxPersonnesSeulesCoutentDeuxFois() {
        Stand autreSeul = standWithStrategy("STAND-SEUL-2");
        verify("travailContinuMaxMajeur")
                .given(
                        parametres(),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(releve, treizeQuatorze, a2),
                        poste(autreSeul, apresMidi, a2))
                .penalizesBy(2);
    }

    /** Counted, never reproached (ADR 0044): a day entirely worked owes nobody a relay any more. */
    @Test
    void aBreakWithoutRelayOnADayAlreadyWorkedIsHistory() {
        verify("travailContinuMaxMajeur")
                .given(parametres(), postePasse(releve, treizeQuatorze, a1), postePasse(seul, apresMidi, a1))
                .penalizesBy(0);
        verify("travailContinuMaxMajeur")
                .given(parametres(), postePasse(releve, treizeQuatorze, a1), poste(seul, apresMidi, a1))
                .penalizesBy(1);
    }
}
