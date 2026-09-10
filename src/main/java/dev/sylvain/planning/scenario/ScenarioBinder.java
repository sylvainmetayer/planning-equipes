package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p>What YAML 1.1 does to unquoted scalars is handled once, in
 * {@link ScenarioYaml}: sexagesimal numbers are switched off so an hour stays
 * the text its author wrote, and the {@code java.util.Date} built for an
 * unquoted date is normalised to ISO text before binding. Requiring authors to
 * know the quoting rules of YAML 1.1 is not a contract, it is a trap; the
 * published JSON Schema says {@code string} either way.
 *
 * <p><b>An unknown key is refused, by name.</b> The hand-written parser this
 * replaces ignored one silently, so a mistyped {@code parametresSolveur:}
 * meant a solve that quietly ran on the database's values rather than the
 * ones the scenario pinned. A file that says something the application does
 * not understand is a file whose author should hear about it.
 */
public final class ScenarioBinder {

    /** Hours as authors write them: {@code 8:00}, {@code 09:30}, {@code 12:00:00}. */
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("H:mm[:ss]");

    /**
     * Built once. An {@code ObjectMapper} caches its deserialisers per
     * instance, so a fresh one per call re-resolves the whole {@code
     * ScenarioDto} tree — on a path that binds files of several megabytes.
     */
    private static final ObjectMapper MAPPER = mapper();

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
            return MAPPER.convertValue(decoupageAutoByPresence(ScenarioYaml.normaliseDates(document)), ScenarioDto.class);
        } catch (IllegalArgumentException e) {
            throw new ScenarioFormatException(explain(e), e);
        }
    }

    /**
     * {@code decoupageAuto:} means "slice on import" by its <em>presence</em>:
     * the canonical {@code decoupageAuto: {}}, a bare {@code decoupageAuto:}
     * (YAML null) and the historical {@code groupeSourceNom} /
     * {@code groupeCibleNom} object all mean present; only an explicit
     * {@code decoupageAuto: false} opts out, and reads as absent. Settled on
     * the document rather than in a deserialiser, because Jackson hands a
     * missing record component the deserialiser's null value too — the first
     * attempt read every scenario as asking to be sliced.
     */
    @SuppressWarnings("unchecked")
    private static Object decoupageAutoByPresence(Object document) {
        Map<String, Object> racine = new LinkedHashMap<>((Map<String, Object>) document);
        if (!racine.containsKey("decoupageAuto")) {
            return racine;
        }
        Object valeur = racine.get("decoupageAuto");
        if (Boolean.FALSE.equals(valeur)) {
            racine.remove("decoupageAuto");
        } else if (valeur == null || Boolean.TRUE.equals(valeur)) {
            racine.put("decoupageAuto", Map.of());
        }
        return racine;
    }

    private static Object parse(String yamlContent) {
        try {
            return ScenarioYaml.parser().load(yamlContent);
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
                    + path(unknown.getPath(), true) + ". Vérifiez l'orthographe : un champ qu'on ne "
                    + "connaît pas n'est pas appliqué.";
        }
        if (e.getCause() instanceof MismatchedInputException wrongType) {
            // Without this, Jackson hands back its reference chain, which reads
            // like a stack trace and names Java classes to somebody writing YAML.
            return "Valeur inattendue dans le scénario, sous " + path(wrongType.getPath(), false)
                    + " : " + firstLine(wrongType);
        }
        return e.getCause() == null ? e.getMessage() : firstLine(e.getCause());
    }

    /**
     * @param sansLeDernier drop Jackson's last reference — for an unknown key it
     *                      <em>is</em> the key, and repeating it would give
     *                      "« prenomm » sous animateurs[0].prenomm"
     */
    private static String path(java.util.List<com.fasterxml.jackson.databind.JsonMappingException.Reference> refs,
            boolean sansLeDernier) {
        StringBuilder path = new StringBuilder();
        int upTo = sansLeDernier ? refs.size() - 1 : refs.size();
        for (int i = 0; i < upTo; i++) {
            var reference = refs.get(i);
            if (reference.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "la racine du fichier" : path.toString();
    }

    private static String firstLine(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.lines().findFirst().orElse(message).replaceAll("\\s*\\(through reference chain.*", "");
    }

    private static ObjectMapper mapper() {
        SimpleModule yamlOneOne = new SimpleModule();
        yamlOneOne.addDeserializer(LocalDate.class, new DateResolvedByYaml());
        yamlOneOne.addDeserializer(LocalTime.class, new TimeResolvedByYaml());
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(yamlOneOne);
    }

    /**
     * A date is text by the time it gets here — {@link ScenarioYaml} normalises
     * the one SnakeYAML built. A bare number is <b>refused</b> rather than read
     * as an epoch: {@code dateNaissance: 1990} is a year somebody typed, and
     * binding it to 1970-01-01 would hand the legal constraints a 56-year-old
     * where a minor was declared.
     */
    private static final class DateResolvedByYaml extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Object value = parser.readValueAs(Object.class);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                throw new IllegalArgumentException("une date doit s'écrire 2026-08-17, pas « " + value + " »");
            }
            return LocalDate.parse(value.toString());
        }
    }

    /**
     * An hour reaches us as text, single-digit hour included: {@link
     * ScenarioYaml} switches off the sexagesimal resolver that used to turn
     * {@code 9:30} into the number 570.
     */
    private static final class TimeResolvedByYaml extends JsonDeserializer<LocalTime> {
        @Override
        public LocalTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Object value = parser.readValueAs(Object.class);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                throw new IllegalArgumentException("une heure doit s'écrire 9:30, pas « " + value + " »");
            }
            return LocalTime.parse(value.toString(), HEURE);
        }
    }
}
