package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.scenario.YamlSections;
import dev.sylvain.planning.solver.ConstraintCatalog;

/**
 * Reads a scenario file: locates it on the classpath, parses the YAML and
 * cross-links what it holds into domain objects. Pure and static, like its
 * counterpart {@link ScenarioYamlWriter} — the one thing it used to reach for,
 * the edition's own {@code ParametresLegaux} when a file pins none, is now a
 * {@link Supplier} its callers pass in.
 *
 * <p>Reader and writer are two hand-written traversals of the same {@code Map}
 * shape, kept in step by hand; {@code PlanningServiceScenarioAllerRetourTest}
 * is what actually holds them together. {@link PlanningService} keeps the
 * public entry points as a façade, so callers and the MCP tools are unchanged.</p>
 */
public final class ScenarioYamlReader {

    private ScenarioYamlReader() {
    }

    /** Classpath folder holding every selectable scenario file. */
    static final String SCENARIOS_DIR = "scenarios";

    /** Default scenario loaded when the caller does not pick one. */
    static final String DEFAULT_SCENARIO = "scenario-complet.yaml";

    /**
     * Resolves a scenario name to its classpath path. Shared by every scenario
     * accessor so they all apply the same two rules: an absent or blank name
     * falls back to {@link #DEFAULT_SCENARIO} — concatenating it raw would ask
     * for {@code scenarios/null}, or worse for {@code scenarios/} itself, whose
     * directory listing parses as a plain YAML string and blows up later as a
     * {@code ClassCastException} — and any path component is rejected so
     * callers cannot escape the scenarios folder.
     */
    static String cheminScenario(String scenarioName) {
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
    static List<String> listScenarios() {
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

    static PlanningEvenement buildPlanningFromData(Map<String, Object> scenarioData,
            Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        ReferenceScenario reference = loadReferenceScenario(scenarioData);

        // Expand the recurring opening hours before deciding anything about
        // openings: a file may describe the opening hours of a stand as rules
        // rather than as dated windows, and they must be resolved on the days of
        // its own timeslots. With no rule, the call changes nothing.
        HoraireStandResolver.apply(reference.standsById().values(), reference.creneauxParId().values());

        // Load the seats: taken as they are from the file when the section is
        // there, otherwise generated from the stands/timeslots (same rules as
        // buildFromReferenceData) — a file no longer has to enumerate its
        // seats by hand to be imported.
        List<Map<String, Object>> postesList = YamlSections.objets(scenarioData, "postes");
        List<PosteAffectation> postes;
        if (postesList == null) {
            postes = ProblemBuilder.buildPostes(new ArrayList<>(reference.standsById().values()),
                    new ArrayList<>(reference.creneauxParId().values()));
        } else {
            postes = new ArrayList<>();
            for (Map<String, Object> posteData : postesList) {
                String id = parseTextId(posteData.get("id"));
                String standId = parseTextId(posteData.get("standId"));
                String creneauId = parseTextId(posteData.get("creneauId"));

                Stand stand = reference.standsById().get(standId);
                Creneau creneau = reference.creneauxParId().get(creneauId);

                PosteAffectation poste = new PosteAffectation(id, stand, creneau);
                // Mirrors buildPostes(): a hand-authored poste can still name a
                // créneau the stand is only partially open for (IndisponibiliteStand /
                // OuvertureStand), so narrow its effective window the same way instead
                // of silently using the créneau's full amplitude.
                List<int[]> segments = creneau.segmentsOuvertsMinutes(stand);
                if (segments.size() == 1) {
                    int[] segment = segments.get(0);
                    boolean creneauEntierOuvert = segment[0] == 0 && segment[1] == creneau.getDureeMinutes();
                    if (!creneauEntierOuvert) {
                        poste.setHeureDebutEffective(ProblemBuilder.decaler(creneau.getHeureDebut(), segment[0]));
                        poste.setHeureFinEffective(ProblemBuilder.decaler(creneau.getHeureDebut(), segment[1]));
                    }
                }
                postes.add(poste);
            }
        }

        // Exactly the file's own ad hoc constraints, and nothing else: a
        // scenario describes the whole problem, and re-importing it must not
        // merge somebody else's. A file carrying no section carries none.
        //
        // It used to fall back on the database's — the *current* edition's,
        // resolved before the target edition is even known. That leaked one
        // edition's exceptions into another (a foreign key violation as soon
        // as the target held neither the stand nor the créneau they name), and
        // was unsound even into the same edition: an import replaces every
        // créneau, so the ids those exceptions point at are deleted on the way
        // through. What preserves them across a round-trip is the export,
        // which writes the section whenever the edition holds any.
        List<ContrainteAdHoc> contraintesAdHoc = parseContraintesAdHoc(scenarioData, reference);
        PlanningEvenement evenement = new PlanningEvenement(reference.dateDebut(), reference.animateurs(), postes,
                contraintesAdHoc != null ? contraintesAdHoc : new ArrayList<>());
        evenement.setParametresLegaux(List.of(
                parseParametresLegaux(scenarioData).orElseGet(parametresLegauxParDefaut)));
        // Same reasoning as the ad hoc constraints above, for the dosage: a file
        // that pins its weights describes the problem it was verified against,
        // and solving it must apply them whether or not it was ever imported.
        evenement.setPonderationsScenario(parseContraintes(scenarioData)
                .map(ContraintesScenario::poids)
                .orElse(null));
        return evenement;
    }

    /**
     * Parses a scenario YAML file uploaded by a user (same shape as the files
     * under {@link #SCENARIOS_DIR}, typically produced by "Exporter les
     * données actuelles en scénario") into the same result the
     * {@code import-scenario} endpoint applies for a built-in scenario name —
     * without ever touching the classpath. Used by the "Importer un file"
     * button on the Scénarios page.
     *
     * <p>Every failure (malformed YAML, a missing/mistyped section) is
     * reported as an {@link IllegalArgumentException} carrying a message
     * meant to be shown to the user as-is, rather than surfacing the raw
     * {@link org.yaml.snakeyaml.error.YAMLException}/{@link ClassCastException}/
     * {@link NullPointerException} a malformed file triggers deep inside
     * {@link #buildPlanningFromData}.</p>
     */
    static ScenarioImporte buildFromScenarioText(String yamlContent,
            Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        Map<String, Object> scenarioData;
        try {
            scenarioData = parserYaml(
                    new java.io.ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException | IOException e) {
            throw new BusinessError.Invalid("YAML invalide : " + messageOr(e), e);
        }
        PlanningEvenement planning;
        try {
            planning = buildPlanningFromData(scenarioData, parametresLegauxParDefaut);
        } catch (RuntimeException e) {
            throw new BusinessError.Invalid("Scénario invalide : " + messageOr(e), e);
        }
        return new ScenarioImporte(planning, sectionsOf(scenarioData));
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
    public record ScenarioImporte(PlanningEvenement planning, ScenarioSections sections) {
    }

    /**
     * Loads a scenario's raw stands/animateurs/creneaux, ignoring any
     * hand-authored {@code postes:} list — unlike {@link PlanningService#buildExample},
     * which uses that list as-is. For tests that need to run découpage (see
     * {@link VacationGeneratorService}) on the raw créneaux themselves before
     * building postes via {@link ProblemBuilder#buildPostes}, the way
     * {@code ProblemBuilder.buildFromReferenceData} does against the database.
     */
    static ReferenceScenario loadReferenceScenario(String scenarioName) throws IOException {
        return loadReferenceScenario(readScenarioData(cheminScenario(scenarioName)));
    }

    /** {@code creneaux}/{@code stands}/{@code animateurs} sections of a scenario file, parsed and cross-linked. */
    record ReferenceScenario(LocalDate dateDebut, Map<String, Creneau> creneauxParId, Map<String, Stand> standsById,
            List<Animateur> animateurs) {
    }

    private static ReferenceScenario loadReferenceScenario(Map<String, Object> scenarioData) {
        // Load the creneaux: the YAML file carries a historical text id (used
        // only to tie postes and creneaux together), replaced here by a synthetic
        // numeric id; jour is recomputed (see Creneau.assignerJours), the value
        // from the file is ignored.
        Map<String, Creneau> creneauxMap = new HashMap<>();
        long compteurCreneauId = 1;
        List<Map<String, Object>> creneauxList = YamlSections.objets(scenarioData, "creneaux");
        for (Map<String, Object> creneauData : creneauxList) {
            String id = parseTextId(creneauData.get("id"));

            LocalDate date = parseLocalDate(creneauData.get("date"), "creneaux.date");
            LocalTime heureDebut = parseLocalTime(creneauData.get("heureDebut"));
            LocalTime heureFin = parseLocalTime(creneauData.get("heureFin"));

            Creneau creneau = new Creneau(compteurCreneauId++, 0, date, heureDebut, heureFin);
            creneauxMap.put(id, creneau);
        }
        Creneau.assignerJours(creneauxMap.values());

        // Load the emplacements
        Map<String, Emplacement> emplacementsMap = new HashMap<>();
        List<Map<String, Object>> emplacementsList = YamlSections.objets(scenarioData, "emplacements");
        if (emplacementsList != null) {
            for (Map<String, Object> emplacementData : emplacementsList) {
                String id = (String) emplacementData.get("id");
                String nom = (String) emplacementData.get("nom");
                Double latitude = emplacementData.get("latitude") == null ? null
                        : ((Number) emplacementData.get("latitude")).doubleValue();
                Double longitude = emplacementData.get("longitude") == null ? null
                        : ((Number) emplacementData.get("longitude")).doubleValue();

                Emplacement emplacement = new Emplacement(id, nom, latitude, longitude);
                emplacementsMap.put(id, emplacement);
            }
        }

        // Load the stands
        Map<String, Stand> standsMap = new HashMap<>();
        List<Map<String, Object>> standsList = YamlSections.objets(scenarioData, "stands");
        for (Map<String, Object> standData : standsList) {
            String id = (String) standData.get("id");
            String nom = (String) standData.get("nom");
            List<String> typologiesStr = YamlSections.chaines(standData, "typologiesProposees");
            if (typologiesStr.isEmpty()) {
                // Same rule as StandValidator (issue #343), said in the file's terms.
                throw new IllegalArgumentException("stands[" + standsList.indexOf(standData) + "] (id " + id
                        + ") : aucune typologie proposée, un stand est toujours rattaché à au moins une typologie");
            }
            Set<String> typologies = new HashSet<>(typologiesStr);
            int effectifMin = ((Number) standData.get("effectifMin")).intValue();
            int effectifMax = ((Number) standData.get("effectifMax")).intValue();
            boolean reserveMajeurs = (Boolean) standData.getOrDefault("reserveMajeurs", false);
            boolean premium = (Boolean) standData.getOrDefault("premium", false);

            Stand stand = new Stand(id, nom, typologies, effectifMin, effectifMax, reserveMajeurs, premium);
            String niveauEffortStr = (String) standData.getOrDefault("niveauEffort", NiveauEffort.NORMAL.name());
            stand.setNiveauEffort(NiveauEffort.valueOf(niveauEffortStr));
            Number famille = (Number) standData.get("famille");
            stand.setFamille(famille == null ? null : famille.intValue());
            String emplacementId = (String) standData.get("emplacementId");
            if (emplacementId != null) {
                stand.setEmplacement(emplacementsMap.get(emplacementId));
            }
            List<Map<String, Object>> indisponibilitesData = YamlSections.objets(standData, "indisponibilites");
            if (indisponibilitesData != null) {
                List<IndisponibiliteStand> indisponibilites = new ArrayList<>();
                for (Map<String, Object> indispoData : indisponibilitesData) {
                    LocalDate date = parseLocalDate(indispoData.get("date"), "stands.indisponibilites.date");
                    LocalTime heureDebut = LocalTime.parse((String) indispoData.get("heureDebut"));
                    LocalTime heureFin = parseTimeOrEndOfDay(indispoData.get("heureFin"));
                    String motif = (String) indispoData.get("motif");
                    indisponibilites.add(new IndisponibiliteStand(null, date, heureDebut, heureFin, motif));
                }
                stand.setIndisponibilites(indisponibilites);
            }
            List<Map<String, Object>> ouverturesData = YamlSections.objets(standData, "ouvertures");
            if (ouverturesData != null) {
                List<OuvertureStand> ouvertures = new ArrayList<>();
                for (Map<String, Object> ouvertureData : ouverturesData) {
                    LocalDate date = parseLocalDate(ouvertureData.get("date"), "stands.ouvertures.date");
                    LocalTime heureDebut = LocalTime.parse((String) ouvertureData.get("heureDebut"));
                    LocalTime heureFin = parseTimeOrEndOfDay(ouvertureData.get("heureFin"));
                    String motif = (String) ouvertureData.get("motif");
                    Object effectif = ouvertureData.get("effectif");
                    if (effectif != null && !(effectif instanceof Number)) {
                        throw new BusinessError.Invalid(
                                "Champ invalide: stands.ouvertures.effectif doit être un entier");
                    }
                    ouvertures.add(new OuvertureStand(null, date, heureDebut, heureFin, motif,
                            effectif == null ? null : ((Number) effectif).intValue()));
                }
                stand.setOuvertures(ouvertures);
            }
            List<Map<String, Object>> horairesData = YamlSections.objets(standData, "horaires");
            if (horairesData != null) {
                stand.setHoraires(readHoraires(horairesData));
            }
            standsMap.put(id, stand);
        }

        // Load the animateurs
        List<Animateur> animateurs = new ArrayList<>();
        List<Map<String, Object>> animateursList = YamlSections.objets(scenarioData, "animateurs");
        for (Map<String, Object> animateurData : animateursList) {
            String id = (String) animateurData.get("id");
            String prenom = (String) animateurData.get("prenom");
            String nom = (String) animateurData.get("nom");
            LocalDate dateNaissance = parseLocalDate(animateurData.get("dateNaissance"), "animateurs.dateNaissance");
            boolean manager = Boolean.TRUE.equals(animateurData.get("manager"));

            Animateur animateur = new Animateur(id, prenom, nom, dateNaissance, manager);
            animateur.setEmail((String) animateurData.get("email"));

            // Load the competences
            Map<String, String> competencesData = (Map<String, String>) animateurData.get("competences");
            Map<String, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(entry.getKey(), NiveauCompetence.valueOf(entry.getValue()));
            }
            animateur.setCompetences(competences);

            // Load the days off (opt-out: available unless listed)
            List<Object> joursOffData = YamlSections.valeurs(animateurData, "joursIndisponibles");
            Set<LocalDate> joursIndisponibles = joursOffData == null
                    ? new HashSet<>()
                    : joursOffData.stream()
                            .map(value -> parseLocalDate(value, "animateurs.joursIndisponibles"))
                            .collect(Collectors.toCollection(HashSet::new));
            animateur.setJoursIndisponibles(joursIndisponibles);

            // Load the souhaits (wished-for stand typologies, with no level and no priority)
            List<Object> souhaitsData = YamlSections.valeurs(animateurData, "souhaits");
            Set<String> souhaits = souhaitsData == null
                    ? new HashSet<>()
                    : souhaitsData.stream()
                            .map(value -> (String) value)
                            .collect(Collectors.toCollection(HashSet::new));
            animateur.setSouhaits(souhaits);

            animateurs.add(animateur);
        }

        // A scenario carries its own typologie referential, so the ninja typologie
        // comes from the file itself — the database one may not be loaded yet (or
        // may describe a different event entirely).
        String typologieNinja = parseTypologies(scenarioData).stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        animateurs.forEach(animateur -> animateur.applyNinjaTypologie(typologieNinja));

        LocalDate dateDebut = parseLocalDate(
            YamlSections.objet(scenarioData, "festival").get("dateDebut"),
            "festival.dateDebut");

        return new ReferenceScenario(dateDebut, creneauxMap, standsMap, animateurs);
    }

    /** Shared YAML loading for {@link #buildPlanningFromData} and the optional-section accessors below. */
    static Map<String, Object> readScenarioData(String scenarioPath) throws IOException {
        InputStream inputStream = ScenarioYamlReader.class.getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        return parserYaml(inputStream);
    }

    /**
     * Parses a scenario's raw YAML bytes, from the classpath ({@link #readScenarioData})
     * or from a user-uploaded file ({@link #buildFromScenarioText}).
     */
    // The one unchecked cast left in this class, and the only one that has no
    // alternative: this IS the entry point that turns SnakeYAML's untyped
    // Object into the scenario document every parseXxx below reads. The check
    // just above it is what makes it safe; every nested section goes through
    // YamlSections instead.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parserYaml(InputStream inputStream) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);
        // SnakeYAML caps aliases at 50 by default, as a billion-laughs guard.
        // Our own exporter used to emit one anchor and an alias per animateur
        // sharing the same list — 152 of them on a real edition — so the
        // application refused to re-import files it had itself produced, with
        // a message about aliases that says nothing to the operator who reads
        // it. The exporter no longer emits any (fbe062e6), but the files
        // people already downloaded are still on their disks.
        //
        // Raised rather than lifted: an alias per row leaves headroom for an
        // edition ten times the size of the real one, and the guard still
        // stops a file whose aliases nest into an expansion bomb.
        loaderOptions.setMaxAliasesForCollections(10_000);
        Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions));
        Object contenu = yaml.load(inputStream);
        // An empty file loads as null, and anything that is not a mapping (a bare
        // scalar, a list) would only surface much later as a ClassCastException
        // deep in a parseXxx: say what is actually wrong with the file instead.
        if (!(contenu instanceof Map)) {
            throw new IOException("Le fichier de scénario n'est pas un document YAML valide (mapping attendu)");
        }
        return (Map<String, Object>) contenu;
    }

