package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningFestival> solverFactory;
    private final SolutionManager<PlanningFestival, ?> solutionManager;
    private final ReferenceDataService referenceDataService;
    private final FeasibilityAnalyzer feasibilityAnalyzer;
    private final long defaultSecondsLimit;
    private final long defaultUnimprovedSecondsLimit;
    private final ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "120") Long secondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "30") Long unimprovedSecondsLimit,
            ReferenceDataService referenceDataService,
            FeasibilityAnalyzer feasibilityAnalyzer,
            Config config) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        applyTermination(solverConfig, secondsLimit, unimprovedSecondsLimit);
        this.solverFactory = SolverFactory.create(solverConfig);
        this.solutionManager = SolutionManager.create(this.solverFactory);
        this.referenceDataService = referenceDataService;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.defaultSecondsLimit = secondsLimit;
        this.defaultUnimprovedSecondsLimit = unimprovedSecondsLimit;
        this.constraintWeightOverrides = buildConstraintWeightOverrides(config);
    }

    /**
     * Reads {@code planning.constraint-weights.<constraintName>} for every
     * constraint in {@link ConstraintCatalog} and turns whichever ones deviate
     * from 1 (the {@code ONE_HARD}/{@code ONE_MEDIUM}/{@code ONE_SOFT} literal
     * already baked into each constraint) into a {@link ConstraintWeightOverrides}
     * that Timefold applies at solve time — retuning a constraint's importance
     * is then a config change, not a code change. Computed once at startup since
     * {@code application.properties} does not change at runtime.
     */
    private static ConstraintWeightOverrides<HardMediumSoftScore> buildConstraintWeightOverrides(Config config) {
        Map<String, HardMediumSoftScore> overrides = new HashMap<>();
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            int weight = config
                    .getOptionalValue("planning.constraint-weights." + definition.name(), Integer.class)
                    .orElse(1);
            if (weight == 1) {
                continue;
            }
            HardMediumSoftScore score = switch (definition.niveau()) {
                case HARD -> HardMediumSoftScore.ofHard(weight);
                case MEDIUM -> HardMediumSoftScore.ofMedium(weight);
                case SOFT -> HardMediumSoftScore.ofSoft(weight);
            };
            overrides.put(definition.name(), score);
        }
        return overrides.isEmpty() ? ConstraintWeightOverrides.none() : ConstraintWeightOverrides.of(overrides);
    }

    private static void applyTermination(SolverConfig solverConfig, Long secondsLimit, Long unimprovedSecondsLimit) {
        if (solverConfig.getTerminationConfig() == null) {
            solverConfig.setTerminationConfig(new TerminationConfig());
        }
        TerminationConfig termination = solverConfig.getTerminationConfig();
        termination.setSecondsSpentLimit(secondsLimit);
        if (unimprovedSecondsLimit != null && unimprovedSecondsLimit > 0) {
            termination.setUnimprovedSecondsSpentLimit(unimprovedSecondsLimit);
        }
    }

    /** Classpath folder holding every selectable scenario file. */
    static final String SCENARIOS_DIR = "scenarios";

    /** Default scenario loaded when the caller does not pick one. */
    static final String DEFAULT_SCENARIO = "scenario-complet.yaml";

    public PlanningFestival construireExemple() {
        return construireExemple(DEFAULT_SCENARIO);
    }

    /**
     * Loads a named scenario from the {@link #SCENARIOS_DIR} folder. The name is
     * a bare file name (e.g. {@code scenario-complet.yaml}); any path component
     * is rejected so callers cannot escape the scenarios folder.
     */
    public PlanningFestival construireExemple(String scenarioName) {
        String name = (scenarioName == null || scenarioName.isBlank()) ? DEFAULT_SCENARIO : scenarioName;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Nom de scénario invalide: " + name);
        }
        try {
            return chargerScenarioYaml(SCENARIOS_DIR + "/" + name);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Small, self-contained scenario used as a fast nominal case (a handful of
     * postes) so the hard-constraint invariant can be checked in seconds. The
     * large {@code scenario-complet.yaml} is the complex performance target
     * solved by {@link #construireExemple()}.
     */
    public PlanningFestival construireExempleSimple() {
        try {
            return chargerScenarioYaml(SCENARIOS_DIR + "/scenario.yml");
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Builds a fresh problem from the persisted reference data: one
     * {@link PosteAffectation} per required seat ({@code stand.effectifMax}) on
     * every stand × timeslot, all seats unassigned. This mirrors the client-side
     * builder so a solve can be launched by sending only a request to the
     * server — the (potentially huge) planning is built here and never travels
     * to the browser and back, which is what makes very large scenarios
     * solvable at all (the JSON of such a planning exceeds the HTTP body limit).
     */
    public PlanningFestival construireDepuisReferenceData() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listStands();
        List<Creneau> creneaux = referenceDataService.listCreneauxGroupeActif();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningFestival festival = new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        festival.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        return festival;
    }

    /**
     * One {@link PosteAffectation} per required seat ({@code stand.effectifMin})
     * on every stand × timeslot where the stand is open (see
     * {@link Creneau#estStandOuvert(String)}), all seats unassigned. Package-
     * private and static so it can be unit-tested without a database.
     *
     * <p>Uses {@code effectifMin}, not {@code effectifMax}: {@code effectifMax}
     * is the upper capacity a stand could accept, not the number of seats that
     * must be staffed (that's exactly what {@code posteDoitEtrePourvu} makes a
     * hard requirement for every generated poste). Confirmed against
     * scenario-complet.yaml, whose own hand-authored poste list has 2088
     * entries — precisely {@code sum(effectifMin) * creneaux} (58 * 36); the
     * effectifMax sum instead gives 2736, 31% more mandatory seats than the
     * scenario intends. Building a problem from reference data with effectifMax
     * silently inflated every solve started from "Lancer le solveur" into a
     * substantially bigger, harder problem than the one actually staffed
     * for — the real reason it kept stalling short of hard-feasibility.</p>
     */
    static List<PosteAffectation> construirePostes(List<Stand> stands, List<Creneau> creneaux) {
        List<PosteAffectation> postes = new ArrayList<>();
        int counter = 0;
        for (Stand stand : stands) {
            int seats = Math.max(1, stand.getEffectifMin());
            for (Creneau creneau : creneaux) {
                if (!creneau.estStandOuvert(stand.getId())) {
                    continue;
                }
                for (int seat = 0; seat < seats; seat++) {
                    postes.add(new PosteAffectation("poste-" + (counter++), stand, creneau));
                }
            }
        }
        return postes;
    }

    /**
     * Serializes the current reference data (animateurs, stands, créneaux) plus
     * the seat list it implies (mirroring {@link #construireDepuisReferenceData})
     * into the same YAML shape read by {@link #chargerScenarioYaml}, so the
     * result can be dropped into the {@link #SCENARIOS_DIR} folder and reloaded
     * as-is.
     */
    public String exporterScenarioYaml() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listStands();
        List<Creneau> creneaux = referenceDataService.listCreneauxGroupeActif();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence à exporter. Créez des stands, des animateurs et des créneaux d'abord.");
        }
        return construireScenarioYaml(animateurs, stands, creneaux, construirePostes(stands, creneaux));
    }

    /**
     * Builds the YAML text from already-fetched data. Package-private and
     * static, like {@link #construirePostes}, so it can be unit-tested without
     * a database.
     */
    static String construireScenarioYaml(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux,
            List<PosteAffectation> postes) {
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);

        Map<String, Object> festival = new LinkedHashMap<>();
        festival.put("dateDebut", asString(dateDebut));

        List<Map<String, Object>> creneauxYaml = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", creneau.getId());
            item.put("jour", creneau.getJour());
            item.put("date", asString(creneau.getDate()));
            item.put("heureDebut", asString(creneau.getHeureDebut()));
            item.put("heureFin", asString(creneau.getHeureFin()));
            creneauxYaml.add(item);
        }

        List<Map<String, Object>> standsYaml = new ArrayList<>();
        for (Stand stand : stands) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", stand.getId());
            item.put("nom", stand.getNom());
            item.put("typologiesProposees", stand.getTypologiesProposees().stream()
                    .map(Enum::name)
                    .collect(Collectors.toList()));
            item.put("effectifMin", stand.getEffectifMin());
            item.put("effectifMax", stand.getEffectifMax());
            item.put("reserveMajeurs", stand.isReserveMajeurs());
            item.put("premium", stand.isPremium());
            standsYaml.add(item);
        }

        List<Map<String, Object>> animateursYaml = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", animateur.getId());
            item.put("prenom", animateur.getPrenom());
            item.put("nom", animateur.getNom());
            item.put("dateNaissance", asString(animateur.getDateNaissance()));
            item.put("manager", animateur.isManager());
            Map<String, String> competences = new LinkedHashMap<>();
            if (animateur.getCompetences() != null) {
                animateur.getCompetences()
                        .forEach((typologie, niveau) -> competences.put(typologie.name(), niveau.name()));
            }
            item.put("competences", competences);
            List<String> joursIndisponibles = animateur.getJoursIndisponibles() == null
                    ? List.of()
                    : animateur.getJoursIndisponibles().stream()
                            .sorted()
                            .map(PlanningService::asString)
                            .collect(Collectors.toList());
            item.put("joursIndisponibles", joursIndisponibles);
            animateursYaml.add(item);
        }

        List<Map<String, Object>> postesYaml = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", poste.getId());
            item.put("standId", poste.getStand().getId());
            item.put("creneauId", poste.getCreneau().getId());
            item.put("animateurId", null);
            postesYaml.add(item);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("festival", festival);
        root.put("creneaux", creneauxYaml);
        root.put("stands", standsYaml);
        root.put("animateurs", animateursYaml);
        root.put("postes", postesYaml);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Lists every {@code .yaml}/{@code .yml} scenario available in the
     * {@link #SCENARIOS_DIR} classpath folder, sorted alphabetically. Drop a new
     * file in that folder and it shows up here (and in the UI dropdown) with no
     * code change. Works both in dev (folder on disk) and from a packaged jar.
     */
    public List<String> listerScenarios() {
        try {
            java.net.URL dirUrl = getClass().getClassLoader().getResource(SCENARIOS_DIR);
            if (dirUrl == null) {
                return List.of();
            }
            Set<String> names = new java.util.TreeSet<>();
            if ("file".equals(dirUrl.getProtocol())) {
                java.nio.file.Path dir = java.nio.file.Paths.get(dirUrl.toURI());
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                    files.filter(java.nio.file.Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .filter(PlanningService::estFichierScenario)
                            .forEach(names::add);
                }
            } else if ("jar".equals(dirUrl.getProtocol())) {
                java.net.JarURLConnection conn = (java.net.JarURLConnection) dirUrl.openConnection();
                String prefix = SCENARIOS_DIR + "/";
                try (java.util.jar.JarFile jar = conn.getJarFile()) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(prefix) && !entry.endsWith("/")) {
                            String fileName = entry.substring(prefix.length());
                            if (!fileName.contains("/") && estFichierScenario(fileName)) {
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

    private static boolean estFichierScenario(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    @SuppressWarnings("unchecked")
    private PlanningFestival chargerScenarioYaml(String scenarioPath) throws IOException {
        Map<String, Object> scenarioData = lireDonneesScenario(scenarioPath);

        // Charger les creneaux : le fichier YAML porte un id texte historique
        // (utilisé seulement pour relier postes/creneaux entre eux ci-dessous),
        // remplacé ici par un id numérique synthétique ; jour est recalculé
        // (voir Creneau.assignerJours), la valeur du fichier est ignorée.
        Map<String, Creneau> creneauxMap = new HashMap<>();
        long compteurCreneauId = 1;
        List<Map<String, Object>> creneauxList = (List<Map<String, Object>>) scenarioData.get("creneaux");
        for (Map<String, Object> creneauData : creneauxList) {
            String id = (String) creneauData.get("id");
            String heureDebutStr = (String) creneauData.get("heureDebut");
            String heureFinStr = (String) creneauData.get("heureFin");

            LocalDate date = parseLocalDate(creneauData.get("date"), "creneaux.date");
            LocalTime heureDebut = LocalTime.parse(heureDebutStr);
            LocalTime heureFin = LocalTime.parse(heureFinStr);

            Creneau creneau = new Creneau(compteurCreneauId++, 0, date, heureDebut, heureFin);
            creneauxMap.put(id, creneau);
        }
        Creneau.assignerJours(creneauxMap.values());

        // Charger les emplacements
        Map<String, Emplacement> emplacementsMap = new HashMap<>();
        List<Map<String, Object>> emplacementsList = (List<Map<String, Object>>) scenarioData.get("emplacements");
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

        // Charger les stands
        Map<String, Stand> standsMap = new HashMap<>();
        List<Map<String, Object>> standsList = (List<Map<String, Object>>) scenarioData.get("stands");
        for (Map<String, Object> standData : standsList) {
            String id = (String) standData.get("id");
            String nom = (String) standData.get("nom");
            List<String> typologiesStr = (List<String>) standData.get("typologiesProposees");
            Set<TypologieJeu> typologies = typologiesStr.stream()
                    .map(TypologieJeu::valueOf)
                    .collect(Collectors.toSet());
            int effectifMin = ((Number) standData.get("effectifMin")).intValue();
            int effectifMax = ((Number) standData.get("effectifMax")).intValue();
            boolean reserveMajeurs = (Boolean) standData.getOrDefault("reserveMajeurs", false);
            boolean premium = (Boolean) standData.getOrDefault("premium", false);

            Stand stand = new Stand(id, nom, typologies, effectifMin, effectifMax, reserveMajeurs, premium);
            String emplacementId = (String) standData.get("emplacementId");
            if (emplacementId != null) {
                stand.setEmplacement(emplacementsMap.get(emplacementId));
            }
            standsMap.put(id, stand);
        }
        
        // Charger les animateurs
        List<Animateur> animateurs = new ArrayList<>();
        List<Map<String, Object>> animateursList = (List<Map<String, Object>>) scenarioData.get("animateurs");
        for (Map<String, Object> animateurData : animateursList) {
            String id = (String) animateurData.get("id");
            String prenom = (String) animateurData.get("prenom");
            String nom = (String) animateurData.get("nom");
            LocalDate dateNaissance = parseLocalDate(animateurData.get("dateNaissance"), "animateurs.dateNaissance");
            boolean manager = Boolean.TRUE.equals(animateurData.get("manager"));

            Animateur animateur = new Animateur(id, prenom, nom, dateNaissance, manager);
            
            // Charger les compétences
            Map<String, String> competencesData = (Map<String, String>) animateurData.get("competences");
            Map<TypologieJeu, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(TypologieJeu.valueOf(entry.getKey()), NiveauCompetence.valueOf(entry.getValue()));
            }
            animateur.setCompetences(competences);
            
            // Charger les jours d'indisponibilité (opt-out: available by default)
            List<Object> joursOffData = (List<Object>) animateurData.get("joursIndisponibles");
            Set<LocalDate> joursIndisponibles = joursOffData == null
                    ? new java.util.HashSet<>()
                    : joursOffData.stream()
                            .map(value -> parseLocalDate(value, "animateurs.joursIndisponibles"))
                            .collect(Collectors.toCollection(java.util.HashSet::new));
            animateur.setJoursIndisponibles(joursIndisponibles);

            animateurs.add(animateur);
        }
        
        // Charger les postes
        List<PosteAffectation> postes = new ArrayList<>();
        List<Map<String, Object>> postesList = (List<Map<String, Object>>) scenarioData.get("postes");
        for (Map<String, Object> posteData : postesList) {
            String id = (String) posteData.get("id");
            String standId = (String) posteData.get("standId");
            String creneauId = (String) posteData.get("creneauId");
            
            Stand stand = standsMap.get(standId);
            Creneau creneau = creneauxMap.get(creneauId);
            
            PosteAffectation poste = new PosteAffectation(id, stand, creneau);
            postes.add(poste);
        }
        
        LocalDate dateDebut = parseLocalDate(
            ((Map<String, Object>) scenarioData.get("festival")).get("dateDebut"),
            "festival.dateDebut");

        PlanningFestival festival = new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        festival.setParametresLegaux(List.of(
                parseParametresLegaux(scenarioData).orElseGet(referenceDataService::getParametresLegaux)));
        return festival;
    }

    /** Shared YAML loading for {@link #chargerScenarioYaml} and the optional-section accessors below. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> lireDonneesScenario(String scenarioPath) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions));
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        return yaml.load(inputStream);
    }

    /**
     * Reads the optional top-level {@code parametresLegaux:} section of a
     * scenario file, if present — lets a scenario pin the legal parameters it
     * was authored/verified against (with a YAML comment explaining why),
     * instead of silently depending on whatever is currently configured in the
     * database. Absent fields within the section fall back to
     * {@link ParametresLegaux}'s own defaults, not to the live database value,
     * so the scenario stays fully reproducible on its own.
     */
    public Optional<ParametresLegaux> chargerParametresLegauxScenario(String scenarioName) {
        try {
            return parseParametresLegaux(lireDonneesScenario(SCENARIOS_DIR + "/" + scenarioName));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Reads the optional top-level {@code parametresDecoupage:} section of a
     * scenario file, if present. {@link ParametresDecoupage} is
     * generation-time-only (never a solver problem fact, see its javadoc), so
     * unlike {@link #chargerParametresLegauxScenario} this has no place on
     * {@link PlanningFestival} — it is read separately and applied by the
     * scenario-import endpoint only.
     */
    public Optional<ParametresDecoupage> chargerParametresDecoupageScenario(String scenarioName) {
        try {
            return parseParametresDecoupage(lireDonneesScenario(SCENARIOS_DIR + "/" + scenarioName));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<ParametresLegaux> parseParametresLegaux(Map<String, Object> scenarioData) {
        Map<String, Object> data = (Map<String, Object>) scenarioData.get("parametresLegaux");
        if (data == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        if (data.get("dureeHebdomadaireMaxMinutes") != null) {
            parametres.setDureeHebdomadaireMaxMinutes(((Number) data.get("dureeHebdomadaireMaxMinutes")).intValue());
        }
        if (data.get("pauseMinimaleEntreVacationsMinutes") != null) {
            parametres.setPauseMinimaleEntreVacationsMinutes(
                    ((Number) data.get("pauseMinimaleEntreVacationsMinutes")).intValue());
        }
        if (data.get("reposQuotidienMinimalMinutes") != null) {
            parametres.setReposQuotidienMinimalMinutes(((Number) data.get("reposQuotidienMinimalMinutes")).intValue());
        }
        return Optional.of(parametres);
    }

    @SuppressWarnings("unchecked")
    private static Optional<ParametresDecoupage> parseParametresDecoupage(Map<String, Object> scenarioData) {
        Map<String, Object> data = (Map<String, Object>) scenarioData.get("parametresDecoupage");
        if (data == null) {
            return Optional.empty();
        }
        ParametresDecoupage parametres = new ParametresDecoupage();
        if (data.get("dureeVacationCibleMinutes") != null) {
            parametres.setDureeVacationCibleMinutes(((Number) data.get("dureeVacationCibleMinutes")).intValue());
        }
        if (data.get("dureeVacationMinMinutes") != null) {
            parametres.setDureeVacationMinMinutes(((Number) data.get("dureeVacationMinMinutes")).intValue());
        }
        if (data.get("dureeVacationMaxMinutes") != null) {
            parametres.setDureeVacationMaxMinutes(((Number) data.get("dureeVacationMaxMinutes")).intValue());
        }
        if (data.get("dureeChevauchementMinutes") != null) {
            parametres.setDureeChevauchementMinutes(((Number) data.get("dureeChevauchementMinutes")).intValue());
        }
        if (data.get("dureePauseRepasMinutes") != null) {
            parametres.setDureePauseRepasMinutes(((Number) data.get("dureePauseRepasMinutes")).intValue());
        }
        if (data.get("fenetreRepasMidiDebut") != null) {
            parametres.setFenetreRepasMidiDebut(LocalTime.parse((String) data.get("fenetreRepasMidiDebut")));
        }
        if (data.get("fenetreRepasMidiFin") != null) {
            parametres.setFenetreRepasMidiFin(LocalTime.parse((String) data.get("fenetreRepasMidiFin")));
        }
        if (data.get("fenetreRepasSoirDebut") != null) {
            parametres.setFenetreRepasSoirDebut(LocalTime.parse((String) data.get("fenetreRepasSoirDebut")));
        }
        if (data.get("fenetreRepasSoirFin") != null) {
            parametres.setFenetreRepasSoirFin(LocalTime.parse((String) data.get("fenetreRepasSoirFin")));
        }
        if (data.get("strategieCouverturePendantPause") != null) {
            parametres.setStrategieCouverturePendantPause(ParametresDecoupage.StrategieCouverturePendantPause
                    .valueOf((String) data.get("strategieCouverturePendantPause")));
        }
        return Optional.of(parametres);
    }

    public PlanningFestival resoudre(PlanningFestival problem) {
        return resoudre(problem, null);
    }

    public PlanningFestival resoudre(PlanningFestival problem, Long secondsLimitOverride) {
        prepareProblem(problem);
        Solver<PlanningFestival> solver = resolveSolverFactory(secondsLimitOverride).buildSolver();
        return solver.solve(problem);
    }

    /**
     * Solves until the plan becomes hard-feasible (or {@code secondsLimitSecurite}
     * elapses, whichever comes first), instead of spending a full time budget on
     * medium/soft polishing. Large scenarios (e.g. {@code scenario-complet.yaml},
     * ~2000 postes) reach hard-feasibility in well under a minute but keep
     * improving medium/soft for the rest of a production-sized budget; a caller
     * that only cares about the hard score (e.g. a regression test) would
     * otherwise wait out that whole budget for nothing.
     */
    public PlanningFestival resoudreJusquaFaisabilite(PlanningFestival problem, long secondsLimitSecurite) {
        prepareProblem(problem);
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(secondsLimitSecurite);
        termination.setBestScoreFeasible(true);
        solverConfig.setTerminationConfig(termination);
        Solver<PlanningFestival> solver = SolverFactory.<PlanningFestival>create(solverConfig).buildSolver();
        return solver.solve(problem);
    }

    private void prepareProblem(PlanningFestival problem) {
        if (problem.getContraintesAdHoc() == null || problem.getContraintesAdHoc().isEmpty()) {
            problem.setContraintesAdHoc(referenceDataService.snapshotContraintes());
        }
        if (problem.getParametresLegaux() == null || problem.getParametresLegaux().isEmpty()) {
            problem.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        }
        if (problem.getConstraintsDesactivees() == null || problem.getConstraintsDesactivees().isEmpty()) {
            problem.setConstraintsDesactivees(referenceDataService.getContraintesDesactivees().stream()
                    .map(ConstraintToggle::new)
                    .toList());
        }
        // Never sent by a caller (the field is @JsonIgnore-d on PlanningFestival),
        // so this always overwrites the ConstraintWeightOverrides.none() default.
        problem.setPonderationsContraintes(constraintWeightOverrides);
    }

    /**
     * Solve and return a structured explanation of every constraint that
     * contributed to the final score — including hard/medium violations that
     * remain in the best solution found. Useful for diagnosing why the solver
     * did not converge to zero hard.
     */
    public PlanningDiagnostic analyser(PlanningFestival problem, Long secondsLimitOverride) {
        PlanningFestival solved = resoudre(problem, secondsLimitOverride);
        return diagnostiquer(solved);
    }

    /**
     * Builds the diagnostic of an already-solved planning, without solving it
     * again. Used right after {@link #resoudre} so a solve is never run twice
     * just to produce its own analysis.
     */
    public PlanningDiagnostic diagnostiquer(PlanningFestival solved) {
        ScoreAnalysis<?> analysis = solutionManager.analyze(solved);
        List<ConstraintDiagnostic> constraintDiagnostics = new ArrayList<>();
        for (ConstraintAnalysis<?> ca : analysis.constraintAnalyses()) {
            constraintDiagnostics.add(new ConstraintDiagnostic(
                    ca.constraintRef().constraintName(),
                    String.valueOf(ca.score()),
                    ca.matchCount()));
        }
        constraintDiagnostics.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));
        int unassigned = (int) solved.getPostes().stream()
                .filter(p -> p.getAnimateur() == null)
                .count();
        FeasibilityAnalyzer.FeasibilityReport faisabilite = feasibilityAnalyzer.analyser(
                solved.getAnimateurs(), distinctStands(solved), distinctCreneaux(solved));
        return new PlanningDiagnostic(String.valueOf(solved.getScore()), unassigned, constraintDiagnostics,
                faisabilite);
    }

    private static List<Stand> distinctStands(PlanningFestival solved) {
        Map<String, Stand> byId = new java.util.LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getStand().getId(), poste.getStand());
        }
        return new ArrayList<>(byId.values());
    }

    private static List<Creneau> distinctCreneaux(PlanningFestival solved) {
        Map<Long, Creneau> byId = new java.util.LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
        }
        return new ArrayList<>(byId.values());
    }

    private SolverFactory<PlanningFestival> resolveSolverFactory(Long secondsLimitOverride) {
        if (secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit)) {
            return solverFactory;
        }
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        // An explicit override means the caller wants exactly that many seconds;
        // the ambient unimproved-time bailout (e.g. the test profile's 2s, far
        // too tight for a large scenario solved with a bigger override) must not
        // silently cut it short, so it is disabled rather than reused here.
        applyTermination(solverConfig, secondsLimitOverride, 0L);
        return SolverFactory.create(solverConfig);
    }

    public record ConstraintDiagnostic(String name, String score, int matchCount) {
    }

    /**
     * Business-facing result of a solve/analyze: score, unfilled seats and
     * per-constraint breakdown. Deliberately excludes the {@link PlanningFestival}
     * itself (animateurs/stands/créneaux/postes) — that payload can reach several
     * dozens of MB and is consulted through the dedicated screens instead, which
     * load it from {@code /api/planning/persisted}.
     */
    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            FeasibilityAnalyzer.FeasibilityReport faisabilite) {
    }

    private LocalDate parseLocalDate(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("Champ date manquant: " + fieldName);
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
        throw new IllegalArgumentException(
                "Type de date non supporte pour " + fieldName + ": " + value.getClass().getName());
    }
}
