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
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DecoupageAutoConfig;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
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
            return chargerScenarioYaml(cheminScenario(scenarioName));
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
            throw new IllegalArgumentException("Nom de scénario invalide: " + name);
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
        // Resolved: construirePostes below asks each créneau which parts of it a
        // stand is open for, so the recurring horaires have to be expanded first.
        List<Stand> stands = referenceDataService.listStandsResolus();
        List<Creneau> creneaux = referenceDataService.listCreneauxGroupeActif();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence. Chargez un scénario ou créez des stands, "
                            + "des animateurs et des créneaux d'abord.");
        }
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        List<VerrouillagePlanning> verrouillages = referenceDataService.snapshotVerrouillagesGroupeActif();
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
        if (verrouillages.isEmpty() || animateursPersistes.isEmpty()) {
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
            List<String> tenants = animateursPersistes.getOrDefault(cle, List.of());
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
     * {@link #chargerScenarioYaml}, so the result can be dropped into the
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
        List<Creneau> creneaux = referenceDataService.listCreneauxGroupeActif();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence à exporter. Créez des stands, des animateurs et des créneaux d'abord.");
        }
        HoraireStandResolver.appliquer(stands, creneaux);

        DecoupageAutoConfig decoupageAuto = decoupageAutoDuGroupeActif();
        List<Creneau> creneauxExportes = creneaux;
        List<PosteAffectation> postes = construirePostes(stands, creneaux);
        if (decoupageAuto != null) {
            creneauxExportes = referenceDataService.listCreneaux().stream()
                    .filter(creneau -> creneau.getGroupe() != null
                            && decoupageAuto.groupeSourceNom().equals(creneau.getGroupe().getNom()))
                    .toList();
            postes = null; // regenerated on import, from the vacations the découpage recreates
        }
        return construireScenarioYaml(new ScenarioExport(
                animateurs,
                stands,
                creneauxExportes,
                postes,
                referenceDataService.listTypologies(),
                referenceDataService.listEmplacements(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.getParametresDecoupage(),
                referenceDataService.getParametresSolveur(),
                decoupageAuto));
    }

    /**
     * The {@code decoupageAuto:} section describing the active planning, or
     * {@code null} when it was not generated from another group. Both group
     * <em>names</em> are used rather than their ids: that is what
     * {@link DecoupageAutoConfig} matches on when the file is read back, on an
     * instance where the ids are not the same.
     */
    private DecoupageAutoConfig decoupageAutoDuGroupeActif() {
        List<GroupeCreneau> groupes = referenceDataService.listGroupesCreneaux();
        GroupeCreneau actif = groupes.stream().filter(GroupeCreneau::isActif).findFirst().orElse(null);
        if (actif == null || actif.getGroupeSourceId() == null) {
            return null;
        }
        return groupes.stream()
                .filter(groupe -> actif.getGroupeSourceId().equals(groupe.getId()))
                .findFirst()
                .map(source -> new DecoupageAutoConfig(source.getNom(), actif.getNom()))
                .orElse(null);
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
            List<ReferenceDataService.TypologieItem> typologies,
            List<Emplacement> emplacements,
            ParametresLegaux parametresLegaux,
            ParametresDecoupage parametresDecoupage,
            ParametresSolveur parametresSolveur,
            DecoupageAutoConfig decoupageAuto) {
    }

    /**
     * Builds the YAML text from already-fetched data. Package-private and
     * static, like {@link #construirePostes}, so it can be unit-tested without
     * a database.
     */
    static String construireScenarioYaml(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux,
            List<PosteAffectation> postes) {
        return construireScenarioYaml(new ScenarioExport(animateurs, stands, creneaux, postes, List.of(), List.of(),
                null, null, null, null));
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
                            .collect(Collectors.toList());
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
        if (export.decoupageAuto() != null) {
            Map<String, Object> decoupage = new LinkedHashMap<>();
            decoupage.put("groupeSourceNom", export.decoupageAuto().groupeSourceNom());
            decoupage.put("groupeCibleNom", export.decoupageAuto().groupeCibleNom());
            root.put("decoupageAuto", decoupage);
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

    /** Only the three fields a scenario file is read back with (see {@code chargerParametresLegauxScenario}). */
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

    private static List<Map<String, Object>> typologiesYaml(List<ReferenceDataService.TypologieItem> typologies) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReferenceDataService.TypologieItem typologie : typologies) {
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

    private PlanningFestival chargerScenarioYaml(String scenarioPath) throws IOException {
        return construirePlanningDepuisDonnees(lireDonneesScenario(scenarioPath));
    }

    @SuppressWarnings("unchecked")
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
        List<Map<String, Object>> postesList = (List<Map<String, Object>>) scenarioData.get("postes");
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
            throw new IllegalArgumentException("Le fichier est vide.");
        }
        Map<String, Object> scenarioData;
        try {
            scenarioData = parserYaml(
                    new java.io.ByteArrayInputStream(yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException("YAML invalide : " + messageOu(e), e);
        }
        PlanningFestival planning;
        try {
            planning = construirePlanningDepuisDonnees(scenarioData);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Scénario invalide : " + messageOu(e), e);
        }
        return new ScenarioImporte(planning, parseParametresLegaux(scenarioData),
                parseParametresDecoupage(scenarioData), parseParametresSolveur(scenarioData),
                parseDecoupageAuto(scenarioData), parseTypologies(scenarioData));
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
    public record ScenarioImporte(PlanningFestival planning, Optional<ParametresLegaux> parametresLegaux,
            Optional<ParametresDecoupage> parametresDecoupage, Optional<ParametresSolveur> parametresSolveur,
            Optional<DecoupageAutoConfig> decoupageAuto, List<ReferenceDataService.TypologieItem> typologies) {
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

    @SuppressWarnings("unchecked")
    private ReferenceScenario chargerReferenceScenario(Map<String, Object> scenarioData) {
        // Charger les creneaux : le fichier YAML porte un id texte historique
        // (utilisé seulement pour relier postes/creneaux entre eux), remplacé
        // ici par un id numérique synthétique ; jour est recalculé (voir
        // Creneau.assignerJours), la valeur du fichier est ignorée.
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
            List<Map<String, Object>> indisponibilitesData = (List<Map<String, Object>>) standData.get("indisponibilites");
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
            List<Map<String, Object>> ouverturesData = (List<Map<String, Object>>) standData.get("ouvertures");
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
            List<Map<String, Object>> horairesData = (List<Map<String, Object>>) standData.get("horaires");
            if (horairesData != null) {
                stand.setHoraires(lireHoraires(horairesData));
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
            Map<String, NiveauCompetence> competences = new HashMap<>();
            for (Map.Entry<String, String> entry : competencesData.entrySet()) {
                competences.put(entry.getKey(), NiveauCompetence.valueOf(entry.getValue()));
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

            // Charger les souhaits (typologies de stand souhaitées, sans niveau ni priorité)
            List<Object> souhaitsData = (List<Object>) animateurData.get("souhaits");
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
                .filter(ReferenceDataService.TypologieItem::ninja)
                .map(ReferenceDataService.TypologieItem::id)
                .findFirst()
                .orElse(null);
        animateurs.forEach(animateur -> animateur.appliquerTypologieNinja(typologieNinja));

        LocalDate dateDebut = parseLocalDate(
            ((Map<String, Object>) scenarioData.get("festival")).get("dateDebut"),
            "festival.dateDebut");

        return new ReferenceScenario(dateDebut, creneauxMap, standsMap, animateurs);
    }

    /** Shared YAML loading for {@link #chargerScenarioYaml} and the optional-section accessors below. */
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
            return parseParametresLegaux(lireDonneesScenario(cheminScenario(scenarioName)));
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
            return parseParametresDecoupage(lireDonneesScenario(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Reads the optional top-level {@code parametresSolveur:} section of a
     * scenario file, if present — lets a large/slow scenario pin the
     * termination duration it actually needs (e.g. {@code scenario-complet.yaml}
     * takes ~8 min to reach a good score) instead of relying on whichever
     * duration is currently configured in the Données tab. Absent, the
     * current database value is left untouched, same as
     * {@link #chargerParametresDecoupageScenario}.
     */
    public Optional<ParametresSolveur> chargerParametresSolveurScenario(String scenarioName) {
        try {
            return parseParametresSolveur(lireDonneesScenario(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Reads the optional top-level {@code decoupageAuto:} section of a
     * scenario file, if present — lets a scenario written straight in
     * "amplitudes" (one long opening window per day, e.g. {@code scenario-continu.yaml})
     * ask its import to auto-slice itself into vacations instead of leaving
     * the operator to run the "Découpage" screen by hand afterwards. See
     * {@link DecoupageAutoConfig}.
     */
    public Optional<DecoupageAutoConfig> chargerDecoupageAutoScenario(String scenarioName) {
        try {
            return parseDecoupageAuto(lireDonneesScenario(cheminScenario(scenarioName)));
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Reads the optional top-level {@code typologies:} section of a scenario
     * file, if present — a list of {@code {id, label}} pairs let the scenario
     * define its own typologie referential entries (e.g. {@code ENF} ->
     * {@code "Enfance"}) up front, instead of leaving every id a stand or
     * animateur references to fall back to the id-as-its-own-label default
     * {@code ReferenceDataRepository#importFromPlanning} derives on the fly.
     * Empty (not absent) when the section is missing, since a list has no
     * natural "absent" distinct from "empty".
     */
    public List<ReferenceDataService.TypologieItem> chargerTypologiesScenario(String scenarioName) {
        try {
            return parseTypologies(lireDonneesScenario(cheminScenario(scenarioName)));
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
            parametres.setFenetreRepasMidiDebut(parseLocalTime(data.get("fenetreRepasMidiDebut")));
        }
        if (data.get("fenetreRepasMidiFin") != null) {
            parametres.setFenetreRepasMidiFin(parseLocalTime(data.get("fenetreRepasMidiFin")));
        }
        if (data.get("fenetreRepasSoirDebut") != null) {
            parametres.setFenetreRepasSoirDebut(parseLocalTime(data.get("fenetreRepasSoirDebut")));
        }
        if (data.get("fenetreRepasSoirFin") != null) {
            parametres.setFenetreRepasSoirFin(parseLocalTime(data.get("fenetreRepasSoirFin")));
        }
        if (data.get("strategieCouverturePendantPause") != null) {
            parametres.setStrategieCouverturePendantPause(ParametresDecoupage.StrategieCouverturePendantPause
                    .valueOf((String) data.get("strategieCouverturePendantPause")));
        }
        if (data.get("nombreFamillesDecalage") != null) {
            parametres.setNombreFamillesDecalage(((Number) data.get("nombreFamillesDecalage")).intValue());
        }
        if (data.get("dureeDecalageMaxMinutes") != null) {
            parametres.setDureeDecalageMaxMinutes(((Number) data.get("dureeDecalageMaxMinutes")).intValue());
        }
        return Optional.of(parametres);
    }

    @SuppressWarnings("unchecked")
    private static Optional<ParametresSolveur> parseParametresSolveur(Map<String, Object> scenarioData) {
        Map<String, Object> data = (Map<String, Object>) scenarioData.get("parametresSolveur");
        if (data == null || data.get("dureeResolutionSecondes") == null) {
            return Optional.empty();
        }
        return Optional.of(new ParametresSolveur(((Number) data.get("dureeResolutionSecondes")).intValue()));
    }

    @SuppressWarnings("unchecked")
    private static Optional<DecoupageAutoConfig> parseDecoupageAuto(Map<String, Object> scenarioData) {
        Map<String, Object> data = (Map<String, Object>) scenarioData.get("decoupageAuto");
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(new DecoupageAutoConfig((String) data.get("groupeSourceNom"),
                (String) data.get("groupeCibleNom")));
    }

    @SuppressWarnings("unchecked")
    private static List<ReferenceDataService.TypologieItem> parseTypologies(Map<String, Object> scenarioData) {
        List<Map<String, Object>> data = (List<Map<String, Object>>) scenarioData.get("typologies");
        if (data == null) {
            return List.of();
        }
        List<ReferenceDataService.TypologieItem> typologies = new ArrayList<>();
        for (Map<String, Object> typologieData : data) {
            typologies.add(new ReferenceDataService.TypologieItem(
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
                .orElseThrow(() -> new IllegalArgumentException("Poste inconnu: " + posteId));
    }

    private static Animateur trouverAnimateur(PlanningFestival solved, String animateurId) {
        return solved.getAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Animateur inconnu: " + animateurId));
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
     * Builds the diagnostic of an already-solved planning, without solving it
     * again. Used right after {@link #resoudre} so a solve is never run twice
     * just to produce its own analysis.
     */
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
    @SuppressWarnings("unchecked")
    private List<HoraireStand> lireHoraires(List<Map<String, Object>> horairesData) {
        List<HoraireStand> horaires = new ArrayList<>();
        for (Map<String, Object> horaireData : horairesData) {
            HoraireStand horaire = new HoraireStand();
            String modeStr = (String) horaireData.get("mode");
            if (modeStr == null) {
                throw new IllegalArgumentException("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
            }
            horaire.setMode(ModeHoraire.valueOf(modeStr));
            String joursStr = (String) horaireData.getOrDefault("jours", TypeJoursHoraire.TOUS.name());
            horaire.setJours(TypeJoursHoraire.valueOf(joursStr));
            List<String> joursSemaine = (List<String>) horaireData.get("joursSemaine");
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
            List<Object> dates = (List<Object>) horaireData.get("dates");
            if (dates != null) {
                horaire.setDates(dates.stream().map(date -> parseLocalDate(date, "stands.horaires.dates"))
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
            List<Map<String, Object>> fenetresData = (List<Map<String, Object>>) horaireData.get("fenetres");
            if (fenetresData == null || fenetresData.isEmpty()) {
                throw new IllegalArgumentException("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
            }
            List<FenetreHoraire> fenetres = new ArrayList<>();
            for (Map<String, Object> fenetreData : fenetresData) {
                Object heureDebut = fenetreData.get("heureDebut");
                if (heureDebut == null) {
                    throw new IllegalArgumentException("Champ manquant: stands.horaires.fenetres.heureDebut");
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