    /**
     * Every optional top-level section a scenario file may pin, read in
     * <b>one</b> pass over the file.
     *
     * <p>There used to be one public accessor per section, each three lines
     * long and each re-reading and re-parsing the whole file. Importing a
     * scenario called five of them plus the planning build, so a single click
     * parsed {@code festival-realiste.yaml} seven times — and adding a section
     * meant adding a seventh near-identical method. One record, one read.</p>
     *
     * @param parametresLegaux    lets a scenario pin the legal parameters it was
     *                            authored and verified against instead of silently
     *                            depending on whatever the database currently holds.
     *                            Absent fields fall back to {@link ParametresLegaux}'s
     *                            own defaults, never to the live value, so the
     *                            scenario stays reproducible on its own
     * @param parametresDecoupage generation-time only (never a solver problem fact,
     *                            see its javadoc), hence read separately and applied
     *                            by the scenario-import endpoint alone — it has no
     *                            place on {@link PlanningEvenement}
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
            Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> edition,
            Optional<ContraintesScenario> contraintes) {
    }

    /**
     * The {@code contraintes:} section of a scenario, parsed.
     *
     * @param desactivees names of the constraints to switch off
     * @param poids       weight per constraint name; what is absent keeps the
     *                    deployment default
     */
    public record ContraintesScenario(Set<String> desactivees, Map<String, Integer> poids) {
    }

