package dev.sylvain.planning.service.analyse;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.diagnostic.BlockerPlaybook;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.diagnostic.PlanningAnalysis;
import dev.sylvain.planning.service.referentiel.ForcedAssignmentOnLockedSchedule;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintFloorRules;
import dev.sylvain.planning.solver.ConstraintFloorRules.Denominator;
import dev.sylvain.planning.solver.ConstraintFloorRules.FloorRule;
import dev.sylvain.planning.solver.ConstraintFloorRules.MissingData;
import dev.sylvain.planning.solver.constraints.ExclusionEligibilite;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * Turns a solved — or merely persisted — planning into the business-facing
 * diagnostic the Contraintes screen reads: score, unfilled seats,
 * per-constraint breakdown, feasibility, and which hand-entered exceptions the
 * still-violated hard rules are about (issue #84).
 *
 * <p>Split out of {@link PlanningService}, which keeps the two public entry
 * points as a façade. It stays in {@code service/} rather than joining
 * {@code service/diagnostic/}: it needs {@link FeasibilityAnalyzer} from here,
 * and {@code service} already imports {@code service.diagnostic}, so the move
 * would close a package cycle the repository does not have. That belongs to the
 * repackaging pass (A4 of the audit), which decides the whole layout at once.</p>
 */
public final class PlanningDiagnosticService {

    private static final Logger LOG = Logger.getLogger(PlanningDiagnosticService.class);

    private final ConstraintDiagnosticService constraintDiagnosticService;

    /**
     * Kept for {@link SolutionManager#update} alone — a freshly reloaded plan
     * carries no score until it runs, and {@link #diagnose} reads that score.
     */
    private final SolutionManager<PlanningEvenement, ?> solutionManager;

    private final FeasibilityAnalyzer feasibilityAnalyzer;

    /** The plan currently in the database, read only by {@link #diagnosePersistedPlan()}. */
    private final Supplier<PlanningEvenement> planPersiste;

    /**
     * The same preparation a solve runs (ad hoc constraints, legal parameters,
     * toggles, weights), so a diagnostic of a reloaded plan is comparable to a
     * post-solve one.
     */
    private final Consumer<PlanningEvenement> preparation;

    public PlanningDiagnosticService(
            ConstraintDiagnosticService constraintDiagnosticService,
            SolutionManager<PlanningEvenement, ?> solutionManager,
            FeasibilityAnalyzer feasibilityAnalyzer,
            Supplier<PlanningEvenement> planPersiste,
            Consumer<PlanningEvenement> preparation) {
        this.constraintDiagnosticService = constraintDiagnosticService;
        this.solutionManager = solutionManager;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.planPersiste = planPersiste;
        this.preparation = preparation;
    }

    /** Caps the per-constraint violation list: a UI detail view, not a full dump. */
    private static final int MAX_VIOLATIONS_PAR_CONTRAINTE = 100;

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
    public PlanningDiagnostic diagnosePersistedPlan() {
        PlanningEvenement persisted = planPersiste.get();
        if (persisted.getPostes().isEmpty()) {
            return null;
        }
        preparation.accept(persisted);
        // diagnose() reads the solution's own score (hardScore, medium
        // breakdown): a freshly reloaded plan has none until update() sets it.
        solutionManager.update(persisted);
        return diagnose(persisted);
    }

    /** How many units a medium or soft rule was evaluated on, and the floor it cannot go below. */
    private record EvaluatedFloor(Integer evaluated, ConstraintFloor floor) {
        static final EvaluatedFloor NONE = new EvaluatedFloor(null, null);
    }

    private static EvaluatedFloor evaluatedFloor(
            ConstraintContribution ca, Map<Denominator, Integer> evaluatedByDenominator, PlanningEvenement solved) {
        FloorRule rule = ConstraintFloorRules.of(ca.constraintName());
        if (rule == null) {
            return EvaluatedFloor.NONE;
        }
        // Several rules share a grain: each denominator is counted once per diagnostic.
        Integer evaluated =
                evaluatedByDenominator.computeIfAbsent(rule.denominator(), denominator -> denominator.count(solved));
        return new EvaluatedFloor(evaluated, floorOf(rule, ca.matchCount(), evaluated, solved));
    }

    /**
     * Builds the diagnostic of an already-solved planning, without solving it
     * again. Used right after {@link SolveRunner#solve(PlanningEvenement)} so a
     * solve is never run twice
     * just to produce its own analysis.
     */
    public PlanningDiagnostic diagnose(PlanningEvenement solved) {
        PlanningAnalysis analysis = constraintDiagnosticService.analyze(solved);
        List<ConstraintDiagnostic> constraintDiagnostics = new ArrayList<>();
        Map<String, ContributionAdHoc> contributionsAdHoc = new LinkedHashMap<>();
        // Every rule, not only the hard ones: « où se concentrent les écarts »
        // is asked of the medium rules too — six over-long days, referents
        // missing on one pavilion (issue #496). Counting is cheap where listing
        // is not, so this walk has no per-constraint cap.
        Map<String, List<MatchFacts>> matchesParContrainte = new LinkedHashMap<>();
        Map<Denominator, Integer> evaluatedByDenominator = new EnumMap<>(Denominator.class);
        long plancherMedium = 0;
        long plancherSoft = 0;
        Predicate<Creneau> timeslotFrozen = frozenTimeslots(solved.getPostes(), solved.getPastHorizon());
        for (ConstraintContribution ca : analysis.contributions()) {
            String name = ca.constraintName();
            boolean hard = ConstraintCatalog.NOMS_DURS.contains(name);
            if (!ca.matches().isEmpty()) {
                matchesParContrainte.put(name, ca.matches());
            }
            List<String> violations = List.of();
            List<ViolationFormatter.ViolationReference> references = List.of();
            EvaluatedFloor evaluatedFloor;
            if (hard) {
                violations = formatViolations(ca.matches());
                references = referenceViolations(ca.matches());
                collectContributionsAdHoc(name, ca.matches(), contributionsAdHoc);
                evaluatedFloor = EvaluatedFloor.NONE;
            } else {
                evaluatedFloor = evaluatedFloor(ca, evaluatedByDenominator, solved);
            }
            Integer evaluated = evaluatedFloor.evaluated();
            ConstraintFloor floor = evaluatedFloor.floor();
            if (floor != null) {
                plancherMedium += ca.score().mediumScore();
                plancherSoft += ca.score().softScore();
            }
            constraintDiagnostics.add(new ConstraintDiagnostic(
                    name,
                    String.valueOf(ca.score()),
                    ca.matchCount(),
                    violations,
                    evaluated,
                    floor,
                    references,
                    positionOf(
                            ca.matches(), onlyStartedTimeslots(ca.matches(), solved.getPastHorizon(), timeslotFrozen)),
                    BreachHotspots.of(ca.matches())));
        }
        constraintDiagnostics.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));
        HardMediumSoftScore floorScore = HardMediumSoftScore.of(0, plancherMedium, plancherSoft);
        String scoreHorsPlancher = solved.getScore() == null
                ? null
                : solved.getScore().subtract(floorScore).toString();
        int unassigned = (int) solved.getPostes().stream()
                .filter(p -> p.getAnimateur() == null)
                .count();
        FeasibilityAnalyzer.FeasibilityReport faisabilite = feasibilityAnalyzer.analyze(
                solved.getAnimateurs(),
                distinctStands(solved),
                distinctCreneaux(solved),
                solved.getContraintesAdHoc(),
                // The plan's own toggles, not the edition's: a diagnostic
                // describes the problem that was solved, and a solve launched
                // with the rule on stays described with it on.
                encadrementMineursActif(solved),
                // Same doctrine for the locks, the seats and the horizon: all
                // three are read off the plan handed here, never re-read from
                // the database or the clock, so a diagnostic describes the plan
                // it was given — including the moment its past was frozen at.
                new FeasibilityAnalyzer.PlanContext(
                        solved.getVerrouillages() == null ? List.of() : solved.getVerrouillages(),
                        () -> placesTenues(solved),
                        solved.getPastHorizon()));
        int hardScore = solved.getScore() == null
                ? 0
                : Math.toIntExact(solved.getScore().hardScore());
        List<ContributionAdHoc> contraintesAdHocEnCause = contributionsAdHoc.values().stream()
                .sorted(Comparator.comparingInt(ContributionAdHoc::violations)
                        .reversed()
                        .thenComparing(ContributionAdHoc::contrainteId))
                .toList();
        PlanningDiagnostic diagnostic = new PlanningDiagnostic(
                String.valueOf(solved.getScore()),
                unassigned,
                constraintDiagnostics,
                faisabilite,
                hardScore,
                contraintesAdHocEnCause,
                scoreHorsPlancher,
                borne(plancherMedium),
                borne(plancherSoft),
                PivotEcarts.of(matchesParContrainte));
        // Read once, here, so every screen and the MCP tool say the same thing.
        return diagnostic.withReading(
                readingOrNothing(() -> ScoreReading.read(diagnostic, Optional.empty(), disabledRules(solved))));
    }

    /**
     * The reading, or none when writing it failed. The sentences are a way in
     * to the tables, not the diagnostic itself: a template tripping on an
     * unexpected label must not take the diagnostic — hence the end of every
     * solve — down with it. Logged, so the defect is still seen.
     */
    static <T> List<T> readingOrNothing(Supplier<List<T>> reading) {
        try {
            List<T> sentences = reading.get();
            return sentences == null ? List.of() : sentences;
        } catch (RuntimeException e) {
            LOG.warn("The reading of the score could not be written; the diagnostic goes out without it", e);
            return List.of();
        }
    }

    /**
     * Names of the rules the plan handed here was solved with switched off —
     * its own toggles, then the catalogue's default, exactly as the solver
     * read them.
     */
    private static Set<String> disabledRules(PlanningEvenement solved) {
        return ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .filter(name -> !ConstraintCatalog.isActive(solved.getConstraintsDesactivees(), name))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Whether every match of a rule sits in the frozen past of ADR 0044 — on
     * seats the solve can no longer move. A seat is read by its own
     * {@link PosteAffectation#isPasse()} flag, the very one the preparation
     * set on its effective start; a bare timeslot is frozen when every seat
     * of it is ({@code timeslotFrozen}). A match naming neither (an
     * aggregate) is not known to be in the past, and neither is anything
     * without a horizon.
     */
    static boolean onlyStartedTimeslots(
            List<MatchFacts> matches, PastHorizon horizon, Predicate<Creneau> timeslotFrozen) {
        if (horizon == null || matches == null || matches.isEmpty()) {
            return false;
        }
        for (MatchFacts match : matches) {
            List<Boolean> frozen = new ArrayList<>();
            collectFrozen(match.facts(), timeslotFrozen, frozen);
            if (frozen.isEmpty() || frozen.contains(Boolean.FALSE)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether each timeslot of the plan is frozen: every seat of it past, as
     * the preparation marked them. A timeslot carrying no seat has nothing a
     * solve could move, and is read on its own start through the same
     * {@link PastHorizon#hasStarted} the freeze uses.
     */
    static Predicate<Creneau> frozenTimeslots(List<PosteAffectation> postes, PastHorizon horizon) {
        Map<Creneau, Boolean> allPast = new HashMap<>();
        for (PosteAffectation poste : postes == null ? List.<PosteAffectation>of() : postes) {
            if (poste.getCreneau() != null) {
                allPast.merge(poste.getCreneau(), poste.isPasse(), Boolean::logicalAnd);
            }
        }
        return creneau -> {
            Boolean past = allPast.get(creneau);
            if (past != null) {
                return past;
            }
            return horizon != null && horizon.hasStarted(creneau.getDate(), creneau.getHeureDebut());
        };
    }

    /**
     * Where the playbook's actions open for a rule: on the first match naming
     * a timeslot, directly or through a seat — its timeslot and day, the
     * stands and holders that same match names, and the game categories of
     * those stands. An aggregate names none, and its actions open bare.
     */
    static BlockerPlaybook.Context positionOf(List<MatchFacts> matches, boolean frozenPast) {
        for (MatchFacts match : matches == null ? List.<MatchFacts>of() : matches) {
            List<Object> facts = new ArrayList<>();
            unfold(match.facts(), facts);
            MatchPosition position = new MatchPosition();
            facts.forEach(position::read);
            if (position.creneau != null) {
                return new BlockerPlaybook.Context(
                        position.creneau.getId(),
                        position.creneau.getDate(),
                        position.stands.stream().map(Stand::getId).toList(),
                        typologieIds(position.stands),
                        List.of(),
                        List.copyOf(position.animateurIds),
                        frozenPast);
            }
        }
        return frozenPast
                ? new BlockerPlaybook.Context(null, null, List.of(), List.of(), List.of(), List.of(), true)
                : BlockerPlaybook.Context.NONE;
    }

    /** What one match names: its first timeslot, and the stands and holders it mentions. */
    private static final class MatchPosition {
        private Creneau creneau;
        private final Set<Stand> stands = new LinkedHashSet<>();
        private final Set<String> animateurIds = new LinkedHashSet<>();

        void read(Object fact) {
            if (fact instanceof PosteAffectation poste) {
                readSeat(poste);
            } else if (fact instanceof Creneau timeslot) {
                firstTimeslot(timeslot);
            } else if (fact instanceof Stand stand) {
                stands.add(stand);
            } else if (fact instanceof Animateur animateur) {
                animateurIds.add(animateur.getId());
            }
        }

        private void readSeat(PosteAffectation poste) {
            firstTimeslot(poste.getCreneau());
            if (poste.getStand() != null) {
                stands.add(poste.getStand());
            }
            if (poste.getAnimateur() != null) {
                animateurIds.add(poste.getAnimateur().getId());
            }
        }

        private void firstTimeslot(Creneau candidate) {
            if (creneau == null) {
                creneau = candidate;
            }
        }
    }

    private static List<String> typologieIds(Set<Stand> stands) {
        return stands.stream()
                .flatMap(stand -> stand.getTypologiesProposees() == null
                        ? Stream.<String>empty()
                        : stand.getTypologiesProposees().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /** A match's facts, collections unfolded. */
    private static void unfold(Collection<?> facts, List<Object> into) {
        for (Object fact : facts) {
            if (fact instanceof Collection<?> nested) {
                unfold(nested, into);
            } else {
                into.add(fact);
            }
        }
    }

    /** Whether each seat or timeslot a match names is frozen, collections unfolded. */
    private static void collectFrozen(Collection<?> facts, Predicate<Creneau> timeslotFrozen, List<Boolean> frozen) {
        for (Object fact : facts) {
            if (fact instanceof PosteAffectation poste) {
                frozen.add(poste.isPasse());
            } else if (fact instanceof Creneau creneau) {
                frozen.add(timeslotFrozen.test(creneau));
            } else if (fact instanceof Collection<?> nested) {
                collectFrozen(nested, timeslotFrozen, frozen);
            }
        }
    }

    /**
     * The floor reading of one non-hard constraint: {@code null} below
     * {@link ConstraintFloorRules#FLOOR_THRESHOLD}, when the rule evaluated
     * nothing (a ratio over zero items is not a floor, it is silence), and for
     * the rules whose match count is not per item. Above it, the missing data
     * is named when the referential actually holds none of it; otherwise the
     * floor is reported bare, since a rule matching everything for another
     * reason — a premium stand nobody can legally hold alone — is just as
     * constant.
     *
     * <p>A <b>bare</b> floor asks for a sample, though, and a named one does
     * not: when the referential holds no wish at all, the constant is a fact
     * about the data, true of three seats as of three thousand. « No missing
     * data identified » is the opposite — an inference drawn from the ratio
     * alone, and one match out of one item is 100 % of nothing. Below
     * {@link ConstraintFloorRules#FLOOR_MIN_SAMPLE} items it stays unsaid,
     * which is what keeps an edition being typed in from flagging almost every
     * rule it has.</p>
     */
    private static ConstraintFloor floorOf(
            FloorRule rule, int matchCount, Integer evaluated, PlanningEvenement solved) {
        if (evaluated == null || evaluated == 0) {
            return null;
        }
        double ratio = matchCount / (double) evaluated;
        if (ratio < ConstraintFloorRules.FLOOR_THRESHOLD) {
            return null;
        }
        MissingData missingData = rule.missingData();
        if (missingData != null && missingData.absentFrom(solved)) {
            return new ConstraintFloor(ratio, missingData.name(), missingData.libelle(), missingData.lien());
        }
        if (evaluated < ConstraintFloorRules.FLOOR_MIN_SAMPLE) {
            return null;
        }
        return new ConstraintFloor(ratio, null, LIBELLE_PLANCHER_SANS_MOTIF, null);
    }

    /**
     * The score levels are {@code long}; the contract carries these two totals
     * as {@code int}. Saturating rather than throwing: a sum beyond two billion
     * points is out of reach of any real edition, and an {@code ArithmeticException}
     * here would take down the whole diagnostic — hence the end of every solve —
     * over a number nobody would read anyway.
     */
    private static int borne(long valeur) {
        return Math.clamp(valeur, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Wording of a floor no missing data explains — the rule itself may not fit this edition. */
    public static final String LIBELLE_PLANCHER_SANS_MOTIF =
            "Aucune donnée absente identifiée : la règle est peut-être inadaptée à cette édition.";

    /**
     * Which hand-entered exceptions the still-violated hard constraints are
     * about (issue #84).
     *
     * <p>A solve that ends hard-negative names the rules that failed, and
     * "affectationForcee: 12" is where the user stops reading: nothing says
     * <em>which</em> of their exceptions the solver could not honour, so the
     * usual conclusion is that the solver is at fault. The three prescriptive
     * ad hoc rules carry the {@code ContrainteAdHoc} itself in their
     * justification, so the attribution is a matter of reading it back.</p>
     *
     * <p>Counted over the matches actually analysed, so a constraint capped by
     * {@link #MAX_VIOLATIONS_PAR_CONTRAINTE} is not capped here — this reads
     * the raw matches, not the formatted lines.</p>
     */
    private static void collectContributionsAdHoc(
            String constraintName, List<MatchFacts> matches, Map<String, ContributionAdHoc> contributions) {
        for (MatchFacts match : matches) {
            for (Object fact : match.facts()) {
                if (fact instanceof ContrainteAdHoc contrainte && contrainte.getId() != null) {
                    contributions.merge(
                            contrainte.getId(),
                            new ContributionAdHoc(
                                    contrainte.getId(),
                                    contrainte.getType() == null
                                            ? null
                                            : contrainte.getType().name(),
                                    contrainte.getRaison(),
                                    1,
                                    List.of(constraintName)),
                            PlanningDiagnosticService::mergeContributions);
                }
            }
        }
    }

    private static ContributionAdHoc mergeContributions(ContributionAdHoc existing, ContributionAdHoc addition) {
        List<String> contraintes = new ArrayList<>(existing.contraintes());
        for (String name : addition.contraintes()) {
            if (!contraintes.contains(name)) {
                contraintes.add(name);
            }
        }
        return new ContributionAdHoc(
                existing.contrainteId(),
                existing.type(),
                existing.raison(),
                existing.violations() + addition.violations(),
                List.copyOf(contraintes));
    }

    /**
     * One line per match, human-readable (see {@link ViolationFormatter}) —
     * e.g. "Sarah Rousseau (A45)" for a {@code reposHebdomadaireMineur} hit, or
     * "Stand tir à l'arc — 2026-07-16 12:30-15:30" for an unfilled
     * {@code posteDoitEtrePourvu} seat. Capped at {@link #MAX_VIOLATIONS_PAR_CONTRAINTE}:
     * this feeds a UI detail popup, not an export.
     */
    public static List<String> formatViolations(List<MatchFacts> matches) {
        return matches.stream()
                .limit(MAX_VIOLATIONS_PAR_CONTRAINTE)
                .map(match -> ViolationFormatter.describe(match.facts()))
                .toList();
    }

    /**
     * The same lines as {@link #formatViolations}, each with the ids it names:
     * what lets a screen open the fiche in question (see
     * {@link ViolationFormatter#references}). Same cap, same order.
     */
    public static List<ViolationFormatter.ViolationReference> referenceViolations(List<MatchFacts> matches) {
        return matches.stream()
                .limit(MAX_VIOLATIONS_PAR_CONTRAINTE)
                .map(match -> ViolationFormatter.references(match.facts()))
                .toList();
    }

    /**
     * The staffed seats of the plan handed here, as
     * {@link ForcedAssignmentOnLockedSchedule} reads them. Built from the plan
     * rather than from the database for the reason the toggles are: a
     * diagnostic describes the plan it was given.
     */
    private static Set<ForcedAssignmentOnLockedSchedule.PlaceTenue> placesTenues(PlanningEvenement solved) {
        return solved.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null && poste.getCreneau() != null)
                .map(poste -> new ForcedAssignmentOnLockedSchedule.PlaceTenue(
                        poste.getAnimateur().getId(),
                        poste.getStand().getId(),
                        poste.getCreneau().getId()))
                .collect(Collectors.toSet());
    }

    /**
     * Whether the plan handed here was solved with the supervision of minors
     * on, read off its own {@code ConstraintToggle} facts: absent means the
     * catalogue's default, exactly as the solver read them.
     */
    private static boolean encadrementMineursActif(PlanningEvenement solved) {
        return ConstraintCatalog.isActive(
                solved.getConstraintsDesactivees(), ExclusionEligibilite.ENCADREMENT_DES_MINEURS);
    }

    private static List<Stand> distinctStands(PlanningEvenement solved) {
        Map<String, Stand> byId = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getStand().getId(), poste.getStand());
        }
        return new ArrayList<>(byId.values());
    }

    private static List<Creneau> distinctCreneaux(PlanningEvenement solved) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            byId.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * @param violations one human-readable line per match (see
     *                    {@link ViolationFormatter}), populated only for
     *                    constraints enforced at
     *                    {@link ConstraintCatalog.Niveau#HARD} — empty for
     *                    medium/soft ones, which can run into the thousands
     *                    of matches (see {@link ConstraintCatalog#NOMS_DURS}).
     * @param postesEvalues how many items the rule evaluated — the
     *                    denominator of the floor ratio, whose grain is the
     *                    rule's own (seats, stand × timeslot groups, pairs;
     *                    see {@link ConstraintFloorRules.Denominator}).
     *                    {@code null} for a hard rule and for a rule whose
     *                    match count is not per item
     * @param plancher    set when the rule matched at least
     *                    {@link ConstraintFloorRules#FLOOR_THRESHOLD} of what
     *                    it evaluated: its points are a constant the solve
     *                    cannot move, and the reading names the missing data
     *                    when one explains it. {@code null} otherwise
     * @param position    what the rule's first breach naming a timeslot
     *                    points at — timeslot, day, stands, holders — for the
     *                    playbook's actions to open their screen on it, and
     *                    whether every breach sits on seats already started
     *                    (ADR 0044): nothing is left to correct there, and the
     *                    playbook says so rather than proposing a gesture.
     *                    Read by the playbook, never put on the wire
     * @param hotspots    the few stands and timeslots gathering most of the
     *                    rule's breaches ({@link BreachHotspots}) — where the
     *                    Problèmes screen says the rule bites. Served by the
     *                    rules' view, not by this diagnostic
     */
    public record ConstraintDiagnostic(
            String name,
            String score,
            int matchCount,
            List<String> violations,
            Integer postesEvalues,
            ConstraintFloor plancher,
            List<ViolationFormatter.ViolationReference> references,
            @JsonIgnore BlockerPlaybook.Context position,
            @JsonIgnore List<BreachHotspots.Hotspot> hotspots) {

        public ConstraintDiagnostic {
            hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
        }

        /** A rule read with a position and no hotspot. */
        public ConstraintDiagnostic(
                String name,
                String score,
                int matchCount,
                List<String> violations,
                Integer postesEvalues,
                ConstraintFloor plancher,
                List<ViolationFormatter.ViolationReference> references,
                BlockerPlaybook.Context position) {
            this(name, score, matchCount, violations, postesEvalues, plancher, references, position, List.of());
        }

        /** A rule read without a position, as the tests build them. */
        public ConstraintDiagnostic(
                String name,
                String score,
                int matchCount,
                List<String> violations,
                Integer postesEvalues,
                ConstraintFloor plancher,
                List<ViolationFormatter.ViolationReference> references) {
            this(
                    name,
                    score,
                    matchCount,
                    violations,
                    postesEvalues,
                    plancher,
                    references,
                    BlockerPlaybook.Context.NONE);
        }
    }

    /**
     * A constraint read as a floor (issue #495): what share of its items it
     * matched, and which referential data — when one — explains it.
     *
     * @param ratio   matches ÷ evaluated items, {@code 0.95} and above
     * @param motif   the {@link MissingData} code, {@code null} when no single
     *                absence explains the floor
     * @param libelle the sentence the screen shows, naming the missing data
     *                or saying none was identified
     * @param lien    Angular route of the screen where that data is entered,
     *                {@code null} when there is no data to enter
     */
    @Schema(requiredProperties = {"ratio"})
    public record ConstraintFloor(double ratio, String motif, String libelle, String lien) {}

    /**
     * Business-facing result of a solve: score, unfilled seats and
     * per-constraint breakdown. Deliberately excludes the {@link PlanningEvenement}
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
     *
     * <p>{@code scoreHorsPlancher} is the score with the floors taken out:
     * the raw score minus, level by level, what the constraints read as a
     * floor cost (see {@link ConstraintDiagnostic#plancher()}). Same format
     * as {@code score}, and equal to it when nothing is a floor — it is the
     * part of the score a solve can actually move, the one worth comparing
     * between two runs. {@code plancherMedium} and {@code plancherSoft} are
     * the constant parts themselves, signed like the score they were taken
     * from ({@code -5000} for a floor costing five thousand medium points).</p>
     *
     * <p>{@code pivotEcarts} says <b>where</b> the breaches concentrate — one
     * count per constraint and per day, stand or animateur (issue #496). It
     * covers every rule, hard and not: {@code violations} stops at the hard
     * ones because listing thousands of lines is what costs, and counting them
     * is not.</p>
     *
     * <p>{@code lecture} is the same diagnostic read out in a few French
     * sentences for an organiser (see {@link ScoreReading}): verdict, coverage,
     * what weighs on the organisation, floors, comfort, exceptions. Rules by
     * their short label, days by their date, never a person.</p>
     */
    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            FeasibilityAnalyzer.FeasibilityReport faisabilite,
            int hardScore,
            List<ContributionAdHoc> contraintesAdHocEnCause,
            String scoreHorsPlancher,
            int plancherMedium,
            int plancherSoft,
            List<PivotEcarts.Cellule> pivotEcarts,
            List<ScoreReading.ScoreSentence> lecture) {

        /** A diagnostic not read yet: {@link #withReading} gives it its sentences. */
        public PlanningDiagnostic(
                String score,
                int postesNonPourvus,
                List<ConstraintDiagnostic> contraintes,
                FeasibilityAnalyzer.FeasibilityReport faisabilite,
                int hardScore,
                List<ContributionAdHoc> contraintesAdHocEnCause,
                String scoreHorsPlancher,
                int plancherMedium,
                int plancherSoft,
                List<PivotEcarts.Cellule> pivotEcarts) {
            this(
                    score,
                    postesNonPourvus,
                    contraintes,
                    faisabilite,
                    hardScore,
                    contraintesAdHocEnCause,
                    scoreHorsPlancher,
                    plancherMedium,
                    plancherSoft,
                    pivotEcarts,
                    List.of());
        }

        /** The same diagnostic carrying {@code lecture} as its reading. */
        public PlanningDiagnostic withReading(List<ScoreReading.ScoreSentence> lecture) {
            return new PlanningDiagnostic(
                    score,
                    postesNonPourvus,
                    contraintes,
                    faisabilite,
                    hardScore,
                    contraintesAdHocEnCause,
                    scoreHorsPlancher,
                    plancherMedium,
                    plancherSoft,
                    pivotEcarts,
                    lecture == null ? List.of() : List.copyOf(lecture));
        }
    }

    /**
     * One hand-entered exception the last analysis found still violated, most
     * violated first.
     *
     * @param contrainteId id of the {@code ContrainteAdHoc}, the one shown on
     *                     the ad hoc screen
     * @param type         its {@code TypeContrainteAdHoc}, as a name
     * @param raison       the free text its author typed, kept as-is
     * @param violations   number of matches it accounts for
     * @param contraintes  names of the solver rules it broke, usually one
     */
    @Schema(requiredProperties = {"violations"})
    public record ContributionAdHoc(
            String contrainteId, String type, String raison, int violations, List<String> contraintes) {}
}
