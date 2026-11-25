package dev.sylvain.planning.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chain that turns an older scenario document into the shape this build
 * reads, applied to the parsed map before binding (ADR 0101).
 *
 * <p>Each link is one pure function from version {@code n} to {@code n + 1},
 * with its own test: a model change that renames or reshapes a key adds its
 * link here, so a file written by any earlier release — a bundled scenario, an
 * end-of-event archive, a copy taken before a wipe — stays importable. What a
 * link cannot carry over is dropped <b>and said</b>, through {@code warnings}
 * (they reach the import report), never silently.</p>
 */
public final class ScenarioMigrations {

    /** One link of the chain: reads a document of version {@code from()} and returns it as {@code from() + 1}. */
    public record Link(int from, Conversion conversion) {}

    @FunctionalInterface
    public interface Conversion {
        /**
         * @param warnings receives one French sentence per thing that could not be converted, naming a key or an
         *     id, never a person: it reaches the import report and the browser's notification log
         */
        Map<String, Object> apply(Map<String, Object> document, List<String> warnings);
    }

    /** Ordered by {@code from}; the last link must end at {@link ScenarioBinder#SCHEMA_VERSION}. */
    static final List<Link> CHAIN = List.of(new Link(1, ScenarioMigrations::v1ToV2));

    private ScenarioMigrations() {}

    /**
     * The document as the current version, plus what was dropped on the way.
     * The input is not modified. A missing {@code schemaVersion} means 1.
     */
    public static Migrated migrate(Map<String, Object> document, int fromVersion) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> current = new LinkedHashMap<>(document);
        for (Link link : CHAIN) {
            if (link.from() >= fromVersion) {
                current = link.conversion().apply(current, warnings);
            }
        }
        current.put("schemaVersion", ScenarioBinder.SCHEMA_VERSION);
        return new Migrated(current, List.copyOf(warnings));
    }

    public record Migrated(Map<String, Object> document, List<String> warnings) {}

    /**
     * 1 → 2: the version that opens the 4.x chain. It changes no key by itself;
     * the links of the model changes of 4.0 come after it, each in its own pull
     * request. Kept as a link so that the mechanism is in place and tested.
     */
    static Map<String, Object> v1ToV2(Map<String, Object> document, List<String> warnings) {
        return document;
    }
}
