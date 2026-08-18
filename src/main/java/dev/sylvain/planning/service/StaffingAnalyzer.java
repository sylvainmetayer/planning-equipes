package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * How many animateurs the current stands/créneaux need at a minimum, computed
 * from the very seats a solve would have to fill — the
 * {@link PosteAffectation} list of
 * {@code PlanningService#construireDepuisReferenceData()} — and not from a
 * stands × créneaux product.
 *
 * <p>That distinction is the whole point of this class. The estimate used to
 * be computed in the browser from {@code effectifMin} summed over the stands
 * "open" on each créneau, which silently broke on any scenario using the two
 * mechanisms this application is built around:</p>
 *
 * <ul>
 * <li><b>Recurring horaires.</b> The browser only knew about dated
 * {@code ouvertures}/{@code indisponibilites}, so a stand describing its
 * schedule as rules (the normal case since the horaires feature) counted as
 * open on every single créneau, around the clock.</li>
 * <li><b>Auto-découpage.</b> When the active group holds generated vacations,
 * its créneaux <em>overlap</em> — several relay familles cover the same hours,
 * and consecutive vacations overlap during handovers. Summing a stand's
 * effectif over every such créneau counts the same hour of the same stand once
 * per famille. On edition-1708 (5 familles, 354 vacations) the workload bound came
 * out above 1500 animateurs for a festival staffed by 153.</li>
 * </ul>
 *
 * <p>Working from the generated postes removes both errors by construction:
 * poste generation already resolved the horaires, already assigned each stand
 * to exactly one famille, and already halved the headcount of a meal-pause
 * coverage vacation. Whatever the seats are, they are what has to be staffed.</p>
 *
 * <p>Three bounds are computed and the largest wins:</p>
 * <ol>
 * <li><b>Pic simultané</b> — the largest number of seats open at the same
 * instant. A floor by definition: nobody holds two seats at once.</li>
 * <li><b>Pic avec tampon de pause</b> — the same peak, over intervals each
 * extended by the legally-required break between two vacations
 * ({@code ParametresLegaux#getPauseMinimaleEntreVacationsMinutes()}). Two
 * vacations can be held by the same person only if their extended intervals
 * don't overlap, so this maximum overlap is <em>exactly</em> the minimum number
 * of distinct animateurs a day requires — the chromatic number of an interval
 * graph is its maximum clique.</li>
 * <li><b>Charge horaire</b> — total person-hours divided by what one animateur
 * may legally work over the festival's ISO weeks.</li>
 * </ol>
 *
 * <p>All three stay optimistic: none of them accounts for compétences, for
 * individual disponibilités, or for the daily-rest constraints. They are a
 * recruitment floor to exceed, never a target — the exact answer only comes
 * from a real solve.</p>
 */
@ApplicationScoped
public class StaffingAnalyzer {

    /** Which of the bounds ended up setting {@link StaffingSummary#minimumTotal()}. */
    public enum BorneRetenue {
        PIC_SIMULTANE,
        PIC_AVEC_PAUSE,
        CHARGE_HORAIRE
    }

    /**
     * One festival day. {@code heures} are person-hours (seats × duration),
     * {@code sieges} the number of postes generated that day.
     */
    public record JourStaffing(
            LocalDate date,
            int jour,
            int standsOuverts,
            int sieges,
            double heures,
            int picSimultane,
            int picAvecPause) {
    }

    public record StaffingSummary(
            List<JourStaffing> parJour,
            int picSimultane,
            int picAvecPause,
            JourStaffing jourCritique,
            double totalDemandeHeures,
            int nombreSemaines,
            double capaciteHeuresParAnimateur,
            int chargeTotal,
            int minimumTotal,
            BorneRetenue borneRetenue,
            int minimumMajeurs,
            int minimumMineurs,
            int pauseMinimaleMinutes,
            int dureeHebdomadaireMaxMinutes) {
    }

    /** Half-open interval of one seat, in minutes from the start of its day. */
    private record Siege(LocalDate date, int debut, int fin, Stand stand) {
    }

    public StaffingSummary analyser(List<PosteAffectation> postes, int dureeHebdomadaireMaxMinutes,
            int pauseMinimaleMinutes) {
        List<Siege> sieges = sieges(postes);
        Map<LocalDate, List<Siege>> parDate = new TreeMap<>();
        for (Siege siege : sieges) {
            parDate.computeIfAbsent(siege.date(), date -> new ArrayList<>()).add(siege);
        }

        Map<LocalDate, Integer> jourParDate = jourParDate(postes);
        List<JourStaffing> parJour = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Siege>> entree : parDate.entrySet()) {
            List<Siege> duJour = entree.getValue();
            Set<String> standsOuverts = new HashSet<>();
            double heures = 0;
            for (Siege siege : duJour) {
                if (siege.stand() != null) {
                    standsOuverts.add(siege.stand().getId());
                }
                heures += (siege.fin() - siege.debut()) / 60.0;
            }
            parJour.add(new JourStaffing(
                    entree.getKey(),
                    jourParDate.getOrDefault(entree.getKey(), 0),
                    standsOuverts.size(),
                    duJour.size(),
                    heures,
                    pic(duJour, 0),
                    pic(duJour, pauseMinimaleMinutes)));
        }

        int picSimultane = parJour.stream().mapToInt(JourStaffing::picSimultane).max().orElse(0);
        int picAvecPause = parJour.stream().mapToInt(JourStaffing::picAvecPause).max().orElse(0);
        JourStaffing jourCritique = parJour.stream()
                .max(Comparator.comparingInt(JourStaffing::picAvecPause))
                .orElse(null);

        double totalDemandeHeures = parJour.stream().mapToDouble(JourStaffing::heures).sum();
        int nombreSemaines = (int) postes.stream()
                .map(PosteAffectation::getCreneau)
                .filter(creneau -> creneau != null && creneau.getDate() != null)
                .map(Creneau::semaineIso)
                .distinct()
                .count();
        double capaciteHeuresParAnimateur = nombreSemaines * (dureeHebdomadaireMaxMinutes / 60.0);
        int chargeTotal = capaciteHeuresParAnimateur > 0
                ? (int) Math.ceil(totalDemandeHeures / capaciteHeuresParAnimateur)
                : 0;

        int minimumTotal = Math.max(Math.max(picSimultane, picAvecPause), chargeTotal);
        BorneRetenue borneRetenue = borneRetenue(picSimultane, picAvecPause, chargeTotal);

        int minimumMajeurs = (int) Math.ceil(minimumTotal * partMajeurs(sieges));
        return new StaffingSummary(
                List.copyOf(parJour),
                picSimultane,
                picAvecPause,
                jourCritique,
                totalDemandeHeures,
                nombreSemaines,
                capaciteHeuresParAnimateur,
                chargeTotal,
                minimumTotal,
                borneRetenue,
                minimumMajeurs,
                minimumTotal - minimumMajeurs,
                pauseMinimaleMinutes,
                dureeHebdomadaireMaxMinutes);
    }

    private static BorneRetenue borneRetenue(int picSimultane, int picAvecPause, int chargeTotal) {
        if (chargeTotal >= picAvecPause && chargeTotal >= picSimultane) {
            return BorneRetenue.CHARGE_HORAIRE;
        }
        return picAvecPause >= picSimultane ? BorneRetenue.PIC_AVEC_PAUSE : BorneRetenue.PIC_SIMULTANE;
    }

    /**
     * Share of the seats that an adult has to hold: all of them on a
     * {@code reserveMajeurs} stand, half of them (rounded up, per stand and
     * per créneau) everywhere else — the same rule the seats themselves are
     * generated under. Defaults to 0.5 when there is nothing to count.
     */
    private static double partMajeurs(List<Siege> sieges) {
        if (sieges.isEmpty()) {
            return 0.5;
        }
        // Group by stand and window, so "half the seats, rounded up" is
        // applied to a real group of simultaneous seats and not to the
        // festival's grand total.
        Map<String, int[]> parGroupe = new LinkedHashMap<>();
        for (Siege siege : sieges) {
            String cle = (siege.stand() == null ? "?" : siege.stand().getId())
                    + "@" + siege.date() + "#" + siege.debut() + "-" + siege.fin();
            int[] compteur = parGroupe.computeIfAbsent(cle, ignored -> new int[] { 0, 0 });
            compteur[0]++;
            compteur[1] = siege.stand() != null && siege.stand().isReserveMajeurs() ? 1 : 0;
        }
        int total = 0;
        int majeurs = 0;
        for (int[] compteur : parGroupe.values()) {
            total += compteur[0];
            majeurs += compteur[1] == 1 ? compteur[0] : (compteur[0] + 1) / 2;
        }
        return total == 0 ? 0.5 : (double) majeurs / total;
    }

    /**
     * Largest number of {@code sieges} overlapping at the same instant, each
     * one extended by {@code tamponMinutes} on its right — see the class
     * javadoc for why extending them turns a "seats at once" reading into an
     * exact "distinct people needed" one.
     */
    private static int pic(List<Siege> sieges, int tamponMinutes) {
        List<int[]> evenements = new ArrayList<>(sieges.size() * 2);
        for (Siege siege : sieges) {
            evenements.add(new int[] { siege.debut(), 1 });
            evenements.add(new int[] { siege.fin() + tamponMinutes, -1 });
        }
        // A seat ending exactly when another starts must not count as an
        // overlap: process the -1 events of an instant before its +1 events.
        evenements.sort(Comparator.<int[]>comparingInt(evenement -> evenement[0])
                .thenComparingInt(evenement -> evenement[1]));
        int courant = 0;
        int pic = 0;
        for (int[] evenement : evenements) {
            courant += evenement[1];
            pic = Math.max(pic, courant);
        }
        return pic;
    }

    private static List<Siege> sieges(List<PosteAffectation> postes) {
        List<Siege> sieges = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau == null || creneau.getDate() == null) {
                continue;
            }
            LocalTime debut = poste.heureDebutEffectif();
            if (debut == null) {
                continue;
            }
            int duree = poste.getDureeEffectiveMinutes();
            if (duree <= 0) {
                continue;
            }
            int debutMinutes = debut.toSecondOfDay() / 60;
            // A window running past midnight stays on its own day, with an end
            // beyond 24 h — the peak of a night slot belongs to the evening it
            // started, not to the next morning.
            sieges.add(new Siege(creneau.getDate(), debutMinutes, debutMinutes + duree, poste.getStand()));
        }
        return sieges;
    }

    private static Map<LocalDate, Integer> jourParDate(List<PosteAffectation> postes) {
        Map<LocalDate, Integer> jours = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && creneau.getDate() != null) {
                jours.putIfAbsent(creneau.getDate(), creneau.getJour());
            }
        }
        return jours;
    }
}
