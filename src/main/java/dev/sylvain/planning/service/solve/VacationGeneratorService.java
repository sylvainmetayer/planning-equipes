package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Slices day-long opening windows ("amplitudes" — plain {@link Creneau} rows
 * imported straight from a "continu" scenario file)
 * into several shorter, overlapping work vacations, so a "continu" scenario
 * (one amplitude per day, e.g. 10:00→00:00) never forces a single animateur
 * to be nominally in-post for the whole opening window.
 *
 * <p>The relay pattern is the main mechanism: consecutive vacations overlap
 * by {@link ParametresDecoupage#getDureeChevauchementMinutes()}, so during a
 * handover two {@code PosteAffectation} exist on the same stand at once —
 * coverage is never in deficit, only briefly in surplus. As long as every
 * vacation stays at or under {@code dureeVacationMaxMinutes} (6h by default,
 * strictly under the art. L3121-16 threshold at which a break becomes legally
 * mandatory) <b>and</b> doesn't itself swallow a whole meal window, no vacation
 * needs an internal pause: the pause/repas an animateur gets is simply the gap
 * between two of their vacations the same day, exactly like any other day
 * off-shift. When a single vacation would otherwise entirely cover a meal
 * window (e.g. a 10:00-14:10 first-of-the-day slot spanning all of
 * 12:00-14:00), {@link #applyLegalPauseIfNeeded} splits it around a
 * real internal break instead, so the animateur working it alone still gets
 * to eat. See {@code docs/domaine.md} for the full rationale.</p>
 */
public final class VacationGeneratorService {

    /** Art. L3121-16: beyond this many minutes of continuous work, a break becomes legally mandatory. */
    static final int SEUIL_PAUSE_LEGALE_MINUTES = 6 * 60;

    /** Duration of the legally-mandated break itself when no meal window applies. */
    private static final int DUREE_PAUSE_LEGALE_MINUTES = 20;

    private VacationGeneratorService() {}

    /**
     * Generates the vacations for every amplitude. Amplitudes shorter than or
     * equal to {@code dureeVacationMaxMinutes} pass through untouched (nothing
     * to split). Every generated {@link Creneau} carries a fresh {@code null}
     * id (left for the caller/repository to assign). Stand availability no
     * longer needs inheriting here: it now lives on the {@code Stand} itself
     * (see {@code IndisponibiliteStand}), so it applies uniformly whichever
     * créneau — amplitude or vacation — ends up referencing that stand.
     */
    public static List<Creneau> generateVacations(
            List<Creneau> amplitudes, ParametresDecoupage parametres, ParametresLegaux legaux) {
        List<Creneau> vacations = new ArrayList<>();
        for (Creneau amplitude : amplitudes) {
            vacations.addAll(sliceAmplitude(amplitude, parametres, legaux));
        }
        return vacations;
    }

    private static List<Creneau> sliceAmplitude(
            Creneau amplitude, ParametresDecoupage parametres, ParametresLegaux legaux) {
        int duree = amplitude.getDureeMinutes();
        List<int[]> segmentsBruts = sliceIntoMinutes(duree, parametres, mealFenetresInMinutes(amplitude, legaux));
        List<Segment> segments = new ArrayList<>();
        for (int[] segment : segmentsBruts) {
            segments.addAll(applyLegalPauseIfNeeded(segment, amplitude, parametres, legaux));
        }
        List<Creneau> result = new ArrayList<>();
        for (Segment segment : segments) {
            Creneau vacation = createVacation(amplitude, segment.debut(), segment.fin());
            vacation.setCouverturePause(segment.couverturePause());
            result.add(vacation);
        }
        return result;
    }

    /**
     * A generated vacation's bounds, in minutes from the amplitude's start,
     * plus whether it is the short slot covering an internal meal pause at
     * reduced headcount. Replaces the bare {@code int[]} the pause split used
     * to return: the flag has to survive all the way to
     * {@link #createVacation}, and only
     * {@link #applyLegalPauseIfNeeded} knows which of the pieces it
     * produced is the covering one.
     */
    private record Segment(int debut, int fin, boolean couverturePause) {}

    /**
     * Greedy forward cut: from the amplitude start, advance by the target
     * vacation length, clamped to {@code [min, max]}; when a meal window
     * intersects that clamped range, snap the cut to land inside the meal
     * window instead, so the relay handover naturally happens right before or
     * right after a meal rather than in the middle of it. The next vacation
     * then starts {@code dureeChevauchementMinutes} before the current one
     * ends, which is the whole coverage guarantee — see class javadoc.
     */
    private static List<int[]> sliceIntoMinutes(int duree, ParametresDecoupage parametres, List<int[]> fenetresRepas) {
        List<int[]> segments = new ArrayList<>();
        int max = parametres.getDureeVacationMaxMinutes();
        int min = parametres.getDureeVacationMinMinutes();
        int target = parametres.getDureeVacationCibleMinutes();
        int chevauchement = parametres.getDureeChevauchementMinutes();
        int courant = 0;
        while (true) {
            int restant = duree - courant;
            if (restant <= max) {
                segments.add(new int[] {courant, duree});
                break;
            }
            int cibleFin = courant + target;
            int borneBasse = courant + min;
            int borneHaute = courant + max;
            int plageBasse = borneBasse;
            int plageHaute = borneHaute;
            for (int[] fenetre : fenetresRepas) {
                int debutFenetre = Math.max(fenetre[0], borneBasse);
                int finFenetre = Math.min(fenetre[1], borneHaute);
                if (debutFenetre <= finFenetre) {
                    plageBasse = debutFenetre;
                    plageHaute = finFenetre;
                    break;
                }
            }
            int fin = clamp(clamp(cibleFin, plageBasse, plageHaute), borneBasse, borneHaute);
            // Never leave a remainder shorter than the minimum after this
            // handover: without that guard, a cut close to borneHaute (to fall
            // inside a meal window, say) can shrink the last shift of the opening
            // span to a few dozen minutes.
            int remainderAfterHandover = duree - (fin - chevauchement);
            if (remainderAfterHandover > 0 && remainderAfterHandover < min) {
                fin = Math.max(fin - (min - remainderAfterHandover), borneBasse);
            }
            segments.add(new int[] {courant, fin});
            courant = fin - chevauchement;
        }
        return segments;
    }

    /**
     * Triggers in two cases: an admin has configured {@code dureeVacationMaxMinutes}
     * above the legal threshold and a segment actually landed above it (with
     * the default configuration this arm never fires, since every segment
     * produced by {@link #sliceIntoMinutes} is already
     * {@code <= dureeVacationMaxMinutes <= SEUIL_PAUSE_LEGALE_MINUTES}); or a
     * segment — whatever its length — entirely swallows a meal window instead
     * of ending inside or before it, which happens whenever
     * {@code dureeVacationMinMinutes} keeps the earliest possible cut past the
     * window's start (see class javadoc). Relying only on the 6 h threshold
     * left that second case with no break at all: an animateur alone on a
     * single ~4-5 h vacation that happens to straddle noon worked straight
     * through lunch, because their "break" — the gap before their next
     * vacation, if any — never actually fell inside the meal window.
     *
     * <p>Splits the offending segment into two, separated by a pause (a meal
     * break if one overlaps the split point, otherwise the 20-min legal
     * minimum), snapped into any meal window that intersects the segment.
     * Unless {@link ParametresDecoupage.PauseCoverageStrategy#FERMETURE}
     * is configured, a third short vacation covering exactly the pause window
     * is added so the stand stays staffed instead of closing — at full
     * headcount under {@code RELEVE}, at half under {@code EFFECTIF_REDUIT},
     * which is the only case where the returned {@link Segment} carries
     * {@code couverturePause}.</p>
     */
    private static List<Segment> applyLegalPauseIfNeeded(
            int[] segment, Creneau amplitude, ParametresDecoupage parametres, ParametresLegaux legaux) {
        int longueur = segment[1] - segment[0];
        List<int[]> fenetresRepas = mealFenetresInMinutes(amplitude, legaux);
        boolean depasseSeuilLegal = longueur > SEUIL_PAUSE_LEGALE_MINUTES;
        if (!depasseSeuilLegal
                && !containsWholeMealFenetre(segment, untruncatedMealFenetresInMinutes(amplitude, legaux))) {
            return List.of(new Segment(segment[0], segment[1], false));
        }
        int milieu = segment[0] + longueur / 2;
        int pauseDebut = milieu;
        int dureePause = DUREE_PAUSE_LEGALE_MINUTES;
        for (int[] fenetre : fenetresRepas) {
            int debutFenetre = Math.max(fenetre[0], segment[0]);
            int finFenetre = Math.min(fenetre[1], segment[1]);
            if (debutFenetre <= finFenetre) {
                pauseDebut = clamp(milieu, debutFenetre, finFenetre);
                dureePause = Math.min(legaux.getCoupureRepasMinutes(), finFenetre - debutFenetre);
                break;
            }
        }
        int pauseFin = Math.min(segment[1], pauseDebut + dureePause);
        ParametresDecoupage.PauseCoverageStrategy strategie = parametres.getStrategieCouverturePendantPause();
        List<Segment> result = new ArrayList<>();
        // The three pieces are only added when they are non-empty. A break
        // starting on the start of the segment, or ending on its end, otherwise
        // produces a zero-length shift — and a shift whose heureFin ==
        // heureDebut is read back by Creneau#getDureeMinutes() as crossing
        // midnight, hence 24 h long. The case shows up as soon as a meal window
        // touches an edge of the opening span: a 20:00-21:00 window on a day
        // closing at 21:00, for instance, used to generate a 21:00→21:00 shift
        // per family, each one claiming full staffing for a fictitious 24 h.
        if (pauseDebut > segment[0]) {
            result.add(new Segment(segment[0], pauseDebut, false));
        }
        if (strategie != ParametresDecoupage.PauseCoverageStrategy.FERMETURE && pauseFin > pauseDebut) {
            // RELEVE and EFFECTIF_REDUIT both cover the break; only the second
            // one reduces the staffing, hence the marker the shift carries.
            boolean effectifReduit = strategie == ParametresDecoupage.PauseCoverageStrategy.EFFECTIF_REDUIT;
            result.add(new Segment(pauseDebut, pauseFin, effectifReduit));
        }
        if (segment[1] > pauseFin) {
            result.add(new Segment(pauseFin, segment[1], false));
        }
        return result;
    }

    /**
     * True when {@code segment} covers a meal window from before its start to
     * after its end — i.e. the window would be entirely worked, not merely
     * touched at one edge. A segment that only ends at/after a window's start
     * (the normal relay-near-lunch case) does not count: that's the window
     * being used as a handover point, not swallowed whole.
     *
     * <p>{@code fenetresRepas} must be the <b>un</b>truncated windows (see
     * {@link #untruncatedMealFenetresInMinutes}): a window truncated by the
     * amplitude's own closing time (e.g. the evening window on a day that
     * shuts at 20:00, well before its 21:00 nominal end) would otherwise
     * always look "fully contained" by the amplitude's last segment, forcing
     * a pointless split that leaves a token few minutes of "vacation" right
     * before closing. A day that simply ends inside — or exactly at — a meal
     * window needs no internal break: whoever's on it goes off-shift for the
     * day at that point, same as the gap-between-vacations case.</p>
     */
    private static boolean containsWholeMealFenetre(int[] segment, List<int[]> fenetresRepas) {
        for (int[] fenetre : fenetresRepas) {
            if (fenetre[1] > fenetre[0] && segment[0] <= fenetre[0] && segment[1] >= fenetre[1]) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> mealFenetresInMinutes(Creneau amplitude, ParametresLegaux legaux) {
        List<int[]> fenetres = new ArrayList<>();
        int duree = amplitude.getDureeMinutes();
        addFenetreIfWithinAmplitude(
                fenetres,
                amplitude.getHeureDebut(),
                duree,
                legaux.getCoupureRepasMidiDebut(),
                legaux.getCoupureRepasMidiFin(),
                true);
        addFenetreIfWithinAmplitude(
                fenetres,
                amplitude.getHeureDebut(),
                duree,
                legaux.getCoupureRepasSoirDebut(),
                legaux.getCoupureRepasSoirFin(),
                true);
        return fenetres;
    }

    /**
     * Same windows as {@link #mealFenetresInMinutes}, but not clipped to the
     * amplitude's own duration — used only by
     * {@link #containsWholeMealFenetre} so a window truncated by
     * closing time never registers as "fully contained". Still dropped
     * entirely when it doesn't start within the amplitude at all.
     */
    private static List<int[]> untruncatedMealFenetresInMinutes(Creneau amplitude, ParametresLegaux legaux) {
        List<int[]> fenetres = new ArrayList<>();
        int duree = amplitude.getDureeMinutes();
        addFenetreIfWithinAmplitude(
                fenetres,
                amplitude.getHeureDebut(),
                duree,
                legaux.getCoupureRepasMidiDebut(),
                legaux.getCoupureRepasMidiFin(),
                false);
        addFenetreIfWithinAmplitude(
                fenetres,
                amplitude.getHeureDebut(),
                duree,
                legaux.getCoupureRepasSoirDebut(),
                legaux.getCoupureRepasSoirFin(),
                false);
        return fenetres;
    }

    private static void addFenetreIfWithinAmplitude(
            List<int[]> fenetres,
            LocalTime heureDebutAmplitude,
            int dureeAmplitude,
            LocalTime debutFenetre,
            LocalTime finFenetre,
            boolean tronquerAFinAmplitude) {
        int offsetDebut = minutesSince(heureDebutAmplitude, debutFenetre);
        int offsetFin = minutesSince(heureDebutAmplitude, finFenetre);
        if (offsetFin <= offsetDebut) {
            offsetFin += 24 * 60;
        }
        if (offsetDebut < dureeAmplitude) {
            fenetres.add(
                    new int[] {offsetDebut, tronquerAFinAmplitude ? Math.min(offsetFin, dureeAmplitude) : offsetFin});
        }
    }

    /** Minutes from {@code reference} clock time to {@code target} clock time, always non-negative. */
    private static int minutesSince(LocalTime reference, LocalTime target) {
        int delta = target.toSecondOfDay() - reference.toSecondOfDay();
        if (delta < 0) {
            delta += 24 * 3600;
        }
        return delta / 60;
    }

    private static int clamp(int valeur, int min, int max) {
        return Math.max(min, Math.min(max, valeur));
    }

    private static Creneau createVacation(Creneau amplitude, int debutMinutes, int finMinutes) {
        long debutSecondes = amplitude.getHeureDebut().toSecondOfDay() + debutMinutes * 60L;
        long finSecondes = amplitude.getHeureDebut().toSecondOfDay() + finMinutes * 60L;
        boolean debutLendemain = debutSecondes >= 24 * 3600L;
        LocalTime heureDebut = LocalTime.ofSecondOfDay(debutSecondes % (24 * 3600));
        LocalTime heureFin = LocalTime.ofSecondOfDay(finSecondes % (24 * 3600));
        LocalDate date = amplitude.getDate();
        if (debutLendemain && date != null) {
            date = date.plusDays(1);
        }
        return new Creneau(null, 0, date, heureDebut, heureFin);
    }
}
