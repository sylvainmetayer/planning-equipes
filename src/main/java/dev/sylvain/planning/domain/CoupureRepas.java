package dev.sylvain.planning.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What one animateur's day owes, and gets, on one {@link FenetreRepas} — the
 * single calculation behind {@code coupureRepasObligatoire},
 * {@code coupureRepasAuPlusTot} and the Pauses screen. Written once so the
 * solver and the read-out can never tell two stories about the same day.
 *
 * <h2>When a break is owed</h2>
 *
 * <p>When the animateur works <b>on both sides</b> of the window: a seat starts
 * before it, and a seat ends after it. Someone whose shift begins at the
 * window's start ate before coming; someone whose day ends at its close eats
 * after leaving. Neither owes anything — which is the organiser's own reading
 * of the rule, and the reason this is not simply "anybody present at midday".</p>
 *
 * <h2>What satisfies it</h2>
 *
 * <p>A free stretch of {@link FenetreRepas#dureeMinutes()} minutes lying
 * <b>entirely inside</b> the window. On a 12:00-14:00 window owing 60 minutes,
 * that is 12:00-13:00 or 13:00-14:00 — the two slots the FESTIVAL grid cuts for the
 * midday rotation — and also anything in between, such as 12:30-13:30. A break
 * running 13:45-14:45 does not count: it leaves the window, whose bounds are
 * the meal service, not a vague suggestion.</p>
 *
 * <p>{@link #plusGrandTrouMinutes()} is what the hard rule measures its
 * shortfall against, so the penalty is a gradient — a day 15 minutes short
 * costs less than one that never stops — and the solver has something to
 * follow down. {@link #debutPremierTrouMinutes()} is what the soft rule reads
 * to prefer the earlier slot.</p>
 *
 * <p>Minutes are counted from midnight of the day the seats belong to. A seat
 * running past midnight simply ends beyond 1440; a meal window never crosses
 * midnight, so the arithmetic stays on one axis.</p>
 *
 * @param fenetre                 the window this analysis is about
 * @param due                     whether a break is owed at all
 * @param plusGrandTrouMinutes    longest free stretch entirely inside the
 *                                window; 0 when nothing is owed
 * @param debutPremierTrouMinutes start, in minutes from midnight, of the
 *                                <i>first</i> free stretch long enough to be
 *                                the break; {@code null} when there is none
 */
public record CoupureRepas(
        FenetreRepas fenetre, boolean due, int plusGrandTrouMinutes, Integer debutPremierTrouMinutes) {

    /** True when a break is owed and the day does not leave room for it. */
    public boolean manquante() {
        return due && plusGrandTrouMinutes < fenetre.dureeMinutes();
    }

    /** Minutes missing from the longest free stretch; 0 when nothing is owed or the break fits. */
    public int minutesManquantes() {
        return manquante() ? fenetre.dureeMinutes() - plusGrandTrouMinutes : 0;
    }

    /**
     * How late the break starts, in minutes after the window opens; 0 when it
     * opens with it, and 0 too when no break is owed or none fits — the hard
     * rule carries that case, and a preference has nothing to say about a
     * break that does not exist.
     */
    public int retardMinutes() {
        return debutPremierTrouMinutes == null ? 0 : debutPremierTrouMinutes - fenetre.debutMinutes();
    }

    /** The break as clock times, or {@code null} when none fits. */
    public LocalTime debut() {
        return debutPremierTrouMinutes == null ? null : LocalTime.ofSecondOfDay(debutPremierTrouMinutes * 60L);
    }

    /** @see #debut() */
    public LocalTime fin() {
        return debutPremierTrouMinutes == null
                ? null
                : LocalTime.ofSecondOfDay((debutPremierTrouMinutes + fenetre.dureeMinutes()) * 60L);
    }

    /**
     * Reads one animateur's seats on one day against one window. The seats may
     * overlap and need not be sorted.
     */
    public static CoupureRepas of(List<PosteAffectation> postesDuJour, FenetreRepas fenetre) {
        int ouverture = fenetre.debutMinutes();
        int fermeture = fenetre.finMinutes();
        boolean travailleAvant = false;
        boolean travailleApres = false;
        List<int[]> occupes = new ArrayList<>();
        for (PosteAffectation poste : postesDuJour) {
            LocalTime debut = poste.heureDebutEffectif();
            if (debut == null) {
                continue;
            }
            int debutMinutes = debut.toSecondOfDay() / 60;
            int finMinutes = debutMinutes + poste.getDureeEffectiveMinutes();
            travailleAvant |= debutMinutes < ouverture;
            travailleApres |= finMinutes > fermeture;
            int bas = Math.max(debutMinutes, ouverture);
            int haut = Math.min(finMinutes, fermeture);
            if (haut > bas) {
                occupes.add(new int[] {bas, haut});
            }
        }
        if (!travailleAvant || !travailleApres) {
            return new CoupureRepas(fenetre, false, 0, null);
        }

        occupes.sort(Comparator.comparingInt(intervalle -> intervalle[0]));
        int requis = fenetre.dureeMinutes();
        int curseur = ouverture;
        int plusGrandTrou = 0;
        Integer premierTrou = null;
        for (int[] occupe : occupes) {
            if (occupe[0] > curseur) {
                int trou = occupe[0] - curseur;
                plusGrandTrou = Math.max(plusGrandTrou, trou);
                if (premierTrou == null && trou >= requis) {
                    premierTrou = curseur;
                }
            }
            curseur = Math.max(curseur, occupe[1]);
        }
        if (fermeture > curseur) {
            int trou = fermeture - curseur;
            plusGrandTrou = Math.max(plusGrandTrou, trou);
            if (premierTrou == null && trou >= requis) {
                premierTrou = curseur;
            }
        }
        return new CoupureRepas(fenetre, true, plusGrandTrou, premierTrou);
    }
}
