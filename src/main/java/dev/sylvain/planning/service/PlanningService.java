package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContactLegal;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutAnimateur;
import dev.sylvain.planning.domain.TypologieJeu;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningFestival> solverFactory;
    private final SolutionManager<PlanningFestival, ?> solutionManager;
    private final ReferenceDataService referenceDataService;
    private final long defaultSecondsLimit;
    private final long defaultUnimprovedSecondsLimit;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "120") Long secondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "30") Long unimprovedSecondsLimit,
            ReferenceDataService referenceDataService) {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        applyTermination(solverConfig, secondsLimit, unimprovedSecondsLimit);
        this.solverFactory = SolverFactory.create(solverConfig);
        this.solutionManager = SolutionManager.create(this.solverFactory);
        this.referenceDataService = referenceDataService;
        this.defaultSecondsLimit = secondsLimit;
        this.defaultUnimprovedSecondsLimit = unimprovedSecondsLimit;
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

    public PlanningFestival construireExemple() {
        try {
            return chargerScenarioYaml("scenario-complet.yaml");
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    @SuppressWarnings("unchecked")
    private PlanningFestival chargerScenarioYaml(String scenarioPath) throws IOException {
        Yaml yaml = new Yaml();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        
        Map<String, Object> scenarioData = yaml.load(inputStream);
        
        // Charger les creneaux
        Map<String, Creneau> creneauxMap = new HashMap<>();
        List<Map<String, Object>> creneauxList = (List<Map<String, Object>>) scenarioData.get("creneaux");
        for (Map<String, Object> creneauData : creneauxList) {
            String id = (String) creneauData.get("id");
            int jour = ((Number) creneauData.get("jour")).intValue();
            String heureDebutStr = (String) creneauData.get("heureDebut");
            String heureFinStr = (String) creneauData.get("heureFin");
            
            LocalDate date = parseLocalDate(creneauData.get("date"), "creneaux.date");
            LocalTime heureDebut = LocalTime.parse(heureDebutStr);
            LocalTime heureFin = LocalTime.parse(heureFinStr);
            
            Creneau creneau = new Creneau(id, jour, date, heureDebut, heureFin);
            creneauxMap.put(id, creneau);
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
            
            Stand stand = new Stand(id, nom, typologies, effectifMin, effectifMax, reserveMajeurs);
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
            String statutStr = (String) animateurData.get("statut");
            StatutAnimateur statut = StatutAnimateur.valueOf(statutStr);
            
            Animateur animateur = new Animateur(id, prenom, nom, dateNaissance, statut);
            
            // Charger les compétences
            Map<String, String> competencesData = (Map<String, String>) animateurData.get("competences");
            Map<TypologieJeu, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(TypologieJeu.valueOf(entry.getKey()), NiveauCompetence.valueOf(entry.getValue()));
            }
            animateur.setCompetences(competences);
            
            // Charger les disponibilités
            List<String> dispoIds = (List<String>) animateurData.get("disponibilites");
            Set<Creneau> disponibilites = dispoIds.stream()
                    .map(creneauxMap::get)
                    .collect(Collectors.toSet());
            animateur.setDisponibilites(disponibilites);
            
            // Charger le contact légal si présent
            Map<String, String> contactData = (Map<String, String>) animateurData.get("contactLegal");
            if (contactData != null) {
                String nomContact = contactData.get("nomContact");
                String telephone = contactData.get("telephone");
                String email = contactData.get("email");
                animateur.setContactLegal(new ContactLegal(nomContact, telephone, email));
            }
            
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
        
        return new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
    }

    public PlanningFestival resoudre(PlanningFestival problem) {
        return resoudre(problem, null);
    }

    public PlanningFestival resoudre(PlanningFestival problem, Long secondsLimitOverride) {
        if (problem.getContraintesAdHoc() == null || problem.getContraintesAdHoc().isEmpty()) {
            problem.setContraintesAdHoc(referenceDataService.snapshotContraintes());
        }
        Solver<PlanningFestival> solver = resolveSolverFactory(secondsLimitOverride).buildSolver();
        return solver.solve(problem);
    }

    /**
     * Solve and return a structured explanation of every constraint that
     * contributed to the final score — including hard/medium violations that
     * remain in the best solution found. Useful for diagnosing why the solver
     * did not converge to zero hard.
     */
    public PlanningDiagnostic analyser(PlanningFestival problem, Long secondsLimitOverride) {
        PlanningFestival solved = resoudre(problem, secondsLimitOverride);
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
        return new PlanningDiagnostic(String.valueOf(solved.getScore()), unassigned, constraintDiagnostics, solved);
    }

    private SolverFactory<PlanningFestival> resolveSolverFactory(Long secondsLimitOverride) {
        if (secondsLimitOverride == null || secondsLimitOverride.equals(defaultSecondsLimit)) {
            return solverFactory;
        }
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        applyTermination(solverConfig, secondsLimitOverride, defaultUnimprovedSecondsLimit);
        return SolverFactory.create(solverConfig);
    }

    public record ConstraintDiagnostic(String name, String score, int matchCount) {
    }

    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            PlanningFestival planning) {
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
