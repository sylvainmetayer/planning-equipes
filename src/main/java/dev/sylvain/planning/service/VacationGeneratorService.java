package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;

/**
 * Slices day-long opening windows ("amplitudes" — plain {@link Creneau} rows
 * living in a source, non-activated {@link dev.sylvain.planning.domain.GroupeCreneau})
 * into several shorter, overlapping work vacations, so a "continu" scenario
 * (one amplitude per day, e.g. 10:00→00:00) never forces a single animateur
 * to be nominally in-post for the whole opening window.
 *
 * <p>The relay pattern is the whole mechanism: consecutive vacations overlap
 * by {@link ParametresDecoupage#getDureeChevauchementMinutes()}, so during a
 * handover two {@code PosteAffectation} exist on the same stand at once —
 * coverage is never in deficit, only briefly in surplus. As long as every
 * vacation stays at or under {@code dureeVacationMaxMinutes} (6h by default,
 * strictly under the art. L3121-16 threshold at which a break becomes legally
 * mandatory), no vacation ever needs an internal pause: the pause/repas an
 * animateur gets is simply the gap between two of their vacations the same
 * day, exactly like any other day off-shift — nothing else to construct or
 * guarantee. See {@code docs/domaine.md} for the full rationale.</p>
 */
public final class VacationGeneratorService {

    /** Art. L3121-16: beyond this many minutes of continuous work, a break becomes legally mandatory. */
    static final int SEUIL_PAUSE_LEGALE_MINUTES = 6 * 60;

    /** Duration of the legally-mandated break itself when no meal window applies. */
    private static final int DUREE_PAUSE_LEGALE_MINUTES = 20;

    private VacationGeneratorService() {
    }

    /**
     * Generates the vacations for every amplitude. Amplitudes shorter than or
     * equal to {@code dureeVacationMaxMinutes} pass through untouched (nothing
     * to split). Every generated {@link Creneau} carries a fresh {@code null}
     * id (left for the caller/repository to assign). Stand availability no
     * longer needs inheriting here: it now lives on the {@code Stand} itself
     * (see {@code IndisponibiliteStand}), so it applies uniformly whichever
     * créneau — amplitude or vacation — ends up referencing that stand.
     */
    public static List<Creneau> genererVacations(List<Creneau> amplitudes, ParametresDecoupage parametres) {
        List<Creneau> vacations = new ArrayList<>();
        for (Creneau amplitude : amplitudes) {
            vacations.addAll(decouperAmplitude(amplitude, parametres));
        }
        return vacations;
    }

    private static List<Creneau> decouperAmplitude(Creneau amplitude, ParametresDecoupage parametres) {
        int duree = amplitude.getDureeMinutes();
        List<int[]> segmentsBruts = decouperEnMinutes(duree, parametres, fenetresRepasEnMinutes(amplitude, parametres));
        List<int[]> segments = new ArrayList<>();
        for (int[] segment : segmentsBruts) {
            segments.addAll(appliquerPauseLegaleSiNecessaire(segment, amplitude, parametres));
        }
        List<Creneau> resultat = new ArrayList<>();
        for (int[] segment : segments) {
            resultat.add(creerVacation(amplitude, segment[0], segment[1]));
        }
        return resultat;
    }

    /**
     * Greedy forward cut: from the amplitude start, advance by the target
     * vacation length, clamped to {@code [min, max]}; when a meal window
     * intersects that clamped range, snap the cut to land inside the meal
     * window instead, so the relay handover naturally happens right before or
     * right after a meal rather than in the middle of it. The next vacation
     * then starts {@code dureeChevauchementMinutes} before the current one
     * ends, which is the whole coverage guarantee — see class javadoc.
     */
    private static List<int[]> decouperEnMinutes(int duree, ParametresDecoupage parametres, List<int[]> fenetresRepas) {
        List<int[]> segments = new ArrayList<>();
        int max = parametres.getDureeVacationMaxMinutes();
        int min = parametres.getDureeVacationMinMinutes();
        int cible = parametres.getDureeVacationCibleMinutes();
        int chevauchement = parametres.getDureeChevauchementMinutes();
        int courant = 0;
        while (true) {
            int restant = duree - courant;
            if (restant <= max) {
                segments.add(new int[] { courant, duree });
                break;
            }
            int cibleFin = courant + cible;
            int borneBasse = courant + min;
            int borneHaute = courant + max;
            int fin = clamp(cibleFin, borneBasse, borneHaute);
            for (int[] fenetre : fenetresRepas) {
                int debutFenetre = Math.max(fenetre[0], borneBasse);
                int finFenetre = Math.min(fenetre[1], borneHaute);
                if (debutFenetre <= finFenetre) {
                    fin = clamp(cibleFin, debutFenetre, finFenetre);
                    break;
                }
            }
            // Ne jamais laisser un reliquat plus court que le minimum après ce
            // relais : sans ce garde-fou, un cut proche de borneHaute (par ex.
            // pour tomber dans une fenêtre repas) peut réduire la dernière
            // vacation de l'amplitude à quelques dizaines de minutes.
            int resteApresRelais = duree - (fin - chevauchement);
            if (resteApresRelais > 0 && resteApresRelais < min) {
                fin = Math.max(fin - (min - resteApresRelais), borneBasse);
            }
            segments.add(new int[] { courant, fin });
            courant = fin - chevauchement;
        }
        return segments;
    }

