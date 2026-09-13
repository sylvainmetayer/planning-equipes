package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pairs (and triples) of hand-entered exceptions that <b>cannot all hold at
 * once</b>, whatever the solver does.
 *
 * <p>Ad hoc constraints are enforced as hard rules, so ten of them typed one
 * at a time eventually make the planning infeasible while the solver only
 * reports a negative hard score — the user reads "the solver can't do it"
 * where the truth is "your own exceptions contradict each other". Detecting
 * that at entry time is both cheaper and far more useful than a numeric cap on
 * how many exceptions may exist (issue #84).</p>
 *
 * <h2>What counts as a contradiction</h2>
 *
 * <p>Only what is <b>certainly</b> unsatisfiable, read against the exact
 * semantics {@code AdHocConstraints} gives each type — an exception refused by
 * mistake is worse than one caught a solve later:</p>
 *
 * <ul>
 *   <li>the same animateur pair declared both INCOMPATIBILITE and AFFINITE
 *       (issue #80): the hard rule would always silently win over the soft
 *       one;</li>
 *   <li>an AFFECTATION_FORCEE whose whole scope is covered by an
 *       INDISPONIBILITE_FORCEE targeting every animateur it could be satisfied
 *       by;</li>
 *   <li>two AFFECTATION_FORCEE pinning one animateur on time-clashing scopes —
 *       {@code pasDeChevauchementHoraire} forbids holding both seats, and no
 *       single seat can satisfy both;</li>
 *   <li>two AFFECTATION_FORCEE putting an INCOMPATIBILITE pair on the same
 *       créneau. Same <em>créneau</em>, not same stand: the incompatibility
 *       rule joins on the créneau alone, so two forced seats on two stands of
 *       that créneau are just as infeasible.</li>
 * </ul>
 *
 * <p>Everything else is left to the solver. A forced assignment naming two
 * animateurs, for instance, is satisfied by <em>either</em> of them
 * ({@code ifNotExists} over a disjunctive scope), so it only contradicts an
 * unavailability that covers them both.</p>
 *
 * <p>Overlaps are computed on the créneau's nominal window. A stand closure
 * can narrow the window a seat actually covers, so two forced assignments on
 * overlapping créneaux of two stands closed at complementary hours are
 * reported here although the solver could have placed them: the scope of an
 * exception is what the user typed, and refusing that combination — with a
 * message naming both — is the honest answer.</p>
 */
public final class ContrainteAdHocContradictions {

    private ContrainteAdHocContradictions() {}

    /** Kind of contradiction, in the order they are described above. */
    public enum TypeContradiction {
        PAIRE_INCOMPATIBLE_ET_AFFINE,
        AFFECTATION_FORCEE_SUR_INDISPONIBILITE,
        AFFECTATIONS_FORCEES_SIMULTANEES,
        AFFECTATIONS_FORCEES_INCOMPATIBLES
    }

    /**
     * One impossible combination.
     *
     * @param contrainteIds every exception involved, the last one being the
     *                      most recently entered — the one a refusal at entry
     *                      time is about
     * @param message       French sentence naming each of them, shown as-is to
     *                      the user
     */
    public record Contradiction(TypeContradiction type, List<String> contrainteIds, String message) {}

    /**
     * Contradictions the whole set holds, each reported once. Used to refuse a
     * scenario import, and to report — before any solve — exceptions entered
     * before this check existed.
     */
    /**
     * Every contradiction among {@code contraintes}, each one checked against
     * the ones ahead of it — the same pairs, in the same order, as calling
     * {@link #against} for each of them on its prefix.
     *
     * <p>That naive form compared every exception with every earlier one, and
     * its fourth rule, for each forced assignment, every earlier forced
     * assignment with every earlier incompatibility: 3.9 s for two thousand
     * exceptions, paid by every feasibility analysis. Every rule needs an
     * animateur in common — the same pair, the forced animateur inside the
     * unavailability, the same sole animateur, a forced animateur inside the
     * incompatible pair — so the earlier exceptions are indexed by animateur,
     * and only those sharing one are looked at, still in their order.</p>
     */
    public static List<Contradiction> detectAll(List<ContrainteAdHoc> contraintes, List<Creneau> creneaux) {
        List<ContrainteAdHoc> known = identified(contraintes);
        Map<Long, Creneau> creneauxById = indexCreneaux(creneaux);
        Map<String, List<Integer>> parAnimateur = new HashMap<>();
        Map<String, List<Integer>> forceesParAnimateurSeul = new HashMap<>();
        Map<String, List<Integer>> incompatibilitesParAnimateur = new HashMap<>();
        List<Contradiction> contradictions = new ArrayList<>();
        for (int index = 0; index < known.size(); index++) {
            ContrainteAdHoc candidate = known.get(index);
            List<String> animateurs = animateurIds(candidate);

            TreeSet<Integer> partageantUnAnimateur = new TreeSet<>();
            for (String animateur : animateurs) {
                partageantUnAnimateur.addAll(parAnimateur.getOrDefault(animateur, List.of()));
            }
            for (int prior : partageantUnAnimateur) {
                ContrainteAdHoc other = known.get(prior);
                contradictoryPairDeclaration(candidate, other).ifPresent(contradictions::add);
                forcedSeatOnUnavailability(candidate, other, creneauxById).ifPresent(contradictions::add);
                simultaneousForcedSeats(candidate, other, creneauxById).ifPresent(contradictions::add);
            }
            contradictions.addAll(forcedSeatsOfAnIncompatiblePairIndexed(
                    candidate, known, forceesParAnimateurSeul, incompatibilitesParAnimateur, creneauxById));

            for (String animateur : animateurs) {
                parAnimateur
                        .computeIfAbsent(animateur, key -> new ArrayList<>())
                        .add(index);
            }
            String seul = soleAnimateur(candidate);
            if (candidate.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE && seul != null) {
                forceesParAnimateurSeul
                        .computeIfAbsent(seul, key -> new ArrayList<>())
                        .add(index);
            }
            if (candidate.getType() == TypeContrainteAdHoc.INCOMPATIBILITE) {
                for (String animateur : animateurs) {
                    incompatibilitesParAnimateur
                            .computeIfAbsent(animateur, key -> new ArrayList<>())
                            .add(index);
                }
            }
        }
        return List.copyOf(contradictions);
    }

    /**
     * Rule 4 read through the indexes of {@link #detectAll}: the forced
     * assignments and incompatibilities that can matter are those naming the
     * candidate's animateurs, taken in their original order so the result is
     * the one {@link #forcedSeatsOfAnIncompatiblePair} gives on the prefix.
     */
    private static List<Contradiction> forcedSeatsOfAnIncompatiblePairIndexed(
            ContrainteAdHoc candidate,
            List<ContrainteAdHoc> known,
            Map<String, List<Integer>> forceesParAnimateurSeul,
            Map<String, List<Integer>> incompatibilitesParAnimateur,
            Map<Long, Creneau> creneaux) {
        List<Contradiction> contradictions = new ArrayList<>();
        if (candidate.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE) {
            String seul = soleAnimateur(candidate);
            if (seul == null) {
                return contradictions;
            }
            List<Integer> incompatibilites = incompatibilitesParAnimateur.getOrDefault(seul, List.of());
            if (incompatibilites.isEmpty()) {
                return contradictions;
            }
            TreeSet<Integer> forcees = new TreeSet<>();
            for (int incompatibilite : incompatibilites) {
                for (String animateur : animateurIds(known.get(incompatibilite))) {
                    forcees.addAll(forceesParAnimateurSeul.getOrDefault(animateur, List.of()));
                }
            }
            for (int other : forcees) {
                for (int incompatibilite : incompatibilites) {
                    forbiddenTogether(known.get(other), candidate, known.get(incompatibilite), creneaux)
                            .ifPresent(contradictions::add);
                }
            }
        } else if (candidate.getType() == TypeContrainteAdHoc.INCOMPATIBILITE) {
            TreeSet<Integer> forcees = new TreeSet<>();
            for (String animateur : animateurIds(candidate)) {
                forcees.addAll(forceesParAnimateurSeul.getOrDefault(animateur, List.of()));
            }
            List<Integer> ordre = new ArrayList<>(forcees);
            for (int first = 0; first < ordre.size(); first++) {
                for (int second = first + 1; second < ordre.size(); second++) {
                    forbiddenTogether(known.get(ordre.get(first)), known.get(ordre.get(second)), candidate, creneaux)
                            .ifPresent(contradictions::add);
                }
            }
        }
        return contradictions;
    }

    /** The naive reading {@link #detectAll} must stay equal to — kept for the test that holds them together. */
    static List<Contradiction> detectAllNaively(List<ContrainteAdHoc> contraintes, List<Creneau> creneaux) {
        List<ContrainteAdHoc> known = identified(contraintes);
        Map<Long, Creneau> creneauxById = indexCreneaux(creneaux);
        List<Contradiction> contradictions = new ArrayList<>();
        for (int index = 0; index < known.size(); index++) {
            contradictions.addAll(against(known.get(index), known.subList(0, index), creneauxById));
        }
        return List.copyOf(contradictions);
    }

    /**
     * Contradictions {@code candidate} would introduce into {@code others}.
     *
     * <p>Saving a constraint under an id that already exists overwrites it, so
     * the id of the candidate is dropped from {@code others}: the saved
     * version replaces the conflicting one instead of coexisting with it.</p>
     */
    public static List<Contradiction> detect(
            ContrainteAdHoc candidate, List<ContrainteAdHoc> others, List<Creneau> creneaux) {
        if (candidate == null || candidate.getId() == null || candidate.getType() == null) {
            return List.of();
        }
        List<ContrainteAdHoc> rest = identified(others).stream()
                .filter(contrainte -> !contrainte.getId().equals(candidate.getId()))
                .toList();
        return against(candidate, rest, indexCreneaux(creneaux));
    }

    /** Every contradiction between {@code candidate} and the exceptions already recorded. */
    private static List<Contradiction> against(
            ContrainteAdHoc candidate, List<ContrainteAdHoc> others, Map<Long, Creneau> creneaux) {
        List<Contradiction> contradictions = new ArrayList<>();
        for (ContrainteAdHoc other : others) {
            contradictoryPairDeclaration(candidate, other).ifPresent(contradictions::add);
            forcedSeatOnUnavailability(candidate, other, creneaux).ifPresent(contradictions::add);
            simultaneousForcedSeats(candidate, other, creneaux).ifPresent(contradictions::add);
        }
        contradictions.addAll(forcedSeatsOfAnIncompatiblePair(candidate, others, creneaux));
        return List.copyOf(contradictions);
    }

    /* ------------------------------------------------------------------ */
    /* Rule 1 — the same pair declared incompatible and in affinité        */
    /* ------------------------------------------------------------------ */

    /**
     * The two facts would pull the solver in opposite directions and the hard
     * one would always win without the user ever being told (issue #80). The
     * pair is the unordered couple of the first two animateur ids — exactly
     * what {@code AdHocConstraints} evaluates.
     */
    private static Optional<Contradiction> contradictoryPairDeclaration(
            ContrainteAdHoc candidate, ContrainteAdHoc other) {
        TypeContrainteAdHoc opposite =
                switch (candidate.getType()) {
                    case AFFINITE -> TypeContrainteAdHoc.INCOMPATIBILITE;
                    case INCOMPATIBILITE -> TypeContrainteAdHoc.AFFINITE;
                    default -> null;
                };
        Set<String> pair = animateurPair(candidate);
        if (opposite == null || pair == null || other.getType() != opposite || !pair.equals(animateurPair(other))) {
            return Optional.empty();
        }
        return Optional.of(new Contradiction(
                TypeContradiction.PAIRE_INCOMPATIBLE_ET_AFFINE,
                List.of(other.getId(), candidate.getId()),
                "La paire d'animateurs " + String.join(" / ", new TreeSet<>(pair))
                        + " est déjà visée par la contrainte " + other.getId()
                        + " (" + other.getType()
                        + ") : une même paire ne peut pas être déclarée à la fois incompatible et en affinité."
                        + " Supprimez d'abord la contrainte existante."));
    }

    /* ------------------------------------------------------------------ */
    /* Rule 2 — forced onto a scope one is forced out of                   */
    /* ------------------------------------------------------------------ */

    /**
     * An AFFECTATION_FORCEE is satisfied by any seat of its scope held by any
     * of its animateurs; an INDISPONIBILITE_FORCEE penalises every seat of its
     * own scope held by any of its animateurs. The two are therefore
     * incompatible exactly when the unavailability covers the whole scope of
     * the forced assignment <em>and</em> every animateur that could satisfy it.
     */
    private static Optional<Contradiction> forcedSeatOnUnavailability(
            ContrainteAdHoc candidate, ContrainteAdHoc other, Map<Long, Creneau> creneaux) {
        Optional<Contradiction> direct = forcedSeatAgainstUnavailability(candidate, other, creneaux);
        return direct.isPresent() ? direct : forcedSeatAgainstUnavailability(other, candidate, creneaux);
    }

    private static Optional<Contradiction> forcedSeatAgainstUnavailability(
            ContrainteAdHoc forced, ContrainteAdHoc unavailability, Map<Long, Creneau> creneaux) {
        if (forced.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE
                || unavailability.getType() != TypeContrainteAdHoc.INDISPONIBILITE_FORCEE) {
            return Optional.empty();
        }
        List<String> targets = animateurIds(forced);
        if (targets.isEmpty()
                || !animateurIds(unavailability).containsAll(targets)
                || !covers(unavailability, forced)) {
            return Optional.empty();
        }
        return Optional.of(new Contradiction(
                TypeContradiction.AFFECTATION_FORCEE_SUR_INDISPONIBILITE,
                List.of(unavailability.getId(), forced.getId()),
                "La contrainte " + forced.getId() + " force " + describeAnimateurs(targets) + " sur "
                        + describeScope(forced, creneaux) + ", alors que la contrainte "
                        + unavailability.getId() + " l'y déclare indisponible : ces deux exceptions ne"
                        + " peuvent pas être satisfaites en même temps."));
    }

    /* ------------------------------------------------------------------ */
    /* Rule 3 — two forced assignments the same person cannot both hold    */
    /* ------------------------------------------------------------------ */

    /**
     * Two forced assignments pinning the <b>same single animateur</b> on
     * time-clashing scopes. One seat can satisfy both only when they name the
     * same créneau and no two different stands; otherwise two seats are
     * needed, and {@code pasDeChevauchementHoraire} forbids holding two seats
     * whose hours overlap.
     *
     * <p>Restricted to constraints naming exactly one animateur on purpose: a
     * forced assignment listing two of them is satisfied by either, so the
     * pair could be spread over the two créneaux.</p>
     */
    private static Optional<Contradiction> simultaneousForcedSeats(
            ContrainteAdHoc candidate, ContrainteAdHoc other, Map<Long, Creneau> creneaux) {
        if (candidate.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE
                || other.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE) {
            return Optional.empty();
        }
        String animateur = soleAnimateur(candidate);
        if (animateur == null || !animateur.equals(soleAnimateur(other))) {
            return Optional.empty();
        }
        Long candidateCreneau = creneauId(candidate);
        Long otherCreneau = creneauId(other);
        if (candidateCreneau == null || otherCreneau == null) {
            return Optional.empty();
        }
        if (candidateCreneau.equals(otherCreneau)) {
            // One seat satisfies both, unless each of them narrows the stand
            // and they disagree on which.
            if (standsMatch(candidate, other)) {
                return Optional.empty();
            }
        } else if (!overlap(creneaux.get(candidateCreneau), creneaux.get(otherCreneau))) {
            return Optional.empty();
        }
        return Optional.of(new Contradiction(
                TypeContradiction.AFFECTATIONS_FORCEES_SIMULTANEES,
                List.of(other.getId(), candidate.getId()),
                "Les contraintes " + other.getId() + " et " + candidate.getId() + " forcent " + animateur
                        + " sur deux postes simultanés (" + describeScope(other, creneaux) + " et "
                        + describeScope(candidate, creneaux)
                        + ") : un animateur ne peut pas tenir deux postes qui se chevauchent."));
    }

    /* ------------------------------------------------------------------ */
    /* Rule 4 — two forced assignments of an incompatible pair             */
    /* ------------------------------------------------------------------ */

    /**
     * Two forced assignments placing an INCOMPATIBILITE pair on one créneau.
     * The incompatibility must cover both seats for the combination to be
     * certainly infeasible: one restricted to a single stand is escaped by a
     * forced assignment that leaves the stand open.
     *
     * <p>Reads the whole set rather than a pair, since the three exceptions
     * involved can be entered in any order — whichever comes last is the one
     * refused.</p>
     */
    private static List<Contradiction> forcedSeatsOfAnIncompatiblePair(
            ContrainteAdHoc candidate, List<ContrainteAdHoc> others, Map<Long, Creneau> creneaux) {
        List<ContrainteAdHoc> forcedSeats = ofType(others, TypeContrainteAdHoc.AFFECTATION_FORCEE);
        List<Contradiction> contradictions = new ArrayList<>();
        if (candidate.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE) {
            List<ContrainteAdHoc> incompatibilities = ofType(others, TypeContrainteAdHoc.INCOMPATIBILITE);
            for (ContrainteAdHoc other : forcedSeats) {
                for (ContrainteAdHoc incompatibility : incompatibilities) {
                    forbiddenTogether(other, candidate, incompatibility, creneaux)
                            .ifPresent(contradictions::add);
                }
            }
        } else if (candidate.getType() == TypeContrainteAdHoc.INCOMPATIBILITE) {
            for (int first = 0; first < forcedSeats.size(); first++) {
                for (int second = first + 1; second < forcedSeats.size(); second++) {
                    forbiddenTogether(forcedSeats.get(first), forcedSeats.get(second), candidate, creneaux)
                            .ifPresent(contradictions::add);
                }
            }
        }
        return contradictions;
    }

    private static Optional<Contradiction> forbiddenTogether(
            ContrainteAdHoc first,
            ContrainteAdHoc second,
            ContrainteAdHoc incompatibility,
            Map<Long, Creneau> creneaux) {
        String firstAnimateur = soleAnimateur(first);
        String secondAnimateur = soleAnimateur(second);
        Long creneau = creneauId(first);
        if (firstAnimateur == null
                || secondAnimateur == null
                || firstAnimateur.equals(secondAnimateur)
                || creneau == null
                || !creneau.equals(creneauId(second))) {
            return Optional.empty();
        }
        Set<String> pair = animateurPair(incompatibility);
        if (pair == null
                || !pair.equals(Set.of(firstAnimateur, secondAnimateur))
                || !covers(incompatibility, first)
                || !covers(incompatibility, second)) {
            return Optional.empty();
        }
        return Optional.of(new Contradiction(
                TypeContradiction.AFFECTATIONS_FORCEES_INCOMPATIBLES,
                List.of(first.getId(), second.getId(), incompatibility.getId()),
                "Les contraintes " + first.getId() + " et " + second.getId() + " forcent " + firstAnimateur
                        + " et " + secondAnimateur + " sur " + describeScope(first, creneaux)
                        + ", alors que la contrainte " + incompatibility.getId()
                        + " les déclare incompatibles sur un même créneau."));
    }

    /* ------------------------------------------------------------------ */
    /* Shared reading of a constraint                                      */
    /* ------------------------------------------------------------------ */

    /**
     * True when {@code wide}'s scope contains {@code narrow}'s: an absent
     * créneau (or stand) means "everywhere", a named one covers only that very
     * créneau (or stand).
     */
    private static boolean covers(ContrainteAdHoc wide, ContrainteAdHoc narrow) {
        boolean creneauOk = creneauId(wide) == null || creneauId(wide).equals(creneauId(narrow));
        boolean standOk = standId(wide) == null || standId(wide).equals(standId(narrow));
        return creneauOk && standOk;
    }

    /** True unless both constraints name a stand and those stands differ. */
    private static boolean standsMatch(ContrainteAdHoc first, ContrainteAdHoc second) {
        return standId(first) == null
                || standId(second) == null
                || standId(first).equals(standId(second));
    }

    /**
     * Whether two créneaux cover overlapping wall-clock time. A slot whose end
     * hour is not after its start hour crosses midnight and ends the next day
     * — the same normalisation {@code Creneau.chevaucheNuit} applies. An
     * unknown date or hour means "cannot tell", and nothing is reported.
     */
    private static boolean overlap(Creneau first, Creneau second) {
        LocalDateTime[] firstWindow = window(first);
        LocalDateTime[] secondWindow = window(second);
        if (firstWindow == null || secondWindow == null) {
            return false;
        }
        return firstWindow[0].isBefore(secondWindow[1]) && secondWindow[0].isBefore(firstWindow[1]);
    }

    private static LocalDateTime[] window(Creneau creneau) {
        if (creneau == null
                || creneau.getDate() == null
                || creneau.getHeureDebut() == null
                || creneau.getHeureFin() == null) {
            return null;
        }
        LocalDateTime debut = creneau.getDate().atTime(creneau.getHeureDebut());
        LocalDateTime fin = creneau.getDate().atTime(creneau.getHeureFin());
        return new LocalDateTime[] {debut, fin.isAfter(debut) ? fin : fin.plusDays(1)};
    }

    private static List<ContrainteAdHoc> identified(List<ContrainteAdHoc> contraintes) {
        if (contraintes == null) {
            return List.of();
        }
        return contraintes.stream()
                .filter(Objects::nonNull)
                .filter(contrainte -> contrainte.getId() != null && contrainte.getType() != null)
                .toList();
    }

    private static List<ContrainteAdHoc> ofType(List<ContrainteAdHoc> contraintes, TypeContrainteAdHoc type) {
        return contraintes.stream()
                .filter(contrainte -> contrainte.getType() == type)
                .toList();
    }

    private static Map<Long, Creneau> indexCreneaux(List<Creneau> creneaux) {
        if (creneaux == null) {
            return Map.of();
        }
        Map<Long, Creneau> byId = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau != null && creneau.getId() != null) {
                byId.putIfAbsent(creneau.getId(), creneau);
            }
        }
        return byId;
    }

    private static Long creneauId(ContrainteAdHoc contrainte) {
        return contrainte.getCreneau() == null ? null : contrainte.getCreneau().getId();
    }

    private static String standId(ContrainteAdHoc contrainte) {
        return contrainte.getStand() == null ? null : contrainte.getStand().getId();
    }

    /** Ids of every animateur the constraint targets, nulls and duplicates dropped. */
    private static List<String> animateurIds(ContrainteAdHoc contrainte) {
        List<Animateur> animateurs = contrainte.getAnimateursConcernes();
        if (animateurs == null) {
            return List.of();
        }
        return animateurs.stream()
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** The only animateur the constraint targets, or null when it names none or several. */
    private static String soleAnimateur(ContrainteAdHoc contrainte) {
        List<String> ids = animateurIds(contrainte);
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /** The unordered pair of the first two animateur ids, or null when the constraint doesn't name a genuine pair. */
    private static Set<String> animateurPair(ContrainteAdHoc contrainte) {
        List<Animateur> animateurs = contrainte.getAnimateursConcernes();
        if (animateurs == null || animateurs.size() < 2 || animateurs.get(0) == null || animateurs.get(1) == null) {
            return null;
        }
        String premier = animateurs.get(0).getId();
        String second = animateurs.get(1).getId();
        if (premier == null || second == null || premier.equals(second)) {
            return null;
        }
        return Set.of(premier, second);
    }

    private static String describeAnimateurs(List<String> ids) {
        return ids.size() == 1 ? ids.getFirst() : String.join(" ou ", ids);
    }

    /** The scope as the user typed it, spelled with the créneau's date and hours rather than its id. */
    private static String describeScope(ContrainteAdHoc contrainte, Map<Long, Creneau> creneaux) {
        Long creneau = creneauId(contrainte);
        String stand = standId(contrainte);
        if (creneau == null && stand == null) {
            return "tout l'événement";
        }
        if (creneau == null) {
            return "le stand " + stand;
        }
        String label = "le créneau " + describeCreneau(creneau, creneaux.get(creneau));
        return stand == null ? label : label + " du stand " + stand;
    }

    private static String describeCreneau(Long id, Creneau creneau) {
        if (creneau == null
                || creneau.getDate() == null
                || creneau.getHeureDebut() == null
                || creneau.getHeureFin() == null) {
            return String.valueOf(id);
        }
        return creneau.getDate() + " " + creneau.getHeureDebut() + "-" + creneau.getHeureFin();
    }
}
