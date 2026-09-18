package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A forced assignment nobody it names is free to honour: every animateur it
 * names has a frozen schedule over its whole scope, and none of them already
 * sits in it.
 *
 * <p>The solve has no move left. {@code affectationForcee} is satisfied only
 * when a seat of the scope holds one of the named animateurs; a lock either
 * pins the seats they already hold or — for {@code ANIMATEUR} and
 * {@code ANIMATEUR_CRENEAU} — forbids the solver to hand them a new one
 * ({@code VerrouillageConstraints}). So an exception whose every named
 * animateur is frozen, and whose scope none of them already occupies, costs one
 * hard point on every solution: it gives up either the lock or the exception.
 * {@code ContrainteAdHocContradictions} already reports the same deadlock
 * between two exceptions; this is the half a lock closes.</p>
 *
 * <p>Only those two lock types are read. A lock on a stand, a timeslot or a day
 * pins the seats that <b>hold somebody</b> and leaves the empty ones fillable
 * ({@code ProblemBuilder}), so it never stands in the way of an assignment the
 * exception asks for.</p>
 *
 * <p>Pure and static like its two siblings. Whether a named animateur already
 * occupies the scope is read from the seats of the <b>persisted</b> plan,
 * handed in as {@link PlaceTenue}s rather than as a plan: this package knows
 * the referential, not how a solved plan is stored, and the three fields are
 * all the question needs. An empty set is no plan at all, which is exactly the
 * state where the exception does need a new seat, and the deadlock is real.</p>
 */
public final class ForcedAssignmentOnLockedSchedule {

    /** How many dates a message spells out before it says "and n others". */
    private static final int DATES_CITEES = 5;

    private ForcedAssignmentOnLockedSchedule() {}

    /**
     * One seat of the persisted plan, as this check reads it: who holds it, on
     * which stand and which timeslot. The caller that knows where plans are
     * stored builds these; nothing here parses a storage key.
     */
    public record PlaceTenue(String animateurId, String standId, Long creneauId) {}

    /** @param dates the dates of the scope, all of them frozen for every animateur the exception names */
    public record Conflit(ContrainteAdHoc contrainte, List<LocalDate> dates) {

        public String message() {
            String cites =
                    dates.stream().limit(DATES_CITEES).map(LocalDate::toString).collect(Collectors.joining(", "));
            String reste = dates.size() > DATES_CITEES ? " et " + (dates.size() - DATES_CITEES) + " autre(s)" : "";
            boolean plusieurs = contrainte.getAnimateursConcernes().size() > 1;
            return "L'affectation forcée " + contrainte.getId() + " ne peut pas être tenue : "
                    + (plusieurs
                            ? "l'emploi du temps de chacun des animateurs qu'elle nomme est verrouillé"
                            : "l'emploi du temps de l'animateur qu'elle nomme est verrouillé")
                    + " sur toute sa portée (" + cites + reste + "), sans y tenir de place. Déverrouillez "
                    + "l'emploi du temps ou supprimez l'exception.";
        }
    }

    public static List<Conflit> detectAll(
            List<ContrainteAdHoc> contraintes,
            List<VerrouillagePlanning> verrouillages,
            List<Stand> stands,
            List<Creneau> creneaux,
            Set<PlaceTenue> placesTenues) {
        if (contraintes == null || contraintes.isEmpty() || verrouillages == null || verrouillages.isEmpty()) {
            return List.of();
        }
        Map<String, Stand> standsParId = ForcedAssignmentScope.index(stands, Stand::getId);
        List<Conflit> conflits = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            detect(contrainte, verrouillages, standsParId, creneaux, placesTenues)
                    .ifPresent(conflits::add);
        }
        return List.copyOf(conflits);
    }

    public static Optional<Conflit> detect(
            ContrainteAdHoc contrainte,
            List<VerrouillagePlanning> verrouillages,
            Map<String, Stand> standsParId,
            List<Creneau> creneaux,
            Set<PlaceTenue> placesTenues) {
        if (contrainte == null
                || contrainte.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE
                || contrainte.getAnimateursConcernes() == null
                || contrainte.getAnimateursConcernes().isEmpty()
                || verrouillages == null
                || verrouillages.isEmpty()) {
            return Optional.empty();
        }
        Set<String> nommes = new LinkedHashSet<>();
        for (Animateur reference : contrainte.getAnimateursConcernes()) {
            if (reference == null || reference.getId() == null) {
                return Optional.empty();
            }
            nommes.add(reference.getId());
        }
        List<PosteAffectation> portee = ForcedAssignmentScope.seats(contrainte, standsParId, creneaux);
        if (portee.isEmpty()) {
            return Optional.empty();
        }
        Set<PlaceTenue> tenues = placesTenues == null ? Set.of() : placesTenues;
        for (PosteAffectation siege : portee) {
            for (String animateurId : nommes) {
                // Already seated in the scope: the exception is satisfied by a
                // seat the lock pins rather than blocks, and asks for nothing.
                if (tenues.contains(new PlaceTenue(
                        animateurId,
                        siege.getStand().getId(),
                        siege.getCreneau().getId()))) {
                    return Optional.empty();
                }
                if (!frozen(verrouillages, animateurId, siege.getCreneau().getId())) {
                    // One seat this person is still free to take.
                    return Optional.empty();
                }
            }
        }
        TreeSet<LocalDate> dates = portee.stream()
                .map(siege -> siege.getCreneau().getDate())
                .collect(Collectors.toCollection(TreeSet::new));
        return Optional.of(new Conflit(contrainte, List.copyOf(dates)));
    }

    /**
     * Whether a lock forbids giving this animateur a new seat on that
     * timeslot — the two types {@code VerrouillageConstraints} penalises, and
     * only those.
     */
    private static boolean frozen(List<VerrouillagePlanning> verrouillages, String animateurId, Long creneauId) {
        return verrouillages.stream().anyMatch(verrouillage -> {
            if (!animateurId.equals(verrouillage.getAnimateurId())) {
                return false;
            }
            if (verrouillage.getType() == TypeVerrouillage.ANIMATEUR) {
                return true;
            }
            return verrouillage.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                    && creneauId != null
                    && creneauId.equals(verrouillage.getCreneauId());
        });
    }
}
