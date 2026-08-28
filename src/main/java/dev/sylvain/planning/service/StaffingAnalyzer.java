package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * How many animateurs the current stands/créneaux need at a minimum, computed
 * from the very seats a solve would have to fill — the
 * {@link PosteAffectation} list of
 * {@code PlanningService#buildFromReferenceData()} — and not from a
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
 * its créneaux <em>overlap</em> — several relay families cover the same hours,
 * and consecutive vacations overlap during handovers. Summing a stand's
 * effectif over every such créneau counts the same hour of the same stand once
 * per famille. On edition-1708 (5 families, 354 vacations) the workload bound came
 * out above 1500 animateurs for an event staffed by 153.</li>
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
 * may legally work over the event's ISO weeks.</li>
 * </ol>
 *
 * <p>All three stay optimistic: none of them accounts for individual
 * disponibilités or for the daily-rest constraints. They are a recruitment
 * floor to exceed, never a target — the exact answer only comes from a real
 * solve.</p>
 *
 * <h2>Bottleneck per game category</h2>
 *
 * <p>The three bounds above are global, so they answer "how many animateurs"
 * and never "how many of which kind" — yet a plan that misses four people
 * almost always misses four people <em>competent on one game category</em>.
 * {@link CompetenceStaffing} replays the same three bounds on the seats of a
 * single category and puts them against the animateurs who can hold them. Two
 * attribution rules make that comparison sound rather than merely plausible:</p>
 *
 * <ul>
 * <li><b>Demand is attributed exclusively.</b> A seat counts for category
 * {@code T} only when its stand proposes {@code T} and nothing else — then,
 * and only then, is {@code T} provably required to staff it. The seats of a
 * stand proposing several categories can be covered from either pool, so no
 * single category can claim them; they are reported apart, as
 * {@link CompetenceStaffing#siegesNonAttribues()}. Splitting them over every
 * category of their stand would count the same seat several times and invent
 * bottlenecks.</li>
 * <li><b>A ninja is a reinforcement, never a specialist.</b>
 * {@link Animateur#hasCompetenceFor(Stand)} lets a polyvalent take any stand,
 * so counting them as available in every category would add the same person to
 * every row — inflating each pool and hiding the very bottleneck this exists
 * to show. A pool therefore holds the {@code specialistes} of a category: the
 * animateurs who declared it (the ninja category included, since on the demand
 * side it is a category like any other). The polyvalents are reported once
 * more, apart, as a shared reserve — {@link CompetenceStaffing#polyvalents()},
 * dispatchable anywhere but each on one seat at a time. A
 * {@link CompetenceStaffing#manqueTotal()} larger than that reserve is a
 * signal, not a proof: two categories peaking at different hours can be served
 * by the same polyvalent. This is the reading the decision record "le ninja
 * est un renfort, jamais un spécialiste" settles for the neighbouring
 * fragilité screen, applied to the same question — measuring the rarity of a
 * competence never counts the polyvalents in.</li>
 * </ul>
 *
 * <p>Both approximations this leaves point the same way — demand understated
 * for a stand proposing several categories, supply overstated for an animateur
 * competent on several — so a flagged bottleneck is a real one, while the
 * absence of one proves nothing. That is the same optimism as the bounds
 * above.</p>
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
     * One event day. {@code heures} are person-hours (seats × duration),
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
            int dureeHebdomadaireMaxMinutes,
            CompetenceStaffing parCompetence) {
    }

    /**
     * One game category: the same bounds as {@link StaffingSummary}, computed
     * on the seats that provably require it, against its {@code specialistes}
     * — the animateurs declaring it. {@code manque} is what that pool is short
     * of: zero when the bound is met, and zero as long as no animateur is
     * known at all, since there is then nothing to compare the bound to.
     */
    public record TypologieStaffing(
            String typologie,
            String label,
            boolean ninja,
            int sieges,
            double heures,
            int nombreSemaines,
            int picSimultane,
            int picAvecPause,
            int chargeTotal,
            int minimumTotal,
            BorneRetenue borneRetenue,
            int specialistes,
            int manque) {
    }

    /**
     * The bottleneck view: one row per game category holding seats, plus what
     * the per-category reading deliberately leaves out — see the class javadoc.
     *
     * @param parTypologie      rows, the tightest bottleneck first
     * @param polyvalents       animateurs holding the ninja category: a shared
     *                          reserve, dispatchable on any category but on one
     *                          seat at a time
     * @param siegesNonAttribues seats of stands proposing several categories (or
     *                          none), which no single category can claim
     * @param manqueTotal       sum of the {@code manque} of every row
     * @param animateursTotal   animateurs known at all — {@code 0} means the
     *                          référentiel is still empty and nothing is compared
     * @param typologieNinjaDefinie whether the referential marks a ninja category
     *                          at all. Without it nobody is polyvalent, and a
     *                          reserve of zero must be read as "no such notion
     *                          here" rather than as a shortage of backup
     */
    public record CompetenceStaffing(
            List<TypologieStaffing> parTypologie,
            int polyvalents,
            int siegesNonAttribues,
            int manqueTotal,
            int animateursTotal,
            boolean typologieNinjaDefinie) {
    }

    /** Half-open interval of one seat, in minutes from the start of its day. */
    private record Siege(LocalDate date, int debut, int fin, Stand stand) {
    }

    /**
     * @param animateurs  the animateurs the referential holds, only ever
     *                    counted — never named. An empty list still yields
     *                    every bound; only the bottleneck comparison is left
     *                    out, since there is nothing to compare against yet.
     * @param typologies  the game category referential, which is what tells
     *                    the ninja one apart. Never a hard-coded list: those
     *                    categories are CRUD data.
     */
    public StaffingSummary analyze(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<TypologieItem> typologies, int dureeHebdomadaireMaxMinutes, int pauseMinimaleMinutes) {
        List<Siege> sieges = sieges(postes);
        Map<LocalDate, List<Siege>> byDate = new TreeMap<>();
        for (Siege siege : sieges) {
            byDate.computeIfAbsent(siege.date(), date -> new ArrayList<>()).add(siege);
        }

        Map<LocalDate, Integer> dayByDate = dayByDate(postes);
        List<JourStaffing> parJour = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Siege>> entree : byDate.entrySet()) {
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
                    dayByDate.getOrDefault(entree.getKey(), 0),
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
                dureeHebdomadaireMaxMinutes,
                bottleneckPerCategory(sieges, animateurs, typologies, dureeHebdomadaireMaxMinutes,
                        pauseMinimaleMinutes));
    }

    /**
     * Replays the three bounds on the seats of each game category and puts
     * them against the animateurs who declare it — see the class javadoc for
     * the two attribution rules this rests on.
     */
    private static CompetenceStaffing bottleneckPerCategory(List<Siege> sieges, List<Animateur> animateurs,
            List<TypologieItem> typologies, int dureeHebdomadaireMaxMinutes, int pauseMinimaleMinutes) {
        List<Animateur> connus = animateurs == null ? List.of() : animateurs;
        List<TypologieItem> referentiel = typologies == null ? List.of() : typologies;

        Map<String, List<Siege>> parTypologie = new LinkedHashMap<>();
        int siegesNonAttribues = 0;
        for (Siege siege : sieges) {
            String typologie = typologieExclusive(siege.stand());
            if (typologie == null) {
                siegesNonAttribues++;
            } else {
                parTypologie.computeIfAbsent(typologie, id -> new ArrayList<>()).add(siege);
            }
        }

        String ninja = referentiel.stream().filter(TypologieItem::ninja).map(TypologieItem::id).findFirst()
                .orElse(null);
        Map<String, String> labels = new LinkedHashMap<>();
        referentiel.forEach(typologie -> labels.put(typologie.id(), typologie.label()));

        Map<String, Integer> specialistes = new LinkedHashMap<>();
        int polyvalents = 0;
        for (Animateur animateur : connus) {
            Map<String, ?> competences = animateur.getCompetences() == null ? Map.of() : animateur.getCompetences();
            // Declared competences only: a ninja is eligible everywhere, but
            // counting them in every category would add one person to every
            // pool at once. They are the shared reserve below instead.
            competences.keySet().forEach(id -> specialistes.merge(id, 1, Integer::sum));
            if (animateur.isNinja() || (ninja != null && competences.containsKey(ninja))) {
                polyvalents++;
            }
        }

        List<TypologieStaffing> lignes = new ArrayList<>();
        for (Map.Entry<String, List<Siege>> entree : parTypologie.entrySet()) {
            String id = entree.getKey();
            Bornes bornes = bornes(entree.getValue(), dureeHebdomadaireMaxMinutes, pauseMinimaleMinutes);
            int disponibles = specialistes.getOrDefault(id, 0);
            int manque = connus.isEmpty() ? 0 : Math.max(0, bornes.minimumTotal() - disponibles);
            lignes.add(new TypologieStaffing(
                    id,
                    labels.getOrDefault(id, id),
                    id.equals(ninja),
                    entree.getValue().size(),
                    bornes.heures(),
                    bornes.semaines(),
                    bornes.picSimultane(),
                    bornes.picAvecPause(),
                    bornes.chargeTotal(),
                    bornes.minimumTotal(),
                    bornes.borneRetenue(),
                    disponibles,
                    manque));
        }
        // Tightest bottleneck first, so the row that explains an infeasibility
        // is the one read first.
        lignes.sort(Comparator.comparingInt(TypologieStaffing::manque).reversed()
                .thenComparing(Comparator.comparingInt(TypologieStaffing::minimumTotal).reversed())
                .thenComparing(TypologieStaffing::label)
                .thenComparing(TypologieStaffing::typologie));

        return new CompetenceStaffing(
                List.copyOf(lignes),
                polyvalents,
                siegesNonAttribues,
                lignes.stream().mapToInt(TypologieStaffing::manque).sum(),
                connus.size(),
                ninja != null);
    }

    /**
     * The one game category a stand's seats provably require, or {@code null}
     * when it proposes several (either pool staffs them) or none at all.
     */
    private static String typologieExclusive(Stand stand) {
        if (stand == null || stand.getTypologiesProposees() == null
                || stand.getTypologiesProposees().size() != 1) {
            return null;
        }
        return stand.getTypologiesProposees().iterator().next();
    }

    /** The three bounds of the class javadoc, over an arbitrary set of seats. */
    private record Bornes(double heures, int semaines, int picSimultane, int picAvecPause, int chargeTotal,
            int minimumTotal, BorneRetenue borneRetenue) {
    }

    private static Bornes bornes(Collection<Siege> sieges, int dureeHebdomadaireMaxMinutes,
            int pauseMinimaleMinutes) {
        Map<LocalDate, List<Siege>> byDate = new TreeMap<>();
        double heures = 0;
        for (Siege siege : sieges) {
            byDate.computeIfAbsent(siege.date(), date -> new ArrayList<>()).add(siege);
            heures += (siege.fin() - siege.debut()) / 60.0;
        }
        int picSimultane = 0;
        int picAvecPause = 0;
        for (List<Siege> duJour : byDate.values()) {
            // Peaks are per day: minutes are counted from the start of a day,
            // so seats of two different dates never overlap.
            picSimultane = Math.max(picSimultane, pic(duJour, 0));
            picAvecPause = Math.max(picAvecPause, pic(duJour, pauseMinimaleMinutes));
        }
        int semaines = (int) byDate.keySet().stream().mapToInt(StaffingAnalyzer::semaineIso).distinct().count();
        double capacite = semaines * (dureeHebdomadaireMaxMinutes / 60.0);
        int chargeTotal = capacite > 0 ? (int) Math.ceil(heures / capacite) : 0;
        int minimumTotal = Math.max(Math.max(picSimultane, picAvecPause), chargeTotal);
        return new Bornes(heures, semaines, picSimultane, picAvecPause, chargeTotal, minimumTotal,
                borneRetenue(picSimultane, picAvecPause, chargeTotal));
    }

    /** Same weeks {@code Creneau#semaineIso()} names, as a comparable number. */
    private static int semaineIso(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) * 100 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
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
        // event's grand total.
        Map<String, int[]> byGroup = new LinkedHashMap<>();
        for (Siege siege : sieges) {
            String key = (siege.stand() == null ? "?" : siege.stand().getId())
                    + "@" + siege.date() + "#" + siege.debut() + "-" + siege.fin();
            int[] compteur = byGroup.computeIfAbsent(key, ignored -> new int[] { 0, 0 });
            compteur[0]++;
            compteur[1] = siege.stand() != null && siege.stand().isReserveMajeurs() ? 1 : 0;
        }
        int total = 0;
        int majeurs = 0;
        for (int[] compteur : byGroup.values()) {
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

    private static Map<LocalDate, Integer> dayByDate(List<PosteAffectation> postes) {
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
