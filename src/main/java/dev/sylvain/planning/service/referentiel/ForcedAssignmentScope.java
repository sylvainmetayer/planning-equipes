package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The seats a forced assignment could be satisfied on, read once for the two
 * checks that need them: {@link ForcedAssignmentOnExcludedSeats} and
 * {@link ForcedAssignmentOnLockedSchedule}.
 *
 * <p>Built exactly as {@code ProblemBuilder.buildPostes} generates the real
 * ones — one per (stand open on the timeslot, timeslot) of the scope — so a
 * check reading them judges the seats a solve would actually offer. One seat
 * per pair, not one per required place: both callers ask whether <em>any</em>
 * seat of the scope can hold the exception, and the second place of a stand is
 * the same answer as the first.</p>
 *
 * <p>A scope naming a stand the referential does not know carries no seat.
 * Guessing "then every stand" would widen the scope on a typo and report a
 * conflict nobody can act on; the checks that read this never invent one.</p>
 */
final class ForcedAssignmentScope {

    private ForcedAssignmentScope() {}

    static List<PosteAffectation> seats(
            ContrainteAdHoc contrainte, Map<String, Stand> standsParId, List<Creneau> creneaux) {
        List<Stand> portee;
        if (contrainte.getStand() == null) {
            portee = List.copyOf(standsParId.values());
        } else {
            Stand stand = standsParId.get(contrainte.getStand().getId());
            portee = stand == null ? List.of() : List.of(stand);
        }
        Long creneauId =
                contrainte.getCreneau() == null ? null : contrainte.getCreneau().getId();
        List<PosteAffectation> sieges = new ArrayList<>();
        for (Creneau creneau : creneaux == null ? List.<Creneau>of() : creneaux) {
            if (creneau.getDate() == null || (creneauId != null && !creneauId.equals(creneau.getId()))) {
                continue;
            }
            for (Stand stand : portee) {
                if (creneau.isStandOpen(stand)) {
                    sieges.add(new PosteAffectation("portee-" + stand.getId() + "-" + creneau.getId(), stand, creneau));
                }
            }
        }
        return List.copyOf(sieges);
    }

    /** The ids a scope check resolves its references against; entries without an id are dropped. */
    static <T> Map<String, T> index(List<T> elements, Function<T, String> id) {
        return elements == null
                ? Map.of()
                : elements.stream()
                        .filter(element -> id.apply(element) != null)
                        .collect(Collectors.toMap(id, Function.identity(), (premier, doublon) -> premier));
    }
}