    /** Reads {@link ScenarioSections} out of an already-parsed scenario document. */
    private static ScenarioSections sectionsOf(Map<String, Object> scenarioData) {
        return new ScenarioSections(
                parseParametresLegaux(scenarioData),
                parseParametresDecoupage(scenarioData),
                parseParametresSolveur(scenarioData),
                parseDecoupageAuto(scenarioData),
                parseTypologies(scenarioData),
                parseTargetEdition(scenarioData),
                parseContraintes(scenarioData));
    }

    /**
     * The optional sections of a bundled scenario, <b>without</b> building its
     * planning: the pre-import step that names the target edition, and the
     * cheap read the tests use to assert what a file pins.
     */
    static ScenarioSections loadScenarioSections(String scenarioName) {
        try {
            return sectionsOf(readScenarioData(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * A bundled scenario, whole: its planning and its optional sections, from
     * a single parse. The named-file counterpart of
     * {@link #buildFromScenarioText}, so the two import paths differ
     * only in where the bytes come from.
     */
    static ScenarioImporte loadScenario(String scenarioName,
            Supplier<ParametresLegaux> parametresLegauxParDefaut) {
        Objects.requireNonNull(parametresLegauxParDefaut, "parametresLegauxParDefaut");
        try {
            Map<String, Object> scenarioData = readScenarioData(cheminScenario(scenarioName));
            return new ScenarioImporte(buildPlanningFromData(scenarioData, parametresLegauxParDefaut),
                    sectionsOf(scenarioData));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Optional {@code edition:} section of an uploaded scenario text, parsed
     * alone — the pre-import step the UI uses to NAME the target edition in
     * its confirmation dialog, before anything is written.
     */
    static Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> loadEditionScenarioText(
            String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        try {
            return parseTargetEdition(parserYaml(new java.io.ByteArrayInputStream(
                    yamlContent.getBytes(StandardCharsets.UTF_8))));
        } catch (RuntimeException | IOException e) {
            throw new BusinessError.Invalid("YAML invalide : " + messageOr(e), e);
        }
    }

    private static Optional<ParametresLegaux> parseParametresLegaux(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresLegaux");
        if (data == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        readInt(data, "dureeHebdomadaireMaxMinutes", parametres::setDureeHebdomadaireMaxMinutes);
        readInt(data, "pauseMinimaleEntreVacationsMinutes", parametres::setPauseMinimaleEntreVacationsMinutes);
        readInt(data, "reposQuotidienMinimalMinutes", parametres::setReposQuotidienMinimalMinutes);
        Object pauseSurPoste = data.get("pauseSurPoste");
        if (pauseSurPoste instanceof Boolean valeur) {
            parametres.setPauseSurPoste(valeur);
        } else if (pauseSurPoste != null) {
            throw new BusinessError.Invalid("parametresLegaux.pauseSurPoste must be true or false");
        }
        return Optional.of(parametres);
    }

    /** Applies the section's integer field to the setter, leaving the target's own default when absent. */
    private static void readInt(Map<String, Object> data, String key, IntConsumer setter) {
        Object valeur = data.get(key);
        if (valeur != null) {
            setter.accept(((Number) valeur).intValue());
        }
    }

    /** Same as {@link #readInt} for an {@code HH:MM:SS} field — see {@link #parseLocalTime(Object)}. */
    private static void readTime(Map<String, Object> data, String key, Consumer<LocalTime> setter) {
        Object valeur = data.get(key);
        if (valeur != null) {
            setter.accept(parseLocalTime(valeur));
        }
    }

    private static Optional<ParametresDecoupage> parseParametresDecoupage(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresDecoupage");
        if (data == null) {
            return Optional.empty();
        }
        ParametresDecoupage parametres = new ParametresDecoupage();
        readInt(data, "dureeVacationCibleMinutes", parametres::setDureeVacationCibleMinutes);
        readInt(data, "dureeVacationMinMinutes", parametres::setDureeVacationMinMinutes);
        readInt(data, "dureeVacationMaxMinutes", parametres::setDureeVacationMaxMinutes);
        readInt(data, "dureeChevauchementMinutes", parametres::setDureeChevauchementMinutes);
        readInt(data, "dureePauseRepasMinutes", parametres::setDureePauseRepasMinutes);
        readTime(data, "fenetreRepasMidiDebut", parametres::setFenetreRepasMidiDebut);
        readTime(data, "fenetreRepasMidiFin", parametres::setFenetreRepasMidiFin);
        readTime(data, "fenetreRepasSoirDebut", parametres::setFenetreRepasSoirDebut);
        readTime(data, "fenetreRepasSoirFin", parametres::setFenetreRepasSoirFin);
        if (data.get("strategieCouverturePendantPause") != null) {
            parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy
                    .valueOf((String) data.get("strategieCouverturePendantPause")));
        }
        readInt(data, "nombreFamillesDecalage", parametres::setNombreFamillesDecalage);
        readInt(data, "dureeDecalageMaxMinutes", parametres::setDureeDecalageMaxMinutes);
        if (data.get("modeGrille") != null) {
            parametres.setModeGrille(ModeGrilleCreneaux.valueOf((String) data.get("modeGrille")));
        }
        return Optional.of(parametres);
    }

    private static Optional<ParametresSolveur> parseParametresSolveur(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresSolveur");
        if (data == null || data.get("dureeResolutionSecondes") == null) {
            return Optional.empty();
        }
        return Optional.of(new ParametresSolveur(((Number) data.get("dureeResolutionSecondes")).intValue()));
    }

    /**
     * True when the scenario carries a top-level {@code decoupageAuto:}
     * section. Its historical {@code groupeSourceNom}/{@code groupeCibleNom}
     * fields are accepted and ignored (issue #172: the découpage replaces the
     * edition's créneaux in place, there are no groups to name anymore).
     */
    private static boolean parseDecoupageAuto(Map<String, Object> scenarioData) {
        // Key presence, not value truthiness: a bare `decoupageAuto:` (YAML
        // null) and the canonical `decoupageAuto: {}` both mean "slice on
        // import"; only an explicit `decoupageAuto: false` opts out.
        return scenarioData.containsKey("decoupageAuto")
                && !Boolean.FALSE.equals(scenarioData.get("decoupageAuto"));
    }

    private static Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> parseTargetEdition(
            Map<String, Object> scenarioData) {
        Object data = scenarioData.get("edition");
        if (data == null) {
            return Optional.empty();
        }
        // Pattern matching rather than a cast: a wildcard Map reads its own
        // values as Object, which is all this section needs, so there is
        // nothing left to suppress.
        if (!(data instanceof Map<?, ?> editionData)) {
            throw new BusinessError.Invalid(
                    "La section edition doit être un objet { id, nom? }, pas une valeur simple.");
        }
        String id = (String) editionData.get("id");
        if (id == null || id.isBlank()) {
            throw new BusinessError.Invalid("La section edition exige un champ id non vide.");
        }
        return Optional.of(new dev.sylvain.planning.scenario.dto.EditionCibleDto(
                id, (String) editionData.get("nom")));
    }

    /**
     * Reads {@code contraintes:} — {@code desactivees:} (a list of constraint
     * names) and {@code poids:} (name → weight).
     *
     * <p>An unknown name is refused rather than ignored: it means either a
     * typo or a file written against another version of the catalogue, and
     * silently dropping it would leave the operator convinced a rule was
     * switched off — or dosed — when it never was.</p>
     */
    private static Optional<ContraintesScenario> parseContraintes(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "contraintes");
        if (data == null) {
            return Optional.empty();
        }
        Set<String> desactivees = new LinkedHashSet<>();
        List<Object> nomsData = YamlSections.valeurs(data, "desactivees");
        if (nomsData != null) {
            for (Object nom : nomsData) {
                desactivees.add(requireKnownConstraint(String.valueOf(nom)));
            }
        }
        Map<String, Integer> poids = new LinkedHashMap<>();
        Map<String, Object> poidsData = YamlSections.objet(data, "poids");
        if (poidsData != null) {
            for (Map.Entry<String, Object> entry : poidsData.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                poids.put(requireKnownConstraint(entry.getKey()), ((Number) entry.getValue()).intValue());
            }
        }
        return Optional.of(new ContraintesScenario(desactivees, poids));
    }

    private static String requireKnownConstraint(String nom) {
        if (!ConstraintCatalog.PAR_NOM.containsKey(nom)) {
            throw new BusinessError.Invalid(
                    "La section contraintes cite « " + nom + " », qui n'est pas une contrainte du catalogue.");
        }
        return nom;
    }

    /**
     * Reads {@code contraintesAdHoc:}, resolving the file's own animateur,
     * stand and créneau ids against the sections already parsed.
     *
     * <p>The créneau reference goes through {@code creneauxParId}: a
     * scenario's créneaux carry a text id in the file and get a synthetic
     * numeric one here (then a fresh database one on import, remapped by
     * {@code ReferenceDataImportRepository}). Resolving it any later would
     * leave the constraint pointing at nothing.</p>
     */
    private static List<ContrainteAdHoc> parseContraintesAdHoc(Map<String, Object> scenarioData,
            ReferenceScenario reference) {
        List<Map<String, Object>> data = YamlSections.objets(scenarioData, "contraintesAdHoc");
        if (data == null) {
            return null;
        }
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        for (Map<String, Object> item : data) {
            String id = (String) item.get("id");
            if (id == null || id.isBlank()) {
                throw new BusinessError.Invalid("Chaque contrainte ad hoc doit porter un id non vide.");
            }
            ContrainteAdHoc contrainte = new ContrainteAdHoc(id,
                    TypeContrainteAdHoc.valueOf((String) item.get("type")));
            List<Object> animateurs = YamlSections.valeurs(item, "animateurs");
            if (animateurs != null) {
                for (Object animateurId : animateurs) {
                    contrainte.getAnimateursConcernes().add(reference.animateurs().stream()
                            .filter(animateur -> animateur.getId().equals(animateurId))
                            .findFirst()
                            .orElseThrow(() -> new BusinessError.Invalid("La contrainte ad hoc " + id
                                    + " vise l'animateur " + animateurId + ", absent du scénario.")));
                }
            }
            // String.valueOf, not a cast: the créneau ids of a hand-authored
            // file are text ("J1-MATIN"), those of an exported one are the
            // numbers SnakeYAML hands back as Integer.
            Object creneauRef = item.get("creneauId");
            if (creneauRef != null) {
                String creneauId = String.valueOf(creneauRef);
                Creneau creneau = reference.creneauxParId().get(creneauId);
                if (creneau == null) {
                    throw new BusinessError.Invalid("La contrainte ad hoc " + id + " vise le créneau "
                            + creneauId + ", absent du scénario.");
                }
                contrainte.setCreneau(creneau);
            }
            String standId = (String) item.get("standId");
            if (standId != null) {
                Stand stand = reference.standsById().get(standId);
                if (stand == null) {
                    throw new BusinessError.Invalid("La contrainte ad hoc " + id + " vise le stand "
                            + standId + ", absent du scénario.");
                }
                contrainte.setStand(stand);
            }
            contrainte.setRaison((String) item.get("raison"));
            contraintes.add(contrainte);
        }
        return contraintes;
    }

    private static List<TypologieItem> parseTypologies(Map<String, Object> scenarioData) {
        List<Map<String, Object>> data = YamlSections.objets(scenarioData, "typologies");
        if (data == null) {
            return List.of();
        }
        List<TypologieItem> typologies = new ArrayList<>();
        for (Map<String, Object> typologieData : data) {
            typologies.add(new TypologieItem(
                    (String) typologieData.get("id"), (String) typologieData.get("label"),
                    Boolean.TRUE.equals(typologieData.get("ninja"))));
        }
        return typologies;
    }

    /**
     * SnakeYAML's default (YAML 1.1) resolver reads an unquoted {@code HH:MM:SS}
     * scalar as sexagesimal ({@code H*3600 + M*60 + S}), not as a string — so a
     * scenario author who doesn't think to quote {@code fenetreRepasMidiDebut:
     * 12:00:00} hands this parser an {@link Integer} (43200), not
     * {@code "12:00:00"}. Both forms are accepted here since the sexagesimal
     * value happens to equal the second-of-day, same as {@link LocalTime}'s own
     * representation.
     */
    /**
     * A scenario's textual id, read whatever type the YAML resolver gave it.
     *
     * <p>These ids only tie the sections of one file together, and the
     * published schema declares them {@code string} — but nothing forces an
     * author to write {@code id: "1"} rather than {@code id: 1}, and the export
     * itself produced the latter for a long time. A direct cast to
     * {@code String} then throws a {@link ClassCastException} on an otherwise
     * valid file.</p>
     *
     * <p>Same stance as {@link #parseLocalTime}: accept what YAML resolved,
     * rather than require the author to know the quoting rules of YAML 1.1.</p>
     */
    private static String parseTextId(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalTime parseLocalTime(Object value) {
        if (value instanceof Number number) {
            return LocalTime.ofSecondOfDay(number.longValue());
        }
        return LocalTime.parse(value.toString());
    }
    /**
     * Reads a window's end hour, {@code null} (absent or explicitly empty)
     * meaning "until closing time" — see {@link FenetreHoraire}. A missing end
     * used to be a hard error; it is now the way to say "to whatever hour this
     * day closes at", which is what lets one rule cover days closing at 20:00
     * and days closing at midnight alike.
     */
    private static LocalTime parseTimeOrEndOfDay(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : LocalTime.parse(text);
    }

    /**
     * Reads the {@code horaires:} section of a stand — recurring rules, with the
     * day selector flattened onto the rule (see {@code ScenarioYamlWriter.horairesYaml}). An absent
     * {@code jours} reads as {@link TypeJoursHoraire#TOUS}, which is what makes
     * the common case a two-line entry.
     */
    private static List<HoraireStand> readHoraires(List<Map<String, Object>> horairesData) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (Map<String, Object> horaireData : horairesData) {
            HoraireStand horaire = new HoraireStand();
            String modeStr = (String) horaireData.get("mode");
            if (modeStr == null) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
            }
            horaire.setMode(ModeHoraire.valueOf(modeStr));
            String joursStr = (String) horaireData.getOrDefault("jours", TypeJoursHoraire.TOUS.name());
            horaire.setJours(TypeJoursHoraire.valueOf(joursStr));
            List<String> joursSemaine = YamlSections.chaines(horaireData, "joursSemaine");
            if (joursSemaine != null) {
                horaire.setJoursSemaine(joursSemaine.stream().map(DayOfWeek::valueOf)
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
            if (horaireData.get("dateDebut") != null) {
                horaire.setDateDebut(parseLocalDate(horaireData.get("dateDebut"), "stands.horaires.dateDebut"));
            }
            if (horaireData.get("dateFin") != null) {
                horaire.setDateFin(parseLocalDate(horaireData.get("dateFin"), "stands.horaires.dateFin"));
            }
            List<Object> dates = YamlSections.valeurs(horaireData, "dates");
            if (dates != null) {
                horaire.setDates(dates.stream().map(date -> parseLocalDate(date, "stands.horaires.dates"))
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
            List<Map<String, Object>> fenetresData = YamlSections.objets(horaireData, "fenetres");
            if (fenetresData == null || fenetresData.isEmpty()) {
                throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
            }
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (Map<String, Object> fenetreData : fenetresData) {
                Object heureDebut = fenetreData.get("heureDebut");
                if (heureDebut == null) {
                    throw new BusinessError.Invalid("Champ manquant: stands.horaires.fenetres.heureDebut");
                }
                Object effectif = fenetreData.get("effectif");
                if (effectif != null && !(effectif instanceof Number)) {
                    throw new BusinessError.Invalid(
                            "Champ invalide: stands.horaires.fenetres.effectif doit être un entier");
                }
                fenetres.add(new FenetreHoraire(LocalTime.parse(heureDebut.toString()),
                        parseTimeOrEndOfDay(fenetreData.get("heureFin")),
                        effectif == null ? null : ((Number) effectif).intValue()));
            }
            horaire.setFenetres(fenetres);
            horaire.setMotif((String) horaireData.get("motif"));
            horaires.add(horaire);
        }
        return horaires;
    }

    private static LocalDate parseLocalDate(Object value, String fieldName) {
        if (value == null) {
            throw new BusinessError.Invalid("Champ date manquant: " + fieldName);
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof CharSequence charSequence) {
            return LocalDate.parse(charSequence.toString());
        }
        throw new BusinessError.Invalid(
                "Type de date non supporte pour " + fieldName + ": " + value.getClass().getName());
    }
}
