package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The exchange format of ADR 0085, proved before any transport exists: what
 * leaves is solved as what stayed would have been, names nobody, and comes
 * back only as a plan the application scores itself.
 *
 * <p>The problem is a ladder scenario pushed through everything the public
 * JSON hides — a published plan, weights, a frozen past, a lock — so that a
 * field this format dropped shows as a score that moved.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SolveExchangeTest {

    private static final String SCENARIO = "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml";

    /** Fixed work rather than fixed time: two solves of one problem then end on one plan, whatever the machine. */
    private static final long CALCULATIONS = 5_000L;

    private static final String LOCK_REASON = "Demande de Lou Martin";

    private static final String WINDOW_REASON = "Inauguration avec Tom Bernard";

    private final SolveExchange exchange = new SolveExchange(new ObjectMapper());

    private SolverConfiguration configuration;

    private SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager;

    private PlanningEvenement prepared;

    /** Who held what once the problem was prepared: what each test starts from again. */
    private Map<String, Animateur> startingHolders;

    @AfterEach
    void putTheStartingPlanBack() {
        prepared.getPostes().forEach(poste -> poste.setAnimateur(startingHolders.get(poste.getId())));
        solutionManager.update(prepared);
    }

    /** Once for the class: the fixture is a solve, and every test leaves the problem as it found it. */
    @BeforeAll
    void prepareAProblemCarryingWhatThePublicJsonHides() throws IOException {
        EmptyReferenceData referenceData = new EmptyReferenceData();
        configuration = new SolverConfiguration(
                30L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                ConfigProvider.getConfig());
        solutionManager = configuration.solutionManager();
        PlanningEvenement problem = ScenarioYamlReader.fromDto(
                        ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(SCENARIO)),
                        referenceData::getParametresLegaux)
                .planning();
        new SolveRunner(configuration, referenceData, null).prepareProblem(problem);
        prepared = solve(problem);

        List<LocalDate> dates = new ArrayList<>(dates(prepared));
        LocalDate first = dates.get(0);
        List<Animateur> animateurs = prepared.getAnimateurs();
        for (Animateur animateur : animateurs) {
            animateur.setEmail(animateur.getId().toLowerCase() + "@exemple.test");
            animateur.setTelephone(
                    "06" + String.format("%08d", animateur.getId().hashCode() & 0xFFFFFF));
            animateur.setAccessToken("token-" + animateur.getId());
        }
        // One of each age bracket, and one who comes of age on the second day.
        animateurs.get(0).setDateNaissance(dates.get(1).minusYears(18));
        animateurs.get(1).setDateNaissance(first.minusYears(17).minusDays(40));
        animateurs.get(2).setDateNaissance(first.minusYears(15));

        List<PosteAffectation> held = prepared.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null)
                .toList();
        prepared.setAffectationsPubliees(held.stream()
                .map(poste -> new AffectationPubliee(
                        poste.getStand().getId(),
                        poste.getCreneau().getDate(),
                        poste.getCreneau().getHeureDebut(),
                        poste.getCreneau().getHeureFin(),
                        poste.getAnimateur().getId()))
                .distinct()
                .toList());
        prepared.setPonderationsContraintes(ConstraintWeightOverrides.of(Map.of(
                "equilibrerCharge",
                HardMediumSoftScore.ofMedium(3),
                FeasibilityFirstSolve.STABILITY_RULE,
                HardMediumSoftScore.ofMedium(7))));

        PastHorizon horizon = new PastHorizon(first.plusDays(1), LocalTime.MIDNIGHT);
        prepared.setPastHorizon(horizon);
        FrozenPast.mark(prepared.getPostes(), horizon);
        FrozenPast.pin(prepared.getPostes());
        PosteAffectation locked =
                held.stream().filter(poste -> !poste.isVerrouille()).findFirst().orElseThrow();
        locked.setVerrouille(true);
        VerrouillagePlanning lock = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        lock.setAnimateurId(locked.getAnimateur().getId());
        lock.setRaison(LOCK_REASON);
        prepared.setVerrouillages(new ArrayList<>(List.of(lock)));

        // Resolved hours that differ from the stand's own windows, as a dated
        // exception leaves them.
        PosteAffectation anySeat = prepared.getPostes().get(0);
        anySeat.getStand()
                .setFenetresEffectives(
                        List.of(),
                        List.of(new OuvertureStand(
                                7L,
                                anySeat.getCreneau().getDate(),
                                LocalTime.of(10, 0),
                                LocalTime.of(12, 0),
                                WINDOW_REASON)));

        // Work left for the search: every third seat still free to move is emptied.
        int index = 0;
        for (PosteAffectation poste : prepared.getPostes()) {
            if (!poste.isVerrouille() && index++ % 3 == 0) {
                poste.setAnimateur(null);
            }
        }
        solutionManager.update(prepared);
        startingHolders = new HashMap<>();
        prepared.getPostes().forEach(poste -> startingHolders.put(poste.getId(), poste.getAnimateur()));
    }

    @Test
    void aProblemReadBackIsSolvedAsTheOneThatLeft() {
        PlanningEvenement readBack = exchange.read(exchange.write(prepared));

        assertThat(solutionManager.update(readBack))
                .as("the starting plan, scored on the far side")
                .isEqualTo(prepared.getScore());
        assertThat(readBack.getPostes().stream().filter(PosteAffectation::isPasse))
                .as("past seats")
                .hasSameSizeAs(prepared.getPostes().stream()
                        .filter(PosteAffectation::isPasse)
                        .toList())
                .isNotEmpty();
        assertThat(readBack.getAffectationsPubliees()).isEqualTo(prepared.getAffectationsPubliees());
        assertThat(readBack.getPastHorizon()).isEqualTo(prepared.getPastHorizon());
        assertThat(readBack.getPostes().get(0).getStand().getOuverturesEffectives())
                .as("the resolved opening hours of a stand")
                .singleElement()
                .satisfies(ouverture -> {
                    assertThat(ouverture.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
                    assertThat(ouverture.getHeureFin()).isEqualTo(LocalTime.of(12, 0));
                });
        assertThat(readBack.getPostes().get(0).getStand().getIndisponibilitesEffectives())
                .isEmpty();
        assertThat(holders(solve(readBack)))
                .as("the plan after %d calculations, same seed", CALCULATIONS)
                .isEqualTo(holders(solve(prepared)));
    }

    @Test
    void aPseudonymisedProblemScoresAsTheRealOneAndItsPlanLands() {
        HardMediumSoftScore start = prepared.getScore();
        Map<String, String> pinned = holders(prepared);
        pinned.keySet().removeIf(seat -> !seat(prepared, seat).isVerrouille());

        SolveExchange.Outbound outbound = exchange.outbound(prepared);
        PlanningEvenement farSide = exchange.read(outbound.payload());
        assertThat(solutionManager.update(farSide))
                .as("pseudonyms and stand-in birth dates change no score")
                .isEqualTo(start);

        PlanningEvenement solved = solve(farSide);
        SolveExchange.Result result = exchange.resultOf(solved, solutionManager);
        HardMediumSoftScore landed = exchange.land(prepared, result, outbound.realIds(), solutionManager);

        assertThat(landed.toString()).isEqualTo(result.score());
        assertThat(landed).as("the search improved the plan it was given").isGreaterThan(start);
        assertThat(prepared.getPostes())
                .as("every holder is one of the application's own animateurs")
                .allSatisfy(poste -> {
                    if (poste.getAnimateur() != null) {
                        assertThat(prepared.getAnimateurs()).contains(poste.getAnimateur());
                        assertThat(poste.getAnimateur().getEmail()).endsWith("@exemple.test");
                    }
                });
        assertThat(holders(prepared)).containsAllEntriesOf(pinned);
    }

    @Test
    void nothingThatNamesSomebodyLeaves() throws IOException {
        Animateur somebody = prepared.getAnimateurs().get(3);
        ContrainteAdHoc exception = prepared.getContraintesAdHoc().get(0);
        assertThat(somebody.getPrenom()).isNotBlank();
        assertThat(exception.getRaison()).isNotBlank();

        SolveExchange.Outbound outbound = exchange.outbound(prepared);
        byte[] json = new GZIPInputStream(new ByteArrayInputStream(outbound.payload())).readAllBytes();
        String payload = new String(json, StandardCharsets.UTF_8);

        for (Animateur animateur : prepared.getAnimateurs()) {
            assertThat(payload)
                    .doesNotContain("\"" + animateur.getId() + "\"")
                    .doesNotContain(animateur.getEmail())
                    .doesNotContain(animateur.getTelephone())
                    .doesNotContain(animateur.getAccessToken())
                    .doesNotContain("\"" + animateur.getPrenom() + "\"")
                    .doesNotContain("\"" + animateur.getNom() + "\"");
        }
        assertThat(payload)
                .doesNotContain(LOCK_REASON)
                .doesNotContain(WINDOW_REASON)
                .doesNotContain(exception.getRaison());

        JsonNode sent = new ObjectMapper().readTree(json).path("problem");
        Set<String> keys = new HashSet<>();
        sent.path("animateurs").forEach(animateur -> animateur.fieldNames().forEachRemaining(keys::add));
        sent.path("postes")
                .forEach(poste -> poste.path("animateur").fieldNames().forEachRemaining(keys::add));
        assertThat(keys)
                .as("what is written of a person, wherever they appear")
                .containsExactlyInAnyOrder(
                        "id", "dateNaissance", "competences", "souhaits", "joursIndisponibles", "ninja");
        assertThat(outbound.realIds().values())
                .as("the way back knows everybody")
                .containsAll(
                        prepared.getAnimateurs().stream().map(Animateur::getId).toList());
    }

    @Test
    void twoSolvesOfOneProblemShareNoPseudonym() {
        Set<String> first = exchange.outbound(prepared).realIds().keySet();
        Set<String> second = exchange.outbound(prepared).realIds().keySet();

        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    void aBirthDateLeavesOnlyWhenTheBracketChangesDuringTheEvent() {
        TreeSet<LocalDate> event =
                new TreeSet<>(List.of(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 21)));
        LocalDate adult = LocalDate.of(1991, 3, 14);
        LocalDate seventeen = LocalDate.of(2009, 2, 2);
        LocalDate fifteen = LocalDate.of(2011, 1, 1);
        LocalDate eighteenDuringTheEvent = LocalDate.of(2008, 7, 12);
        LocalDate sixteenOnTheLastDay = LocalDate.of(2010, 7, 21);

        assertThat(SolveExchange.standInBirthDate(adult, event)).isEqualTo(LocalDate.of(1970, 1, 1));
        assertThat(SolveExchange.standInBirthDate(seventeen, event))
                .isNotEqualTo(seventeen)
                .satisfies(standIn -> assertSameAnswers(seventeen, standIn, event));
        assertThat(SolveExchange.standInBirthDate(fifteen, event))
                .isNotEqualTo(fifteen)
                .satisfies(standIn -> assertSameAnswers(fifteen, standIn, event));
        assertThat(SolveExchange.standInBirthDate(eighteenDuringTheEvent, event))
                .isEqualTo(eighteenDuringTheEvent);
        assertThat(SolveExchange.standInBirthDate(sixteenOnTheLastDay, event)).isEqualTo(sixteenOnTheLastDay);
        assertThat(SolveExchange.standInBirthDate(null, event)).isNull();
    }

    @Test
    void aPlanWhoseScoreIsNotTheOneClaimedIsRefusedAndTheProblemLeftAsItWas() {
        Map<String, String> before = holders(prepared);
        HardMediumSoftScore start = prepared.getScore();
        SolveExchange.Outbound outbound = exchange.outbound(prepared);
        SolveExchange.Result honest = exchange.resultOf(solve(exchange.read(outbound.payload())), solutionManager);
        SolveExchange.Result lying = new SolveExchange.Result(honest.holders(), "0hard/0medium/0soft");

        assertThatThrownBy(() -> exchange.land(prepared, lying, outbound.realIds(), solutionManager))
                .isInstanceOf(SolveExchange.Refused.class)
                .hasMessageContaining("0hard/0medium/0soft");
        assertThat(holders(prepared)).isEqualTo(before);
        assertThat(prepared.getScore()).isEqualTo(start);
    }

    @Test
    void aPlanThatMovedAPinnedSeatOrNamesAStrangerIsRefused() {
        SolveExchange.Outbound outbound = exchange.outbound(prepared);
        SolveExchange.Result honest = exchange.resultOf(solve(exchange.read(outbound.payload())), solutionManager);
        String pinnedSeat = prepared.getPostes().stream()
                .filter(poste -> poste.isVerrouille() && poste.getAnimateur() != null)
                .findFirst()
                .orElseThrow()
                .getId();
        String somebodyElse = honest.holders().values().stream()
                .filter(pseudonym ->
                        pseudonym != null && !pseudonym.equals(honest.holders().get(pinnedSeat)))
                .findFirst()
                .orElseThrow();

        assertRefused(with(honest, pinnedSeat, somebodyElse), outbound, "pinned");
        assertRefused(with(honest, pinnedSeat, null), outbound, "pinned");
        String freeSeat = prepared.getPostes().stream()
                .filter(poste -> !poste.isVerrouille())
                .findFirst()
                .orElseThrow()
                .getId();
        assertRefused(with(honest, freeSeat, "p-unknown"), outbound, "does not know");
        Map<String, String> oneSeatShort = new LinkedHashMap<>(honest.holders());
        oneSeatShort.remove(freeSeat);
        assertRefused(new SolveExchange.Result(oneSeatShort, honest.score()), outbound, "seats");
    }

    private void assertRefused(SolveExchange.Result result, SolveExchange.Outbound outbound, String reason) {
        Map<String, String> before = holders(prepared);
        assertThatThrownBy(() -> exchange.land(prepared, result, outbound.realIds(), solutionManager))
                .isInstanceOf(SolveExchange.Refused.class)
                .hasMessageContaining(reason);
        assertThat(holders(prepared)).isEqualTo(before);
    }

    private static SolveExchange.Result with(SolveExchange.Result result, String seat, String pseudonym) {
        Map<String, String> holders = new LinkedHashMap<>(result.holders());
        holders.put(seat, pseudonym);
        return new SolveExchange.Result(holders, result.score());
    }

    private static void assertSameAnswers(LocalDate real, LocalDate standIn, TreeSet<LocalDate> dates) {
        Animateur asReal = new Animateur();
        asReal.setDateNaissance(real);
        Animateur asStandIn = new Animateur();
        asStandIn.setDateNaissance(standIn);
        for (LocalDate date : dates) {
            assertThat(asStandIn.isMineurOn(date)).isEqualTo(asReal.isMineurOn(date));
            assertThat(asStandIn.isUnder16On(date)).isEqualTo(asReal.isUnder16On(date));
        }
    }

    /** The production search, stopped on a count of score calculations instead of on time. */
    private PlanningEvenement solve(PlanningEvenement problem) {
        SolverConfig solverConfig = configuration.solverConfigFor(new SolveBudget(30L, 0L, null), problem);
        solverConfig.setTerminationConfig(new TerminationConfig().withScoreCalculationCountLimit(CALCULATIONS));
        SolverConfiguration.adaptToProblem(solverConfig, problem);
        return SolverFactory.<PlanningEvenement>create(solverConfig)
                .buildSolver()
                .solve(problem);
    }

    private static Map<String, String> holders(PlanningEvenement planning) {
        Map<String, String> holders = new HashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            holders.put(
                    poste.getId(),
                    poste.getAnimateur() == null ? null : poste.getAnimateur().getId());
        }
        return holders;
    }

    private static PosteAffectation seat(PlanningEvenement planning, String id) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static TreeSet<LocalDate> dates(PlanningEvenement planning) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        planning.getPostes().forEach(poste -> dates.add(poste.getCreneau().getDate()));
        return dates;
    }
}
