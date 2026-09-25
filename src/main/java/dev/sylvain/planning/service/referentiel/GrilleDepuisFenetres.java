package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The créneaux grid derived from what the stands already say: every hour at
 * which some stand opens or closes is a cut, and every stretch between two
 * cuts on which at least one stand is open is a créneau.
 *
 * <p>This is the reverse of the entry grid ({@link GrilleHorairesStands}),
 * which takes the créneaux as its columns: when the stands' hours are the
 * data the organiser already has — typed as rules, or imported — the grid
 * follows from them instead of being typed a second time.</p>
 *
 * <p>Only the days a stand states as <em>open on these windows</em> take
 * part — the shape every stand of the reference event has. A stand that says
 * nothing about a day is open whenever the others are, and a stand stating
 * closures only opens "from the day's start", a bound this derivation does
 * not know; neither cuts anything. A window running "until closing time" ends
 * at the closing hour given, {@code 00:00} meaning midnight, which makes the
 * last créneau cross it the way the domain writes a nocturne.</p>
 *
 * <p>Static, and the only state it touches is the stands it is handed: their
 * rules are resolved onto the dates asked for, the way every reader of
 * {@link Stand#getOuverturesEffectives()} needs. The persistence is
 * {@code ReferenceDataService}'s business.</p>
 */
public final class GrilleDepuisFenetres {

    /** Below this, a stretch between two cuts is a clock artefact and is merged into its neighbour. */
    public static final int DUREE_MINIMALE_PAR_DEFAUT = OuvertureStandsAnalyzer.DUREE_MINIMALE_EXPLOITABLE_MINUTES;

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private GrilleDepuisFenetres() {}

    /**
     * @param heureFermeture      the end of a window left open-ended; {@code 00:00} reads as midnight
     * @param dureeMinimaleMinutes a stretch shorter than this joins the one next to it, and a hole
     *                             shorter than this is closed rather than splitting a créneau in two
     */
    public record Parametres(
            LocalDate dateDebut, LocalDate dateFin, LocalTime heureFermeture, int dureeMinimaleMinutes) {

        public Parametres {
            if (dateDebut == null || dateFin == null) {
                throw new BusinessError.Invalid("dateDebut et dateFin sont requis (AAAA-MM-JJ), bornes incluses");
            }
            if (dateFin.isBefore(dateDebut)) {
                throw new BusinessError.Invalid(
                        "dateFin (" + dateFin + ") est antérieure à dateDebut (" + dateDebut + ")");
            }
            if (heureFermeture == null) {
                throw new BusinessError.Invalid("heureFermeture est requise : la fin des fenêtres « jusqu'à la "
                        + "fermeture » (00:00 pour minuit)");
            }
            if (dureeMinimaleMinutes < 0) {
                throw new BusinessError.Invalid("dureeMinimaleMinutes doit être positive ou nulle");
            }
        }
    }

    /** One cut of one day, and the stands whose windows start or end there — the first few, by id. */
    @Schema(requiredProperties = {"nombreStands"})
    public record Coupure(LocalDate date, LocalTime heure, List<String> standIds, int nombreStands) {}

    /** What the derivation produced: the créneaux, why each cut is there, and the days nothing said anything about. */
    public record Derivation(List<Creneau> creneaux, List<Coupure> coupures, List<LocalDate> joursSansFenetre) {}

    private static final int STANDS_CITES = 3;

    public static Derivation deriver(List<Stand> stands, Parametres parametres) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = parametres.dateDebut(); !date.isAfter(parametres.dateFin()); date = date.plusDays(1)) {
            dates.add(date);
        }
        for (Stand stand : stands) {
            HoraireStandResolver.apply(stand, dates);
        }
        int fermeture = parametres.heureFermeture().toSecondOfDay() / 60;
        if (fermeture == 0) {
            fermeture = MINUTES_PAR_JOUR;
        }

        List<Creneau> creneaux = new ArrayList<>();
        List<Coupure> coupures = new ArrayList<>();
        List<LocalDate> joursSansFenetre = new ArrayList<>();
        for (LocalDate date : dates) {
            // Every open window of the day, in minutes: [debut, fin].
            List<int[]> fenetres = new ArrayList<>();
            Map<Integer, TreeSet<String>> standsParBorne = new TreeMap<>();
            collectWindows(stands, date, fermeture, fenetres, standsParBorne);
            if (fenetres.isEmpty()) {
                joursSansFenetre.add(date);
            } else {
                deriveDay(date, fenetres, standsParBorne, parametres.dureeMinimaleMinutes(), creneaux, coupures);
            }
        }
        return new Derivation(creneaux, coupures, joursSansFenetre);
    }

    /** Collects every open window of {@code date}, and the stands starting or ending at each bound. */
    private static void collectWindows(
            List<Stand> stands,
            LocalDate date,
            int fermeture,
            List<int[]> fenetres,
            Map<Integer, TreeSet<String>> standsParBorne) {
        for (Stand stand : stands) {
            if (HoraireStandResolver.sourceOfDay(stand, date) == HoraireStandResolver.SourceHoraire.DEFAUT) {
                continue;
            }
            for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
                if (date.equals(ouverture.getDate()) && ouverture.hasValidRange()) {
                    int debut = ouverture.getHeureDebut().toSecondOfDay() / 60;
                    int fin = windowEnd(ouverture, debut, fermeture);
                    fenetres.add(new int[] {debut, fin});
                    standsParBorne
                            .computeIfAbsent(debut, key -> new TreeSet<>())
                            .add(stand.getId());
                    standsParBorne.computeIfAbsent(fin, key -> new TreeSet<>()).add(stand.getId());
                }
            }
        }
    }

    /**
     * The end of a window in minutes. A window may not cross midnight on its own
     * (hasValidRange), but « until closing » may: an event closing at 02:00, or a
     * window opening after the closing hour given, means the next day. Read as a
     * same-day end it was silently dropped.
     */
    private static int windowEnd(OuvertureStand ouverture, int debut, int fermeture) {
        if (ouverture.getHeureFin() != null) {
            return ouverture.getHeureFin().toSecondOfDay() / 60;
        }
        return fermeture > debut ? fermeture : fermeture + MINUTES_PAR_JOUR;
    }

    /** Cuts one day's windows into créneaux, and records the cuts that survived. */
    private static void deriveDay(
            LocalDate date,
            List<int[]> fenetres,
            Map<Integer, TreeSet<String>> standsParBorne,
            int dureeMinimaleMinutes,
            List<Creneau> creneaux,
            List<Coupure> coupures) {
        List<Integer> bornes = new ArrayList<>(standsParBorne.keySet());
        List<int[]> tranches = new ArrayList<>();
        for (int i = 1; i < bornes.size(); i++) {
            int debut = bornes.get(i - 1);
            int fin = bornes.get(i);
            if (fenetres.stream().anyMatch(fenetre -> fenetre[0] <= debut && fin <= fenetre[1])) {
                tranches.add(new int[] {debut, fin});
            }
        }
        // A hole too short to be worth a break is one stand's clock
        // artefact too — one closing at 18:00 while its neighbour opens at
        // 18:05 — and closing it keeps one créneau where two would be cut.
        tranches = withoutShortHoles(tranches, dureeMinimaleMinutes);
        // A stretch too short to be a slot joins the stretch after it, its
        // own end cut dropped — or the one before it, when nothing follows.
        tranches = withoutShortStretches(tranches, dureeMinimaleMinutes);
        for (int[] tranche : tranches) {
            creneaux.add(new Creneau(null, 0, date, minuteToTime(tranche[0]), minuteToTime(tranche[1])));
        }
        // Only the cuts that survived: reporting the merged-away ones as
        // reasons for a grid that no longer holds them explains nothing.
        for (int borne : retainedBounds(tranches)) {
            TreeSet<String> ids = standsParBorne.get(borne);
            coupures.add(new Coupure(
                    date, minuteToTime(borne), ids.stream().limit(STANDS_CITES).toList(), ids.size()));
        }
    }

    /** Joins two stretches separated by a hole shorter than {@code dureeMinimaleMinutes}. */
    private static List<int[]> withoutShortHoles(List<int[]> tranches, int dureeMinimaleMinutes) {
        List<int[]> jointes = new ArrayList<>();
        for (int[] tranche : tranches) {
            int[] precedente = jointes.isEmpty() ? null : jointes.get(jointes.size() - 1);
            int trou = precedente == null ? 0 : tranche[0] - precedente[1];
            // A zero-length gap is not a hole: it is a cut, and the stretches
            // it separates are two créneaux the stands actually asked for.
            if (trou > 0 && trou < dureeMinimaleMinutes) {
                precedente[1] = tranche[1];
            } else {
                jointes.add(new int[] {tranche[0], tranche[1]});
            }
        }
        return jointes;
    }

    /** Merges away the stretches shorter than {@code dureeMinimaleMinutes}, into the next one by preference. */
    private static List<int[]> withoutShortStretches(List<int[]> tranches, int dureeMinimaleMinutes) {
        List<int[]> retenues = new ArrayList<>();
        for (int i = 0; i < tranches.size(); i++) {
            int[] tranche = tranches.get(i);
            boolean courte = tranche[1] - tranche[0] < dureeMinimaleMinutes;
            int[] suivante = i + 1 < tranches.size() ? tranches.get(i + 1) : null;
            int[] precedente = retenues.isEmpty() ? null : retenues.get(retenues.size() - 1);
            if (courte && suivante != null && suivante[0] == tranche[1]) {
                suivante[0] = tranche[0];
            } else if (courte && precedente != null && precedente[1] == tranche[0]) {
                precedente[1] = tranche[1];
            } else {
                retenues.add(tranche);
            }
        }
        return retenues;
    }

    /** The bounds the retained stretches still stand on, in order and without repeats. */
    private static List<Integer> retainedBounds(List<int[]> tranches) {
        TreeSet<Integer> bornes = new TreeSet<>();
        for (int[] tranche : tranches) {
            bornes.add(tranche[0]);
            bornes.add(tranche[1]);
        }
        return List.copyOf(bornes);
    }

    /** Minutes since midnight back to a wall-clock time; {@code 24:00} wraps to {@code 00:00}. */
    private static LocalTime minuteToTime(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % MINUTES_PAR_JOUR) * 60L);
    }
}
