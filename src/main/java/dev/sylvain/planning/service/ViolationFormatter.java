package dev.sylvain.planning.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Turns the raw justification facts of one constraint match (an
 * {@code Animateur}, a {@code PosteAffectation}, a week's worth of dates...)
 * into one human-readable line, so a hard-constraint violation can be shown
 * to a non-technical user without them having to read Java toString() output.
 *
 * <p>Domain classes deliberately carry no {@code toString()} override (see
 * their own javadoc / equals-by-id contracts), so this is the one place that
 * knows how to label each fact type. Generic by fact <em>type</em>, not by
 * constraint name: every hard constraint's justification is some combination
 * of {@code Animateur}/{@code PosteAffectation}/{@code Creneau}/{@code Stand}/
 * {@code ContrainteAdHoc}, so one formatter per type covers all of them
 * without a per-constraint special case.</p>
 */
final class ViolationFormatter {

    private ViolationFormatter() {
    }

    /** Joins every non-null fact of a match into one line, in justification order. */
    static String describe(List<Object> facts) {
        return facts.stream()
                .filter(Objects::nonNull)
                .map(ViolationFormatter::label)
                .collect(Collectors.joining(" — "));
    }

    private static String label(Object fact) {
        if (fact instanceof Animateur animateur) {
            return animateurLabel(animateur);
        }
        if (fact instanceof PosteAffectation poste) {
            return posteLabel(poste);
        }
        if (fact instanceof Creneau creneau) {
            return creneauLabel(creneau);
        }
        if (fact instanceof Stand stand) {
            return stand.getNom();
        }
        if (fact instanceof ContrainteAdHoc contrainte) {
            return contrainte.getType()
                    + (contrainte.getRaison() != null && !contrainte.getRaison().isBlank()
                            ? " (" + contrainte.getRaison() + ")"
                            : "");
        }
        if (fact instanceof Collection<?> collection) {
            return collection.stream()
                    .map(ViolationFormatter::label)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(fact);
    }

    private static String animateurLabel(Animateur animateur) {
        return animateur.nomWithId();
    }

    private static String posteLabel(PosteAffectation poste) {
        StringBuilder label = new StringBuilder();
        if (poste.getStand() != null) {
            label.append(poste.getStand().getNom()).append(" — ");
        }
        if (poste.getCreneau() != null) {
            label.append(creneauLabel(poste.getCreneau()));
        }
        if (poste.getAnimateur() != null) {
            label.append(" (").append(animateurLabel(poste.getAnimateur())).append(")");
        }
        return label.toString();
    }

    private static String creneauLabel(Creneau creneau) {
        String date = creneau.getDate() == null ? "" : creneau.getDate().toString();
        String heures = (creneau.getHeureDebut() == null ? "" : creneau.getHeureDebut())
                + "-" + (creneau.getHeureFin() == null ? "" : creneau.getHeureFin());
        return (date.isEmpty() ? heures : date + " " + heures).trim();
    }
}
