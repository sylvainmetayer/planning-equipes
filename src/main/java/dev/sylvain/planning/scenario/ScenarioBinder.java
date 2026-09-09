package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Turns a scenario YAML document into a {@link ScenarioDto} — the single shape
 * a scenario has, whether it is being validated, imported or written back.
 *
 * <p><b>SnakeYAML parses, Jackson binds.</b> Not Jackson end to end, and the
 * reason is measurable: our own exporter used to emit an anchor and an alias
 * per animateur sharing a list, and Jackson's YAML parser hands such an alias
 * over as the literal string {@code *id001} instead of the list it names.
 * SnakeYAML resolves aliases while parsing, so binding the map it returns
 * costs nothing and keeps every file the application ever produced readable.
 *
 * <p>The two coercions below exist for the same reason, and both are YAML 1.1
 * resolver behaviour rather than sloppiness in the files:
 * <ul>
 *   <li>an unquoted {@code 2026-08-17} resolves to a {@link Date}, not to a
 *       string a date deserialiser would accept;</li>
 *   <li>an unquoted {@code 8:00} resolves to the <em>sexagesimal number</em>
 *       480 — seconds, in YAML 1.1 — not to a time.</li>
 * </ul>
 * Requiring authors to know the quoting rules of YAML 1.1 is not a contract,
 * it is a trap; the published JSON Schema says {@code string} either way.
 *
 * <p><b>An unknown key is refused, by name.</b> The hand-written parser this
 * replaces ignored one silently, so a mistyped {@code parametresSolveur:}
 * meant a solve that quietly ran on the database's values rather than the
 * ones the scenario pinned. A file that says something the application does
 * not understand is a file whose author should hear about it.
 */
public final class ScenarioBinder {

    /**
     * SnakeYAML caps aliases at fifty as a billion-laughs guard. Raised, not
     * lifted: see {@code ScenarioYamlReader}'s own limit and the test that
     * pins it.
     */
    private static final int MAX_ALIASES = 10_000;

    private ScenarioBinder() {
    }

    /** @throws ScenarioFormatException on anything the DTO shape does not accept */
    public static ScenarioDto bind(String yamlContent) {
        Object document = parse(yamlContent);
        if (!(document instanceof Map)) {
            throw new ScenarioFormatException(
                    "Le fichier de scénario n'est pas un document YAML valide (mapping attendu).");
        }
        try {
            return mapper().convertValue(document, ScenarioDto.class);
        } catch (IllegalArgumentException e) {
            throw new ScenarioFormatException(explain(e), e);
        }
    }

    private static Object parse(String yamlContent) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        try {
            return new Yaml(new SafeConstructor(options)).load(yamlContent);
        } catch (RuntimeException e) {
            throw new ScenarioFormatException("YAML invalide : " + e.getMessage(), e);
        }
    }

    /**
     * Jackson names the offending property in a reference chain that reads
     * like a stack trace. The author needs the path and the word, not the
     * chain.
     */
    private static String explain(IllegalArgumentException e) {
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            return "Champ inconnu dans le scénario : « " + unknown.getPropertyName() + " » sous "
                    + chemin(unknown) + ". Vérifiez l'orthographe : un champ qu'on ne connaît pas "
                    + "n'est pas appliqué.";
        }
        return e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
    }

    /**
     * The path <em>to</em> the offending key, not including it: Jackson's last
     * reference is the unknown property itself, and repeating it would give
     * "« prenomm » sous animateurs[0].prenomm".
     */
    private static String chemin(UnrecognizedPropertyException unknown) {
        var references = unknown.getPath();
        StringBuilder chemin = new StringBuilder();
        for (int i = 0; i < references.size() - 1; i++) {
            var reference = references.get(i);
            if (reference.getFieldName() != null) {
                chemin.append(chemin.isEmpty() ? "" : ".").append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                chemin.append('[').append(reference.getIndex()).append(']');
            }
        }
        return chemin.isEmpty() ? "la racine du fichier" : chemin.toString();
    }

    private static ObjectMapper mapper() {
        SimpleModule yamlOneOne = new SimpleModule();
        yamlOneOne.addDeserializer(LocalDate.class, new DateResolvedByYaml());
        yamlOneOne.addDeserializer(LocalTime.class, new TimeResolvedByYaml());
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(yamlOneOne);
    }

    /** An unquoted {@code 2026-08-17} reaches us as a {@link Date}. */
    private static final class DateResolvedByYaml extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Object value = parser.readValueAs(Object.class);
            if (value instanceof Date date) {
                return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            }
            if (value instanceof Number millis) {
                return java.time.Instant.ofEpochMilli(millis.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
            }
            return value == null ? null : LocalDate.parse(value.toString());
        }
    }

    /** An unquoted {@code 8:00} reaches us as the sexagesimal number 480. */
    private static final class TimeResolvedByYaml extends JsonDeserializer<LocalTime> {
        @Override
        public LocalTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Object value = parser.readValueAs(Object.class);
            if (value instanceof Number seconds) {
                return LocalTime.ofSecondOfDay(seconds.longValue());
            }
            return value == null ? null : LocalTime.parse(value.toString());
        }
    }
}
