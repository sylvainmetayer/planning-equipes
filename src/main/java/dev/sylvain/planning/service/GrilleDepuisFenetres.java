package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;

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
 * <p>Pure and static: the persistence is {@code ReferenceDataService}'s business.</p>
 */
public final class GrilleDepuisFenetres {

    /** Below this, a stretch between two cuts is a clock artefact and is merged into its neighbour. */
    public static final int DUREE_MINIMALE_PAR_DEFAUT = OuvertureStandsAnalyzer.DUREE_MINIMALE_EXPLOITABLE_MINUTES;

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private GrilleDepuisFenetres() {
    }

    /**
     * @param heureFermeture      the end of a window left open-ended; {@code 00:00} reads as midnight
     * @param dureeMinimaleMinutes stretches shorter than this are merged into the previous one
     */
    public record Parametres(LocalDate dateDebut, LocalDate dateFin, LocalTime heureFermeture,
            int dureeMinimaleMinutes) {

        public Parametres {
            if (dateDebut == null || dateFin == null) {
                throw new BusinessError.Invalid("dateDebut et dateFin sont requis (AAAA-MM-JJ), bornes incluses");
            }
            if (dateFin.isBefore(dateDebut)) {
                throw new BusinessError.Invalid("dateFin (" + dateFin + ") est antérieure à dateDebut (" + dateDebut + ")");
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
    public record Coupure(LocalDate date, LocalTime heure, List<String> standIds, int nombreStands) {
    }

    /** What the derivation produced: the créneaux, why each cut is there, and the days nothing said anything about. */
    public record Derivation(List<Creneau> creneaux, List<Coupure> coupures, List<LocalDate> joursSansFenetre) {
    }

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
            // Every open window of the day, in minutes: [debut, fin, stand index].
            List<int[]> fenetres = new ArrayList<>();
            Map<Integer, TreeSet<String>> standsParBorne = new TreeMap<>();
            for (Stand stand : stands) {
                if (HoraireStandResolver.sourceOfDay(stand, date) == HoraireStandResolver.SourceHoraire.DEFAUT) {
                    continue;
                }
                for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
                    if (!date.equals(ouverture.getDate()) || !ouverture.hasValidRange()) {
                        continue;
                    }
                    int debut = ouverture.getHeureDebut().toSecondOfDay() / 60;
                    int fin = ouverture.getHeureFin() == null ? fermeture : ouverture.getHeureFin().toSecondOfDay() / 60;
                    if (fin <= debut) {
                        continue;
                    }
                    fenetres.add(new int[] {debut, fin});
                    standsParBorne.computeIfAbsent(debut, key -> new TreeSet<>()).add(stand.getId());
                    standsParBorne.computeIfAbsent(fin, key -> new TreeSet<>()).add(stand.getId());
                }
            }
            if (fenetres.isEmpty()) {
                joursSansFenetre.add(date);
                continue;
            }
            List<Integer> bornes = new ArrayList<>(standsParBorne.keySet());
            List<int[]> tranches = new ArrayList<>();
            for (int i = 1; i < bornes.size(); i++) {
                int debut = bornes.get(i - 1);
                int fin = bornes.get(i);
                if (fenetres.stream().anyMatch(fenetre -> fenetre[0] <= debut && fin <= fenetre[1])) {
                    tranches.add(new int[] {debut, fin});
                }
            }
            // A stretch too short to be a slot is a clock artefact of one
            // stand's bound: its own end cut is dropped, so it joins the
            // stretch after it — or the one before, when nothing follows.
            List<int[]> retenues = new ArrayList<>();
            for (int i = 0; i < tranches.size(); i++) {
                int[] tranche = tranches.get(i);
                boolean courte = tranche[1] - tranche[0] < parametres.dureeMinimaleMinutes();
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
            tranches = retenues;
            for (int[] tranche : tranches) {
                creneaux.add(new Creneau(null, 0, date, minuteToTime(tranche[0]), minuteToTime(tranche[1])));
            }
            standsParBorne.forEach((borne, ids) -> coupures.add(new Coupure(date, minuteToTime(borne),
                    ids.stream().limit(STANDS_CITES).toList(), ids.size())));
        }
        return new Derivation(creneaux, coupures, joursSansFenetre);
    }

    /** Minutes since midnight back to a wall-clock time; {@code 24:00} wraps to {@code 00:00}. */
    private static LocalTime minuteToTime(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % MINUTES_PAR_JOUR) * 60L);
    }
}
