package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalTime;
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
     * {@code [-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+} — the sexagesimal one. Its
     * other alternatives are kept one pattern each, registered in their
     * original order: the resolver tries every pattern of a tag, so a list is
     * the same alternation, each branch simple enough to read. The {@code _*}
     * SnakeYAML puts after a radix prefix is dropped, since the digit class
     * that follows already takes underscores.
     */
    private static final List<Pattern> INT_WITHOUT_SEXAGESIMAL = List.of(
            Pattern.compile("^[-+]?0b[01_]+$"),
            Pattern.compile("^[-+]?0[0-7_]+$"),
            Pattern.compile("^[-+]?(?:0|[1-9][\\d_]*)$"),
            Pattern.compile("^[-+]?0x[\\da-fA-F_]+$"));

    /** Same idea for floats: {@code [-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*} is dropped. */
    private static final List<Pattern> FLOAT_WITHOUT_SEXAGESIMAL = List.of(
            Pattern.compile("^[-+]?\\d[\\d_]*\\.[\\d_]*(?:[eE][-+]?\\d+)?$"),
            Pattern.compile("^\\.[\\d_]+(?:[eE][-+]\\d+)?$"),
            Pattern.compile("^[-+]?\\.(?:inf|Inf|INF)$"),
            Pattern.compile("^\\.(?:nan|NaN|NAN)$"));

    private ScenarioYaml() {}

    /**
     * How a {@link dev.sylvain.planning.scenario.dto.ScenarioDto} becomes the
     * map a scenario file is dumped from.
     *
     * <p><b>Absent rather than null.</b> The hand-written writer decided key by
     * key whether to emit a section, and forgot in places — {@code postes[]}
     * carried an explicit {@code animateurId: null}, and a stand with no motif
     * wrote {@code motif: null}. A key the reader treats exactly like an
     * absent one is noise in a file meant to be read and diffed by hand.</p>
     *
     * <p>Dates and hours are written as the text the schema declares, never as
     * the epoch numbers Jackson defaults to — a scenario that could not be read
     * back would be no scenario at all. An hour is written the way
     * {@link LocalTime#toString()} writes it, {@code 09:00} rather than
     * Jackson's {@code 09:00:00}: the seconds are always zero here, and adding
     * them would move every hour of every exported file for nothing.</p>
     */
    public static ObjectMapper writer() {
        SimpleModule heures = new SimpleModule();
        heures.addSerializer(LocalTime.class, new HourAsWritten());
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(heures)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** {@code 09:00}, not {@code 09:00:00}. */
    private static final class HourAsWritten extends JsonSerializer<LocalTime> {
        @Override
        public void serialize(LocalTime heure, JsonGenerator generateur, SerializerProvider fournisseur)
                throws java.io.IOException {
            generateur.writeString(heure.toString());
        }
    }

    /** A parser configured for scenario documents, aliases and all. */
    public static Yaml parser() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        return new Yaml(
                new SafeConstructor(options),
                new org.yaml.snakeyaml.representer.Representer(new org.yaml.snakeyaml.DumperOptions()),
                new org.yaml.snakeyaml.DumperOptions(),
                options,
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
            INT_WITHOUT_SEXAGESIMAL.forEach(pattern -> addImplicitResolver(Tag.INT, pattern, "-+0123456789"));
            FLOAT_WITHOUT_SEXAGESIMAL.forEach(pattern -> addImplicitResolver(Tag.FLOAT, pattern, "-+0123456789."));
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789");
            addImplicitResolver(Tag.YAML, YAML, "!&*");
        }
    }
}
