package dev.sylvain.planning.service.analyse;

import ai.timefold.solver.core.api.solver.SolutionManager;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.service.diagnostic.ConstraintDiagnosticService;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.diagnostic.PlanningAnalysis;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
        for (ConstraintContribution ca : analysis.contributions()) {
            String name = ca.constraintName();
            boolean hard = ConstraintCatalog.NOMS_DURS.contains(name);
            List<String> violations = hard ? formatViolations(ca.matches()) : List.of();
            if (hard) {
                collectContributionsAdHoc(name, ca.matches(), contributionsAdHoc);
            }
            constraintDiagnostics.add(
                    new ConstraintDiagnostic(name, String.valueOf(ca.score()), ca.matchCount(), violations));
        }
        constraintDiagnostics.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));
        int unassigned = (int) solved.getPostes().stream()
                .filter(p -> p.getAnimateur() == null)
                .count();
        FeasibilityAnalyzer.FeasibilityReport faisabilite = feasibilityAnalyzer.analyze(
                solved.getAnimateurs(), distinctStands(solved), distinctCreneaux(solved), solved.getContraintesAdHoc());
        int hardScore = solved.getScore() == null
                ? 0
                : Math.toIntExact(solved.getScore().hardScore());
        List<ContributionAdHoc> contraintesAdHocEnCause = contributionsAdHoc.values().stream()
                .sorted(Comparator.comparingInt(ContributionAdHoc::violations)
                        .reversed()
                        .thenComparing(ContributionAdHoc::contrainteId))
                .toList();
        return new PlanningDiagnostic(
                String.valueOf(solved.getScore()),
                unassigned,
                constraintDiagnostics,
                faisabilite,
                hardScore,
                contraintesAdHocEnCause);
    }

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
     */
    public record ConstraintDiagnostic(String name, String score, int matchCount, List<String> violations) {}

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
     */
    public record PlanningDiagnostic(
            String score,
            int postesNonPourvus,
            List<ConstraintDiagnostic> contraintes,
            FeasibilityAnalyzer.FeasibilityReport faisabilite,
            int hardScore,
            List<ContributionAdHoc> contraintesAdHocEnCause) {}

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
