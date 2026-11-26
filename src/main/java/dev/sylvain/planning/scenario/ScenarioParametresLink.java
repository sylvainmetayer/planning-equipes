package dev.sylvain.planning.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2 → 3: the three parameter sections of the file move under one
 * {@code parametres:} key, as the application's own settings are now laid out
 * ({@code parametresLegaux} → {@code parametres.legal}, {@code parametresQualite}
 * → {@code parametres.organisation}, {@code parametresSolveur} →
 * {@code parametres.instance}). Nothing is dropped.
 */
final class ScenarioParametresLink {

    private static final Map<String, String> RENAMED = Map.of(
            "parametresLegaux", "legal",
            "parametresQualite", "organisation",
            "parametresSolveur", "instance");

    private ScenarioParametresLink() {}

    static Map<String, Object> convert(Map<String, Object> document, List<String> warnings) {
        if (RENAMED.keySet().stream().noneMatch(document::containsKey)) {
            return document;
        }
        Map<String, Object> result = new LinkedHashMap<>(document);
        Map<String, Object> parametres = new LinkedHashMap<>();
        if (document.get("parametres") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> parametres.put(String.valueOf(key), value));
        }
        RENAMED.forEach((old, section) -> {
            if (result.containsKey(old)) {
                parametres.putIfAbsent(section, result.remove(old));
            }
        });
        result.put("parametres", parametres);
        return result;
    }
}
