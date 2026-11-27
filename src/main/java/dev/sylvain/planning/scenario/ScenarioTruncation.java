package dev.sylvain.planning.scenario;

import dev.sylvain.planning.scenario.dto.ScenarioDto;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The signs that a scenario file was cut short — a copy interrupted, a
 * download stopped — said as warnings, never as a refusal: a short file and a
 * cut one can be the same bytes, and only the author knows which.
 *
 * <p>A cut removes the tail of a file, so every sign looks at the tail: a last
 * line with no line break (a file the application writes always ends with
 * one), mandatory sections missing after the last one present, and a last
 * entry that stops before the keys every entry above it carries. A cut that
 * falls exactly between two entries or two optional sections leaves none of
 * these, and nothing short of an end marker in the format would tell it.</p>
 */
public final class ScenarioTruncation {

    /** A line that is a whole entry written inline: {@code - { heureDebut: "14:00" }}. */
    private static final Pattern INLINE_ENTRY = Pattern.compile("\\s*(-\\s+)?[\\[{]");

    private ScenarioTruncation() {}

    /** @param document the file as SnakeYAML parsed it, before any conversion */
    static List<String> warnings(String yamlContent, Map<?, ?> document) {
        List<String> warnings = new ArrayList<>();
        if (!yamlContent.isEmpty() && !yamlContent.endsWith("\n")) {
            warnings.add("Le fichier ne se termine pas par un retour à la ligne : vérifiez que sa dernière ligne (n° "
                    + yamlContent.lines().count() + ") est complète. Un fichier coupé en cours de copie s'arrête"
                    + " ainsi.");
        }
        String derniereLigne =
                yamlContent.stripTrailing().lines().reduce((a, b) -> b).orElse("");
        if (INLINE_ENTRY.matcher(derniereLigne).lookingAt()) {
            // An entry written inline closes on its bracket: a cut through it does not parse at all, and
            // the keys it leaves out were left out on purpose.
            return warnings;
        }
        String chemin = null;
        Object noeud = document;
        while (noeud != null) {
            Object dernier = null;
            if (noeud instanceof Map<?, ?> map && !map.isEmpty()) {
                Map.Entry<?, ?> entree =
                        map.entrySet().stream().reduce((a, b) -> b).orElseThrow();
                chemin = chemin == null ? String.valueOf(entree.getKey()) : chemin + "." + entree.getKey();
                dernier = entree.getValue();
            } else if (noeud instanceof List<?> list && !list.isEmpty()) {
                cutEntry(chemin, list).ifPresent(warnings::add);
                chemin = chemin + "[" + (list.size() - 1) + "]";
                dernier = list.getLast();
            }
            noeud = dernier;
        }
        return warnings;
    }

    /**
     * Mandatory sections missing after the last section the scenario carries,
     * in the order the format writes them: what a cut there would have taken.
     * Read on the bound scenario, beside the violation that already refuses
     * the file, to say why it is likely missing.
     */
    public static Optional<String> missingTail(ScenarioDto scenario) {
        RecordComponent[] sections = ScenarioDto.class.getRecordComponents();
        int derniere = -1;
        for (int i = 0; i < sections.length; i++) {
            if (value(sections[i], scenario) != null) {
                derniere = i;
            }
        }
        if (derniere < 0) {
            return Optional.empty();
        }
        List<String> manquantes = new ArrayList<>();
        for (int i = derniere + 1; i < sections.length; i++) {
            if (sections[i].getAccessor().isAnnotationPresent(NotNull.class)) {
                manquantes.add("« " + sections[i].getName() + " »");
            }
        }
        if (manquantes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Le fichier s'arrête après la section « " + sections[derniere].getName() + " », sans "
                + String.join(", ", manquantes) + " qui la suivent : il a sans doute été tronqué.");
    }

    private static Object value(RecordComponent section, ScenarioDto scenario) {
        try {
            return section.getAccessor().invoke(scenario);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The last entry of a list, when it stops before a key that every entry
     * above it carries after the last key it has: the keys of an entry are
     * written in one order, and a cut takes them from the end.
     */
    private static Optional<String> cutEntry(String chemin, List<?> list) {
        if (list.size() < 2 || !(list.getLast() instanceof Map<?, ?> derniere) || derniere.isEmpty()) {
            return Optional.empty();
        }
        List<?> precedentes = list.subList(0, list.size() - 1);
        if (!precedentes.stream().allMatch(Map.class::isInstance)) {
            return Optional.empty();
        }
        List<?> ordre = new ArrayList<>(((Map<?, ?>) precedentes.getLast()).keySet());
        Object derniereCle = derniere.keySet().stream().reduce((a, b) -> b).orElseThrow();
        int position = ordre.indexOf(derniereCle);
        if (position < 0) {
            return Optional.empty();
        }
        List<String> absentes = ordre.subList(position + 1, ordre.size()).stream()
                .filter(cle -> !derniere.containsKey(cle)
                        && precedentes.stream().allMatch(entree -> ((Map<?, ?>) entree).containsKey(cle)))
                .map(cle -> "« " + cle + " »")
                .toList();
        if (absentes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("La dernière entrée de « " + chemin + " » (n° " + list.size() + ") s'arrête"
                + " avant " + String.join(", ", absentes) + ", que portent toutes les précédentes : le fichier a"
                + " peut-être été coupé à cet endroit.");
    }
}