    /**
     * Only triggers when an admin has configured {@code dureeVacationMaxMinutes}
     * above the legal threshold and a segment actually landed above it — with
     * the default configuration this never runs, since every segment produced
     * by {@link #decouperEnMinutes} is already {@code <= dureeVacationMaxMinutes
     * <= SEUIL_PAUSE_LEGALE_MINUTES}. Splits the offending segment into two,
     * separated by a pause (a meal break if one overlaps the split point,
     * otherwise the 20-min legal minimum), snapped into any meal window that
     * intersects the segment. When {@link ParametresDecoupage.StrategieCouverturePendantPause#RELEVE}
     * is configured, a third short vacation covering exactly the pause window
     * is added so the stand stays staffed instead of closing.
     */
    private static List<int[]> appliquerPauseLegaleSiNecessaire(int[] segment, Creneau amplitude,
            ParametresDecoupage parametres) {
        int longueur = segment[1] - segment[0];
        if (longueur <= SEUIL_PAUSE_LEGALE_MINUTES) {
            return List.of(segment);
        }
        List<int[]> fenetresRepas = fenetresRepasEnMinutes(amplitude, parametres);
        int milieu = segment[0] + longueur / 2;
        int pauseDebut = milieu;
        int dureePause = DUREE_PAUSE_LEGALE_MINUTES;
        for (int[] fenetre : fenetresRepas) {
            int debutFenetre = Math.max(fenetre[0], segment[0]);
            int finFenetre = Math.min(fenetre[1], segment[1]);
            if (debutFenetre <= finFenetre) {
                pauseDebut = clamp(milieu, debutFenetre, finFenetre);
                dureePause = Math.min(parametres.getDureePauseRepasMinutes(), finFenetre - debutFenetre);
                break;
            }
        }
        int pauseFin = Math.min(segment[1], pauseDebut + dureePause);
        List<int[]> resultat = new ArrayList<>();
        resultat.add(new int[] { segment[0], pauseDebut });
        if (parametres.getStrategieCouverturePendantPause() == ParametresDecoupage.StrategieCouverturePendantPause.RELEVE
                && pauseFin > pauseDebut) {
            resultat.add(new int[] { pauseDebut, pauseFin });
        }
        resultat.add(new int[] { pauseFin, segment[1] });
        return resultat;
    }

    private static List<int[]> fenetresRepasEnMinutes(Creneau amplitude, ParametresDecoupage parametres) {
        List<int[]> fenetres = new ArrayList<>();
        int duree = amplitude.getDureeMinutes();
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasMidiDebut(), parametres.getFenetreRepasMidiFin());
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasSoirDebut(), parametres.getFenetreRepasSoirFin());
        return fenetres;
    }

    private static void ajouterFenetreSiDansAmplitude(List<int[]> fenetres, LocalTime heureDebutAmplitude,
            int dureeAmplitude, LocalTime debutFenetre, LocalTime finFenetre) {
        int offsetDebut = minutesDepuis(heureDebutAmplitude, debutFenetre);
        int offsetFin = minutesDepuis(heureDebutAmplitude, finFenetre);
        if (offsetFin <= offsetDebut) {
            offsetFin += 24 * 60;
        }
        if (offsetDebut < dureeAmplitude) {
            fenetres.add(new int[] { offsetDebut, Math.min(offsetFin, dureeAmplitude) });
        }
    }

    /** Minutes from {@code reference} clock time to {@code cible} clock time, always non-negative. */
    private static int minutesDepuis(LocalTime reference, LocalTime cible) {
        int delta = cible.toSecondOfDay() - reference.toSecondOfDay();
        if (delta < 0) {
            delta += 24 * 3600;
        }
        return delta / 60;
    }

    private static int clamp(int valeur, int min, int max) {
        return Math.max(min, Math.min(max, valeur));
    }

    private static Creneau creerVacation(Creneau amplitude, int debutMinutes, int finMinutes) {
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
