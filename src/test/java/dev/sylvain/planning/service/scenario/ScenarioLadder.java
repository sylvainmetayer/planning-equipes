package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ReferenceScenario;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ScenarioSections;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.constraints.ExclusionEligibilite;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The scenario ladder, the {@code gamme-…} files of
 * {@code src/main/resources/scenarios/}: files
 * that grow from one day, two stands and three animateurs to a month-long
 * event, each one exercising a part of what a scenario can say — day
 * templates, recurring openings, minors, ad hoc rules, the découpage, switched
 * off constraints — so that a change anywhere in the pipeline meets a file
 * that depends on it.
 *
 * <p>The problem is built the way production builds it, without a database:
 * the same binder and mapper as the import, the recurring openings resolved on
 * the grid's dates, the découpage run when the file asks for it, and the file's
 * own legal parameters, meal windows, weights and switched-off rules handed to
 * the solver. Three things a plain-Java harness has to do by hand, and which
 * the older scenario tests learnt one incident at a time: resolving the
 * {@code horaires}, giving the sliced vacations the ids the repository would —
 * the generator leaves them unset — and applying {@code contraintes.desactivees},
 * which the mapper leaves to the import.</p>
 *
 * <p>{@link #assertCoreRules} re-checks the rules a zero hard score stands
 * for, computed from the plan rather than read from the score: a constraint
 * that silently stopped penalising would still leave the score at zero, and
 * these checks would not follow it there.</p>
 */
final class ScenarioLadder {

    /**
     * The one classpath folder every scenario lives in, ladder and extremes
     * included since they became selectable from the interface: the selector
     * reads that folder flat, and {@code ScenarioYamlReader.scenarioPath}
     * refuses a name carrying a path component.
     */
    static final String FOLDER = "scenarios/";

    /** Prefix of the files that probe the limits rather than the features — see {@code ScenarioExtreme*Test}. */
    static final String EXTREME_PREFIX = "extreme-";

    /** Prefix of the ladder's own files. */
    static final String GAMME_PREFIX = "gamme-";

    private ScenarioLadder() {}

    /**
     * A ladder file, read and turned into the problem production would solve.
     *
     * @param stands   every stand of the file, its recurring openings resolved
     * @param creneaux the grid the seats were generated from — the file's own,
     *                 or the vacations the découpage cut from its amplitudes
     */
    record Loaded(
            String name,
            ScenarioDto dto,
            ScenarioSections sections,
            PlanningEvenement problem,
            List<Stand> stands,
            List<Creneau> creneaux) {

        FeasibilityAnalyzer.FeasibilityReport feasibility() {
            // With the file's own constraint states: the estimate of how many
            // seats a team can hold depends on the supervision of minors, so a
            // file that asks for that rule must be analysed under it.
            return new FeasibilityAnalyzer()
                    .analyze(
                            problem.getAnimateurs(),
                            stands,
                            creneaux,
                            problem.getContraintesAdHoc(),
                            encadrementMineursActif(problem));
        }

        /** What applying the file's day templates to its own grid would change: nothing, for a consistent file. */
        JourneesTypesMaterialisation.Plan dayTemplatesPlan() {
            ScenarioYamlReader.JourneesTypesScenario section =
                    sections.journeesTypes().orElseThrow();
            return JourneesTypesMaterialisation.planifier(section.journeesTypes(), section.calendrier(), creneaux);
        }
    }

    static String yaml(String name) {
        try (InputStream in = ScenarioLadder.class.getClassLoader().getResourceAsStream(FOLDER + name + ".yaml")) {
            if (in == null) {
                throw new IllegalArgumentException("No scenario named " + name + " under " + FOLDER);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Loaded load(String name) {
        ScenarioDto dto = ScenarioBinder.bind(yaml(name));
        ScenarioSections sections = ScenarioDomainMapper.sections(dto);
        PlanningEvenement problem = ScenarioDomainMapper.planning(dto, ParametresLegaux::new);
        ReferenceScenario reference = ScenarioDomainMapper.reference(dto);
        List<Stand> stands = new ArrayList<>(reference.standsById().values());
        List<Creneau> creneaux = new ArrayList<>(reference.creneauxParId().values());
        HoraireStandResolver.apply(stands, creneaux);
        // Both directions, as the import does: what the file switches off, and
        // what it switches on although the catalogue ships it off. A rule it
        // names in neither list keeps the catalogue's own state.
        sections.contraintes().ifPresent(contraintes -> {
            List<ConstraintToggle> toggles = new ArrayList<>();
            contraintes.desactivees().forEach(nom -> toggles.add(new ConstraintToggle(nom, false)));
            contraintes.activees().forEach(nom -> toggles.add(new ConstraintToggle(nom, true)));
            problem.setConstraintsDesactivees(toggles);
        });
        return new Loaded(name, dto, sections, problem, stands, creneaux);
    }

    /** The state of {@code mineurNecessiteEncadrementMajeur} on a problem, catalogue default included. */
    static boolean encadrementMineursActif(PlanningEvenement problem) {
        List<ConstraintToggle> toggles = problem.getConstraintsDesactivees();
        if (toggles != null) {
            for (ConstraintToggle toggle : toggles) {
                if (ExclusionEligibilite.ENCADREMENT_DES_MINEURS.equals(toggle.getNom())) {
                    return toggle.isActif();
                }
            }
        }
        return ConstraintCatalog.activeByDefault(ExclusionEligibilite.ENCADREMENT_DES_MINEURS);
    }

    /* ------------------------------- solving ------------------------------- */

    static PlanningService service() {
        return new PlanningService(
                420L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());
    }

    /** Stops the moment the hard score reaches zero; {@code ceilingSeconds} only bounds a run that does not. */
    static PlanningEvenement solveUntilFeasible(Loaded loaded, long ceilingSeconds) {
        return service().solveUntilFeasible(loaded.problem(), ceilingSeconds);
    }

    /** Spends the whole budget, for the files whose assertions read the medium or soft level. */
    static PlanningEvenement solveFor(Loaded loaded, long seconds) {
        return service().solve(loaded.problem(), seconds);
    }

    /** Match count per constraint of the plan, only those that matched. */
    static Map<String, Integer> matchCounts(PlanningEvenement solved) {
        return service().diagnose(solved).contraintes().stream()
                .filter(diagnostic -> diagnostic.matchCount() > 0)
                .collect(Collectors.toMap(
                        ConstraintDiagnostic::name, ConstraintDiagnostic::matchCount, Integer::sum, TreeMap::new));
    }

    static Set<String> brokenHardConstraints(PlanningEvenement solved) {
        return matchCounts(solved).keySet().stream()
                .filter(ConstraintCatalog.NOMS_DURS::contains)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /* ------------------------------ reading ------------------------------ */

    static Map<Animateur, List<PosteAffectation>> seatsByAnimateur(PlanningEvenement solved) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null)
                .collect(Collectors.groupingBy(PosteAffectation::getAnimateur));
    }

    static List<PosteAffectation> seatsOf(PlanningEvenement solved, String animateurId) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getAnimateur().getId().equals(animateurId))
                .toList();
    }

    static List<PosteAffectation> seatsOnStand(Collection<PosteAffectation> postes, String standId) {
        return postes.stream()
                .filter(poste -> poste.getStand().getId().equals(standId))
                .toList();
    }

    /** Seats per stand id and date, the shape a scenario's openings are easiest to state in. */
    static Map<String, Map<LocalDate, Long>> seatCountByStandAndDate(Collection<PosteAffectation> postes) {
        return postes.stream()
                .collect(Collectors.groupingBy(
                        poste -> poste.getStand().getId(),
                        TreeMap::new,
                        Collectors.groupingBy(
                                poste -> poste.getCreneau().getDate(), TreeMap::new, Collectors.counting())));
    }

    static long startMinute(PosteAffectation poste) {
        return epochMinute(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    static long endMinute(PosteAffectation poste) {
        long start = startMinute(poste);
        long end = epochMinute(poste.getCreneau().getDate(), poste.heureFinEffectif());
        return end <= start ? end + 24 * 60 : end;
    }

    private static long epochMinute(LocalDate date, LocalTime time) {
        return date.toEpochDay() * 24 * 60 + time.toSecondOfDay() / 60;
    }

    static boolean isUnderSixteen(Animateur animateur, LocalDate date) {
        return Period.between(animateur.getDateNaissance(), date).getYears() < 16;
    }

    static String isoWeek(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-W" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /* ----------------------------- assertions ----------------------------- */

    static void assertFeasible(PlanningEvenement solved) {
        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore())
                .as("hard score, broken rules %s", brokenHardConstraints(solved))
                .isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
        assertCoreRules(solved);
    }

    /**
     * The rules every ladder file must keep, checked on the plan itself: nobody
     * on a day off or on two overlapping seats, nobody on a stand outside their
     * skills, no minor on a stand reserved to adults, on a public holiday or at
     * night, nobody past six days in a week.
     */
    static void assertCoreRules(PlanningEvenement solved) {
        Map<Animateur, List<PosteAffectation>> parAnimateur = seatsByAnimateur(solved);
        for (Map.Entry<Animateur, List<PosteAffectation>> entry : parAnimateur.entrySet()) {
            Animateur animateur = entry.getKey();
            List<PosteAffectation> postes = entry.getValue().stream()
                    .sorted((a, b) -> Long.compare(startMinute(a), startMinute(b)))
                    .toList();
            for (int i = 0; i < postes.size(); i++) {
                PosteAffectation poste = postes.get(i);
                LocalDate date = poste.getCreneau().getDate();
                assertThat(animateur.getJoursIndisponibles())
                        .as("%s works on a day off, %s", animateur.getId(), date)
                        .doesNotContain(date);
                if (i > 0) {
                    assertThat(startMinute(poste))
                            .as("%s holds two overlapping seats on %s", animateur.getId(), date)
                            .isGreaterThanOrEqualTo(endMinute(postes.get(i - 1)));
                }
                if (animateur.isMineurOn(date)) {
                    assertMinorSeat(solved, animateur, poste);
                }
            }
            Map<String, Set<LocalDate>> joursParSemaine = postes.stream()
                    .map(poste -> poste.getCreneau().getDate())
                    .collect(Collectors.groupingBy(ScenarioLadder::isoWeek, Collectors.toSet()));
            joursParSemaine.forEach((semaine, jours) -> assertThat(jours)
                    .as("%s works more than six days in %s", animateur.getId(), semaine)
                    .hasSizeLessThanOrEqualTo(6));
        }
        assertAdHocRules(solved);
    }

    /** The three prescriptive ad hoc kinds, read on the plan; the affinity is a reward and binds nothing. */
    private static void assertAdHocRules(PlanningEvenement solved) {
        for (ContrainteAdHoc contrainte : solved.getContraintesAdHoc()) {
            List<PosteAffectation> perimetre = solved.getPostes().stream()
                    .filter(poste ->
                            contrainte.getStand() == null || poste.getStand().equals(contrainte.getStand()))
                    .filter(poste -> contrainte.getCreneau() == null
                            || poste.getCreneau().equals(contrainte.getCreneau()))
                    .toList();
            List<String> concernes = contrainte.getAnimateursConcernes().stream()
                    .map(Animateur::getId)
                    .toList();
            switch (contrainte.getType()) {
                case INDISPONIBILITE_FORCEE ->
                    assertThat(perimetre)
                            .as("ad hoc %s", contrainte.getId())
                            .noneMatch(poste -> poste.getAnimateur() != null
                                    && concernes.contains(poste.getAnimateur().getId()));
                case AFFECTATION_FORCEE ->
                    assertThat(perimetre)
                            .as("ad hoc %s", contrainte.getId())
                            .anyMatch(poste -> poste.getAnimateur() != null
                                    && concernes.contains(poste.getAnimateur().getId()));
                case INCOMPATIBILITE -> {
                    Set<Creneau> creneauxDuPremier = seatsOf(solved, concernes.get(0)).stream()
                            .map(PosteAffectation::getCreneau)
                            .collect(Collectors.toSet());
                    assertThat(seatsOf(solved, concernes.get(1)))
                            .as("ad hoc %s", contrainte.getId())
                            .noneMatch(poste -> creneauxDuPremier.contains(poste.getCreneau()));
                }
                case AFFINITE -> {
                    // A soft reward: nothing to hold.
                }
            }
        }
    }

    private static void assertMinorSeat(PlanningEvenement solved, Animateur mineur, PosteAffectation poste) {
        LocalDate date = poste.getCreneau().getDate();
        assertThat(JoursFeries.isFerieInFrance(date))
                .as("minor %s works on the public holiday %s", mineur.getId(), date)
                .isFalse();
        assertThat(poste.getStand().isReserveMajeurs())
                .as(
                        "minor %s on the adults-only stand %s",
                        mineur.getId(), poste.getStand().getId())
                .isFalse();
        long debutNuit = epochMinute(date, isUnderSixteen(mineur, date) ? LocalTime.of(20, 0) : LocalTime.of(22, 0));
        long finNuitPrecedente = epochMinute(date, LocalTime.of(6, 0));
        assertThat(startMinute(poste) >= finNuitPrecedente && endMinute(poste) <= debutNuit)
                .as("minor %s works at night on %s", mineur.getId(), date)
                .isTrue();
        // Nothing here about an adult beside them: the catalogue ships
        // mineurNecessiteEncadrementMajeur switched off (issue #595), so a
        // minor holding a day stand alone is a correct plan, not a breach. The
        // files that do want the rule ask for it, and their own test checks it.
    }
}
