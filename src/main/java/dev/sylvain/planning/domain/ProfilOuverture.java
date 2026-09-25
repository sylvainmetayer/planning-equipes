package dev.sylvain.planning.domain;

import dev.sylvain.planning.domain.Creneau.SegmentOuvert;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * When, inside one créneau, a stand is open, and with how many seats: the
 * intersection of a {@link Creneau} and a {@link Stand}'s effective schedule.
 *
 * <p>This used to be the middle third of {@code Creneau} — 290 lines of
 * window geometry inside a planning fact whose own business is a date and
 * two hours (issue #392, A10). A fact that knows {@code Stand},
 * {@code IndisponibiliteStand}, {@code OuvertureStand} and the arithmetic of
 * a day crossing midnight is not a fact any more; it is this value object,
 * which {@code Creneau} now delegates to. Nothing on the scoring path calls
 * it: seats carry their effective window on {@code PosteAffectation}, so
 * this runs at problem building and in the analyses, never per move.</p>
 *
 * <p>A stand's day is in exactly one of three states — see
 * {@link OuvertureStand}'s javadoc:</p>
 * <ul>
 * <li><b>No {@link OuvertureStand} that day.</b> Open-by-default: the whole
 * créneau, unless an {@link IndisponibiliteStand} that day carves out closed
 * stretches (nothing left if it closes the slot entirely, several segments if
 * it sits strictly inside the slot).</li>
 * <li><b>At least one {@link OuvertureStand} that day.</b> Closed-by-default:
 * the segments are exactly the (possibly merged) opening windows clamped to
 * the créneau — none if none of them overlap it. Any
 * {@link IndisponibiliteStand} for that same day is ignored (the two are
 * mutually exclusive per day; {@code ReferenceDataService} enforces it at
 * write time — this class has no other way to arbitrate a conflict).</li>
 * </ul>
 *
 * <p>A window counts against the créneau only when its {@code date} is the
 * créneau's start date or — <b>for a créneau that actually crosses midnight</b>
 * — the following calendar day, which covers a window falling after midnight
 * inside such a slot (a 20:00-02:00 slot closed 00:30-01:30 on the next day).
 * A window that isn't {@code hasValidRange()} is ignored defensively — a
 * window may not itself cross midnight. A window with a {@code null}
 * {@code heureFin} runs to the end of the créneau: "open from 14:00 until
 * closing", whichever hour this particular day closes at.</p>
 *
 * <p>The windows read are the <b>effective</b> ones
 * ({@link Stand#getOuverturesEffectives()}): the dated exceptions plus
 * whatever {@link HoraireStand} rules expand to, when a resolution has run.</p>
 */
public final class ProfilOuverture {

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    private final List<SegmentOuvert> segments;

    private ProfilOuverture(List<SegmentOuvert> segments) {
        this.segments = List.copyOf(segments);
    }

    /** The open segments of {@code creneau} for {@code stand}, each with its resolved headcount. */
    public static ProfilOuverture of(Creneau creneau, Stand stand) {
        return new ProfilOuverture(segmentsOuverts(creneau, stand));
    }

    /**
     * Open sub-intervals in minutes from the créneau's start, each carrying
     * the headcount that applies to it. A slot spanning two windows of
     * different effectifs comes back as <b>two</b> segments, and poste
     * generation emits the right number of seats on each. Where two opening
     * windows overlap, the overlap takes the <b>higher</b> effectif — two
     * statements of a need over the same minutes, and satisfying the larger
     * satisfies both. Adjacent stretches of equal effectif are merged back.
     */
    public List<SegmentOuvert> segments() {
        return segments;
    }

    /**
     * The same intervals as pure opening geometry: two segments that touch
     * and differ only by their effectif are one continuous opening here, so a
     * caller that only asks "when is this stand open" gets the answer it got
     * before windows carried a headcount.
     */
    public List<int[]> segmentsMinutes() {
        List<int[]> minutes = new ArrayList<>();
        for (SegmentOuvert segment : segments) {
            int[] dernier = minutes.isEmpty() ? null : minutes.get(minutes.size() - 1);
            if (dernier != null && dernier[1] == segment.debutMinutes()) {
                dernier[1] = segment.finMinutes();
            } else {
                minutes.add(new int[] {segment.debutMinutes(), segment.finMinutes()});
            }
        }
        return minutes;
    }

    /** True when the stand is open for at least part of the créneau. */
    public boolean ouvert() {
        return !segments.isEmpty();
    }

    /* ------------------------------ geometry ------------------------------ */

    private static List<SegmentOuvert> segmentsOuverts(Creneau creneau, Stand stand) {
        int dureeMinutes = creneau.getDureeMinutes();
        if (dureeMinutes <= 0) {
            return List.of();
        }
        int dureeSecondes = dureeMinutes * 60;
        int effectifStand = stand == null ? 0 : stand.getEffectifMin();
        if (dayInOuvertureMode(creneau, stand)) {
            // The day has at least one OuvertureStand: closed-by-default. This
            // slot's open segments are exactly whichever of that day's opening
            // windows overlap it — possibly none at all, i.e. this slot is
            // fully closed even though the stand does open elsewhere that day.
            List<int[]> ouverturesSecondes = ouverturesInSeconds(creneau, stand, dureeSecondes);
            return ouverturesSecondes.isEmpty() ? List.of() : profilEffectif(ouverturesSecondes);
        }
        List<int[]> fermeturesSecondes = closingsInSeconds(creneau, stand, dureeSecondes);
        if (fermeturesSecondes.isEmpty()) {
            return List.of(new SegmentOuvert(0, dureeMinutes, effectifStand));
        }
        fermeturesSecondes.sort(Comparator.comparingInt(f -> f[0]));
        List<SegmentOuvert> ouverts = new ArrayList<>();
        int curseur = 0;
        for (int[] fermeture : fermeturesSecondes) {
            int debut = Math.max(curseur, fermeture[0]);
            if (debut > curseur) {
                ouverts.add(new SegmentOuvert(curseur / 60, debut / 60, effectifStand));
            }
            curseur = Math.max(curseur, fermeture[1]);
        }
        if (curseur < dureeSecondes) {
            ouverts.add(new SegmentOuvert(curseur / 60, dureeMinutes, effectifStand));
        }
        return ouverts;
    }

    /** Closure windows of {@code stand} overlapping the créneau, clamped to {@code [0, dureeSecondes]}, in seconds since its start. */
    private static List<int[]> closingsInSeconds(Creneau creneau, Stand stand, int dureeSecondes) {
        if (stand == null
                || stand.getIndisponibilitesEffectives().isEmpty()
                || creneau.getHeureDebut() == null
                || creneau.getDate() == null) {
            return List.of();
        }
        int debutSlotSecondes = creneau.getHeureDebut().toSecondOfDay();
        List<int[]> fermetures = new ArrayList<>();
        for (IndisponibiliteStand indispo : stand.getIndisponibilitesEffectives()) {
            int[] fermeture = clampedInSeconds(creneau, indispo, debutSlotSecondes, dureeSecondes, 2);
            if (fermeture.length > 0) {
                fermetures.add(fermeture);
            }
        }
        return fermetures;
    }

    private static final int[] NO_WINDOW = new int[0];

    /**
     * A dated window of the stand on the créneau's timeline, clamped to
     * {@code [0, dureeSecondes]}: an array of {@code size} cells whose first
     * two hold its start and end in seconds since the créneau's start, the
     * others left for the caller. Empty when the window is invalid, bears on
     * another day, or does not overlap the créneau.
     */
    private static int[] clampedInSeconds(
            Creneau creneau, FenetreDateeStand fenetre, int debutSlotSecondes, int dureeSecondes, int size) {
        int decalageJour = fenetre.hasValidRange() ? decalageJourFenetre(creneau, fenetre.getDate()) : -1;
        if (decalageJour < 0) {
            return NO_WINDOW;
        }
        int debut = Math.max(0, decalageJour + fenetre.getHeureDebut().toSecondOfDay() - debutSlotSecondes);
        int fin = fenetreEndInSeconds(fenetre.getHeureFin(), decalageJour, debutSlotSecondes, dureeSecondes);
        if (fin <= debut) {
            return NO_WINDOW;
        }
        int[] clamped = new int[size];
        clamped[0] = debut;
        clamped[1] = fin;
        return clamped;
    }

    /**
     * Offset in seconds to add to a window dated {@code dateFenetre} to place
     * it on the créneau's timeline, or {@code -1} when that date has no bearing
     * on it: {@code 0} for the créneau's own date, one day for the next one —
     * <b>only</b> when the créneau really crosses midnight, since a window
     * dated the day after a 10:00-20:00 slot cannot possibly overlap it.
     */
    private static int decalageJourFenetre(Creneau creneau, LocalDate dateFenetre) {
        if (dateFenetre.equals(creneau.getDate())) {
            return 0;
        }
        if (traverseMinuit(creneau) && dateFenetre.equals(creneau.getDate().plusDays(1))) {
            return SECONDES_PAR_JOUR;
        }
        return -1;
    }

    /**
     * End of a window on the créneau's timeline, clamped to it: a {@code null}
     * {@code heureFin} means "until closing time" and therefore lands exactly
     * on the créneau's end, whatever hour that is.
     */
    private static int fenetreEndInSeconds(
            LocalTime heureFin, int decalageJour, int debutSlotSecondes, int dureeSecondes) {
        if (heureFin == null) {
            return dureeSecondes;
        }
        return Math.min(dureeSecondes, decalageJour + heureFin.toSecondOfDay() - debutSlotSecondes);
    }

    /** True when the créneau runs past midnight, i.e. its end is at or before its start. */
    private static boolean traverseMinuit(Creneau creneau) {
        return creneau.getHeureDebut() != null
                && creneau.getHeureFin() != null
                && !creneau.getHeureFin().isAfter(creneau.getHeureDebut());
    }

    /**
     * True when {@code stand} has at least one valid {@link OuvertureStand}
     * bearing on the créneau's day — regardless of whether its time window
     * actually overlaps the créneau. Deciding "closed-by-default" mode on this
     * alone (rather than on whether {@link #ouverturesInSeconds} came back
     * non-empty) is what makes a slot that happens to fall entirely outside
     * every opening window that day come out fully closed, instead of wrongly
     * falling back to open-by-default.
     *
     * <p>"Bearing on the créneau's day" goes through
     * {@link #decalageJourFenetre}, so the next day only counts for a slot
     * that genuinely crosses midnight. Reading the next day unconditionally —
     * as this did before recurring rules existed — let an opening dated the
     * day <i>after</i> a 10:00-20:00 slot flip that slot to closed-by-default,
     * and so silently close a stand that a same-day closure exception meant to
     * shut for two hours only.</p>
     */
    private static boolean dayInOuvertureMode(Creneau creneau, Stand stand) {
        if (stand == null || stand.getOuverturesEffectives().isEmpty() || creneau.getDate() == null) {
            return false;
        }
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            if (ouverture.hasValidRange() && decalageJourFenetre(creneau, ouverture.getDate()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opening windows of {@code stand} overlapping the créneau, clamped to
     * {@code [0, dureeSecondes]} and expressed in seconds since its start, as
     * {@code {debut, fin, effectif}} — the effectif already resolved against
     * {@link Stand#getEffectifMin()} for a window that names none.
     */
    private static List<int[]> ouverturesInSeconds(Creneau creneau, Stand stand, int dureeSecondes) {
        if (stand == null
                || stand.getOuverturesEffectives().isEmpty()
                || creneau.getHeureDebut() == null
                || creneau.getDate() == null) {
            return List.of();
        }
        int debutSlotSecondes = creneau.getHeureDebut().toSecondOfDay();
        List<int[]> ouvertures = new ArrayList<>();
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            int[] fenetre = clampedInSeconds(creneau, ouverture, debutSlotSecondes, dureeSecondes, 3);
            if (fenetre.length > 0) {
                Integer effectif = ouverture.getEffectif();
                fenetre[2] = effectif != null ? effectif : stand.getEffectifMin();
                ouvertures.add(fenetre);
            }
        }
        return ouvertures;
    }

    /**
     * Turns overlapping/touching second-granularity windows into the staffing
     * profile they describe: a sweep over every window boundary, each resulting
     * stretch taking the highest effectif among the windows covering it, then
     * touching stretches of equal effectif merged back and converted to minutes.
     *
     * <p>Uncovered stretches between two windows are dropped, which is what
     * makes a gap between two openings stay closed. With every window carrying
     * the same effectif — the case whenever nobody sets one — this collapses to
     * the plain interval merge it replaces.</p>
     */
    private static List<SegmentOuvert> profilEffectif(List<int[]> fenetresSecondes) {
        List<Integer> bornes = new ArrayList<>();
        for (int[] fenetre : fenetresSecondes) {
            bornes.add(fenetre[0]);
            bornes.add(fenetre[1]);
        }
        bornes.sort(Comparator.naturalOrder());
        List<SegmentOuvert> segments = new ArrayList<>();
        for (int i = 1; i < bornes.size(); i++) {
            int debut = bornes.get(i - 1);
            int fin = bornes.get(i);
            int effectif = fin > debut ? highestEffectifCovering(fenetresSecondes, debut, fin) : Integer.MIN_VALUE;
            if (effectif != Integer.MIN_VALUE) {
                appendSegment(segments, debut, fin, effectif);
            }
        }
        return segments;
    }

    /** The highest effectif among the windows covering {@code [debut, fin]}; {@code Integer.MIN_VALUE} when none does. */
    private static int highestEffectifCovering(List<int[]> fenetresSecondes, int debut, int fin) {
        int effectif = Integer.MIN_VALUE;
        for (int[] fenetre : fenetresSecondes) {
            if (fenetre[0] <= debut && fenetre[1] >= fin) {
                effectif = Math.max(effectif, fenetre[2]);
            }
        }
        return effectif;
    }

    /** A stretch in seconds, merged into the last segment when it touches it with the same effectif. */
    private static void appendSegment(List<SegmentOuvert> segments, int debut, int fin, int effectif) {
        SegmentOuvert dernier = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (dernier != null && dernier.finMinutes() == debut / 60 && dernier.effectif() == effectif) {
            segments.set(segments.size() - 1, new SegmentOuvert(dernier.debutMinutes(), fin / 60, effectif));
        } else {
            segments.add(new SegmentOuvert(debut / 60, fin / 60, effectif));
        }
    }
}
