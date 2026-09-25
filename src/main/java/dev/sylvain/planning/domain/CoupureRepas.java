package dev.sylvain.planning.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What one animateur's day owes, and gets, on one {@link FenetreRepas} — the
 * single calculation behind {@code coupureRepasObligatoire},
 * {@code coupureRepasPlacementPrefere} and the Pauses screen. Written once so the
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
 * that is 12:00-13:00 or 13:00-14:00 — the two slots the organiser's grid cuts for the
 * midday rotation — and also anything in between, such as 12:30-13:30. A break
 * running 13:45-14:45 does not count: it leaves the window, whose bounds are
 * the meal service, not a vague suggestion.</p>
 *
 * <p>{@link #plusGrandTrouMinutes()} is what the hard rule measures its
 * shortfall against, so the penalty is a gradient — a day 15 minutes short
 * costs less than one that never stops — and the solver has something to
 * follow down. {@link #debutPremierTrouMinutes()} and
 * {@link #debutDernierTrouMinutes()} are the earliest and the latest a break
 * could start, which is what the soft rule reads to prefer one end of the
 * window over the other — early in the evening, late at midday (issue #596).</p>
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
 *                                <i>earliest</i> break that fits; {@code null}
 *                                when none does
 * @param debutDernierTrouMinutes start of the <i>latest</i> break that fits —
 *                                the last long-enough stretch, entered as late
 *                                as it can be. Equal to the first one when a
 *                                single tight stretch leaves no choice, and
 *                                {@code null} with it
 */
public record CoupureRepas(
        FenetreRepas fenetre,
        boolean due,
        int plusGrandTrouMinutes,
        Integer debutPremierTrouMinutes,
        Integer debutDernierTrouMinutes) {

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

    /**
     * How early the break has to be taken, in minutes before the last moment
     * the window allows; 0 when it can be taken at that last moment, and 0 too
     * when no break is owed or none fits. The mirror of {@link #retardMinutes()},
     * for the window whose preference points the other way.
     */
    public int avanceMinutes() {
        if (debutDernierTrouMinutes == null) {
            return 0;
        }
        int auPlusTard = fenetre.finMinutes() - fenetre.dureeMinutes();
        return Math.max(0, auPlusTard - debutDernierTrouMinutes);
    }

    /** What this window's preference costs: minutes away from the end it points to. */
    public int preferredSlotGapMinutes() {
        return fenetre.auPlusTard() ? avanceMinutes() : retardMinutes();
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
        if (!worksAround(postesDuJour, ouverture, fermeture)) {
            return new CoupureRepas(fenetre, false, 0, null, null);
        }
        List<int[]> occupes = occupiedInside(postesDuJour, ouverture, fermeture);
        occupes.sort(Comparator.comparingInt(intervalle -> intervalle[0]));
        GapScan scan = new GapScan(fenetre.dureeMinutes());
        int curseur = ouverture;
        for (int[] occupe : occupes) {
            scan.gap(curseur, occupe[0]);
            curseur = Math.max(curseur, occupe[1]);
        }
        scan.gap(curseur, fermeture);
        return new CoupureRepas(fenetre, true, scan.plusGrandTrou, scan.premierTrou, scan.dernierTrou);
    }

    /** Whether a seat starts before the window opens and a seat ends after it closes. */
    private static boolean worksAround(List<PosteAffectation> postesDuJour, int ouverture, int fermeture) {
        boolean travailleAvant = false;
        boolean travailleApres = false;
        for (PosteAffectation poste : postesDuJour) {
            LocalTime debut = poste.heureDebutEffectif();
            if (debut != null) {
                int debutMinutes = debut.toSecondOfDay() / 60;
                travailleAvant |= debutMinutes < ouverture;
                travailleApres |= debutMinutes + poste.getDureeEffectiveMinutes() > fermeture;
            }
        }
        return travailleAvant && travailleApres;
    }

    /** The seats clipped to the window, as {@code [start, end]} minutes; those outside it left out. */
    private static List<int[]> occupiedInside(List<PosteAffectation> postesDuJour, int ouverture, int fermeture) {
        List<int[]> occupes = new ArrayList<>();
        for (PosteAffectation poste : postesDuJour) {
            LocalTime debut = poste.heureDebutEffectif();
            if (debut != null) {
                int debutMinutes = debut.toSecondOfDay() / 60;
                int bas = Math.max(debutMinutes, ouverture);
                int haut = Math.min(debutMinutes + poste.getDureeEffectiveMinutes(), fermeture);
                if (haut > bas) {
                    occupes.add(new int[] {bas, haut});
                }
            }
        }
        return occupes;
    }

    /** The free stretches of the window, read in order. */
    private static final class GapScan {
        private final int requis;
        private int plusGrandTrou;
        private Integer premierTrou;
        // The latest a break could start: inside the LAST long-enough stretch,
        // pushed to its own end. A day leaving the whole window free must not
        // read as « eats at noon » when the window prefers late — the person
        // picks, and the preference reads what they could pick (issue #596).
        private Integer dernierTrou;

        GapScan(int requis) {
            this.requis = requis;
        }

        /** The free stretch {@code [debut, fin)}, when not empty. */
        void gap(int debut, int fin) {
            if (fin <= debut) {
                return;
            }
            int trou = fin - debut;
            plusGrandTrou = Math.max(plusGrandTrou, trou);
            if (trou >= requis) {
                if (premierTrou == null) {
                    premierTrou = debut;
                }
                dernierTrou = fin - requis;
            }
        }
    }
}
