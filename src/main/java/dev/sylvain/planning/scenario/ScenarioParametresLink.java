package dev.sylvain.planning.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2 → 3: the parameter sections of the file move under one {@code parametres:}
 * key, as the application's own settings are now laid out
 * ({@code parametresLegaux} → {@code parametres.legal}, {@code parametresQualite}
 * → {@code parametres.organisation}). {@code parametresSolveur}, the edition's
 * solve budget, is dropped and said: the budget is the instance's now (ADR
 * 0110), a file can no longer pin it. Nothing else is dropped, except an old
 * key whose section the file already carries, which is said.
 */
final class ScenarioParametresLink {

    private static final Map<String, String> RENAMED = Map.of(
            "parametresLegaux", "legal",
            "parametresQualite", "organisation");

    private static final String DROPPED = "parametresSolveur";

    private ScenarioParametresLink() {}

    static Map<String, Object> convert(Map<String, Object> document, List<String> warnings) {
        if (RENAMED.keySet().stream().noneMatch(document::containsKey) && !document.containsKey(DROPPED)) {
            return document;
        }
        Map<String, Object> result = new LinkedHashMap<>(document);
        if (result.remove(DROPPED) != null) {
            warnings.add("« " + DROPPED + " » ignoré : la durée de résolution et l'arrêt sur plateau sont un réglage "
                    + "de l'instance (SOLVER_SECONDS_LIMIT), un fichier ne peut plus les fixer.");
        }
        Map<String, Object> parametres = new LinkedHashMap<>();
        if (document.get("parametres") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> parametres.put(String.valueOf(key), value));
        }
        RENAMED.forEach((old, section) -> {
            if (result.containsKey(old)) {
                Object value = result.remove(old);
                if (parametres.putIfAbsent(section, value) != null) {
                    warnings.add("« " + old + " » ignoré : le fichier porte déjà « parametres." + section + " ».");
                }
            }
        });
        result.put("parametres", parametres);
        return result;
    }
}
