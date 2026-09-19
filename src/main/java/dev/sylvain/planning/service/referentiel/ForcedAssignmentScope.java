package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p><b>A stream, and deliberately not a list.</b> Both callers stop at the
 * first seat that accepts somebody, which is the ordinary case, while a scope
 * naming neither stand nor timeslot spans every stand × timeslot of the
 * edition — five hundred stands over a hundred and twenty days, once per ad
 * hoc rule, on an analysis that runs at every load of the Accueil, Solveur and
 * Problèmes screens. Materialising it built millions of seats to look at one.
 * The caller that really does need them all — the one confirming a conflict —
 * walks the stream once and collects its dates on the way.</p>
 *
 * <p>A scope naming a stand or a timeslot the referential does not know
 * carries no seat. Guessing "then every stand" — or, for a timeslot reference
 * whose id came back {@code null} because the grid no longer holds that
 * vacation, "then every timeslot" — would widen the scope far past what the
 * operator wrote, and report a conflict on dates the exception never covered.
 * {@code AdHocConstraints.matchesScope} reads such a reference as matching
 * <b>nothing</b> (issue #577), and so does this.</p>
 */
final class ForcedAssignmentScope {

    private ForcedAssignmentScope() {}

    static Stream<PosteAffectation> seats(
            ContrainteAdHoc contrainte, Map<String, Stand> standsParId, List<Creneau> creneaux) {
        Collection<Stand> portee;
        if (contrainte.getStand() == null) {
            portee = standsParId.values();
        } else {
            Stand stand = standsParId.get(contrainte.getStand().getId());
            portee = stand == null ? List.of() : List.of(stand);
        }
        boolean surUnCreneau = contrainte.getCreneau() != null;
        Long creneauId = surUnCreneau ? contrainte.getCreneau().getId() : null;
        if (portee.isEmpty() || creneaux == null || (surUnCreneau && creneauId == null)) {
            // Nothing to walk: a stand the referential does not know, an empty
            // grid, or a rule whose vacation is gone — which matches no seat at
            // all rather than every seat.
            return Stream.empty();
        }
        return creneaux.stream()
                .filter(creneau -> creneau.getDate() != null)
                .filter(creneau -> creneauId == null || creneauId.equals(creneau.getId()))
                .flatMap(creneau -> portee.stream()
                        .filter(creneau::isStandOpen)
                        .map(stand -> new PosteAffectation(
                                "portee-" + stand.getId() + "-" + creneau.getId(), stand, creneau)));
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
