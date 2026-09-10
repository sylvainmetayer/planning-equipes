package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The meal break (issue #438): a ten-hour day chaining 10-12, 12-13, 13-14 and
 * 14-20 used to score zero hard, because no rule modelled lunch at all.
 *
 * <p>The reference dataset is the reported one — édition demo-festival-2026, A84,
 * 13/07 — under the midday window that edition really runs: 12:00-14:00 owing
 * 60 minutes.</p>
 */
class RepasConstraintsTest extends ConstraintTestBase {

    private static final FenetreRepas MIDI =
            new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60);
    private static final FenetreRepas SOIR =
            new FenetreRepas(FenetreRepas.SOIR, LocalTime.of(19, 0), LocalTime.of(21, 0), 60);

    private final Stand standA = standWithStrategy("STAND-A");
    private final Stand standB = standWithStrategy("STAND-B");
    private final Stand standC = standWithStrategy("STAND-C");
    private final Animateur a84 = majeurAutonome("A84");

    private static Creneau vacation(String id, int debutHeure, int finHeure) {
        return creneau(id, 1, D1, LocalTime.of(debutHeure, 0), LocalTime.of(finHeure, 0));
    }

    // --- coupureRepasObligatoire -------------------------------------------

    @Test
    void dixHeuresSansCoupureCoutentLaCoupureEntiere() {
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standA, vacation("12-13", 12, 13), a84),
                        poste(standB, vacation("13-14", 13, 14), a84),
                        poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(60);
    }

    @Test
    void laCoupureDeMidiSuffit() {
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standB, vacation("13-14", 13, 14), a84),
                        poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(0);
    }

    @Test
    void laCoupureDeTreizeHeuresSuffitAussi() {
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standA, vacation("12-13", 12, 13), a84),
                        poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(0);
    }

    /**
     * A break running 13:45-14:45 is a real hour off, but only its first
     * quarter falls inside the window — the window is the meal service, not a
     * suggestion. 60 required, 15 available, 45 missing.
     */
    @Test
    void uneCoupureQuiDebordeNeCompteQuePourSaPartInterne() {
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, creneau("10-13h45", 1, D1, LocalTime.of(10, 0), LocalTime.of(13, 45)), a84),
                        poste(standC, creneau("14h45-20", 1, D1, LocalTime.of(14, 45), LocalTime.of(20, 0)), a84))
                .penalizesBy(45);
    }

    @Test
    void finirSaJourneeALaFermetureDeLaFenetreNeDoitRien() {
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standA, vacation("12-14", 12, 14), a84))
                .penalizesBy(0);
    }

    /** « Si on commence son shift à 19 h, on considère que les gens se sont arrangés pour manger avant. » */
    @Test
    void commencerSonShiftALOuvertureDeLaFenetreNeDoitRien() {
        verify("coupureRepasObligatoire")
                .given(SOIR, poste(standC, vacation("19-23", 19, 23), a84))
                .penalizesBy(0);
    }

    @Test
    void unJourAChevalSurLesDeuxFenetresDoitDeuxCoupures() {
        verify("coupureRepasObligatoire")
                .given(MIDI, SOIR, poste(standA, vacation("10-23", 10, 23), a84))
                .penalizesBy(120);
    }

    /**
     * The heart of the issue: the twenty-minute legal break taken on the post,
     * by relay, and the meal break are two distinct objects. The parameter that
     * neutralises {@code travailContinuMaxMajeur} must not carry away this one.
     */
    @Test
    void laPauseSurPosteNeNeutralisePasLaCoupureRepas() {
        ParametresLegaux pauseSurPoste = new ParametresLegaux();
        pauseSurPoste.setPauseSurPoste(true);

        verify("coupureRepasObligatoire")
                .given(MIDI, pauseSurPoste, poste(standA, vacation("10-20", 10, 20), a84))
                .penalizesBy(60);
    }

    /** Nothing is owed by a day that never crosses the window. */
    @Test
    void uneJourneeEntierementApresLaFenetreNeDoitRien() {
        verify("coupureRepasObligatoire")
                .given(MIDI, poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(0);
    }

    /** Two animateurs, two days: each day is judged on its own seats. */
    @Test
    void chaqueAnimateurEtChaqueJourEstJugeSeparement() {
        Animateur autre = majeurAutonome("A85");
        verify("coupureRepasObligatoire")
                .given(
                        MIDI,
                        poste(standA, vacation("10-20", 10, 20), a84),
                        poste(standB, creneau("J2-10-20", 2, D2, LocalTime.of(10, 0), LocalTime.of(20, 0)), autre))
                .penalizesBy(120);
    }

    /**
     * A meal break <b>is</b> a break: an hour off resets the six-hour counter
     * of art. L3121-16, so a day the rule would otherwise refuse becomes legal
     * once the break is taken. Nothing in the code says so explicitly — it
     * falls out of {@code longestSequenceMinutes} splitting a stretch on any
     * gap of twenty minutes or more — which is exactly why it is locked here:
     * a change to that threshold, or to how the meal break is modelled, must
     * not silently make the two rules disagree.
     */
    @Test
    void laCoupureRepasRemetLeCompteurDesSixHeuresAZero() {
        Creneau journeeEntiere = creneau("07-19", 1, D1, LocalTime.of(7, 0), LocalTime.of(19, 0));

        // Twelve hours in one go: six hours over the uninterrupted maximum.
        verify("travailContinuMaxMajeur")
                .given(new ParametresLegaux(), poste(standA, journeeEntiere, a84))
                .penalizesBy(6 * 60);

        // The same twelve hours cut by the midday break: two stretches of five
        // and six hours, neither of them over.
        verify("travailContinuMaxMajeur")
                .given(
                        new ParametresLegaux(),
                        poste(standA, vacation("07-12", 7, 12), a84),
                        poste(standC, vacation("13-19", 13, 19), a84))
                .penalizesBy(0);
    }

    /** And that same day owes nothing on the meal rule: the hour is there, inside the window. */
    @Test
    void laMemeJourneeCoupeeSatisfaitLaRegleRepas() {
        FenetreRepas midiUneHeure = new FenetreRepas(FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60);

        verify("coupureRepasObligatoire")
                .given(
                        midiUneHeure,
                        poste(standA, vacation("07-12", 7, 12), a84),
                        poste(standC, vacation("13-19", 13, 19), a84))
                .penalizesBy(0);
    }

    // --- coupureRepasAuPlusTot ---------------------------------------------

    @Test
    void laCoupureDeMidiNeCoutePasDeSoft() {
        verify("coupureRepasAuPlusTot")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standB, vacation("13-14", 13, 14), a84),
                        poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(0);
    }

    @Test
    void laCoupureDeTreizeHeuresCouteSonHeureDeRetard() {
        verify("coupureRepasAuPlusTot")
                .given(
                        MIDI,
                        poste(standA, vacation("10-12", 10, 12), a84),
                        poste(standA, vacation("12-13", 12, 13), a84),
                        poste(standC, vacation("14-20", 14, 20), a84))
                .penalizesBy(60);
    }

    @Test
    void aucunePreferenceQuandAucuneCoupureNeTient() {
        verify("coupureRepasAuPlusTot")
                .given(MIDI, poste(standA, vacation("10-20", 10, 20), a84))
                .penalizesBy(0);
    }
}
