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
 * 12:00-14:00), {@link #appliquerPauseLegaleSiNecessaire} splits it around a
 * real internal break instead, so the animateur working it alone still gets
 * to eat. See {@code docs/domaine.md} for the full rationale.</p>
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
     *
     * <p>When {@link ParametresDecoupage#getNombreFamillesDecalage()} is
     * greater than 1, each amplitude is sliced once per "famille" instead of
     * once overall, each with its internal relay cuts offset by a different
     * amount (see {@link #finDeVacation}) and tagged via
     * {@link Creneau#setFamille(int)}. A shared grid otherwise snaps every
     * amplitude's handover to the same clock minute (a target vacation length
     * that overshoots a meal window's end always clamps to that same end,
     * whatever the amplitude), which piles every stand's crew change onto a
     * handful of instants and forces overlaps that a staggered grid avoids —
     * see {@code docs/domaine.md}. Poste generation then assigns each stand to
     * exactly one famille, so it only ever sees that variant's créneaux.</p>
     */
    public static List<Creneau> genererVacations(List<Creneau> amplitudes, ParametresDecoupage parametres) {
        int nombreFamilles = Math.max(1, parametres.getNombreFamillesDecalage());
        List<Creneau> vacations = new ArrayList<>();
        for (Creneau amplitude : amplitudes) {
            for (int famille = 0; famille < nombreFamilles; famille++) {
                List<Creneau> tranche = decouperAmplitude(amplitude, parametres, famille, nombreFamilles);
                for (Creneau vacation : tranche) {
                    vacation.setFamille(famille);
                }
                vacations.addAll(tranche);
            }
        }
        return vacations;
    }

    private static List<Creneau> decouperAmplitude(Creneau amplitude, ParametresDecoupage parametres, int famille,
            int nombreFamilles) {
        int duree = amplitude.getDureeMinutes();
        List<int[]> segmentsBruts = decouperEnMinutes(duree, parametres, fenetresRepasEnMinutes(amplitude, parametres),
                famille, nombreFamilles);
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
     *
     * <p>With more than one famille, the cut is not placed at the target but
     * <b>fanned out inside the retained range</b>: the familles are spread
     * evenly across {@code dureeDecalageMaxMinutes}, centred on the target and
     * shrunk to fit. See {@link #finDeVacation} for why the fan has to live
     * inside the range rather than shift the target ahead of the clamp.</p>
     */
    private static List<int[]> decouperEnMinutes(int duree, ParametresDecoupage parametres, List<int[]> fenetresRepas,
            int famille, int nombreFamilles) {
        List<int[]> segments = new ArrayList<>();
        int max = parametres.getDureeVacationMaxMinutes();
        int min = parametres.getDureeVacationMinMinutes();
        int cible = parametres.getDureeVacationCibleMinutes();
        int chevauchement = parametres.getDureeChevauchementMinutes();
        int etalement = nombreFamilles > 1 ? parametres.getDureeDecalageMaxMinutes() : 0;
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
            int plageBasse = borneBasse;
            int plageHaute = borneHaute;
            for (int[] fenetre : fenetresRepas) {
                int debutFenetre = Math.max(fenetre[0], borneBasse);
                int finFenetre = Math.min(fenetre[1], borneHaute);
                // La fenêtre repas n'est retenue comme plage de coupure que si
                // elle est assez large pour contenir tout l'éventail : sinon
                // toutes les familles s'y écraseraient sur le même instant et
                // la désynchronisation serait perdue (cf. finDeVacation).
                if (debutFenetre <= finFenetre && finFenetre - debutFenetre >= etalement) {
                    plageBasse = debutFenetre;
                    plageHaute = finFenetre;
                    break;
                }
            }
            if (nombreFamilles > 1) {
                // Réserve de quoi tenir une dernière vacation complète après ce
                // relais, en rétrécissant la plage plutôt qu'en corrigeant la
                // coupure après coup : une correction a posteriori ramènerait
                // toutes les familles sur le même point et annulerait l'éventail.
                int plafondReliquat = duree - min + chevauchement;
                if (plafondReliquat >= plageBasse) {
                    plageHaute = Math.min(plageHaute, plafondReliquat);
                }
            }
            int fin = finDeVacation(cibleFin, plageBasse, plageHaute, famille, nombreFamilles, etalement);
            fin = clamp(fin, borneBasse, borneHaute);
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
     * Where this famille's relay cut lands inside {@code [plageBasse,
     * plageHaute]}: the familles are fanned out evenly over {@code etalement}
     * minutes, centred on {@code cibleFin} and pushed inwards so the whole fan
     * fits in the range. A single famille (or a zero fan) lands exactly on
     * {@code clamp(cibleFin, plageBasse, plageHaute)} — bit-for-bit the
     * historical behaviour.
     *
     * <p><b>Why the fan must live inside the range.</b> The first attempt at
     * staggering shifted {@code cibleFin} itself before the range clamp
     * applied. That silently does nothing, because a clamp has <i>two</i>
     * edges: a target pushed past {@code plageHaute} snaps back to
     * {@code plageHaute}, and a target pulled below {@code plageBasse} snaps
     * back up to {@code plageBasse} — every famille landing on the very same
     * instant either way. On real data every stand's evening changeover piled
     * onto the same minute (the meal window's own edge), so at that instant
     * both the outgoing and the incoming crew were on the clock at once and
     * the seat count momentarily <b>doubled</b> — 86 seats genuinely open
     * became 172 to staff at 18:30, against 153 animateurs. That peak, not any
     * shortage of people, was what made the scenario unsolvable: no search
     * budget can fill 172 seats with 153 bodies. Fanning inside the range
     * spreads the changeovers instead of stacking them, and the peak drops
     * back under the roster. See {@code docs/optimisation-solveur.md}.</p>
     */
    private static int finDeVacation(int cibleFin, int plageBasse, int plageHaute, int famille, int nombreFamilles,
            int etalement) {
        int largeur = Math.max(0, plageHaute - plageBasse);
        int fan = Math.min(etalement, largeur);
        if (nombreFamilles <= 1 || fan == 0) {
            return clamp(cibleFin, plageBasse, plageHaute);
        }
        double demi = fan / 2.0;
        double centre = clamp(cibleFin, plageBasse, plageHaute);
        centre = Math.min(Math.max(centre, plageBasse + demi), plageHaute - demi);
        double position = (double) famille / (nombreFamilles - 1) - 0.5;
        return (int) Math.round(centre + position * fan);
    }

    /**
     * Triggers in two cases: an admin has configured {@code dureeVacationMaxMinutes}
     * above the legal threshold and a segment actually landed above it (with
     * the default configuration this arm never fires, since every segment
     * produced by {@link #decouperEnMinutes} is already
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
     * minimum), snapped into any meal window that intersects the segment. When
     * {@link ParametresDecoupage.StrategieCouverturePendantPause#RELEVE} is
     * configured, a third short vacation covering exactly the pause window is
     * added so the stand stays staffed instead of closing.</p>
     */
    private static List<int[]> appliquerPauseLegaleSiNecessaire(int[] segment, Creneau amplitude,
            ParametresDecoupage parametres) {
        int longueur = segment[1] - segment[0];
        List<int[]> fenetresRepas = fenetresRepasEnMinutes(amplitude, parametres);
        boolean depasseSeuilLegal = longueur > SEUIL_PAUSE_LEGALE_MINUTES;
        if (!depasseSeuilLegal
                && !contientUneFenetreRepasEntiere(segment, fenetresRepasNonTronqueesEnMinutes(amplitude, parametres))) {
            return List.of(segment);
        }
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

    /**
     * True when {@code segment} covers a meal window from before its start to
     * after its end — i.e. the window would be entirely worked, not merely
     * touched at one edge. A segment that only ends at/after a window's start
     * (the normal relay-near-lunch case) does not count: that's the window
     * being used as a handover point, not swallowed whole.
     *
     * <p>{@code fenetresRepas} must be the <b>un</b>truncated windows (see
     * {@link #fenetresRepasNonTronqueesEnMinutes}): a window truncated by the
     * amplitude's own closing time (e.g. the evening window on a day that
     * shuts at 20:00, well before its 21:00 nominal end) would otherwise
     * always look "fully contained" by the amplitude's last segment, forcing
     * a pointless split that leaves a token few minutes of "vacation" right
     * before closing. A day that simply ends inside — or exactly at — a meal
     * window needs no internal break: whoever's on it goes off-shift for the
     * day at that point, same as the gap-between-vacations case.</p>
     */
    private static boolean contientUneFenetreRepasEntiere(int[] segment, List<int[]> fenetresRepas) {
        for (int[] fenetre : fenetresRepas) {
            if (fenetre[1] > fenetre[0] && segment[0] <= fenetre[0] && segment[1] >= fenetre[1]) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> fenetresRepasEnMinutes(Creneau amplitude, ParametresDecoupage parametres) {
        List<int[]> fenetres = new ArrayList<>();
        int duree = amplitude.getDureeMinutes();
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasMidiDebut(), parametres.getFenetreRepasMidiFin(), true);
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasSoirDebut(), parametres.getFenetreRepasSoirFin(), true);
        return fenetres;
    }

    /**
     * Same windows as {@link #fenetresRepasEnMinutes}, but not clipped to the
     * amplitude's own duration — used only by
     * {@link #contientUneFenetreRepasEntiere} so a window truncated by
     * closing time never registers as "fully contained". Still dropped
     * entirely when it doesn't start within the amplitude at all.
     */
    private static List<int[]> fenetresRepasNonTronqueesEnMinutes(Creneau amplitude, ParametresDecoupage parametres) {
        List<int[]> fenetres = new ArrayList<>();
        int duree = amplitude.getDureeMinutes();
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasMidiDebut(), parametres.getFenetreRepasMidiFin(), false);
        ajouterFenetreSiDansAmplitude(fenetres, amplitude.getHeureDebut(), duree,
                parametres.getFenetreRepasSoirDebut(), parametres.getFenetreRepasSoirFin(), false);
        return fenetres;
    }

    private static void ajouterFenetreSiDansAmplitude(List<int[]> fenetres, LocalTime heureDebutAmplitude,
            int dureeAmplitude, LocalTime debutFenetre, LocalTime finFenetre, boolean tronquerAFinAmplitude) {
        int offsetDebut = minutesDepuis(heureDebutAmplitude, debutFenetre);
        int offsetFin = minutesDepuis(heureDebutAmplitude, finFenetre);
        if (offsetFin <= offsetDebut) {
            offsetFin += 24 * 60;
        }
        if (offsetDebut < dureeAmplitude) {
            fenetres.add(new int[] { offsetDebut, tronquerAFinAmplitude ? Math.min(offsetFin, dureeAmplitude) : offsetFin });
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
