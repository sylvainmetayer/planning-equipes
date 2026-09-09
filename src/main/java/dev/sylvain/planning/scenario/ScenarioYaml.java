package dev.sylvain.planning.scenario;

import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The one place that decides how a scenario document is parsed, for every
 * reader of one.
 *
 * <p>It used to be decided twice, and the two copies each carried a comment
 * saying the other existed. Raising a limit in one and not the other silently
 * reintroduces the bug it was raised for.</p>
 *
 * <p><b>Sexagesimal numbers are switched off</b>, and that fixes a defect
 * older than this class. YAML 1.1 resolves {@code 9:30} to the number
 * <b>570</b> — nine minutes and thirty seconds, base sixty — so a scenario
 * that wrote a perfectly natural {@code heureDebut: 9:30} was read as
 * <b>00:09:30</b> and scheduled a créneau nine minutes after midnight, in
 * silence. {@code 08:00} escaped only by accident: the leading zero keeps it
 * out of the pattern. With the resolver switched off, a time reaches the
 * reader as the text its author wrote.</p>
 */
public final class ScenarioYaml {

    /**
     * SnakeYAML caps aliases at fifty, as a billion-laughs guard. Raised, not
     * lifted: our exporter used to emit one anchor and an alias per animateur
     * sharing a list — 152 on a real edition — so the application refused to
     * re-import files it had itself produced. One alias per row leaves room for
     * an edition ten times the size, and the guard still stops a file whose
     * aliases nest into an expansion bomb.
     */
    private static final int MAX_ALIASES = 10_000;

    /**
     * SnakeYAML's own integer pattern, minus its last alternative
     * {@code [-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+} — the sexagesimal one.
     */
    private static final Pattern INT_WITHOUT_SEXAGESIMAL = Pattern.compile(
            "^(?:[-+]?0b_*[0-1_]+|[-+]?0_*[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x_*[0-9a-fA-F_]+)$");

    /** Same idea for floats: {@code [-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*} is dropped. */
    private static final Pattern FLOAT_WITHOUT_SEXAGESIMAL = Pattern.compile(
            "^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+]?[0-9]+)?"
                    + "|\\.[0-9_]+(?:[eE][-+][0-9]+)?"
                    + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");

    private ScenarioYaml() {
    }

    /** A parser configured for scenario documents, aliases and all. */
    public static Yaml parser() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        return new Yaml(new SafeConstructor(options), new org.yaml.snakeyaml.representer.Representer(
                new org.yaml.snakeyaml.DumperOptions()), new org.yaml.snakeyaml.DumperOptions(), options,
                new NoSexagesimalResolver());
    }

    /**
     * Replaces the {@link Date} SnakeYAML builds for an unquoted date by the
     * ISO text its author wrote.
     *
     * <p>Binding a scenario with Jackson's {@code convertValue} re-serialises
     * the map, and a {@code Date} comes out the other side as a number of
     * milliseconds — indistinguishable from an author writing
     * {@code dateNaissance: 1990}, which would then bind to 1970-01-01 rather
     * than being refused. Normalising here keeps the two apart: after this, a
     * date is a string and a bare number is a bare number.</p>
     */
    public static Object normaliseDates(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalised = new LinkedHashMap<>();
            map.forEach((key, v) -> normalised.put(key, normaliseDates(v)));
            return normalised;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ScenarioYaml::normaliseDates).toList();
        }
        return value;
    }

    private static final class NoSexagesimalResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            addImplicitResolver(Tag.INT, INT_WITHOUT_SEXAGESIMAL, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT_WITHOUT_SEXAGESIMAL, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789");
            addImplicitResolver(Tag.YAML, YAML, "!&*");
        }
    }
}
