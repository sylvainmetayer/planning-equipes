package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The shape found on the 2026 edition: a meal-relief seat followed by a full
 * afternoon on a single-seat stand, seven hours alone, a break due at 19:00
 * that nobody can relay.
 */
class PauseSurPosteRelayTest extends ConstraintTestBase {

    private final Stand seul = standWithStrategy("STAND-SEUL");
    private final Stand releve = standWithStrategy("STAND-RELEVE");
    private final Animateur a1 = majeurAutonome("A1");
    private final Animateur a2 = majeurAutonome("A2");
    private final Creneau treizeQuatorze = creneau("13-14", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
    private final Creneau apresMidi = creneau("14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));

    private static ParametresLegaux onPost(boolean actif) {
        ParametresLegaux parametres = new ParametresLegaux();
        parametres.setPauseSurPoste(actif);
        return parametres;
    }

    @Test
    void septHeuresSeulSurSonStandCoutentLaPauseSansRelais() {
        verify("pauseSurPosteSansRelais")
                .given(onPost(true), poste(releve, treizeQuatorze, a1), poste(seul, apresMidi, a1))
                .penalizesBy(1);
    }

    @Test
    void unCollegueSurLeStandALaSixiemeHeureRelaie() {
        verify("pauseSurPosteSansRelais")
                .given(
                        onPost(true),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(seul, apresMidi, a2))
                .penalizesBy(0);
    }

    @Test
    void unCollegueSurUnAutreStandNeRelaiePas() {
        verify("pauseSurPosteSansRelais")
                .given(
                        onPost(true),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(releve, apresMidi, a2))
                .penalizesBy(1);
    }

    @Test
    void sixHeuresExactementNeDoiventRien() {
        verify("pauseSurPosteSansRelais")
                .given(onPost(true), poste(seul, apresMidi, a1))
                .penalizesBy(0);
    }

    @Test
    void muetteQuandLaPauseNestPasDeclareeSurLePoste() {
        verify("pauseSurPosteSansRelais")
                .given(onPost(false), poste(releve, treizeQuatorze, a1), poste(seul, apresMidi, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxPersonnesSeulesCoutentDeuxFois() {
        Stand autreSeul = standWithStrategy("STAND-SEUL-2");
        verify("pauseSurPosteSansRelais")
                .given(
                        onPost(true),
                        poste(releve, treizeQuatorze, a1),
                        poste(seul, apresMidi, a1),
                        poste(releve, treizeQuatorze, a2),
                        poste(autreSeul, apresMidi, a2))
                .penalizesBy(2);
    }

    /** Counted, never reproached (ADR 0044): a day entirely worked owes nobody a relay any more. */
    @Test
    void aBreakWithoutRelayOnADayAlreadyWorkedIsHistory() {
        verify("pauseSurPosteSansRelais")
                .given(onPost(true), postePasse(releve, treizeQuatorze, a1), postePasse(seul, apresMidi, a1))
                .penalizesBy(0);
        verify("pauseSurPosteSansRelais")
                .given(onPost(true), postePasse(releve, treizeQuatorze, a1), poste(seul, apresMidi, a1))
                .penalizesBy(1);
    }
}
