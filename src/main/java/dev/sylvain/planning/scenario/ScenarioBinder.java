package dev.sylvain.planning.scenario;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
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

    private ScenarioBinder() {}

    /** @throws ScenarioFormatException on anything the DTO shape does not accept */
    public static ScenarioDto bind(String yamlContent) {
        Object document = parse(yamlContent);
        if (!(document instanceof Map)) {
            throw new ScenarioFormatException(
                    "Le fichier de scénario n'est pas un document YAML valide (mapping attendu).");
        }
        refuseRetiredSections(document);
        try {
            return MAPPER.convertValue(ScenarioYaml.normaliseDates(document), ScenarioDto.class);
        } catch (IllegalArgumentException e) {
            throw new ScenarioFormatException(explain(e), e);
        }
    }

    /**
     * The keys of settings that no longer exist, refused by name rather than as
     * "champ unknown", each with what replaces it (ADR 0037, then ADR 0048).
     *
     * <p>The two sections the découpage owned first. A grid is made of
     * vacations now — the event knows its opening hours and projects them
     * through its journées types — so {@code parametresDecoupage:} configures
     * nothing and {@code decoupageAuto:} slices nothing. Ignoring them would be
     * worse than refusing: a file of 14-hour amplitudes would import as 14-hour
     * vacations, and the author would find out from the solver.</p>
     *
     * <p>Then the four pause keys of {@code parametresLegaux:}. Same reason,
     * and sharper: a file declaring {@code pauseSurPoste: false} was verified
     * under a mode where the legal break had to be a hole in the grid, and
     * importing it silently would now judge it under the single rule, which
     * accepts a relay. A file setting {@code pauseMinimaleEntreVacationsMinutes}
     * was tuning a rule that no longer exists.</p>
     */
    @SuppressWarnings("unchecked")
    private static void refuseRetiredSections(Object document) {
        Map<String, Object> racine = (Map<String, Object>) document;
        refuseRetiredLegalKeys(racine.get("parametresLegaux"));
        if (racine.containsKey("parametresDecoupage")) {
            throw new ScenarioFormatException("La section « parametresDecoupage » n'existe plus : le découpage"
                    + " automatique a été retiré, une grille est toujours faite de vacations. Déclarez les"
                    + " vacations telles quelles sous « creneaux », ou décrivez-les une fois sous"
                    + " « journeesTypes » et affectez-leur des dates. Le seuil « dureeVacationMaxMinutes »,"
                    + " lui, a rejoint « parametresLegaux ».");
        }
        if (racine.containsKey("decoupageAuto")) {
            throw new ScenarioFormatException("La section « decoupageAuto » n'existe plus : il n'y a plus"
                    + " d'amplitudes à découper à l'import. Remplacez les amplitudes de « creneaux » par les"
                    + " vacations attendues — un relais repas se marque « couverturePause: true » — ou passez"
                    + " par « journeesTypes ».");
        }
    }

    /** What each retired key of {@code parametresLegaux:} is replaced by. */
    private static final Map<String, String> CLES_LEGALES_RETIREES = Map.of(
            "pauseSurPoste",
                    "il n'y a plus de mode : une pause due est soit un trou d'au moins « dureePauseMinutes »"
                            + " dans la grille, soit relayée par un collègue du même stand, sinon c'est un écart"
                            + " dur. Retirez la clé.",
            "pauseMinimaleEntreVacationsMinutes",
                    "la règle « pauseMinimaleEntreVacations » a été retirée : un trou plus court que la pause"
                            + " compte comme travaillé, un trou plus long est une pause. Retirez la clé.",
            "dureePauseMajeurMinutes",
                    "une seule durée pour l'édition : renommez la clé en « dureePauseMinutes » (plancher 20 min,"
                            + " portée à 30 pour un mineur).",
            "dureePauseMineurMinutes",
                    "la durée des mineurs n'est plus réglable : « dureePauseMinutes » vaut pour tous, et le"
                            + " plancher de 30 min de l'art. L3162-3 s'applique tout seul. Retirez la clé.");

    @SuppressWarnings("unchecked")
    private static void refuseRetiredLegalKeys(Object parametresLegaux) {
        if (!(parametresLegaux instanceof Map)) {
            return;
        }
        Map<String, Object> legaux = (Map<String, Object>) parametresLegaux;
        for (Map.Entry<String, String> retiree : CLES_LEGALES_RETIREES.entrySet()) {
            if (legaux.containsKey(retiree.getKey())) {
                throw new ScenarioFormatException(
                        "La clé « parametresLegaux." + retiree.getKey() + " » n'existe plus : " + retiree.getValue());
            }
        }
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
        if (e.getCause() instanceof InvalidFormatException wrongValue) {
            // Raised by the two deserialisers below, with a sentence of their
            // own; Jackson attached the path on the way up.
            return "Valeur inattendue dans le scénario, sous " + path(wrongValue.getPath(), false) + " : "
                    + wrongValue.getOriginalMessage();
        }
        if (e.getCause() instanceof MismatchedInputException wrongType) {
            // Without this, Jackson hands back its reference chain, which reads
            // like a stack trace and names Java classes to somebody writing YAML.
            String forme = formeAttendue(wrongType.getTargetType());
            String chemin = path(wrongType.getPath(), false);
            if (forme != null) {
                return "La section « " + chemin + " » doit être " + forme + " dans le fichier de scénario.";
            }
            return "Valeur inattendue dans le scénario, sous " + chemin + " : " + firstLine(wrongType);
        }
        return e.getCause() == null ? e.getMessage() : firstLine(e.getCause());
    }

    /**
     * What the file should have written where it wrote something else, in
     * the author's words: a list, a block of fields, a number. {@code null}
     * for the types the sentence cannot name better than Jackson does.
     */
    private static String formeAttendue(Class<?> cible) {
        if (cible == null) {
            return null;
        }
        if (Collection.class.isAssignableFrom(cible)) {
            return "une liste";
        }
        if (Map.class.isAssignableFrom(cible)
                || (cible.isRecord() && cible.getPackageName().equals(ScenarioDto.class.getPackageName()))) {
            return "un bloc de champs (clé: valeur)";
        }
        if (Number.class.isAssignableFrom(cible) || cible == int.class || cible == long.class) {
            return "un nombre";
        }
        if (cible == Boolean.class || cible == boolean.class) {
            return "true ou false";
        }
        return null;
    }

    /**
     * @param sansLeDernier drop Jackson's last reference — for an unknown key it
     *                      <em>is</em> the key, and repeating it would give
     *                      "« prenomm » sous animateurs[0].prenomm"
     */
    private static String path(
            java.util.List<com.fasterxml.jackson.databind.JsonMappingException.Reference> refs, boolean sansLeDernier) {
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
                throw InvalidFormatException.from(
                        parser, "une date doit s'écrire 2026-08-17, pas « " + value + " »", value, LocalDate.class);
            }
            try {
                return LocalDate.parse(value.toString());
            } catch (DateTimeParseException e) {
                // A Jackson exception rather than a bare one: Jackson attaches
                // the path to it, so the author learns which stand, which day.
                throw InvalidFormatException.from(
                        parser, "« " + value + " » n'est pas une date (attendu 2026-08-17)", value, LocalDate.class);
            }
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
            // An empty heureFin is « until closing », the way the hand-written
            // reader always read it (FenetreHoraire); refusing it broke files
            // edited by hand that the application used to open.
            if (value == null || value.toString().isEmpty()) {
                return null;
            }
            if (value instanceof Number) {
                throw InvalidFormatException.from(
                        parser, "une heure doit s'écrire 9:30, pas « " + value + " »", value, LocalTime.class);
            }
            try {
                return LocalTime.parse(value.toString(), HEURE);
            } catch (DateTimeParseException e) {
                throw InvalidFormatException.from(
                        parser, "« " + value + " » n'est pas une heure (attendu 9:30)", value, LocalTime.class);
            }
        }
    }
}
