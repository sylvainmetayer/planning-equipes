package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.ScenarioFormatException;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads a scenario file: locates it on the classpath, binds the YAML to the
 * {@link ScenarioDto} every path of the application shares, and hands it to
 * {@link ScenarioDomainMapper} for the domain objects. Pure and static, like
 * its counterpart {@link ScenarioYamlWriter} — the one thing it used to reach
 * for, the edition's own {@code ParametresLegaux} when a file pins none, is a
 * {@link Supplier} its callers pass in.
 *
 * <p>The format exists once (issue #392, A2): {@link ScenarioBinder} decides
 * what a file may say, on the way in here as well as in the Validateur screen,
 * and {@link ScenarioDtoAssembler} writes the same record back. The two
 * differential tests, {@code ScenarioLectureDifferentielleTest} and
 * {@code ScenarioEcritureDifferentielleTest}, are what hold reading and
 * writing together. {@link PlanningService} keeps the public entry points as a
 * façade, so callers and the MCP tools are unchanged.</p>
 */
public final class ScenarioYamlReader {

    private ScenarioYamlReader() {}

    /** Classpath folder holding every selectable scenario file. */
    public static final String SCENARIOS_DIR = "scenarios";

    /** Default scenario loaded when the caller does not pick one. */
    public static final String DEFAULT_SCENARIO = "scenario-complet.yaml";

    /**
     * Resolves a scenario name to its classpath path. Shared by every scenario
     * accessor so they all apply the same two rules: an absent or blank name
     * falls back to {@link #DEFAULT_SCENARIO} — concatenating it raw would ask
     * for {@code scenarios/null}, or worse for {@code scenarios/} itself, whose
     * directory listing parses as a plain YAML string and blows up later — and
     * any path component is rejected so callers cannot escape the scenarios
     * folder.
     */
    public static String scenarioPath(String scenarioName) {
        String name = (scenarioName == null || scenarioName.isBlank()) ? DEFAULT_SCENARIO : scenarioName;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BusinessError.Invalid("Nom de scénario invalide: " + name);
        }
        return SCENARIOS_DIR + "/" + name;
    }

    /**
     * Lists every {@code .yaml}/{@code .yml} scenario available in the
     * {@link #SCENARIOS_DIR} classpath folder, sorted alphabetically. Drop a new
     * file in that folder and it shows up here (and in the UI dropdown) with no
     * code change. Works both in dev (folder on disk) and from a packaged jar.
     */
    public static List<String> listScenarios() {
        try {
            java.net.URL dirUrl = ScenarioYamlReader.class.getClassLoader().getResource(SCENARIOS_DIR);
            if (dirUrl == null) {
                return List.of();
            }
            Set<String> names = new TreeSet<>();
            if ("file".equals(dirUrl.getProtocol())) {
                Path dir = Paths.get(dirUrl.toURI());
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .filter(ScenarioYamlReader::isScenarioFile)
                            .forEach(names::add);
                }
            } else if ("jar".equals(dirUrl.getProtocol())) {
                java.net.JarURLConnection conn = (java.net.JarURLConnection) dirUrl.openConnection();
                String prefix = SCENARIOS_DIR + "/";
                try (JarFile jar = conn.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(prefix) && !entry.endsWith("/")) {
                            String fileName = entry.substring(prefix.length());
                            if (!fileName.contains("/") && isScenarioFile(fileName)) {
                                names.add(fileName);
                            }
                        }
                    }
                }
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la lecture du dossier des scénarios", e);
        }
    }

    private static boolean isScenarioFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    /* ------------------------------ reading ------------------------------- */

    /** A bundled scenario file, bound. Shared by every accessor of a named scenario. */
    public static ScenarioDto readScenario(String scenarioPath) throws IOException {
        InputStream inputStream = ScenarioYamlReader.class.getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        try (inputStream) {
            return ScenarioBinder.bind(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * An uploaded scenario text, bound — every refusal as a
     * {@link BusinessError.Invalid} whose message is meant to be shown as-is.
     * Malformed YAML keeps the binder's own "YAML invalide" sentence; a file
     * that parses but says something the format does not accept is a
     * "Scénario invalide", like a file whose sections do not fit together.
     */
    private static ScenarioDto bind(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        try {
            return ScenarioBinder.bind(yamlContent);
        } catch (ScenarioFormatException e) {
            String message = messageOr(e);
            throw new BusinessError.Invalid(
                    message.startsWith("YAML invalide") ? message : "Scénario invalide : " + message, e);
        }
    }

    /** The whole problem a scenario describes — see {@link ScenarioDomainMapper#planning}. */
    public static PlanningEvenement buildPlanning(
            ScenarioDto scenario, Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        return ScenarioDomainMapper.planning(scenario, parametresLegauxParDefaut);
    }

    /**
     * Parses a scenario YAML file uploaded by a user (same shape as the files
     * under {@link #SCENARIOS_DIR}, typically produced by "Exporter les
     * données actuelles en scénario") into the same result the
     * {@code import-scenario} endpoint applies for a built-in scenario name —
     * without ever touching the classpath. Used by the "Importer un fichier"
     * button on the Scénarios page.
     *
     * <p>Every failure (malformed YAML, an unknown key, a missing section) is
     * reported as an {@link IllegalArgumentException} carrying a message
     * meant to be shown to the user as-is.</p>
     */
    public static ScenarioImporte buildFromScenarioText(
            String yamlContent, Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        ScenarioDto scenario = bind(yamlContent);
        PlanningEvenement planning;
        try {
            planning = ScenarioDomainMapper.planning(scenario, parametresLegauxParDefaut);
        } catch (RuntimeException e) {
            throw new BusinessError.Invalid("Scénario invalide : " + messageOr(e), e);
        }
        return new ScenarioImporte(planning, ScenarioDomainMapper.sections(scenario));
    }

    private static String messageOr(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Result of {@link #buildFromScenarioText}: the built planning plus
     * whichever optional parameter sections the file pinned, mirroring what
     * {@code POST /reference-data/import-scenario} applies for a named
     * built-in scenario.
     */
    public record ScenarioImporte(PlanningEvenement planning, ScenarioSections sections) {}

    /**
     * Loads a scenario's raw stands/animateurs/creneaux, ignoring any
     * hand-authored {@code postes:} list — unlike {@link PlanningService#buildExample},
     * which uses that list as-is. For tests that need to run découpage (see
     * {@link VacationGeneratorService}) on the raw créneaux themselves before
     * building postes via {@link ProblemBuilder#buildPostes}, the way
     * {@code ProblemBuilder.buildFromReferenceData} does against the database.
     */
    public static ReferenceScenario loadReferenceScenario(String scenarioName) throws IOException {
        return ScenarioDomainMapper.reference(readScenario(scenarioPath(scenarioName)));
    }

    /** {@code creneaux}/{@code stands}/{@code animateurs} sections of a scenario file, parsed and cross-linked. */
    public record ReferenceScenario(
            LocalDate dateDebut,
            Map<String, Creneau> creneauxParId,
            Map<String, Stand> standsById,
            List<Animateur> animateurs) {}

    /**
     * Every optional top-level section a scenario file may pin, read in
     * <b>one</b> pass over the file.
     *
     * <p>There used to be one public accessor per section, each three lines
     * long and each re-reading and re-parsing the whole file. Importing a
     * scenario called five of them plus the planning build, so a single click
     * parsed {@code festival-realiste-canicule.yaml} seven times — and adding a section
     * meant adding a seventh near-identical method. One record, one read.</p>
     *
     * @param parametresLegaux    lets a scenario pin the legal parameters it was
     *                            authored and verified against instead of silently
     *                            depending on whatever the database currently holds.
     *                            Absent fields fall back to {@link ParametresLegaux}'s
     *                            own defaults, never to the live value, so the
     *                            scenario stays reproducible on its own
     * @param parametresDecoupage what the découpage consumes, read separately and
     *                            applied by the scenario-import endpoint; none of
     *                            it reaches the solver. The meal break used to be
     *                            here and now sits with {@code parametresLegaux},
     *                            projected into {@link dev.sylvain.planning.domain.FenetreRepas}
     *                            facts (issue #438); its old keys are still read
     * @param parametresSolveur   lets a large scenario pin the termination duration
     *                            it actually needs ({@code scenario-complet.yaml}
     *                            takes ~8 min to reach a good score) rather than
     *                            relying on the Données tab. Absent, the current
     *                            database value is left untouched
     * @param decoupageAuto       a scenario written in "amplitudes" (one long opening
     *                            window per day, e.g. {@code scenario-continu.yaml})
     *                            asks its import to slice itself into vacations,
     *                            instead of leaving the operator to run the
     *                            "Découpage" screen by hand afterwards
     * @param typologies          {@code {id, label}} pairs defining the scenario's own
     *                            typologie referential entries up front, instead of
     *                            leaving every referenced id to the id-as-its-own-label
     *                            default {@code ReferenceDataImportRepository#importFromPlanning}
     *                            derives on the fly. Empty, not absent, when the section
     *                            is missing: a list has no "absent" distinct from "empty"
     * @param edition             the edition the import must write into; absent means
     *                            the caller's current one
     * @param contraintes         which constraints the scenario switches off and how
     *                            it weights the others. Absent leaves the target
     *                            edition's own tuning alone; present, it replaces it
     *                            wholesale — a file that pins nothing but the section
     *                            itself re-enables everything, which is what "this is
     *                            the tuning this scenario was verified with" means
     */
    public record ScenarioSections(
            Optional<ParametresLegaux> parametresLegaux,
            Optional<ParametresDecoupage> parametresDecoupage,
            Optional<ParametresSolveur> parametresSolveur,
            boolean decoupageAuto,
            List<TypologieItem> typologies,
            Optional<EditionCibleDto> edition,
            Optional<ContraintesScenario> contraintes,
            Optional<JourneesTypesScenario> journeesTypes) {}

    /**
     * The {@code journeesTypes:} section of a scenario, parsed: the templates
     * with provisional ids the calendar names, which the repository reassigns.
     */
    public record JourneesTypesScenario(
            List<JourneeType> journeesTypes, List<JourneesTypesMaterialisation.Affectation> calendrier) {}

    /**
     * The {@code contraintes:} section of a scenario, parsed.
     *
     * @param desactivees names of the constraints to switch off
     * @param poids       weight per constraint name; what is absent keeps the
     *                    deployment default
     */
    public record ContraintesScenario(Set<String> desactivees, Map<String, Integer> poids) {}

    /**
     * The optional sections of a bundled scenario, <b>without</b> building its
     * planning: the pre-import step that names the target edition, and the
     * cheap read the tests use to assert what a file pins.
     */
    public static ScenarioSections loadScenarioSections(String scenarioName) {
        try {
            return ScenarioDomainMapper.sections(readScenario(scenarioPath(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * A bundled scenario, whole: its planning and its optional sections, from
     * a single read. The named-file counterpart of
     * {@link #buildFromScenarioText}, so the two import paths differ
     * only in where the bytes come from.
     */
    public static ScenarioImporte loadScenario(
            String scenarioName, Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        try {
            ScenarioDto scenario = readScenario(scenarioPath(scenarioName));
            return new ScenarioImporte(
                    ScenarioDomainMapper.planning(scenario, parametresLegauxParDefaut),
                    ScenarioDomainMapper.sections(scenario));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Optional {@code edition:} section of an uploaded scenario text — the
     * pre-import step the UI uses to NAME the target edition in its
     * confirmation dialog, before anything is written. The whole file is bound
     * on the way, so a file the import would refuse is refused here already.
     */
    public static Optional<EditionCibleDto> loadEditionScenarioText(String yamlContent) {
        return ScenarioDomainMapper.edition(bind(yamlContent));
    }
}
