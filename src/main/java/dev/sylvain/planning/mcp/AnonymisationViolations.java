package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.analyse.ViolationFormatter;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Strips animateur names, and the free-text reason of ad hoc constraints, out
 * of the human-readable violation lines, so the hard-constraint diagnostic can
 * be exposed over MCP without breaking the privacy rule of issue #107.
 *
 * <p>Those lines are produced by {@link ViolationFormatter} for the web UI,
 * where names are wanted: it labels an animateur as {@code "Prénom Nom (id)"}
 * and an ad hoc constraint as {@code "TYPE id (raison)"}.
 *
 * <p><b>The edition's own fiches come first.</b> Each known animateur's exact
 * label is replaced by {@code "animateur id"}, whatever shape the name has: a
 * single word, an initial with a dot, a digit, an id with a space in it. The
 * previous, purely shape-based rewrite needed two words before the parenthesis
 * and let every other shape through verbatim. Each known constraint's reason
 * is dropped the same way, parentheses inside it included.
 *
 * <p><b>Then a net, for what the referential no longer knows.</b> A line may
 * outlive its subject — the stored analysis, a swap request's prevalidation —
 * so a deleted animateur or constraint must be anonymised too. The net is a
 * shape again, widened to one word: a constraint type followed by a
 * parenthesis loses the parenthesis, and any word run directly followed by a
 * space-free parenthesised token becomes {@code "animateur token"}. Its worst
 * case is a cosmetically mangled label, never a leaked name; a known stand
 * whose name has that shape is spared.
 */
final class AnonymisationViolations {

    /** One or more words (letters, marks, digits, dots, hyphens, apostrophes) directly followed by a parenthesised, space-free id. */
    private static final Pattern LIBELLE_ANIMATEUR = Pattern.compile(
            "\\p{L}[\\p{L}\\p{M}\\p{N}.'’\\-]*(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}.'’\\-]*+)*+\\s*\\(([^()\\s]+)\\)");

    /** A constraint type, its optional id, then a parenthesised reason — one level of nested parentheses allowed. */
    private static final Pattern RAISON_CONTRAINTE = Pattern.compile("\\b("
            + Arrays.stream(TypeContrainteAdHoc.values()).map(Enum::name).collect(Collectors.joining("|"))
            + ")( [^\\s()]+)? \\((?:[^()]|\\([^()]*\\))*\\)");

    /** Exact label → its replacement, longest first so no label is cut by a shorter one it contains. */
    private final Map<String, String> remplacements;

    private final List<String> nomsDeStand;

    private AnonymisationViolations(Map<String, String> remplacements, List<String> nomsDeStand) {
        this.remplacements = remplacements;
        this.nomsDeStand = nomsDeStand;
    }

    /** Against the referential of the edition the calling tool targets. */
    static AnonymisationViolations of(ReferenceDataService referentiel) {
        return of(referentiel.listAnimateurs(), referentiel.listContraintesAdHoc(), referentiel.listStands());
    }

    static AnonymisationViolations of(
            Collection<Animateur> animateurs, Collection<ContrainteAdHoc> contraintes, Collection<Stand> stands) {
        Map<String, String> remplacements = new HashMap<>();
        for (Animateur animateur : animateurs) {
            if (animateur.getId() != null) {
                remplacements.put(ViolationFormatter.animateurLabel(animateur), "animateur " + animateur.getId());
            }
        }
        for (ContrainteAdHoc contrainte : contraintes) {
            String complet = ViolationFormatter.contrainteLabel(contrainte);
            String reference = ViolationFormatter.contrainteReference(contrainte);
            if (!complet.equals(reference)) {
                remplacements.put(complet, reference);
            }
        }
        Map<String, String> ordonnes = new LinkedHashMap<>();
        remplacements.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entree) ->
                                entree.getKey().length())
                        .reversed())
                .forEach(entree -> ordonnes.put(entree.getKey(), entree.getValue()));
        List<String> nomsDeStand =
                stands.stream().map(Stand::getNom).filter(Objects::nonNull).toList();
        return new AnonymisationViolations(ordonnes, nomsDeStand);
    }

    List<String> anonymiser(List<String> violations) {
        if (violations == null) {
            return List.of();
        }
        return violations.stream().map(this::anonymiser).toList();
    }

    String anonymiser(String violation) {
        if (violation == null || violation.indexOf('(') < 0) {
            return violation;
        }
        String ligne = violation;
        for (Map.Entry<String, String> remplacement : remplacements.entrySet()) {
            ligne = ligne.replace(remplacement.getKey(), remplacement.getValue());
        }
        ligne = RAISON_CONTRAINTE
                .matcher(ligne)
                .replaceAll(match ->
                        Matcher.quoteReplacement(match.group(1) + (match.group(2) == null ? "" : match.group(2))));
        return LIBELLE_ANIMATEUR.matcher(ligne).replaceAll(match -> {
            String libelle = match.group();
            if (nomsDeStand.stream().anyMatch(nom -> nom.endsWith(libelle))) {
                return Matcher.quoteReplacement(libelle);
            }
            return "animateur " + Matcher.quoteReplacement(match.group(1));
        });
    }
}
