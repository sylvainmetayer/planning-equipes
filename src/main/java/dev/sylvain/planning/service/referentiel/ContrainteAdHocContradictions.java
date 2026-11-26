package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pairs of hand-entered exceptions that <b>cannot both hold</b>, whatever the
 * solver does.
 *
 * <p>An incompatibility is a hard rule, so exceptions typed one at a time can
 * contradict each other while the solver only reports a negative hard score —
 * the user reads "the solver can't do it" where the truth is "your own
 * exceptions contradict each other". Detecting that at entry time is both
 * cheaper and far more useful than a numeric cap on how many exceptions may
 * exist (issue #84).</p>
 *
 * <h2>What counts as a contradiction</h2>
 *
 * <p>Only what is <b>certainly</b> contradictory, read against the exact
 * semantics {@code AdHocConstraints} gives each type — an exception refused by
 * mistake is worse than one caught a solve later. Since the forced assignment
 * and the forced unavailability were withdrawn (ADR 0117), two readings are
 * left:</p>
 *
 * <ul>
 *   <li>the same animateur pair declared both INCOMPATIBILITE and AFFINITE
 *       (issue #80): the hard rule would always silently win over the soft
 *       one;</li>
 *   <li>an ARRIVEE_GROUPEE two of whose members form an unscoped INCOMPATIBILITE pair.
 *       The one rule of this list that is not certainly unsatisfiable — the
 *       grouped arrival is soft — refused all the same because the
 *       combination describes nothing an organiser wants: aligning two
 *       people's days while refusing to have them work together.</li>
 * </ul>
 */
public final class ContrainteAdHocContradictions {

    private ContrainteAdHocContradictions() {}

    /** Kind of contradiction, in the order they are described above. */
    public enum TypeContradiction {
        PAIRE_INCOMPATIBLE_ET_AFFINE,
        ARRIVEE_GROUPEE_INCOMPATIBLE
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
     *
     * <p>Every contradiction among {@code contraintes}, each one checked against
     * the ones ahead of it — the same pairs, in the same order, as calling
     * {@link #detect} for each of them on its prefix.</p>
     *
     * <p>The naive form compared every exception with every earlier one: 3.9 s
     * for two thousand exceptions, paid by every feasibility analysis. Both
     * readings need an animateur in common, so the earlier exceptions are
     * indexed by animateur and only those sharing one are looked at, still in
     * their order.</p>
     */
    public static List<Contradiction> detectAll(List<ContrainteAdHoc> contraintes) {
        List<ContrainteAdHoc> known = identified(contraintes);
        Map<String, List<Integer>> parAnimateur = new HashMap<>();
        List<Contradiction> contradictions = new ArrayList<>();
        for (int index = 0; index < known.size(); index++) {
            ContrainteAdHoc candidate = known.get(index);
            List<String> animateurs = animateurIds(candidate);
            TreeSet<Integer> partageantUnAnimateur = new TreeSet<>();
            for (String animateur : animateurs) {
                partageantUnAnimateur.addAll(parAnimateur.getOrDefault(animateur, List.of()));
            }
            for (int prior : partageantUnAnimateur) {
                between(candidate, known.get(prior)).forEach(contradictions::add);
            }
            for (String animateur : animateurs) {
                parAnimateur
                        .computeIfAbsent(animateur, key -> new ArrayList<>())
                        .add(index);
            }
        }
        return List.copyOf(contradictions);
    }

    /** The naive reading {@link #detectAll} must stay equal to — kept for the test that holds them together. */
    static List<Contradiction> detectAllNaively(List<ContrainteAdHoc> contraintes) {
        List<ContrainteAdHoc> known = identified(contraintes);
        List<Contradiction> contradictions = new ArrayList<>();
        for (int index = 0; index < known.size(); index++) {
            for (ContrainteAdHoc other : known.subList(0, index)) {
                between(known.get(index), other).forEach(contradictions::add);
            }
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
    public static List<Contradiction> detect(ContrainteAdHoc candidate, List<ContrainteAdHoc> others) {
        if (candidate == null || candidate.getId() == null || candidate.getType() == null) {
            return List.of();
        }
        List<Contradiction> contradictions = new ArrayList<>();
        for (ContrainteAdHoc other : identified(others)) {
            if (!other.getId().equals(candidate.getId())) {
                between(candidate, other).forEach(contradictions::add);
            }
        }
        return List.copyOf(contradictions);
    }

    /** What {@code candidate} and one exception already recorded cannot say together. */
    private static List<Contradiction> between(ContrainteAdHoc candidate, ContrainteAdHoc other) {
        List<Contradiction> contradictions = new ArrayList<>();
        contradictoryPairDeclaration(candidate, other).ifPresent(contradictions::add);
        groupedArrivalOfIncompatiblePair(candidate, other).ifPresent(contradictions::add);
        return contradictions;
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
        if (opposite == null || pair.isEmpty() || other.getType() != opposite || !pair.equals(animateurPair(other))) {
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
    /* Rule 2 — a grouped arrival holding an incompatible pair             */
    /* ------------------------------------------------------------------ */

    /**
     * An ARRIVEE_GROUPEE whose members include both animateurs of an
     * <b>unscoped</b> INCOMPATIBILITE, whichever of the two was entered last.
     * The pair is the one {@code AdHocConstraints} evaluates: the first two ids
     * of the incompatibility. An incompatibility limited to a stand or to a
     * timeslot leaves the pair free to work the same hours elsewhere, which is
     * all the car asks: only one that holds everywhere is at odds with
     * aligning their days.
     */
    private static Optional<Contradiction> groupedArrivalOfIncompatiblePair(
            ContrainteAdHoc candidate, ContrainteAdHoc other) {
        ContrainteAdHoc groupe;
        ContrainteAdHoc incompatibilite;
        if (candidate.getType() == TypeContrainteAdHoc.ARRIVEE_GROUPEE
                && other.getType() == TypeContrainteAdHoc.INCOMPATIBILITE) {
            groupe = candidate;
            incompatibilite = other;
        } else if (candidate.getType() == TypeContrainteAdHoc.INCOMPATIBILITE
                && other.getType() == TypeContrainteAdHoc.ARRIVEE_GROUPEE) {
            groupe = other;
            incompatibilite = candidate;
        } else {
            return Optional.empty();
        }
        if (incompatibilite.getStand() != null || incompatibilite.getCreneau() != null) {
            return Optional.empty();
        }
        Set<String> pair = animateurPair(incompatibilite);
        if (pair.isEmpty() || !animateurIds(groupe).containsAll(pair)) {
            return Optional.empty();
        }
        return Optional.of(new Contradiction(
                TypeContradiction.ARRIVEE_GROUPEE_INCOMPATIBLE,
                List.of(other.getId(), candidate.getId()),
                "La paire d'animateurs " + String.join(" / ", new TreeSet<>(pair))
                        + " est à la fois dans l'arrivée groupée " + groupe.getId()
                        + " et déclarée incompatible par " + incompatibilite.getId()
                        + " : on ne fait pas arriver ensemble deux personnes qu'on refuse de faire travailler"
                        + " ensemble. Supprimez d'abord l'une des deux."));
    }

    /* ------------------------------------------------------------------ */
    /* Shared reading of a constraint                                      */
    /* ------------------------------------------------------------------ */

    private static List<ContrainteAdHoc> identified(List<ContrainteAdHoc> contraintes) {
        if (contraintes == null) {
            return List.of();
        }
        return contraintes.stream()
                .filter(Objects::nonNull)
                .filter(contrainte -> contrainte.getId() != null && contrainte.getType() != null)
                .toList();
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

    /** The unordered pair of the first two animateur ids, or an empty set when the constraint doesn't name a genuine pair. */
    private static Set<String> animateurPair(ContrainteAdHoc contrainte) {
        List<Animateur> animateurs = contrainte.getAnimateursConcernes();
        if (animateurs == null || animateurs.size() < 2 || animateurs.get(0) == null || animateurs.get(1) == null) {
            return Set.of();
        }
        String premier = animateurs.get(0).getId();
        String second = animateurs.get(1).getId();
        if (premier == null || second == null || premier.equals(second)) {
            return Set.of();
        }
        return Set.of(premier, second);
    }
}
