package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Who is a single point of failure in the plan currently persisted, and where
 * the referential is one person away from having nobody at all.
 *
 * <p>Two questions, both answered in plain Java from the persisted seats and
 * the competence referential — <b>no solve, ever</b>, in the spirit of
 * {@link StaffingAnalyzer} and {@link FeasibilityAnalyzer}:</p>
 *
 * <ol>
 * <li><b>What collapses if one animateur withdraws.</b> Seats are grouped by
 * stand × timeslot × window — the very grouping seat generation uses, since
 * {@code ProblemBuilder.buildPostes} emits {@code max(1, effectifMin)} seats
 * per group (halved on a break-covering shift). The number of seats of a group
 * <em>is</em> its effectif floor, so a group drops below it as soon as one of
 * its filled seats is vacated. Reported per animateur, together with the
 * qualifier that makes the figure discriminating: whether anybody else could
 * step in.</li>
 * <li><b>Where a single person is competent.</b> Every group whose stand has at
 * most one animateur competent for its typologies and free that day. That one
 * is about the referential, not about the plan: it is the recruiting and
 * training list.</li>
 * </ol>
 *
 * <h2>How a ninja is counted</h2>
 *
 * <p>{@link Animateur#hasCompetenceFor(Stand)} answers {@code true} for a ninja
 * on every stand — polyvalence is exactly what the flag means. Counting them as
 * competent everywhere would make the second question return almost nothing on
 * a referential holding a handful of ninjas, and that emptiness would be a
 * measurement artefact, not good news.</p>
 *
 * <p>So the two questions count them differently, on purpose:</p>
 *
 * <ul>
 * <li>the <b>competence scarcity</b> list counts only <em>specialists</em> —
 * animateurs holding one of the stand's own typologies — and reports the
 * available ninjas beside them as reinforcements. A stand with one specialist
 * and three ninjas is still a stand with one specialist; it is simply less
 * severe than one with none;</li>
 * <li>the <b>replaceability</b> of a seat counts ninjas in full: the question
 * there is whether the seat can be held tomorrow, and the solver does dispatch
 * a ninja on it.</li>
 * </ul>
 *
 * <p>A referential with no ninja typologie at all makes the two counts
 * coincide, which {@code ninjaConfigure} says out loud so a reader does not
 * take the absence of reinforcements for a shortage. See
 * {@code docs/decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md}.</p>
 *
 * <h2>What this deliberately ignores</h2>
 *
 * <p>A replacement is called possible when the person is competent, not
 * declared unavailable that day, adult where the stand requires it, and not
 * already holding an overlapping seat. Daily rest, weekly hours, ad hoc
 * exceptions and preferences are <b>not</b> checked: like the bounds of
 * {@link StaffingAnalyzer}, the answer stays optimistic — somebody reported as
 * irreplaceable certainly is, somebody reported as replaceable may still turn
 * out not to be. Only one withdrawal at a time is simulated.</p>
 */
@ApplicationScoped
public class FragiliteAnalyzer {

    /** Seats detailed per animateur; the rest is only counted. */
    static final int MAX_POSTES_DETAILLES = 20;

    /** Scarcity rows returned; the rest is only counted. */
    static final int MAX_COMPETENCES_RARES = 100;

    private static final long MINUTES_PAR_JOUR = 24 * 60L;

    /** How badly a row hurts, ranked the way {@code FeasibilityAnalyzer} ranks its causes. */
    public enum SeveriteFragilite {
        CRITIQUE,
        ELEVEE,
        MODEREE
    }

    /**
     * One stand × timeslot group an animateur's withdrawal would leave short.
     *
     * @param effectifMin      the effectif configured for this window — the
     *                         opening window's own, or the stand's minimum
     *                         when the window names none — for reference; not
     *                         this group's floor, which {@code siegesRequis}
     *                         carries
     * @param couverturePause  true on a break-covering shift, where seat
     *                         generation halves the headcount (rounded up):
     *                         that, and nothing else, is why
     *                         {@code siegesRequis} can be half
     *                         {@code effectifMin}
     * @param siegesRequis     seats the group holds, which <b>is</b> the
     *                         effectif floor that applies to it
     * @param siegesPourvus    seats currently filled
     * @param siegesLiberes    seats this animateur would vacate
     * @param remplacants      animateurs who could take one of them over
     * @param irremplacable    true when {@code remplacants} is zero
     */
    public record PosteFragile(
            String standId,
            String standNom,
            long creneauId,
            LocalDate date,
            int jour,
            LocalTime heureDebut,
            LocalTime heureFin,
            int effectifMin,
            boolean couverturePause,
            int siegesRequis,
            int siegesPourvus,
            int siegesLiberes,
            int remplacants,
            boolean irremplacable) {
    }

    /**
     * One animateur of the persisted plan, and what leaves with them. Animateurs
     * holding no seat are not listed: they cannot be a point of failure.
     */
    public record AnimateurFragilite(
            String animateurId,
            String nom,
            boolean ninja,
            int affectations,
            int postesEffondres,
            int postesIrremplacables,
            int competencesRares,
            SeveriteFragilite severite,
            List<PosteFragile> postes,
            int postesNonDetailles) {
    }

    /**
     * One stand × timeslot at most one specialist can hold.
     *
     * @param specialistes animateurs holding one of the stand's typologies and
     *                     free that day — zero or one, by construction
     * @param animateurId  the single specialist, {@code null} when there is none
     * @param renforts     ninjas free that day, who could stand in
     * @param pourvu       true when the plan already staffs the group in full
     */
    public record CompetenceRare(
            String standId,
            String standNom,
            long creneauId,
            LocalDate date,
            int jour,
            LocalTime heureDebut,
            LocalTime heureFin,
            List<String> typologies,
            int specialistes,
            String animateurId,
            String nom,
            int renforts,
            boolean pourvu,
            SeveriteFragilite severite) {
    }

    /**
     * @param groupesSansSpecialiste how many of {@code competencesRares} have
     *                               <em>no</em> specialist at all. Counted in
     *                               stand × timeslot × window groups, like
     *                               {@code groupesAnalyses} — one stand nobody
     *                               can hold, open on forty timeslots, weighs
     *                               forty here.
     */
    public record RapportFragilite(
            List<AnimateurFragilite> animateurs,
            List<CompetenceRare> competencesRares,
            int totalCompetencesRares,
            int groupesSansSpecialiste,
            int groupesAnalyses,
            int groupesDejaSousEffectif,
            int animateursIrremplacables,
            boolean ninjaConfigure,
            String message) {
    }

    /**
     * Scarcity rows worst first: a group nobody is competent for, then one
     * specialist and no ninja, then the mitigated ones — and inside a severity,
     * the groups the plan already staffs, since those are the ones a withdrawal
     * would empty tomorrow.
     */
    private static final Comparator<CompetenceRare> ORDRE_RARES = Comparator
            .comparing(CompetenceRare::severite)
            .thenComparing(CompetenceRare::pourvu, Comparator.reverseOrder())
            .thenComparing(CompetenceRare::standId)
            .thenComparingLong(CompetenceRare::creneauId);

    /**
     * Animateurs worst first: the ones nobody could replace, then the widest
     * blast radius. Ties settle on the id so two runs on the same plan return
     * the same order.
     */
    private static final Comparator<AnimateurFragilite> ORDRE_ANIMATEURS = Comparator
            .comparingInt(AnimateurFragilite::postesIrremplacables).reversed()
            .thenComparing(Comparator.comparingInt(AnimateurFragilite::competencesRares).reversed())
            .thenComparing(Comparator.comparingInt(AnimateurFragilite::postesEffondres).reversed())
            .thenComparing(Comparator.comparingInt(AnimateurFragilite::affectations).reversed())
            .thenComparing(AnimateurFragilite::animateurId, NaturalOrder.DES_IDS);

    /** Seats detailed first: the unreplaceable ones, then the largest holes. */
    private static final Comparator<PosteFragile> ORDRE_POSTES = Comparator
            .comparing(PosteFragile::irremplacable, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingInt(PosteFragile::siegesLiberes).reversed())
            .thenComparing(poste -> poste.date() == null ? LocalDate.MIN : poste.date())
            .thenComparingLong(PosteFragile::creneauId)
            .thenComparing(PosteFragile::standId);

    /** Identity of a seat group: one stand, one timeslot, one window inside it. */
    private record SeatGroupKey(String standId, long creneauId, LocalTime debut, LocalTime fin) {
    }

    /** The seats of one group, and what the plan currently does with them. */
    private static final class SeatGroup {
        private final Stand stand;
        private final Creneau creneau;
        private final LocalTime debut;
        private final LocalTime fin;
        private int sieges;
        private int pourvus;
        private final Map<String, Integer> occupants = new LinkedHashMap<>();

        private SeatGroup(Stand stand, Creneau creneau, LocalTime debut, LocalTime fin) {
            this.stand = stand;
            this.creneau = creneau;
            this.debut = debut;
            this.fin = fin;
        }
    }

    /**
     * Half-open interval of an assigned seat, in minutes since the epoch day —
     * <b>absolute</b>, not relative to a day. A seat running 22:00 → 02:00 has
     * to be comparable with the 00:00 → 04:00 seat of the next day, and two
     * per-day buckets never compare them: the overlap fell between the two, and
     * somebody already working through the night came out "free".
     */
    private record Interval(long debut, long fin) {

        private boolean overlaps(Interval other) {
            return debut < other.fin() && other.debut() < fin;
        }
    }

    public RapportFragilite analyze(PlanningEvenement planning) {
        List<Animateur> animateurs = planning == null || planning.getAnimateurs() == null
                ? List.of()
                : planning.getAnimateurs();
        List<PosteAffectation> postes = planning == null || planning.getPostes() == null
                ? List.of()
                : planning.getPostes();

        Map<SeatGroupKey, SeatGroup> groupes = groupSeats(postes);
        Map<String, List<Interval>> busy = busyIntervals(postes);
        boolean ninjaConfigure = animateurs.stream().anyMatch(Animateur::isNinja);

        List<CompetenceRare> rares = new ArrayList<>();
        Map<String, Integer> raresParAnimateur = new HashMap<>();
        Map<SeatGroupKey, Integer> remplacantsParGroupe = new HashMap<>();
        int dejaSousEffectif = 0;

        for (Map.Entry<SeatGroupKey, SeatGroup> entree : groupes.entrySet()) {
            SeatGroup groupe = entree.getValue();
            if (groupe.pourvus < groupe.sieges) {
                dejaSousEffectif++;
            }
            List<Animateur> specialistes = specialists(animateurs, groupe);
            List<Animateur> renforts = reinforcements(animateurs, groupe, specialistes);
            remplacantsParGroupe.put(entree.getKey(), countSubstitutes(specialistes, renforts, groupe, busy));
            if (specialistes.size() > 1) {
                continue;
            }
            Animateur seul = specialistes.isEmpty() ? null : specialistes.getFirst();
            if (seul != null) {
                raresParAnimateur.merge(seul.getId(), 1, Integer::sum);
            }
            rares.add(new CompetenceRare(
                    groupe.stand.getId(),
                    groupe.stand.getNom(),
                    groupe.creneau.getId(),
                    groupe.creneau.getDate(),
                    groupe.creneau.getJour(),
                    groupe.debut,
                    groupe.fin,
                    typologies(groupe.stand),
                    specialistes.size(),
                    seul == null ? null : seul.getId(),
                    seul == null ? null : seul.nomAffiche(),
                    renforts.size(),
                    groupe.pourvus >= groupe.sieges,
                    severityOfScarcity(specialistes.size(), renforts.size())));
        }

        rares.sort(ORDRE_RARES);
        int groupesSansSpecialiste = (int) rares.stream().filter(rare -> rare.specialistes() == 0).count();

        List<AnimateurFragilite> lignes = animateurLines(animateurs, groupes, remplacantsParGroupe, raresParAnimateur);
        int animateursIrremplacables = (int) lignes.stream()
                .filter(ligne -> ligne.postesIrremplacables() > 0)
                .count();

        return new RapportFragilite(
                lignes,
                List.copyOf(rares.subList(0, Math.min(MAX_COMPETENCES_RARES, rares.size()))),
                rares.size(),
                groupesSansSpecialiste,
                groupes.size(),
                dejaSousEffectif,
                animateursIrremplacables,
                ninjaConfigure,
                buildMessage(groupes.size(), lignes, animateursIrremplacables, rares.size(), groupesSansSpecialiste));
    }

    private List<AnimateurFragilite> animateurLines(List<Animateur> animateurs, Map<SeatGroupKey, SeatGroup> groupes,
            Map<SeatGroupKey, Integer> remplacantsParGroupe, Map<String, Integer> raresParAnimateur) {
        Map<String, List<PosteFragile>> parAnimateur = new LinkedHashMap<>();
        Map<String, Integer> affectations = new HashMap<>();
        for (Map.Entry<SeatGroupKey, SeatGroup> entree : groupes.entrySet()) {
            SeatGroup groupe = entree.getValue();
            int remplacants = remplacantsParGroupe.getOrDefault(entree.getKey(), 0);
            for (Map.Entry<String, Integer> occupant : groupe.occupants.entrySet()) {
                affectations.merge(occupant.getKey(), occupant.getValue(), Integer::sum);
                int restants = groupe.pourvus - occupant.getValue();
                // Only a group the plan currently satisfies can "collapse": one
                // already short of staff is a hole this screen did not open and
                // does not claim.
                if (groupe.pourvus < groupe.sieges || restants >= groupe.sieges) {
                    continue;
                }
                parAnimateur.computeIfAbsent(occupant.getKey(), id -> new ArrayList<>())
                        .add(new PosteFragile(
                                groupe.stand.getId(),
                                groupe.stand.getNom(),
                                groupe.creneau.getId(),
                                groupe.creneau.getDate(),
                                groupe.creneau.getJour(),
                                groupe.debut,
                                groupe.fin,
                                effectifConfigure(groupe),
                                groupe.creneau.isCouverturePause(),
                                groupe.sieges,
                                groupe.pourvus,
                                occupant.getValue(),
                                remplacants,
                                remplacants == 0));
            }
        }

        List<AnimateurFragilite> lignes = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            int total = affectations.getOrDefault(animateur.getId(), 0);
            if (total == 0) {
                continue;
            }
            List<PosteFragile> fragiles = new ArrayList<>(
                    parAnimateur.getOrDefault(animateur.getId(), List.of()));
            fragiles.sort(ORDRE_POSTES);
            int irremplacables = (int) fragiles.stream().filter(PosteFragile::irremplacable).count();
            int rares = raresParAnimateur.getOrDefault(animateur.getId(), 0);
            lignes.add(new AnimateurFragilite(
                    animateur.getId(),
                    animateur.nomAffiche(),
                    animateur.isNinja(),
                    total,
                    fragiles.size(),
                    irremplacables,
                    rares,
                    severityOfAnimateur(irremplacables, rares),
                    List.copyOf(fragiles.subList(0, Math.min(MAX_POSTES_DETAILLES, fragiles.size()))),
                    Math.max(0, fragiles.size() - MAX_POSTES_DETAILLES)));
        }
        lignes.sort(ORDRE_ANIMATEURS);
        return List.copyOf(lignes);
    }

    private static SeveriteFragilite severityOfAnimateur(int irremplacables, int rares) {
        if (irremplacables > 0) {
            return SeveriteFragilite.CRITIQUE;
        }
        return rares > 0 ? SeveriteFragilite.ELEVEE : SeveriteFragilite.MODEREE;
    }

    private static SeveriteFragilite severityOfScarcity(int specialistes, int renforts) {
        if (specialistes == 0 && renforts == 0) {
            return SeveriteFragilite.CRITIQUE;
        }
        return renforts == 0 ? SeveriteFragilite.ELEVEE : SeveriteFragilite.MODEREE;
    }

    /**
     * Animateurs holding one of the stand's own typologies, free that day and
     * old enough for it. The blanket "a ninja is competent everywhere" rule of
     * {@link Animateur#hasCompetenceFor(Stand)} is deliberately not used here —
     * see the class javadoc.
     */
    private static List<Animateur> specialists(List<Animateur> animateurs, SeatGroup groupe) {
        return animateurs.stream()
                .filter(animateur -> available(animateur, groupe))
                .filter(animateur -> groupe.stand.getTypologiesProposees().stream()
                        .anyMatch(typologie -> animateur.getCompetences().containsKey(typologie)))
                .toList();
    }

    /** Ninjas free that day who are not already counted as specialists. */
    private static List<Animateur> reinforcements(List<Animateur> animateurs, SeatGroup groupe,
            List<Animateur> specialistes) {
        Set<String> deja = new HashSet<>();
        for (Animateur specialiste : specialistes) {
            deja.add(specialiste.getId());
        }
        return animateurs.stream()
                .filter(Animateur::isNinja)
                .filter(animateur -> !deja.contains(animateur.getId()))
                .filter(animateur -> available(animateur, groupe))
                .toList();
    }

    private static boolean available(Animateur animateur, SeatGroup groupe) {
        LocalDate date = groupe.creneau.getDate();
        if (animateur.isIndisponibleOn(date)) {
            return false;
        }
        return !groupe.stand.isReserveMajeurs() || animateur.isMajeurOn(date);
    }

    /**
     * How many of the competent animateurs — specialists <em>and</em> ninjas,
     * since either can hold the seat — are neither already in the group nor
     * busy elsewhere at that very moment.
     */
    private static int countSubstitutes(List<Animateur> specialistes, List<Animateur> renforts, SeatGroup groupe,
            Map<String, List<Interval>> busy) {
        Interval plage = window(groupe.creneau.getDate(), groupe.debut, groupe.fin);
        if (plage == null) {
            return 0;
        }
        int libres = 0;
        for (List<Animateur> candidats : List.of(specialistes, renforts)) {
            for (Animateur candidat : candidats) {
                if (groupe.occupants.containsKey(candidat.getId())) {
                    continue;
                }
                if (isBusy(busy.get(candidat.getId()), plage)) {
                    continue;
                }
                libres++;
            }
        }
        return libres;
    }

    /**
     * The effectif configured for the group's window: the open segment of the
     * créneau that this group's effective window is, or the stand's minimum
     * when the seats were not generated from windows (hand-authored postes, a
     * closure-mode day).
     */
    private static int effectifConfigure(SeatGroup groupe) {
        for (Creneau.SegmentOuvert segment : groupe.creneau.segmentsOuverts(groupe.stand)) {
            LocalTime debut = groupe.creneau.getHeureDebut().plusMinutes(segment.debutMinutes());
            LocalTime fin = groupe.creneau.getHeureDebut().plusMinutes(segment.finMinutes());
            boolean memeDebut = groupe.debut == null ? segment.debutMinutes() == 0 : groupe.debut.equals(debut);
            boolean memeFin = groupe.fin == null
                    ? segment.finMinutes() == groupe.creneau.getDureeMinutes() : groupe.fin.equals(fin);
            if (memeDebut && memeFin) {
                return segment.effectif();
            }
        }
        return groupe.stand.getEffectifMin();
    }

    private static boolean isBusy(List<Interval> plages, Interval plage) {
        return plages != null && plages.stream().anyMatch(autre -> autre.overlaps(plage));
    }

    private static Map<SeatGroupKey, SeatGroup> groupSeats(List<PosteAffectation> postes) {
        Map<SeatGroupKey, SeatGroup> groupes = new TreeMap<>(Comparator
                .comparing(SeatGroupKey::standId)
                .thenComparingLong(SeatGroupKey::creneauId)
                .thenComparing(SeatGroupKey::debut, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(SeatGroupKey::fin, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            Creneau creneau = poste.getCreneau();
            if (stand == null || creneau == null) {
                continue;
            }
            LocalTime debut = poste.heureDebutEffectif();
            LocalTime fin = poste.heureFinEffectif();
            SeatGroupKey cle = new SeatGroupKey(stand.getId(), creneau.getId(), debut, fin);
            SeatGroup groupe = groupes.computeIfAbsent(cle, ignored -> new SeatGroup(stand, creneau, debut, fin));
            groupe.sieges++;
            Animateur animateur = poste.getAnimateur();
            if (animateur != null) {
                groupe.pourvus++;
                groupe.occupants.merge(animateur.getId(), 1, Integer::sum);
            }
        }
        return groupes;
    }

    /**
     * When each animateur is already busy, so a substitute is not
     * double-booked. One list per animateur, on the absolute timeline — see
     * {@link Interval}.
     */
    private static Map<String, List<Interval>> busyIntervals(List<PosteAffectation> postes) {
        Map<String, List<Interval>> plages = new HashMap<>();
        for (PosteAffectation poste : postes) {
            Animateur animateur = poste.getAnimateur();
            Creneau creneau = poste.getCreneau();
            if (animateur == null || creneau == null) {
                continue;
            }
            Interval plage = window(creneau.getDate(), poste.heureDebutEffectif(), poste.heureFinEffectif());
            if (plage == null) {
                continue;
            }
            plages.computeIfAbsent(animateur.getId(), ignored -> new ArrayList<>()).add(plage);
        }
        return plages;
    }

    /**
     * The window an assigned seat occupies, on an absolute timeline: minutes
     * since the epoch, so a window running past midnight simply ends on the
     * next day instead of needing a day bucket of its own.
     */
    private static Interval window(LocalDate date, LocalTime debut, LocalTime fin) {
        if (date == null || debut == null || fin == null) {
            return null;
        }
        long base = date.toEpochDay() * MINUTES_PAR_JOUR;
        long debutMinutes = base + debut.toSecondOfDay() / 60;
        long finMinutes = base + fin.toSecondOfDay() / 60;
        if (finMinutes <= debutMinutes) {
            finMinutes += MINUTES_PAR_JOUR;
        }
        return new Interval(debutMinutes, finMinutes);
    }

    private static List<String> typologies(Stand stand) {
        return stand.getTypologiesProposees().stream().sorted().toList();
    }

    private String buildMessage(int groupes, List<AnimateurFragilite> lignes, int animateursIrremplacables,
            int rares, int sansSpecialiste) {
        if (groupes == 0) {
            return "Aucun planning persisté : lancez une résolution pour mesurer la fragilité du planning.";
        }
        StringBuilder message = new StringBuilder();
        if (animateursIrremplacables == 0) {
            message.append("Aucun animateur n'est irremplaçable : chaque poste qu'un désistement libérerait "
                    + "pourrait être repris par quelqu'un d'autre.");
        } else {
            AnimateurFragilite premier = lignes.getFirst();
            message.append(animateursIrremplacables > 1
                    ? animateursIrremplacables + " animateurs laisseraient au moins un poste que personne "
                            + "d'autre ne peut reprendre"
                    : "1 animateur laisserait au moins un poste que personne d'autre ne peut reprendre");
            message.append(", à commencer par ").append(premier.nom()).append(" (")
                    .append(premier.postesIrremplacables())
                    .append(premier.postesIrremplacables() > 1 ? " postes)." : " poste).");
        }
        if (rares > 0) {
            message.append(' ').append(rares > 1
                    ? rares + " couples stand × créneau reposent sur une seule personne compétente"
                    : "1 couple stand × créneau repose sur une seule personne compétente");
            message.append(sansSpecialiste > 0
                    ? ", dont " + sansSpecialiste + " sans aucun spécialiste."
                    : ".");
        }
        return message.toString();
    }
}
