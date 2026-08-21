package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.score.stream.DefaultConstraintJustification;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.scenario.YamlSections;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.constraints.AdHocConstraints;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

@ApplicationScoped
public class PlanningService {

    private final SolverFactory<PlanningFestival> solverFactory;
    private final SolutionManager<PlanningFestival, ?> solutionManager;
    private final Referentiel referenceDataService;
    private final FeasibilityAnalyzer feasibilityAnalyzer;
    private final long defaultSecondsLimit;
    private final ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides;
    /** Cap fed to {@code limiterEmplacementsParJour} through {@link ParametresQualite}. */
    private final int maxEmplacementsParJour;

    /**
     * Field-injected rather than a constructor parameter: the plain (non-CDI)
     * tests build this service with {@code new} and never exercise the locks,
     * so it stays null there — {@link #appliquerVerrouillages} guards on it.
     */
    @Inject
    PlanningPersistenceService planningPersistenceService;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "120") Long secondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "30") Long unimprovedSecondsLimit,
            @ConfigProperty(name = "planning.contraintes.max-emplacements-par-jour",
                    defaultValue = "" + ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT) Integer maxEmplacementsParJour,
            Referentiel referenceDataService,
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
        this.maxEmplacementsParJour = maxEmplacementsParJour;
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

    /**
     * Two ways for a solve to end, whichever comes first: the time budget is
     * exhausted, or the planning is <b>already feasible</b> and has stopped
     * improving for {@code unimprovedSecondsLimit}.
     *
     * <p>The second half is deliberately gated on feasibility
     * ({@link TerminationConfig#withBestScoreFeasible}, AND-ed with the plateau
     * limit). A bare unimproved-time limit — what this used to configure, and
     * why it ended up disabled altogether — bails out of local search on a
     * <em>hard-constraint</em> plateau too: the solver gave up minutes early on
     * a planning that still had unfilled seats, exactly the case where it needs
     * the rest of its budget. Gated this way, the bailout can only ever cut
     * time that was being spent polishing medium/soft score on an already
     * workable planning, never time spent reaching hard-feasibility.</p>
     *
     * <p>{@code unimprovedSecondsLimit <= 0} disables the plateau branch and
     * leaves the plain time budget.</p>
     */
    private static void applyTermination(SolverConfig solverConfig, Long secondsLimit, Long unimprovedSecondsLimit) {
        if (solverConfig.getTerminationConfig() == null) {
            solverConfig.setTerminationConfig(new TerminationConfig());
        }
        TerminationConfig termination = solverConfig.getTerminationConfig();
        termination.setSecondsSpentLimit(secondsLimit);
        if (unimprovedSecondsLimit != null && unimprovedSecondsLimit > 0) {
            termination.setTerminationConfigList(List.of(new TerminationConfig()
                    .withBestScoreFeasible(true)
                    .withUnimprovedSecondsSpentLimit(unimprovedSecondsLimit)
                    .withTerminationCompositionStyle(TerminationCompositionStyle.AND)));
        }
    }

    /** Classpath folder holding every selectable scenario file. */
    static final String SCENARIOS_DIR = "scenarios";

    /** Default scenario loaded when the caller does not pick one. */
    static final String DEFAULT_SCENARIO = "scenario-complet.yaml";

    /**
     * Names of every constraint enforced at {@link ConstraintCatalog.Niveau#HARD}.
     * {@link #diagnostiquer} only builds per-match {@code violations} for these:
     * a soft or medium constraint like {@code souhaitsIncompatibles} can have
     * thousands of matches, which would bloat the diagnostic payload for a
     * detail nobody blocking on a failed solve needs to see.
     */
    private static final Set<String> HARD_CONSTRAINT_NAMES = ConstraintCatalog.definitions().stream()
            .filter(definition -> definition.niveau() == ConstraintCatalog.Niveau.HARD)
            .map(ConstraintCatalog.ConstraintDefinition::name)
            .collect(Collectors.toUnmodifiableSet());

    /** Caps the per-constraint violation list: a UI detail view, not a full dump. */
    private static final int MAX_VIOLATIONS_PAR_CONTRAINTE = 100;

    public PlanningFestival construireExemple() {
        return construireExemple(DEFAULT_SCENARIO);
    }

    /**
     * Loads a named scenario from the {@link #SCENARIOS_DIR} folder. The name is
     * a bare file name (e.g. {@code scenario-complet.yaml}); any path component
     * is rejected so callers cannot escape the scenarios folder.
     */
    public PlanningFestival construireExemple(String scenarioName) {
        try {
            return construirePlanningDepuisDonnees(lireDonneesScenario(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Resolves a scenario name to its classpath path. Shared by every scenario
     * accessor so they all apply the same two rules: an absent or blank name
     * falls back to {@link #DEFAULT_SCENARIO} — concatenating it raw would ask
     * for {@code scenarios/null}, or worse for {@code scenarios/} itself, whose
     * directory listing parses as a plain YAML string and blows up later as a
     * {@code ClassCastException} — and any path component is rejected so
     * callers cannot escape the scenarios folder.
     */
    private static String cheminScenario(String scenarioName) {
        String name = (scenarioName == null || scenarioName.isBlank()) ? DEFAULT_SCENARIO : scenarioName;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ErreurMetier.Invalide("Nom de scénario invalide: " + name);
        }
        return SCENARIOS_DIR + "/" + name;
    }

    /**
     * Small, self-contained scenario used as a fast nominal case (a handful of
     * postes) so the hard-constraint invariant can be checked in seconds. The
     * large {@code scenario-complet.yaml} is the complex performance target
     * solved by {@link #construireExemple()}.
     */
    public PlanningFestival construireExempleSimple() {
        try {
            return construirePlanningDepuisDonnees(
                    lireDonneesScenario(SCENARIOS_DIR + "/scenario.yml"));
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
        // Resolved stands: construirePostes asks each créneau which parts of it
        // a stand is open for, so the recurring horaires have to be expanded
        // first.
        return construireDepuisReferenceData(
                referenceDataService.listAnimateurs(),
                referenceDataService.listStandsResolus(),
                referenceDataService.listCreneaux());
    }

    /**
     * Same problem, built from lists the caller provides instead of reading the
     * referential — what {@link WhatIfService} uses to evaluate a variant
     * (animateurs added or removed, a stand closed) without writing anything.
     * Everything else still comes from the referential: locks, ad hoc
     * constraints and legal parameters are not what a simulation varies.
     */
    public PlanningFestival construireDepuisReferenceData(List<Animateur> animateurs, List<Stand> stands,
            List<Creneau> creneaux) {
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        appliquerVerrouillages(postes, animateurs, verrouillages);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningFestival festival = new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        festival.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        festival.setVerrouillages(verrouillages);
        return festival;
    }

    /** How much of an incremental problem is frozen versus re-opened (issue #86). */
    public record StatistiquesIncremental(
            int postesTotal,
            int postesFiges,
            int postesLiberes,
            int postesLiberesManuellement,
            int postesNouveaux) {
    }

    /**
     * An incremental re-solve problem: the planning to hand to the solver, how
     * much of it is frozen, and the persisted assignments it was seeded from —
     * kept so the caller can diff the result against them.
     */
    public record ProblemeIncremental(PlanningFestival planning, StatistiquesIncremental statistiques,
            Map<String, List<String>> affectationsPrecedentes) {
    }

    /**
     * Builds an incremental re-solve problem (issue #86): the same seats as
     * {@link #construireDepuisReferenceData()}, but seeded from the persisted
     * plan and <b>pinned wherever that plan is still valid</b>, so a short
     * solve only has to fill what a late change actually opened — a fresh
     * unavailability, a new stand, seats the previous solve left empty, plus
     * whatever {@code perimetre} re-opens on purpose.
     *
     * <p>Seats are matched positionally on stand × créneau, the same convention
     * as the locks of issue #87 (see
     * {@link PlanningPersistenceService#chargerAnimateursParStandCreneau()}):
     * the seats of one stand and créneau are interchangeable, so no seat id has
     * to survive a reference-data change for the reconciliation to hold.</p>
     *
     * <p>Everything still valid and outside the perimeter is pinned, including
     * seats covered by no explicit lock: an incremental re-solve exists to keep
     * the standing plan stable, not to re-optimise it. Re-opening a validated
     * area is therefore an explicit act — name it in {@code perimetre}, or run
     * a full solve with locks protecting what must survive it.</p>
     */
    public ProblemeIncremental construireIncrementalDepuisReferenceData(PerimetreReplanification perimetre) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<Stand> stands = referenceDataService.listStandsResolus();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        Map<String, List<String>> affectationsPrecedentes =
                planningPersistenceService.chargerAnimateursParStandCreneau();
        if (affectationsPrecedentes.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun plan persisté : lancez d'abord une résolution complète, "
                            + "la replanification incrémentale repart de son résultat.");
        }
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        List<ContrainteAdHoc> contraintesAdHoc = referenceDataService.snapshotContraintes();
        StatistiquesIncremental statistiques = figerPostesIncremental(postes, animateurs, affectationsPrecedentes,
                perimetre == null ? PerimetreReplanification.automatique() : perimetre, contraintesAdHoc);
        LocalDate dateDebut = creneaux.stream()
                .map(Creneau::getDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningFestival festival = new PlanningFestival(dateDebut, animateurs, postes, contraintesAdHoc);
        festival.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        festival.setVerrouillages(referenceDataService.listVerrouillages());
        return new ProblemeIncremental(festival, statistiques, affectationsPrecedentes);
    }

    /**
     * The incremental reconciliation itself (issue #86), positional like
     * {@link #appliquerVerrouillages}: every seat is re-seeded with the
     * animateur the persisted plan gave it, then
     * <ul>
     * <li>named by {@code perimetre} → cleared and left free, whatever its
     * state: this is the operator saying "redo that";</li>
     * <li>still valid (the animateur exists and is not unavailable on the
     * seat's day) → pinned, the solver may not touch it;</li>
     * <li>invalidated by a late change (animateur deleted, freshly declared
     * unavailable that day, or covered by a fresh forced-unavailability ad hoc
     * constraint) → cleared and left free: exactly what the incremental solve
     * has to re-fill. Pinning such a seat would freeze a hard violation nobody
     * could then fix;</li>
     * <li>never staffed, or newly created (added stand or créneau) → left
     * free, as in a full solve.</li>
     * </ul>
     * Package-private and static so it can be unit-tested without a database.
     */
    static StatistiquesIncremental figerPostesIncremental(List<PosteAffectation> postes, List<Animateur> animateurs,
            Map<String, List<String>> animateursPersistes, PerimetreReplanification perimetre,
            List<ContrainteAdHoc> contraintesAdHoc) {
        Map<String, Animateur> animateursParId = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursParId.put(animateur.getId(), animateur);
        }
        // Filtered once: the loop below runs on thousands of seats, and this
        // list is normally empty.
        List<ContrainteAdHoc> indisponibilitesForcees = contraintesAdHoc == null
                ? List.of()
                : contraintesAdHoc.stream()
                        .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                        .toList();
        Map<String, Integer> prochainePlace = new HashMap<>();
        int figes = 0;
        int liberes = 0;
        int liberesManuellement = 0;
        int nouveaux = 0;
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String cle = PlanningPersistenceService.cleStandCreneau(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = animateursPersistes.getOrDefault(cle, List.of());
            int place = prochainePlace.merge(cle, 1, Integer::sum) - 1;
            String tenantId = place < tenants.size() ? tenants.get(place) : null;
            if (tenantId == null) {
                nouveaux++;
                continue;
            }
            if (perimetre.liberer(poste, tenantId)) {
                liberesManuellement++;
                continue;
            }
            Animateur tenant = animateursParId.get(tenantId);
            // Seeded first: the ad hoc check below reads the seat as staffed,
            // exactly like the constraint it shares its implementation with.
            poste.setAnimateur(tenant);
            if (tenant == null || indisponible(tenant, poste)
                    || interditParContrainteAdHoc(indisponibilitesForcees, poste)) {
                poste.setAnimateur(null);
                liberes++;
                continue;
            }
            poste.setVerrouille(true);
            figes++;
        }
        return new StatistiquesIncremental(postes.size(), figes, liberes, liberesManuellement, nouveaux);
    }

    private static boolean indisponible(Animateur animateur, PosteAffectation poste) {
        return animateur.getJoursIndisponibles() != null
                && animateur.getJoursIndisponibles().contains(poste.getCreneau().getDate());
    }

    /**
     * Whether a forced-unavailability ad hoc constraint forbids this seat as
     * staffed — the other way a late change lands, alongside a day off. The
     * predicate is the solver's own
     * ({@link AdHocConstraints#violeIndisponibiliteForcee}), so the two can
     * never disagree about what is allowed.
     */
    private static boolean interditParContrainteAdHoc(List<ContrainteAdHoc> indisponibilitesForcees,
            PosteAffectation poste) {
        for (ContrainteAdHoc contrainte : indisponibilitesForcees) {
            if (AdHocConstraints.violeIndisponibiliteForcee(contrainte, poste)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Freezes the seats covered by the active group's locks (issue #87): each
     * one is re-seeded with the animateur the last persisted solve gave it and
     * pinned ({@link PosteAffectation#setVerrouille}), so no move can change it
     * while the rest of the plan is re-optimised from scratch.
     *
     * <p>Only a seat that <em>was</em> staffed can be frozen: an empty seat is
     * left unassigned and movable, because pinning a hole would make it
     * permanently unfillable. For the same reason, a lock recorded before any
     * solve has been persisted simply freezes nothing.</p>
     *
     * <p>Seats are re-seeded before the locks are evaluated because a
     * {@link TypeVerrouillage#ANIMATEUR} lock is expressed in terms of who
     * holds the seat; anything seeded but not covered by a lock is cleared
     * again, leaving the unlocked part of the problem exactly as it was
     * before.</p>
     */
    private void appliquerVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages) {
        if (verrouillages.isEmpty() || planningPersistenceService == null) {
            return;
        }
        appliquerVerrouillages(postes, animateurs, verrouillages,
                planningPersistenceService.chargerAnimateursParStandCreneau());
    }

    /**
     * The pinning itself, taking the persisted assignments as a parameter:
     * package-private and static so it can be unit-tested without a database,
     * like {@link #construirePostes}.
     */
    static void appliquerVerrouillages(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> animateursPersistes) {
        if (verrouillages.isEmpty()) {
            return;
        }
        seedDepuisAffectations(postes, animateurs, verrouillages, animateursPersistes);
    }

    /**
     * Re-seeds the seats positionally from {@code seed} (animateur ids per
     * stand × créneau key, seat order — ids are interchangeable within one
     * key, see {@link PlanningPersistenceService#chargerAnimateursParStandCreneau()}),
     * pins the seats covered by a lock, and clears the others again so the
     * solver restarts from scratch everywhere it is free to. An animateur id
     * the referential no longer knows simply leaves its seat empty — a stale
     * seed is a worse starting point, never an error.
     *
     * <p>The variant that <em>keeps</em> the unlocked seeds as a warm start
     * belongs to the incremental re-solve of issue #86, and lives in
     * {@link #figerPostesIncremental}: it has its own notion of what stays
     * valid, and pins rather than merely seeds.</p>
     */
    static void seedDepuisAffectations(List<PosteAffectation> postes, List<Animateur> animateurs,
            List<VerrouillagePlanning> verrouillages, Map<String, List<String>> seed) {
        if (seed.isEmpty()) {
            return;
        }
        Map<String, Animateur> animateursParId = new HashMap<>();
        for (Animateur animateur : animateurs) {
            animateursParId.put(animateur.getId(), animateur);
        }
        Map<String, Integer> prochaineePlace = new HashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            String cle = PlanningPersistenceService.cleStandCreneau(
                    poste.getStand().getId(), poste.getCreneau().getId());
            List<String> tenants = seed.getOrDefault(cle, List.of());
            int place = prochaineePlace.merge(cle, 1, Integer::sum) - 1;
            if (place < tenants.size()) {
                poste.setAnimateur(animateursParId.get(tenants.get(place)));
            }
            if (poste.getAnimateur() == null) {
                continue;
            }
            boolean gele = verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste));
            poste.setVerrouille(gele);
            if (!gele) {
                poste.setAnimateur(null);
            }
        }
    }

    /**
     * One {@link PosteAffectation} per required seat ({@code stand.effectifMin})
     * on every stand × timeslot × open segment (see
     * {@link Creneau#segmentsOuvertsMinutes(Stand)}), all seats unassigned.
     * Package-private and static so it can be unit-tested without a database.
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
     *
     * <p>A stand closed for only part of a créneau (see
     * {@link dev.sylvain.planning.domain.IndisponibiliteStand})
     * still generates a poste for the créneau's open remainder(s), each one
     * carrying an effective time-window override
     * ({@link PosteAffectation#getHeureDebutEffective()}) narrower than the
     * créneau itself — the poste still references the real, persisted créneau
     * (a hard requirement of {@code poste_affectation.creneau_id}'s foreign
     * key), so it cannot be split into a synthetic sub-créneau instead.</p>
     *
     * <p>When {@code creneaux} contains more than one relay-grid "famille"
     * (see {@link VacationGeneratorService#genererVacations}), each stand is
     * deterministically assigned to exactly one (see
     * {@link #repartirStandsParFamille}) and only ever paired against that
     * famille's créneaux, instead of the full cross product. Whichever family a
     * stand lands on is stable across regenerations (it depends only on the set
     * of stand ids), so re-running découpage doesn't reshuffle which stands
     * share a grid.
     * With a single famille (the default, {@code famille} always 0) this is
     * exactly the historical unfiltered cross product.</p>
     */
    static List<PosteAffectation> construirePostes(List<Stand> stands, List<Creneau> creneaux) {
        int nombreFamilles = creneaux.stream().mapToInt(Creneau::getFamille).max().orElse(0) + 1;
        Map<String, Integer> familleParStand = repartirStandsParFamille(stands, nombreFamilles);
        List<PosteAffectation> postes = new ArrayList<>();
        int counter = 0;
        for (Stand stand : stands) {
            int effectif = Math.max(1, stand.getEffectifMin());
            // Sur une vacation de couverture de pause (stratégie EFFECTIF_REDUIT),
            // le stand tourne à demi-effectif, arrondi au supérieur : un stand
            // tenu par une seule personne la garde plutôt que de fermer.
            int effectifPause = (effectif + 1) / 2;
            int familleStand = familleParStand.get(stand.getId());
            for (Creneau creneau : creneaux) {
                if (creneau.getFamille() != familleStand) {
                    continue;
                }
                int seats = creneau.isCouverturePause() ? effectifPause : effectif;
                List<int[]> segments = creneau.segmentsOuvertsMinutes(stand);
                boolean creneauEntierOuvert = segments.size() == 1 && segments.get(0)[0] == 0
                        && segments.get(0)[1] == creneau.getDureeMinutes();
                for (int[] segment : segments) {
                    for (int seat = 0; seat < seats; seat++) {
                        PosteAffectation poste = new PosteAffectation("poste-" + (counter++), stand, creneau);
                        if (!creneauEntierOuvert) {
                            poste.setHeureDebutEffective(decaler(creneau.getHeureDebut(), segment[0]));
                            poste.setHeureFinEffective(decaler(creneau.getHeureDebut(), segment[1]));
                        }
                        postes.add(poste);
                    }
                }
            }
        }
        return postes;
    }

    /**
     * Assigns every stand to exactly one relay-grid famille, round-robin over
     * the stands sorted by id. Deterministic and stable across regenerations
     * (it depends on nothing but the set of stand ids), just like the hash it
     * replaces — but <b>balanced</b>, which the hash was not.
     *
     * <p>{@code floorMod(id.hashCode(), n)} spreads ids pseudo-randomly, and on
     * a roster this small that is visibly lumpy: on the reference scenario it
     * put 36 of the 91 seats on a single famille out of four (19/21/36/15).
     * That famille alone then changed crew at one instant with 40% of the whole
     * festival's demand behind it, which is exactly the simultaneity peak the
     * staggering exists to break — the mechanism was working against itself.
     * Round-robin over sorted ids gives buckets that differ by at most one
     * stand.</p>
     */
    private static Map<String, Integer> repartirStandsParFamille(List<Stand> stands, int nombreFamilles) {
        List<String> ids = stands.stream().map(Stand::getId).sorted().toList();
        Map<String, Integer> familles = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            familles.put(ids.get(i), i % nombreFamilles);
        }
        return familles;
    }

    /** {@code heureDebut} shifted forward by {@code minutes}, wrapping past midnight. */
    private static LocalTime decaler(LocalTime heureDebut, int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(heureDebut.toSecondOfDay() + minutes * 60L, 24 * 3600L));
    }

    /**
     * Serializes the current reference data into the same YAML shape read by
     * {@link #construirePlanningDepuisDonnees}, so the result can be dropped into the
     * {@link #SCENARIOS_DIR} folder and reloaded as-is.
     *
     * <p>Everything that shapes a solve is written, not only the entities:
     * {@code typologies}, {@code emplacements}, {@code parametresLegaux},
     * {@code parametresDecoupage}, {@code parametresSolveur} and, when the
     * active planning was generated by an auto-découpage, {@code decoupageAuto}
     * — re-importing the file therefore reproduces the very same problem, which
     * is the whole point of exporting it. A file missing those sections silently
     * fell back to the importing instance's own settings (its solve duration,
     * its vacation lengths, its relay familles), so the "same" scenario replayed
     * elsewhere solved a different problem.</p>
     *
     * <p>Two mutually exclusive shapes come out of that, depending on the active
     * group:</p>
     * <ul>
     * <li>a hand-built planning exports its own créneaux plus the seat list they
     * imply ({@code postes});</li>
     * <li>a planning generated by découpage exports its <b>source</b>
     * amplitudes plus {@code decoupageAuto}, and no {@code postes} — the import
     * re-runs the découpage and regenerates the seats from the vacations it
     * creates, which is the only way the ids stay consistent (the generated
     * créneaux get fresh database ids on the way in).</li>
     * </ul>
     */
    public String exporterScenarioYaml() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        // Raw stands, so the file gets the recurring horaires as rules rather
        // than the few hundred dated windows they expand to — the resolution
        // still runs, because the seat list does depend on it.
        List<Stand> stands = referenceDataService.listStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence à exporter. Créez des stands, des animateurs et des créneaux d'abord.");
        }
        HoraireStandResolver.appliquer(stands, creneaux);

        // The edition's créneaux are exported as-is (issue #172): once the
        // découpage ran, the amplitudes it consumed are gone, so a découpé
        // edition exports its vacations plainly — the hand-maintained
        // "amplitudes + decoupageAuto:" scenario file stays the source of
        // truth for re-slicing, never this export.
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        return construireScenarioYaml(new ScenarioExport(
                animateurs,
                stands,
                creneaux,
                postes,
                referenceDataService.listTypologies(),
                referenceDataService.listEmplacements(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.getParametresDecoupage(),
                referenceDataService.getParametresSolveur()));
    }

    /**
     * Everything one exported scenario file holds. A record rather than ten
     * positional parameters, since {@link #construireScenarioYaml} is called
     * both from the export above and from its unit tests.
     *
     * @param postes the seat list, or {@code null} to leave the section out
     *               (see {@link #exporterScenarioYaml()})
     */
    record ScenarioExport(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<PosteAffectation> postes,
            List<TypologieItem> typologies,
            List<Emplacement> emplacements,
            ParametresLegaux parametresLegaux,
            ParametresDecoupage parametresDecoupage,
            ParametresSolveur parametresSolveur) {
    }

    /**
     * Builds the YAML text from already-fetched data. Package-private and
     * static, like {@link #construirePostes}, so it can be unit-tested without
     * a database.
     */
    static String construireScenarioYaml(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux,
            List<PosteAffectation> postes) {
        return construireScenarioYaml(new ScenarioExport(animateurs, stands, creneaux, postes, List.of(), List.of(),
                null, null, null));
    }

    /** Full-fidelity variant: writes every optional section {@link ScenarioExport} carries. */
    static String construireScenarioYaml(ScenarioExport export) {
        List<Animateur> animateurs = export.animateurs();
        List<Stand> stands = export.stands();
        List<Creneau> creneaux = export.creneaux();
        List<PosteAffectation> postes = export.postes();
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
            if (stand.getEmplacement() != null) {
                item.put("emplacementId", stand.getEmplacement().getId());
            }
            item.put("typologiesProposees", new ArrayList<>(stand.getTypologiesProposees()));
            item.put("effectifMin", stand.getEffectifMin());
            item.put("effectifMax", stand.getEffectifMax());
            item.put("reserveMajeurs", stand.isReserveMajeurs());
            item.put("premium", stand.isPremium());
            item.put("niveauEffort", stand.getNiveauEffort().name());
            item.put("indisponibilites", indisponibilitesYaml(stand.getIndisponibilites()));
            item.put("ouvertures", ouverturesYaml(stand.getOuvertures()));
            item.put("horaires", horairesYaml(stand.getHoraires()));
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
            // Contact only — the espace-animateur access token never travels
            // through a scenario file (regenerated from the database instead).
            if (animateur.getEmail() != null && !animateur.getEmail().isBlank()) {
                item.put("email", animateur.getEmail());
            }
            Map<String, String> competences = new LinkedHashMap<>();
            if (animateur.getCompetences() != null) {
                animateur.getCompetences()
                        .forEach((typologie, niveau) -> competences.put(typologie, niveau.name()));
            }
            item.put("competences", competences);
            List<String> joursIndisponibles = animateur.getJoursIndisponibles() == null
                    ? List.of()
                    : animateur.getJoursIndisponibles().stream()
                            .sorted()
                            .map(PlanningService::asString)
                            .toList();
            item.put("joursIndisponibles", joursIndisponibles);
            List<String> souhaits = animateur.getSouhaits() == null
                    ? List.of()
                    : new ArrayList<>(animateur.getSouhaits());
            item.put("souhaits", souhaits);
            animateursYaml.add(item);
        }

        // null (not empty): a scenario carrying decoupageAuto must not pin a seat
        // list, since the créneaux it would reference only exist after the
        // découpage has run on import.
        List<Map<String, Object>> postesYaml = postes == null ? null : new ArrayList<>();
        for (PosteAffectation poste : postes == null ? List.<PosteAffectation>of() : postes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", poste.getId());
            item.put("standId", poste.getStand().getId());
            item.put("creneauId", poste.getCreneau().getId());
            item.put("animateurId", null);
            postesYaml.add(item);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("festival", festival);
        if (export.parametresSolveur() != null) {
            root.put("parametresSolveur",
                    Map.of("dureeResolutionSecondes", export.parametresSolveur().getDureeResolutionSecondes()));
        }
        if (export.parametresLegaux() != null) {
            root.put("parametresLegaux", parametresLegauxYaml(export.parametresLegaux()));
        }
        if (export.parametresDecoupage() != null) {
            root.put("parametresDecoupage", parametresDecoupageYaml(export.parametresDecoupage()));
        }
        if (!export.typologies().isEmpty()) {
            root.put("typologies", typologiesYaml(export.typologies()));
        }
        root.put("creneaux", creneauxYaml);
        if (!export.emplacements().isEmpty()) {
            root.put("emplacements", emplacementsYaml(export.emplacements()));
        }
        root.put("stands", standsYaml);
        root.put("animateurs", animateursYaml);
        if (postesYaml != null) {
            root.put("postes", postesYaml);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Only the three fields a scenario file is read back with (see {@link SectionsScenario}). */
    private static Map<String, Object> parametresLegauxYaml(ParametresLegaux parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeHebdomadaireMaxMinutes", parametres.getDureeHebdomadaireMaxMinutes());
        item.put("pauseMinimaleEntreVacationsMinutes", parametres.getPauseMinimaleEntreVacationsMinutes());
        item.put("reposQuotidienMinimalMinutes", parametres.getReposQuotidienMinimalMinutes());
        return item;
    }

    private static Map<String, Object> parametresDecoupageYaml(ParametresDecoupage parametres) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dureeVacationCibleMinutes", parametres.getDureeVacationCibleMinutes());
        item.put("dureeVacationMinMinutes", parametres.getDureeVacationMinMinutes());
        item.put("dureeVacationMaxMinutes", parametres.getDureeVacationMaxMinutes());
        item.put("dureeChevauchementMinutes", parametres.getDureeChevauchementMinutes());
        item.put("dureePauseRepasMinutes", parametres.getDureePauseRepasMinutes());
        item.put("fenetreRepasMidiDebut", asString(parametres.getFenetreRepasMidiDebut()));
        item.put("fenetreRepasMidiFin", asString(parametres.getFenetreRepasMidiFin()));
        item.put("fenetreRepasSoirDebut", asString(parametres.getFenetreRepasSoirDebut()));
        item.put("fenetreRepasSoirFin", asString(parametres.getFenetreRepasSoirFin()));
        item.put("strategieCouverturePendantPause", parametres.getStrategieCouverturePendantPause().name());
        item.put("nombreFamillesDecalage", parametres.getNombreFamillesDecalage());
        item.put("dureeDecalageMaxMinutes", parametres.getDureeDecalageMaxMinutes());
        return item;
    }

    private static List<Map<String, Object>> typologiesYaml(List<TypologieItem> typologies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TypologieItem typologie : typologies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", typologie.id());
            item.put("label", typologie.label());
            item.put("ninja", typologie.ninja());
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> emplacementsYaml(List<Emplacement> emplacements) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Emplacement emplacement : emplacements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", emplacement.getId());
            item.put("nom", emplacement.getNom());
            item.put("latitude", emplacement.getLatitude());
            item.put("longitude", emplacement.getLongitude());
            result.add(item);
        }
        return result;
    }

    /** Serializes a stand's {@link IndisponibiliteStand} closures to the shape {@link #chargerReferenceScenario} reads back. */
    private static List<Map<String, Object>> indisponibilitesYaml(List<IndisponibiliteStand> indisponibilites) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IndisponibiliteStand indispo : indisponibilites) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(indispo.getDate()));
            item.put("heureDebut", asString(indispo.getHeureDebut()));
            item.put("heureFin", asString(indispo.getHeureFin()));
            item.put("motif", indispo.getMotif());
            result.add(item);
        }
        return result;
    }

    /** Serializes a stand's {@link OuvertureStand} openings to the shape {@link #chargerReferenceScenario} reads back. */
    private static List<Map<String, Object>> ouverturesYaml(List<OuvertureStand> ouvertures) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OuvertureStand ouverture : ouvertures) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", asString(ouverture.getDate()));
            item.put("heureDebut", asString(ouverture.getHeureDebut()));
            item.put("heureFin", asString(ouverture.getHeureFin()));
            item.put("motif", ouverture.getMotif());
            result.add(item);
        }
        return result;
    }

    /**
     * Serializes a stand's recurring {@link HoraireStand} rules to the shape
     * {@link #chargerReferenceScenario} reads back — the day selector flattened
     * onto the rule itself, so the common "every day" case stays a two-line
     * entry and the reader needs no polymorphism.
     *
     * <p>Only the fields the selector actually uses are written: a {@code TOUS}
     * rule carries no dates, so emitting empty {@code dates}/{@code dateDebut}
     * keys would be noise in a file meant to be read and diffed by hand.</p>
     */
    private static List<Map<String, Object>> horairesYaml(List<HoraireStand> horaires) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (HoraireStand horaire : horaires) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", horaire.getMode().name());
            item.put("jours", horaire.getJours().name());
            switch (horaire.getJours()) {
                case JOURS_SEMAINE -> item.put("joursSemaine",
                        horaire.getJoursSemaine().stream().map(Enum::name).toList());
                case PLAGE -> {
                    item.put("dateDebut", asString(horaire.getDateDebut()));
                    item.put("dateFin", asString(horaire.getDateFin()));
                }
                case DATES -> item.put("dates", horaire.getDates().stream().map(PlanningService::asString).toList());
                case TOUS -> {
                    // No selector data to write.
                }
            }
            List<Map<String, Object>> fenetres = new ArrayList<>();
            for (FenetreHoraire fenetre : horaire.getFenetres()) {
                Map<String, Object> fenetreYaml = new LinkedHashMap<>();
                fenetreYaml.put("heureDebut", asString(fenetre.getHeureDebut()));
                // Absent rather than null: "jusqu'à la fermeture" reads better as
                // a missing end than as an explicit empty one.
                if (fenetre.getHeureFin() != null) {
                    fenetreYaml.put("heureFin", asString(fenetre.getHeureFin()));
                }
                fenetres.add(fenetreYaml);
            }
            item.put("fenetres", fenetres);
            if (horaire.getMotif() != null) {
                item.put("motif", horaire.getMotif());
            }
            result.add(item);
        }
        return result;
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

    private PlanningFestival construirePlanningDepuisDonnees(Map<String, Object> scenarioData) {
        ReferenceScenario reference = chargerReferenceScenario(scenarioData);

        // Étendre les horaires récurrents avant toute décision d'ouverture : un
        // fichier peut décrire les horaires d'un stand en règles plutôt qu'en
        // fenêtres datées, et c'est bien sur les jours de ses propres créneaux
        // qu'il faut les résoudre. Sans règle, l'appel ne change rien.
        HoraireStandResolver.appliquer(reference.standsParId().values(), reference.creneauxParId().values());

        // Charger les postes : repris tels quels du fichier si la section est
        // présente, sinon générés à partir des stands/créneaux (mêmes règles que
        // construireDepuisReferenceData) — un fichier n'a plus besoin d'énumérer
        // ses postes à la main pour être importé.
        List<Map<String, Object>> postesList = YamlSections.objets(scenarioData, "postes");
        List<PosteAffectation> postes;
        if (postesList == null) {
            postes = construirePostes(new ArrayList<>(reference.standsParId().values()),
                    new ArrayList<>(reference.creneauxParId().values()));
        } else {
            postes = new ArrayList<>();
            for (Map<String, Object> posteData : postesList) {
                String id = (String) posteData.get("id");
                String standId = (String) posteData.get("standId");
                String creneauId = (String) posteData.get("creneauId");

                Stand stand = reference.standsParId().get(standId);
                Creneau creneau = reference.creneauxParId().get(creneauId);

                PosteAffectation poste = new PosteAffectation(id, stand, creneau);
                // Mirrors construirePostes(): a hand-authored poste can still name a
                // créneau the stand is only partially open for (IndisponibiliteStand /
                // OuvertureStand), so narrow its effective window the same way instead
                // of silently using the créneau's full amplitude.
                List<int[]> segments = creneau.segmentsOuvertsMinutes(stand);
                if (segments.size() == 1) {
                    int[] segment = segments.get(0);
                    boolean creneauEntierOuvert = segment[0] == 0 && segment[1] == creneau.getDureeMinutes();
                    if (!creneauEntierOuvert) {
                        poste.setHeureDebutEffective(decaler(creneau.getHeureDebut(), segment[0]));
                        poste.setHeureFinEffective(decaler(creneau.getHeureDebut(), segment[1]));
                    }
                }
                postes.add(poste);
            }
        }

        PlanningFestival festival = new PlanningFestival(reference.dateDebut(), reference.animateurs(), postes,
                referenceDataService.snapshotContraintes());
        festival.setParametresLegaux(List.of(
                parseParametresLegaux(scenarioData).orElseGet(referenceDataService::getParametresLegaux)));
        return festival;
    }

    /**
     * Parses a scenario YAML file uploaded by a user (same shape as the files
     * under {@link #SCENARIOS_DIR}, typically produced by "Exporter les
     * données actuelles en scénario") into the same result the
     * {@code import-scenario} endpoint applies for a built-in scenario name —
     * without ever touching the classpath. Used by the "Importer un fichier"
     * button on the Scénarios page.
     *
     * <p>Every failure (malformed YAML, a missing/mistyped section) is
     * reported as an {@link IllegalArgumentException} carrying a message
     * meant to be shown to the user as-is, rather than surfacing the raw
     * {@link org.yaml.snakeyaml.error.YAMLException}/{@link ClassCastException}/
     * {@link NullPointerException} a malformed file triggers deep inside
     * {@link #construirePlanningDepuisDonnees}.</p>
     */
    public ScenarioImporte construireDepuisTexteScenario(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new ErreurMetier.Invalide("Le fichier est vide.");
        }
        Map<String, Object> scenarioData;
        try {
            scenarioData = parserYaml(
                    new java.io.ByteArrayInputStream(yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RuntimeException | IOException e) {
            throw new ErreurMetier.Invalide("YAML invalide : " + messageOu(e), e);
        }
        PlanningFestival planning;
        try {
            planning = construirePlanningDepuisDonnees(scenarioData);
        } catch (RuntimeException e) {
            throw new ErreurMetier.Invalide("Scénario invalide : " + messageOu(e), e);
        }
        return new ScenarioImporte(planning, sectionsDe(scenarioData));
    }

    private static String messageOu(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Result of {@link #construireDepuisTexteScenario}: the built planning plus
     * whichever optional parameter sections the file pinned, mirroring what
     * {@code POST /reference-data/import-scenario} applies for a named
     * built-in scenario.
     */
    public record ScenarioImporte(PlanningFestival planning, SectionsScenario sections) {
    }

    /**
     * Loads a scenario's raw stands/animateurs/creneaux, ignoring any
     * hand-authored {@code postes:} list — unlike {@link #construireExemple},
     * which uses that list as-is. For tests that need to run découpage (see
     * {@link VacationGeneratorService}) on the raw créneaux themselves before
     * building postes via {@link #construirePostes}, the way
     * {@link #construireDepuisReferenceData} does against the database.
     */
    ReferenceScenario chargerReferenceScenario(String scenarioName) throws IOException {
        return chargerReferenceScenario(lireDonneesScenario(cheminScenario(scenarioName)));
    }

    /** {@code creneaux}/{@code stands}/{@code animateurs} sections of a scenario file, parsed and cross-linked. */
    record ReferenceScenario(LocalDate dateDebut, Map<String, Creneau> creneauxParId, Map<String, Stand> standsParId,
            List<Animateur> animateurs) {
    }

    private ReferenceScenario chargerReferenceScenario(Map<String, Object> scenarioData) {
        // Charger les creneaux : le fichier YAML porte un id texte historique
        // (utilisé seulement pour relier postes/creneaux entre eux), remplacé
        // ici par un id numérique synthétique ; jour est recalculé (voir
        // Creneau.assignerJours), la valeur du fichier est ignorée.
        Map<String, Creneau> creneauxMap = new HashMap<>();
        long compteurCreneauId = 1;
        List<Map<String, Object>> creneauxList = YamlSections.objets(scenarioData, "creneaux");
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

        // Charger les stands
        Map<String, Stand> standsMap = new HashMap<>();
        List<Map<String, Object>> standsList = YamlSections.objets(scenarioData, "stands");
        for (Map<String, Object> standData : standsList) {
            String id = (String) standData.get("id");
            String nom = (String) standData.get("nom");
            List<String> typologiesStr = YamlSections.chaines(standData, "typologiesProposees");
            Set<String> typologies = new java.util.HashSet<>(typologiesStr);
            int effectifMin = ((Number) standData.get("effectifMin")).intValue();
            int effectifMax = ((Number) standData.get("effectifMax")).intValue();
            boolean reserveMajeurs = (Boolean) standData.getOrDefault("reserveMajeurs", false);
            boolean premium = (Boolean) standData.getOrDefault("premium", false);

            Stand stand = new Stand(id, nom, typologies, effectifMin, effectifMax, reserveMajeurs, premium);
            String niveauEffortStr = (String) standData.getOrDefault("niveauEffort", NiveauEffort.NORMAL.name());
            stand.setNiveauEffort(NiveauEffort.valueOf(niveauEffortStr));
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
                    LocalTime heureFin = parseHeureOuFinDeJournee(indispoData.get("heureFin"));
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
                    LocalTime heureFin = parseHeureOuFinDeJournee(ouvertureData.get("heureFin"));
                    String motif = (String) ouvertureData.get("motif");
                    ouvertures.add(new OuvertureStand(null, date, heureDebut, heureFin, motif));
                }
                stand.setOuvertures(ouvertures);
            }
            List<Map<String, Object>> horairesData = YamlSections.objets(standData, "horaires");
            if (horairesData != null) {
                stand.setHoraires(lireHoraires(horairesData));
            }
            standsMap.put(id, stand);
        }

        // Charger les animateurs
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

            // Charger les compétences
            Map<String, String> competencesData = (Map<String, String>) animateurData.get("competences");
            Map<String, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(entry.getKey(), NiveauCompetence.valueOf(entry.getValue()));
            }
            animateur.setCompetences(competences);

            // Charger les jours d'indisponibilité (opt-out: available by default)
            List<Object> joursOffData = YamlSections.valeurs(animateurData, "joursIndisponibles");
            Set<LocalDate> joursIndisponibles = joursOffData == null
                    ? new java.util.HashSet<>()
                    : joursOffData.stream()
                            .map(value -> parseLocalDate(value, "animateurs.joursIndisponibles"))
                            .collect(Collectors.toCollection(java.util.HashSet::new));
            animateur.setJoursIndisponibles(joursIndisponibles);

            // Charger les souhaits (typologies de stand souhaitées, sans niveau ni priorité)
            List<Object> souhaitsData = YamlSections.valeurs(animateurData, "souhaits");
            Set<String> souhaits = souhaitsData == null
                    ? new java.util.HashSet<>()
                    : souhaitsData.stream()
                            .map(value -> (String) value)
                            .collect(Collectors.toCollection(java.util.HashSet::new));
            animateur.setSouhaits(souhaits);

            animateurs.add(animateur);
        }

        // A scenario carries its own typologie referential, so the ninja typologie
        // comes from the file itself — the database one may not be loaded yet (or
        // may describe a different festival entirely).
        String typologieNinja = parseTypologies(scenarioData).stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        animateurs.forEach(animateur -> animateur.appliquerTypologieNinja(typologieNinja));

        LocalDate dateDebut = parseLocalDate(
            YamlSections.objet(scenarioData, "festival").get("dateDebut"),
            "festival.dateDebut");

        return new ReferenceScenario(dateDebut, creneauxMap, standsMap, animateurs);
    }

    /** Shared YAML loading for {@link #construirePlanningDepuisDonnees} and the optional-section accessors below. */
    private Map<String, Object> lireDonneesScenario(String scenarioPath) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scenarioPath);
        if (inputStream == null) {
            throw new IOException("Fichier de scénario non trouvé: " + scenarioPath);
        }
        return parserYaml(inputStream);
    }

    /**
     * Parses a scenario's raw YAML bytes, from the classpath ({@link #lireDonneesScenario})
     * or from a user-uploaded file ({@link #construireDepuisTexteScenario}).
     */
    // The one unchecked cast left in this class, and the only one that has no
    // alternative: this IS the entry point that turns SnakeYAML's untyped
    // Object into the scenario document every parseXxx below reads. The check
    // just above it is what makes it safe; every nested section goes through
    // YamlSections instead.
    @SuppressWarnings("unchecked")
    private Map<String, Object> parserYaml(InputStream inputStream) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);
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
     *                            place on {@link PlanningFestival}
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
     *                            default {@code ImportReferentielRepository#importFromPlanning}
     *                            derives on the fly. Empty, not absent, when the section
     *                            is missing: a list has no "absent" distinct from "empty"
     * @param edition             the edition the import must write into; absent means
     *                            the caller's current one
     */
    public record SectionsScenario(
            Optional<ParametresLegaux> parametresLegaux,
            Optional<ParametresDecoupage> parametresDecoupage,
            Optional<ParametresSolveur> parametresSolveur,
            boolean decoupageAuto,
            List<TypologieItem> typologies,
            Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> edition) {
    }

    /** Reads {@link SectionsScenario} out of an already-parsed scenario document. */
    private static SectionsScenario sectionsDe(Map<String, Object> scenarioData) {
        return new SectionsScenario(
                parseParametresLegaux(scenarioData),
                parseParametresDecoupage(scenarioData),
                parseParametresSolveur(scenarioData),
                parseDecoupageAuto(scenarioData),
                parseTypologies(scenarioData),
                parseEditionCible(scenarioData));
    }

    /**
     * The optional sections of a bundled scenario, <b>without</b> building its
     * planning: the pre-import step that names the target edition, and the
     * cheap read the tests use to assert what a file pins.
     */
    public SectionsScenario chargerSectionsScenario(String scenarioName) {
        try {
            return sectionsDe(lireDonneesScenario(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * A bundled scenario, whole: its planning and its optional sections, from
     * a single parse. The named-file counterpart of
     * {@link #construireDepuisTexteScenario}, so the two import paths differ
     * only in where the bytes come from.
     */
    public ScenarioImporte chargerScenario(String scenarioName) {
        try {
            Map<String, Object> scenarioData = lireDonneesScenario(cheminScenario(scenarioName));
            return new ScenarioImporte(construirePlanningDepuisDonnees(scenarioData), sectionsDe(scenarioData));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Optional {@code edition:} section of an uploaded scenario text, parsed
     * alone — the pre-import step the UI uses to NAME the target edition in
     * its confirmation dialog, before anything is written.
     */
    public Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> chargerEditionTexteScenario(
            String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new ErreurMetier.Invalide("Le fichier est vide.");
        }
        try {
            return parseEditionCible(parserYaml(new java.io.ByteArrayInputStream(
                    yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } catch (RuntimeException | IOException e) {
            throw new ErreurMetier.Invalide("YAML invalide : " + messageOu(e), e);
        }
    }

    private static Optional<ParametresLegaux> parseParametresLegaux(Map<String, Object> scenarioData) {
        Map<String, Object> data = YamlSections.objet(scenarioData, "parametresLegaux");
        if (data == null) {
            return Optional.empty();
        }
        ParametresLegaux parametres = new ParametresLegaux();
        lireEntier(data, "dureeHebdomadaireMaxMinutes", parametres::setDureeHebdomadaireMaxMinutes);
        lireEntier(data, "pauseMinimaleEntreVacationsMinutes", parametres::setPauseMinimaleEntreVacationsMinutes);
        lireEntier(data, "reposQuotidienMinimalMinutes", parametres::setReposQuotidienMinimalMinutes);
        return Optional.of(parametres);
    }

    /** Applies the section's integer field to the setter, leaving the target's own default when absent. */
    private static void lireEntier(Map<String, Object> data, String cle, IntConsumer setter) {
        Object valeur = data.get(cle);
        if (valeur != null) {
            setter.accept(((Number) valeur).intValue());
        }
    }

    /** Same as {@link #lireEntier} for an {@code HH:MM:SS} field — see {@link #parseLocalTime(Object)}. */
    private static void lireHeure(Map<String, Object> data, String cle, Consumer<LocalTime> setter) {
        Object valeur = data.get(cle);
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
        lireEntier(data, "dureeVacationCibleMinutes", parametres::setDureeVacationCibleMinutes);
        lireEntier(data, "dureeVacationMinMinutes", parametres::setDureeVacationMinMinutes);
        lireEntier(data, "dureeVacationMaxMinutes", parametres::setDureeVacationMaxMinutes);
        lireEntier(data, "dureeChevauchementMinutes", parametres::setDureeChevauchementMinutes);
        lireEntier(data, "dureePauseRepasMinutes", parametres::setDureePauseRepasMinutes);
        lireHeure(data, "fenetreRepasMidiDebut", parametres::setFenetreRepasMidiDebut);
        lireHeure(data, "fenetreRepasMidiFin", parametres::setFenetreRepasMidiFin);
        lireHeure(data, "fenetreRepasSoirDebut", parametres::setFenetreRepasSoirDebut);
        lireHeure(data, "fenetreRepasSoirFin", parametres::setFenetreRepasSoirFin);
        if (data.get("strategieCouverturePendantPause") != null) {
            parametres.setStrategieCouverturePendantPause(ParametresDecoupage.StrategieCouverturePendantPause
                    .valueOf((String) data.get("strategieCouverturePendantPause")));
        }
        lireEntier(data, "nombreFamillesDecalage", parametres::setNombreFamillesDecalage);
        lireEntier(data, "dureeDecalageMaxMinutes", parametres::setDureeDecalageMaxMinutes);
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

    private static Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> parseEditionCible(
            Map<String, Object> scenarioData) {
        Object data = scenarioData.get("edition");
        if (data == null) {
            return Optional.empty();
        }
        // Pattern matching rather than a cast: a wildcard Map reads its own
        // values as Object, which is all this section needs, so there is
        // nothing left to suppress.
        if (!(data instanceof Map<?, ?> editionData)) {
            throw new ErreurMetier.Invalide(
                    "La section edition doit être un objet { id, nom? }, pas une valeur simple.");
        }
        String id = (String) editionData.get("id");
        if (id == null || id.isBlank()) {
            throw new ErreurMetier.Invalide("La section edition exige un champ id non vide.");
        }
        return Optional.of(new dev.sylvain.planning.scenario.dto.EditionCibleDto(
                id, (String) editionData.get("nom")));
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
    private static LocalTime parseLocalTime(Object value) {
        if (value instanceof Number number) {
            return LocalTime.ofSecondOfDay(number.longValue());
        }
        return LocalTime.parse(value.toString());
    }

    public PlanningFestival resoudre(PlanningFestival problem) {
        return resoudre(problem, null);
    }

    public PlanningFestival resoudre(PlanningFestival problem, Long secondsLimitOverride) {
        return resoudre(problem, secondsLimitOverride, null);
    }

    /**
     * Same as {@link #resoudre(PlanningFestival, Long)}, but hands the freshly
     * built {@link Solver} to {@code onSolverReady} before blocking on
     * {@code solve()} — the only way a caller running this on a background
     * thread (see {@code SolverJobService}) can later call
     * {@link Solver#terminateEarly()} to stop a solve started by mistake.
     */
    public PlanningFestival resoudre(PlanningFestival problem, Long secondsLimitOverride,
            Consumer<Solver<PlanningFestival>> onSolverReady) {
        prepareProblem(problem);
        Solver<PlanningFestival> solver = resolveSolverFactory(secondsLimitOverride).buildSolver();
        if (onSolverReady != null) {
            onSolverReady.accept(solver);
        }
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
        // Server-side configuration, like the weights below: always overwritten
        // so a caller cannot loosen a quality threshold by sending its own.
        problem.setParametresQualite(List.of(new ParametresQualite(maxEmplacementsParJour)));
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
        return analyser(problem, secondsLimitOverride, null);
    }

    /**
     * Same as {@link #analyser(PlanningFestival, Long)}, but exposes the
     * {@link Solver} it builds so a background caller can stop it early.
     */
    public PlanningDiagnostic analyser(PlanningFestival problem, Long secondsLimitOverride,
            Consumer<Solver<PlanningFestival>> onSolverReady) {
        PlanningFestival solved = resoudre(problem, secondsLimitOverride, onSolverReady);
        return diagnostiquer(solved);
    }

    /**
     * Every constraint definition indexed by name, for {@link #expliquerAffectation}
     * and {@link #simulerSwap} to attach the business-facing niveau/catégorie/
     * description to a raw {@code ConstraintAnalysis} without a linear scan.
     */
    private static final Map<String, ConstraintCatalog.ConstraintDefinition> DEFINITIONS_PAR_NOM =
            ConstraintCatalog.definitions().stream()
                    .collect(Collectors.toUnmodifiableMap(ConstraintCatalog.ConstraintDefinition::name,
                            java.util.function.Function.identity()));

    /**
     * Per-assignment explainability ("Pourquoi lui ?"): every constraint match
     * of the already-solved {@code solved} planning whose justification facts
     * involve {@code posteId}, split into violated / not violated for that one
     * poste. "Respected" only means no violation was found for this poste, not
     * that the constraint is even applicable to it — the UI must present it as
     * such rather than as a positive endorsement.
     */
    public AffectationExplanation expliquerAffectation(PlanningFestival solved, String posteId) {
        PosteAffectation poste = trouverPoste(solved, posteId);
        ScoreAnalysis<?> analysis = solutionManager.analyze(solved);
        String animateurId = poste.getAnimateur() == null ? null : poste.getAnimateur().getId();
        return new AffectationExplanation(posteId, animateurId, (HardMediumSoftScore) analysis.score(),
                impactsPour(analysis, poste, true), impactsPour(analysis, poste, false));
    }

    /**
     * Simulates giving {@code posteId} to {@code animateurCandidatId} instead
     * of its current occupant, and reports the resulting score delta plus how
     * that poste's own violated constraints change. The candidate substitution
     * is applied to {@code solved} only for the duration of the second
     * {@code analyze} call and reverted immediately after (the caller's object
     * graph is a throwaway per-request payload, never shared/cached, so a
     * temporary in-place mutation is safe and avoids a full deep copy of a
     * planning that can hold thousands of postes).
     */
    public SwapSimulation simulerSwap(PlanningFestival solved, String posteId, String animateurCandidatId) {
        PosteAffectation poste = trouverPoste(solved, posteId);
        Animateur candidat = trouverAnimateur(solved, animateurCandidatId);
        Animateur actuel = poste.getAnimateur();

        ScoreAnalysis<?> avant = solutionManager.analyze(solved);
        List<ContrainteImpact> violeesAvant = impactsPour(avant, poste, true);

        ScoreAnalysis<?> apres;
        poste.setAnimateur(candidat);
        try {
            apres = solutionManager.analyze(solved);
        } finally {
            poste.setAnimateur(actuel);
        }
        List<ContrainteImpact> violeesApres = impactsPour(apres, poste, true);

        HardMediumSoftScore scoreAvant = (HardMediumSoftScore) avant.score();
        HardMediumSoftScore scoreApres = (HardMediumSoftScore) apres.score();
        return new SwapSimulation(posteId, actuel == null ? null : actuel.getId(), animateurCandidatId,
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant), violeesAvant, violeesApres);
    }

    /**
     * Simulates a demande d'échange (issue #165) on an already-solved planning:
     * the demandeur's seat on ({@code creneauId}, {@code standId}) goes to
     * {@code cibleId}, and — when the cible also works that créneau — their own
     * seat goes to the demandeur (échange croisé). Nothing is persisted; the
     * substitution lives only for the second {@code analyze} call, exactly like
     * {@link #simulerSwap}.
     *
     * <p>Unlike {@code simulerSwap}'s per-poste view, the verdict here is
     * planning-wide: a swap can break a hard constraint on a poste it does not
     * touch (weekly hours, rest periods…), so feasibility is judged on the
     * global hard score and the extra hard matches, not on the two seats
     * alone.</p>
     */
    public EchangeSimulation simulerEchange(PlanningFestival solved, String demandeurId, String cibleId,
            long creneauId, String standId) {
        PosteAffectation posteDemandeur = solved.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && demandeurId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Invalide(
                        "Aucun poste de l'animateur " + demandeurId + " sur ce créneau et ce stand"));
        Animateur demandeur = posteDemandeur.getAnimateur();
        // Invalide and not Introuvable, unlike the lookups of
        // expliquerAffectation/simulerSwap: there the id is the path of the
        // resource being asked for, so an unknown one means "no such thing
        // here" (404). Here it is a field of a submitted demande, so an
        // unknown one means "your form is wrong" (400) — the same answer as
        // the sibling check just above.
        Animateur cible = solved.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(cibleId))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Invalide("Animateur inconnu: " + cibleId));
        PosteAffectation posteCible = solved.getPostes().stream()
                .filter(poste -> poste != posteDemandeur
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && cibleId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElse(null);

        ScoreAnalysis<?> avant = solutionManager.analyze(solved);
        ScoreAnalysis<?> apres;
        posteDemandeur.setAnimateur(cible);
        if (posteCible != null) {
            posteCible.setAnimateur(demandeur);
        }
        try {
            apres = solutionManager.analyze(solved);
        } finally {
            posteDemandeur.setAnimateur(demandeur);
            if (posteCible != null) {
                posteCible.setAnimateur(cible);
            }
        }

        HardMediumSoftScore scoreAvant = (HardMediumSoftScore) avant.score();
        HardMediumSoftScore scoreApres = (HardMediumSoftScore) apres.score();
        return new EchangeSimulation(
                posteDemandeur.getId(),
                posteCible == null ? null : posteCible.getId(),
                posteCible != null,
                posteCible == null ? null : posteCible.getStand().getId(),
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant),
                scoreApres.hardScore() < scoreAvant.hardScore(),
                violationsDuresSupplementaires(avant, apres));
    }

    /**
     * Directed variant of {@link #simulerEchange}: the demandeur's seat on
     * (créneau, stand) goes to the cible, and the CIBLE'S seat on
     * (créneau cible, stand cible) goes to the demandeur — two different
     * créneaux, "I give you my Monday, I take your Tuesday". Both seats must
     * exist; feasibility is judged planning-wide like the plain variant.
     */
    public EchangeSimulation simulerEchangeDirige(PlanningFestival solved, String demandeurId, String cibleId,
            long creneauId, String standId, long creneauCibleId, String standCibleId) {
        PosteAffectation posteDemandeur = posteDe(solved, demandeurId, creneauId, standId);
        PosteAffectation posteCible = posteDe(solved, cibleId, creneauCibleId, standCibleId);
        Animateur demandeur = posteDemandeur.getAnimateur();
        Animateur cible = posteCible.getAnimateur();

        ScoreAnalysis<?> avant = solutionManager.analyze(solved);
        ScoreAnalysis<?> apres;
        posteDemandeur.setAnimateur(cible);
        posteCible.setAnimateur(demandeur);
        try {
            apres = solutionManager.analyze(solved);
        } finally {
            posteDemandeur.setAnimateur(demandeur);
            posteCible.setAnimateur(cible);
        }

        HardMediumSoftScore scoreAvant = (HardMediumSoftScore) avant.score();
        HardMediumSoftScore scoreApres = (HardMediumSoftScore) apres.score();
        return new EchangeSimulation(
                posteDemandeur.getId(),
                posteCible.getId(),
                true,
                posteCible.getStand().getId(),
                scoreAvant, scoreApres, scoreApres.subtract(scoreAvant),
                scoreApres.hardScore() < scoreAvant.hardScore(),
                violationsDuresSupplementaires(avant, apres));
    }

    /** The seat {@code animateurId} holds on (créneau, stand), or throws in business words. */
    private static PosteAffectation posteDe(PlanningFestival solved, String animateurId, long creneauId,
            String standId) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId())
                        && poste.getCreneau() != null && poste.getCreneau().getId() != null
                        && poste.getCreneau().getId() == creneauId
                        && poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Invalide(
                        "Aucun poste de l'animateur " + animateurId + " sur ce créneau et ce stand"));
    }

    /**
     * The hard constraints with strictly more matches after the simulated swap
     * than before, each carried with its business description from the
     * {@link ConstraintCatalog} — what the animateur (and the admin) reads,
     * rather than a technical constraint dump.
     */
    private static List<ViolationDure> violationsDuresSupplementaires(ScoreAnalysis<?> avant,
            ScoreAnalysis<?> apres) {
        Map<String, Integer> matchesAvant = new HashMap<>();
        for (ConstraintAnalysis<?> ca : avant.constraintAnalyses()) {
            matchesAvant.put(ca.constraintRef().constraintName(), ca.matchCount());
        }
        List<ViolationDure> violations = new ArrayList<>();
        for (ConstraintAnalysis<?> ca : apres.constraintAnalyses()) {
            String name = ca.constraintRef().constraintName();
            if (!HARD_CONSTRAINT_NAMES.contains(name)) {
                continue;
            }
            int supplement = ca.matchCount() - matchesAvant.getOrDefault(name, 0);
            if (supplement <= 0) {
                continue;
            }
            ConstraintCatalog.ConstraintDefinition definition = DEFINITIONS_PAR_NOM.get(name);
            violations.add(new ViolationDure(name,
                    definition == null ? name : definition.description(), supplement));
        }
        return violations;
    }

    /** @return one {@link ContrainteImpact} per constraint that matches (violées) or does not (respectées) for {@code poste}. */
    private static List<ContrainteImpact> impactsPour(ScoreAnalysis<?> analysis, PosteAffectation poste, boolean violees) {
        List<ContrainteImpact> impacts = new ArrayList<>();
        for (ConstraintAnalysis<?> ca : analysis.constraintAnalyses()) {
            List<? extends MatchAnalysis<?>> matches = ca.matches().stream()
                    .filter(match -> concerne(match, poste))
                    .toList();
            if (matches.isEmpty() == violees) {
                continue;
            }
            ConstraintCatalog.ConstraintDefinition definition = DEFINITIONS_PAR_NOM.get(ca.constraintRef().constraintName());
            impacts.add(new ContrainteImpact(
                    ca.constraintRef().constraintName(),
                    definition == null ? null : definition.niveau().name(),
                    definition == null ? null : definition.categorie(),
                    definition == null ? null : definition.description(),
                    matches.size(),
                    formatViolations(matches)));
        }
        return impacts;
    }

    /** True when {@code poste} itself appears among a match's justification facts, flattening any collection fact. */
    private static boolean concerne(MatchAnalysis<?> match, PosteAffectation poste) {
        return factsOf(match).stream().anyMatch(fact -> concerneFait(fact, poste));
    }

    private static boolean concerneFait(Object fact, PosteAffectation poste) {
        if (fact instanceof Collection<?> collection) {
            return collection.stream().anyMatch(element -> concerneFait(element, poste));
        }
        return fact == poste;
    }

    private static PosteAffectation trouverPoste(PlanningFestival solved, String posteId) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getId().equals(posteId))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Introuvable("Poste inconnu: " + posteId));
    }

    private static Animateur trouverAnimateur(PlanningFestival solved, String animateurId) {
        return solved.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new ErreurMetier.Introuvable("Animateur inconnu: " + animateurId));
    }

    /**
     * One constraint's impact on a single poste: either one of the violations
     * it is party to (see {@link #expliquerAffectation}), or an entry meaning
     * this constraint had no match involving that poste.
     *
     * @param details one human-readable line per match (see {@link ViolationFormatter}), empty when not violated
     */
    public record ContrainteImpact(String name, String niveau, String categorie, String description,
            int matchCount, List<String> details) {
    }

    /** @param animateurId the poste's current occupant, {@code null} when unassigned */
    public record AffectationExplanation(String posteId, String animateurId, HardMediumSoftScore score,
            List<ContrainteImpact> contraintesViolees, List<ContrainteImpact> contraintesRespectees) {
    }

    /**
     * @param animateurActuelId    the poste's occupant before the simulation, {@code null} when unassigned
     * @param animateurCandidatId  the animateur substituted in for the simulation
     * @param delta                {@code scoreApres - scoreAvant}: positive/less-negative means the swap improves the score
     */
    public record SwapSimulation(String posteId, String animateurActuelId, String animateurCandidatId,
            HardMediumSoftScore scoreAvant, HardMediumSoftScore scoreApres, HardMediumSoftScore delta,
            List<ContrainteImpact> contraintesVioleesAvant, List<ContrainteImpact> contraintesVioleesApres) {
    }

    /**
     * Result of {@link #simulerEchange}: what a demande d'échange would do to
     * the persisted planning, without persisting anything.
     *
     * @param posteCibleId  the cible's own seat on the same créneau, {@code null}
     *                      when the cible is free there (simple takeover)
     * @param echangeCroise true when both seats swap occupants
     * @param standCibleId  stand of {@code posteCibleId}, {@code null} on takeover
     * @param casseContrainteDure true when the swap makes the global hard score
     *                      worse — the prevalidation verdict shown to the animateur
     */
    public record EchangeSimulation(String posteDemandeurId, String posteCibleId, boolean echangeCroise,
            String standCibleId, HardMediumSoftScore scoreAvant, HardMediumSoftScore scoreApres,
            HardMediumSoftScore delta, boolean casseContrainteDure,
            List<ViolationDure> nouvellesViolationsDures) {
    }

    /** One hard constraint the simulated échange would newly violate, in business words. */
    public record ViolationDure(String name, String description, int matchesSupplementaires) {
    }

    /**
     * Builds the diagnostic of an already-solved planning, without solving it
     * again. Used right after {@link #resoudre} so a solve is never run twice
     * just to produce its own analysis.
     */
    /**
     * Re-derives the constraint analysis of the plan currently persisted —
     * called after a snapshot restore rewrote {@code poste_affectation}
     * outside of any solve. Without it, the Contraintes screen kept
     * describing the <b>last solve</b>: after a group switch plus a one-click
     * restore (the issue #167 flow), it still showed the previous group's
     * hard violations against the freshly restored plan. Runs the same
     * preparation as a solve (ad hoc constraints, legal parameters, toggles,
     * weights) so the diagnostic is comparable to a post-solve one. Returns
     * {@code null} when nothing is persisted.
     */
    public PlanningDiagnostic diagnostiquerPlanPersiste() {
        PlanningFestival persisted = planningPersistenceService.loadPersistedPlanning();
        if (persisted.getPostes().isEmpty()) {
            return null;
        }
        prepareProblem(persisted);
        // diagnostiquer() reads the solution's own score (hardScore, medium
        // breakdown): a freshly reloaded plan has none until update() sets it.
        solutionManager.update(persisted);
        return diagnostiquer(persisted);
    }

    public PlanningDiagnostic diagnostiquer(PlanningFestival solved) {
        ScoreAnalysis<?> analysis = solutionManager.analyze(solved);
        List<ConstraintDiagnostic> constraintDiagnostics = new ArrayList<>();
        for (ConstraintAnalysis<?> ca : analysis.constraintAnalyses()) {
            String name = ca.constraintRef().constraintName();
            List<String> violations = HARD_CONSTRAINT_NAMES.contains(name)
                    ? formatViolations(ca.matches())
                    : List.of();
            constraintDiagnostics.add(new ConstraintDiagnostic(
                    name,
                    String.valueOf(ca.score()),
                    ca.matchCount(),
                    violations));
        }
        constraintDiagnostics.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));
        int unassigned = (int) solved.getPostes().stream()
                .filter(p -> p.getAnimateur() == null)
                .count();
        FeasibilityAnalyzer.FeasibilityReport faisabilite = feasibilityAnalyzer.analyser(
                solved.getAnimateurs(), distinctStands(solved), distinctCreneaux(solved));
        int hardScore = solved.getScore() == null ? 0 : solved.getScore().hardScore();
        return new PlanningDiagnostic(String.valueOf(solved.getScore()), unassigned, constraintDiagnostics,
                faisabilite, hardScore);
    }

    /**
     * One line per match, human-readable (see {@link ViolationFormatter}) —
     * e.g. "Sarah Rousseau (A45)" for a {@code reposHebdomadaireMineur} hit, or
     * "Stand tir à l'arc — 2026-07-16 12:30-15:30" for an unfilled
     * {@code posteDoitEtrePourvu} seat. Capped at {@link #MAX_VIOLATIONS_PAR_CONTRAINTE}:
     * this feeds a UI detail popup, not an export.
     */
    private static List<String> formatViolations(List<? extends MatchAnalysis<?>> matches) {
        return matches.stream()
                .limit(MAX_VIOLATIONS_PAR_CONTRAINTE)
                .map(match -> ViolationFormatter.describe(factsOf(match)))
                .toList();
    }

    private static List<Object> factsOf(MatchAnalysis<?> match) {
        ConstraintJustification justification = match.justification();
        if (justification instanceof DefaultConstraintJustification defaultJustification) {
            return defaultJustification.getFacts();
        }
        return List.of(justification);
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

    /**
     * @param violations one human-readable line per match (see
     *                    {@link ViolationFormatter}), populated only for
     *                    constraints enforced at
     *                    {@link ConstraintCatalog.Niveau#HARD} — empty for
     *                    medium/soft ones, which can run into the thousands
     *                    of matches (see {@link #HARD_CONSTRAINT_NAMES}).
     */
    public record ConstraintDiagnostic(String name, String score, int matchCount, List<String> violations) {
    }

    /**
     * Business-facing result of a solve/analyze: score, unfilled seats and
     * per-constraint breakdown. Deliberately excludes the {@link PlanningFestival}
     * itself (animateurs/stands/créneaux/postes) — that payload can reach several
     * dozens of MB and is consulted through the dedicated screens instead, which
     * load it from {@code /api/planning/persisted}.
     *
     * <p>{@code hardScore} is the actually-reached hard score, distinct from
     * {@code faisabilite}: the latter is a cheap, optimistic pre-solve capacity
     * estimate (see {@link FeasibilityAnalyzer}'s javadoc — it can under-report a
     * shortfall it didn't account for, e.g. one only created by the vacation
     * découpage or by a legal constraint on minors) and can say "réalisable"
     * for a plan the solver still could not bring to zero hard within its time
     * budget. Callers that need to know whether the plan actually in hand is
     * fully legal/staffed must check {@code hardScore == 0}, not just
     * {@code faisabilite.feasible()}.</p>
     */
    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            FeasibilityAnalyzer.FeasibilityReport faisabilite,
            int hardScore) {
    }

    /**
     * Reads a window's end hour, {@code null} (absent or explicitly empty)
     * meaning "until closing time" — see {@link FenetreHoraire}. A missing end
     * used to be a hard error; it is now the way to say "to whatever hour this
     * day closes at", which is what lets one rule cover days closing at 20:00
     * and days closing at midnight alike.
     */
    private static LocalTime parseHeureOuFinDeJournee(Object value) {
        if (value == null) {
            return null;
        }
        String texte = value.toString().trim();
        return texte.isEmpty() ? null : LocalTime.parse(texte);
    }

    /**
     * Reads the {@code horaires:} section of a stand — recurring rules, with the
     * day selector flattened onto the rule (see {@link #horairesYaml}). An absent
     * {@code jours} reads as {@link TypeJoursHoraire#TOUS}, which is what makes
     * the common case a two-line entry.
     */
    private List<HoraireStand> lireHoraires(List<Map<String, Object>> horairesData) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (Map<String, Object> horaireData : horairesData) {
            HoraireStand horaire = new HoraireStand();
            String modeStr = (String) horaireData.get("mode");
            if (modeStr == null) {
                throw new ErreurMetier.Invalide("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
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
                throw new ErreurMetier.Invalide("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
            }
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (Map<String, Object> fenetreData : fenetresData) {
                Object heureDebut = fenetreData.get("heureDebut");
                if (heureDebut == null) {
                    throw new ErreurMetier.Invalide("Champ manquant: stands.horaires.fenetres.heureDebut");
                }
                fenetres.add(new FenetreHoraire(LocalTime.parse(heureDebut.toString()),
                        parseHeureOuFinDeJournee(fenetreData.get("heureFin"))));
            }
            horaire.setFenetres(fenetres);
            horaire.setMotif((String) horaireData.get("motif"));
            horaires.add(horaire);
        }
        return horaires;
    }

    private LocalDate parseLocalDate(Object value, String fieldName) {
        if (value == null) {
            throw new ErreurMetier.Invalide("Champ date manquant: " + fieldName);
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
        throw new ErreurMetier.Invalide(
                "Type de date non supporte pour " + fieldName + ": " + value.getClass().getName());
    }
}
