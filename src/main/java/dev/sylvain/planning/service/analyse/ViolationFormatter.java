package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
public final class ViolationFormatter {

    private ViolationFormatter() {}

    /**
     * The objects a match names, by id: what a screen needs to open the fiche
     * in question rather than making the reader retype a name. A seat names
     * its animateur, its stand and its timeslot at once; the first of each
     * kind wins when a match names several.
     */
    static ViolationReference references(List<Object> facts) {
        Set<String> animateurIds = new LinkedHashSet<>();
        Set<String> standIds = new LinkedHashSet<>();
        Set<Long> creneauIds = new LinkedHashSet<>();
        for (Object fact : flatten(facts)) {
            if (fact instanceof Animateur animateur) {
                add(animateurIds, animateur.getId());
            } else if (fact instanceof Stand stand) {
                add(standIds, stand.getId());
            } else if (fact instanceof Creneau creneau) {
                add(creneauIds, creneau.getId());
            } else if (fact instanceof PosteAffectation poste) {
                if (poste.getAnimateur() != null) {
                    add(animateurIds, poste.getAnimateur().getId());
                }
                if (poste.getStand() != null) {
                    add(standIds, poste.getStand().getId());
                }
                if (poste.getCreneau() != null) {
                    add(creneauIds, poste.getCreneau().getId());
                }
            }
        }
        return new ViolationReference(describe(facts), single(animateurIds), single(standIds), single(creneauIds));
    }

    /**
     * The id of a kind the match designates <b>unambiguously</b>, {@code null}
     * otherwise. « The first of each kind » was wrong on the two shapes a match
     * commonly has: a rule grouping seats hands over a whole list, whose first
     * stand is no more the subject than the ninth, and
     * {@code incompatibiliteAdHoc} names two animateurs, of whom the first is
     * not « the » one. A link to the wrong fiche is worse than no link: the
     * reader opens it, finds nothing wrong, and stops trusting the others.
     */
    private static <T> T single(Set<T> ids) {
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    private static <T> void add(Set<T> ids, T id) {
        if (id != null) {
            ids.add(id);
        }
    }

    /** One match, as a sentence and as the ids the sentence names; any id may be null. */
    public record ViolationReference(String texte, String animateurId, String standId, Long creneauId) {}

    private static List<Object> flatten(List<Object> facts) {
        List<Object> flat = new java.util.ArrayList<>();
        for (Object fact : facts) {
            if (fact instanceof Collection<?> collection) {
                flat.addAll(flatten(new java.util.ArrayList<>(collection)));
            } else if (fact != null) {
                flat.add(fact);
            }
        }
        return flat;
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
        if (fact instanceof QuotaTypologie plafond) {
            return "typologie " + plafond.getTypologie() + ", " + plafond.getMaxCreneaux()
                    + (plafond.getMaxCreneaux() > 1 ? " créneaux au maximum" : " créneau au maximum");
        }
        if (fact instanceof ContrainteAdHoc contrainte) {
            return contrainteLabel(contrainte);
        }
        if (fact instanceof Collection<?> collection) {
            return collection.stream().map(ViolationFormatter::label).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(fact);
    }

    /**
     * The exception's own id comes first: a hard-negative solve names the rule
     * ("affectationForcee") and the reader's next question is always which of
     * their exceptions it is about (issue #84).
     *
     * <p>Public, like {@link #animateurLabel}, so that the MCP anonymisation
     * removes exactly what is written here rather than a copy of the format
     * that would drift from it.</p>
     */
    public static String contrainteLabel(ContrainteAdHoc contrainte) {
        String label = contrainteReference(contrainte);
        return contrainte.getRaison() == null || contrainte.getRaison().isBlank()
                ? label
                : label + " (" + contrainte.getRaison() + ")";
    }

    /** The constraint's label without its free-text reason: its type, then its id. */
    public static String contrainteReference(ContrainteAdHoc contrainte) {
        return contrainte.getId() == null
                ? String.valueOf(contrainte.getType())
                : contrainte.getType() + " " + contrainte.getId();
    }

    public static String animateurLabel(Animateur animateur) {
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
        String heures = (creneau.getHeureDebut() == null ? "" : creneau.getHeureDebut()) + "-"
                + (creneau.getHeureFin() == null ? "" : creneau.getHeureFin());
        return (date.isEmpty() ? heures : date + " " + heures).trim();
    }
}
