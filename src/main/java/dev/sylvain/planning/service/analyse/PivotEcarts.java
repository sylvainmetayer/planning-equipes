package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Where the breaches of a rule <b>concentrate</b> (issue #496): one count per
 * constraint and per key of an axis — the day, the stand, the animateur.
 *
 * <p>The Contraintes screen says how many times a rule is in default and lists
 * the lines; the load heatmap crosses day × stand for hours. Neither says
 * whether the six over-long days are the week-end, or whether the missing
 * referents are all on the same pavilion — the question one asks <i>before</i>
 * deciding what to fix, and one a cross-table answers in ten seconds.</p>
 *
 * <p>Counted from the justification facts of each match, which every rule
 * already carries. A match naming three stands counts on <b>all three</b>: the
 * cell answers « how many breaches touch this stand », and dropping the
 * ambiguous ones would answer nothing. That is deliberately not the rule of
 * {@code ViolationFormatter.references}, which links to a fiche and must not
 * link to the wrong one.</p>
 *
 * <p>Ids only, never names: the screen resolves the labels from its own
 * referential, and an aggregate that named people would be one more place a
 * name travels ({@code docs/rgpd.md} §7).</p>
 */
public final class PivotEcarts {

    private PivotEcarts() {}

    /** What a cross-table can be read against. */
    public enum Axe {
        /** The day of the event, as an ISO date. */
        JOUR,
        STAND,
        ANIMATEUR
    }

    /**
     * One cell: how many matches of {@code contrainte} name {@code cle} on
     * {@code axe}. Only the cells carrying at least one breach exist — a table
     * of zeroes is what the screen draws, not what the server sends.
     *
     * @param cle ISO date on {@link Axe#JOUR}, stand id or animateur id
     *            otherwise
     */
    @Schema(requiredProperties = {"ecarts"})
    public record Cellule(String contrainte, Axe axe, String cle, int ecarts) {}

    /**
     * The cells of one analysis, constraint by constraint.
     *
     * <p>Sorted by constraint, then axis, then key, so two analyses of the same
     * plan produce identical payloads and a diff between them says
     * something.</p>
     */
    public static List<Cellule> of(Map<String, List<MatchFacts>> matchesParContrainte) {
        Map<Tally, Integer> comptes = new LinkedHashMap<>();
        matchesParContrainte.forEach((contrainte, matches) -> {
            for (MatchFacts match : matches) {
                for (Tally cle : keys(match)) {
                    comptes.merge(new Tally(contrainte, cle.axe(), cle.cle()), 1, Integer::sum);
                }
            }
        });
        List<Cellule> cellules = new ArrayList<>(comptes.size());
        comptes.forEach((cle, ecarts) -> cellules.add(new Cellule(cle.contrainte(), cle.axe(), cle.cle(), ecarts)));
        cellules.sort(Comparator.comparing(Cellule::contrainte)
                .thenComparing(Cellule::axe)
                .thenComparing(Cellule::cle));
        return List.copyOf(cellules);
    }

    /** Tally key. A record rather than a joined string: no separator to pick, none to escape. */
    private record Tally(String contrainte, Axe axe, String cle) {}

    /**
     * Every key one match names, on every axis, without duplicates: a match
     * naming the same animateur twice is one breach for them, not two.
     */
    private static Set<Tally> keys(MatchFacts match) {
        Set<Tally> keys = new LinkedHashSet<>();
        for (Object fact : flatten(match.facts())) {
            if (fact instanceof Animateur animateur) {
                add(keys, Axe.ANIMATEUR, animateur.getId());
            } else if (fact instanceof Stand stand) {
                add(keys, Axe.STAND, stand.getId());
            } else if (fact instanceof Creneau creneau) {
                add(keys, Axe.JOUR, jour(creneau));
            } else if (fact instanceof PosteAffectation poste) {
                if (poste.getAnimateur() != null) {
                    add(keys, Axe.ANIMATEUR, poste.getAnimateur().getId());
                }
                if (poste.getStand() != null) {
                    add(keys, Axe.STAND, poste.getStand().getId());
                }
                add(keys, Axe.JOUR, jour(poste.getCreneau()));
            } else if (fact instanceof LocalDate date) {
                // A rule grouping a week hands over its dates directly.
                add(keys, Axe.JOUR, date.toString());
            }
        }
        return keys;
    }

    private static String jour(Creneau creneau) {
        return creneau == null || creneau.getDate() == null
                ? null
                : creneau.getDate().toString();
    }

    private static void add(Set<Tally> keys, Axe axe, String cle) {
        if (cle != null && !cle.isBlank()) {
            keys.add(new Tally(null, axe, cle));
        }
    }

    /**
     * Same walk as {@code ViolationFormatter}: a fact may be a collection of
     * facts. Shared with {@link BreachHotspots}, which reads the same facts.
     */
    static List<Object> flatten(List<Object> facts) {
        List<Object> flat = new ArrayList<>();
        for (Object fact : facts) {
            if (fact instanceof Collection<?> collection) {
                flat.addAll(flatten(new ArrayList<>(collection)));
            } else if (fact != null) {
                flat.add(fact);
            }
        }
        return flat;
    }
}
