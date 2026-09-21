package dev.sylvain.planning.domain;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@PlanningSolution
public class PlanningEvenement {

    private LocalDate dateDebutFestival;

    @ValueRangeProvider(id = "animateurRange")
    @ProblemFactCollectionProperty
    private List<Animateur> animateurs;

    @PlanningEntityCollectionProperty
    private List<PosteAffectation> postes;

    @ProblemFactCollectionProperty
    private List<ContrainteAdHoc> contraintesAdHoc = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<ParametresLegaux> parametresLegaux = new ArrayList<>(List.of(new ParametresLegaux()));

    @ProblemFactCollectionProperty
    private List<ParametresQualite> parametresQualite = new ArrayList<>(List.of(new ParametresQualite()));

    @ProblemFactCollectionProperty
    private List<ConstraintToggle> constraintsDesactivees = new ArrayList<>();

    /**
     * The per-typologie quotas of the edition, read by
     * {@code plafondCreneauxParTypologie}. Only the typologies that carry one
     * are here: an empty list is an edition that caps nothing, which is what a
     * plain-Java harness gets.
     */
    @ProblemFactCollectionProperty
    private List<QuotaTypologie> quotasTypologies = new ArrayList<>();

    /**
     * The edition's meal windows, read by {@code coupureRepasObligatoire}.
     * Empty means no meal rule applies to this solve — which is what a plain
     * Java harness building a problem by hand gets, and what an edition whose
     * windows cannot be honoured gets too (see
     * {@link FenetreRepas#from(ParametresLegaux)}).
     */
    @ProblemFactCollectionProperty
    private List<FenetreRepas> fenetresRepas = new ArrayList<>();

    /**
     * Locks applying to this solve, already filtered on the active groupe de
     * créneaux. Most of their effect is applied before the solve, by pinning
     * the covered seats; they travel as facts only so
     * {@code VerrouillageConstraints} can forbid giving a frozen animateur a
     * new seat.
     */
    @ProblemFactCollectionProperty
    private List<VerrouillagePlanning> verrouillages = new ArrayList<>();

    /**
     * The seats of the last published plan, read by {@code stabiliteDuPlanPublie}
     * so a re-solve after publication moves as few people as the weight allows.
     * Empty until a plan is published; filled by {@code SolveRunner.prepareProblem}
     * before every solve and every diagnosis, never sent by a caller — and
     * never returned to one either: the field is the server's own knowledge,
     * and on a 3 500-seat plan it is a megabyte the response has no use for.
     */
    @JsonIgnore
    @ProblemFactCollectionProperty
    private List<AffectationPubliee> affectationsPubliees = new ArrayList<>();

    // Never exposed over the API: SolveRunner.prepareProblem always sets
    // this from server-side configuration before a solve. Auto-discovered by
    // Timefold from its type alone (no annotation needed), and must never be
    // null when a solve runs, hence the non-null default.
    @JsonIgnore
    private ConstraintWeightOverrides<HardMediumSoftScore> ponderationsContraintes = ConstraintWeightOverrides.none();

    // The per-constraint weights the scenario file pinned, raw, or null when it
    // pinned none. Carried here rather than passed alongside because a planning
    // built from a scenario travels through several callers before it is
    // solved, and every one of them used to drop the section on the floor: the
    // weights only reached the solver once the scenario had been *imported*
    // into an edition. @JsonIgnore for the same reason as the overrides above —
    // a caller must not be able to weaken a constraint by sending its own.
    @JsonIgnore
    private Map<String, Integer> ponderationsScenario;

    /**
     * The moment this problem's past was judged against (ADR 0044), set once
     * — by the builder, or by the first preparation of a problem that came
     * without one — and read by every later preparation, so the seats pinned
     * as past are exactly the seats marked as past. {@code null} while the
     * freeze is off, or before the problem was ever prepared. Never on the
     * wire: the server's clock is not the caller's to set.
     */
    @JsonIgnore
    private PastHorizon pastHorizon;

    @PlanningScore
    private HardMediumSoftScore score;

    public PlanningEvenement() {}

    public PlanningEvenement(LocalDate dateDebutFestival, List<Animateur> animateurs, List<PosteAffectation> postes) {
        this.dateDebutFestival = dateDebutFestival;
        this.animateurs = animateurs;
        this.postes = postes;
    }

    public PlanningEvenement(
            LocalDate dateDebutFestival,
            List<Animateur> animateurs,
            List<PosteAffectation> postes,
            List<ContrainteAdHoc> contraintesAdHoc) {
        this.dateDebutFestival = dateDebutFestival;
        this.animateurs = animateurs;
        this.postes = postes;
        this.contraintesAdHoc = contraintesAdHoc;
    }

    public LocalDate getDateDebutFestival() {
        return dateDebutFestival;
    }

    public void setDateDebutFestival(LocalDate dateDebutFestival) {
        this.dateDebutFestival = dateDebutFestival;
    }

    public List<Animateur> getAnimateurs() {
        return animateurs;
    }

    public void setAnimateurs(List<Animateur> animateurs) {
        this.animateurs = animateurs;
    }

    public List<PosteAffectation> getPostes() {
        return postes;
    }

    public void setPostes(List<PosteAffectation> postes) {
        this.postes = postes;
    }

    public List<ContrainteAdHoc> getContraintesAdHoc() {
        return contraintesAdHoc;
    }

    public void setContraintesAdHoc(List<ContrainteAdHoc> contraintesAdHoc) {
        this.contraintesAdHoc = contraintesAdHoc;
    }

    public List<ParametresLegaux> getParametresLegaux() {
        return parametresLegaux;
    }

    public void setParametresLegaux(List<ParametresLegaux> parametresLegaux) {
        this.parametresLegaux = parametresLegaux;
    }

    /**
     * The single {@link ParametresLegaux} fact of this planning, or a default
     * instance when it is absent — never {@code null}, so a caller on the
     * solver's hot path reads a setting without three guards. Not a bean
     * property on purpose: it is a shortcut for the move filter, not a
     * serialised field, and {@link #getParametresLegaux()} stays the list the
     * solver loads as a problem fact.
     *
     * <p>The fallback builds a fresh instance rather than handing out a shared
     * one. {@link ParametresLegaux} is a mutable bean with public setters, and
     * a single caller writing to a static default would have rewritten the
     * fallback of every planning in the JVM. The allocation costs nothing on
     * the hot path: the field is initialised with one instance, so a planning
     * only reaches this branch if somebody cleared the list on purpose.</p>
     */
    public ParametresLegaux parametresLegaux() {
        if (parametresLegaux == null || parametresLegaux.isEmpty() || parametresLegaux.get(0) == null) {
            return new ParametresLegaux();
        }
        return parametresLegaux.get(0);
    }

    public List<ParametresQualite> getParametresQualite() {
        return parametresQualite;
    }

    public void setParametresQualite(List<ParametresQualite> parametresQualite) {
        this.parametresQualite = parametresQualite;
    }

    public List<ConstraintToggle> getConstraintsDesactivees() {
        return constraintsDesactivees;
    }

    public void setConstraintsDesactivees(List<ConstraintToggle> constraintsDesactivees) {
        this.constraintsDesactivees = constraintsDesactivees;
    }

    public List<QuotaTypologie> getQuotasTypologies() {
        return quotasTypologies;
    }

    public void setQuotasTypologies(List<QuotaTypologie> quotasTypologies) {
        this.quotasTypologies = quotasTypologies;
    }

    public List<FenetreRepas> getFenetresRepas() {
        return fenetresRepas;
    }

    public void setFenetresRepas(List<FenetreRepas> fenetresRepas) {
        this.fenetresRepas = fenetresRepas == null ? new ArrayList<>() : fenetresRepas;
    }

    public List<VerrouillagePlanning> getVerrouillages() {
        return verrouillages;
    }

    public void setVerrouillages(List<VerrouillagePlanning> verrouillages) {
        this.verrouillages = verrouillages;
    }

    public List<AffectationPubliee> getAffectationsPubliees() {
        return affectationsPubliees;
    }

    public void setAffectationsPubliees(List<AffectationPubliee> affectationsPubliees) {
        this.affectationsPubliees = affectationsPubliees == null ? new ArrayList<>() : affectationsPubliees;
    }

    public ConstraintWeightOverrides<HardMediumSoftScore> getPonderationsContraintes() {
        return ponderationsContraintes;
    }

    public Map<String, Integer> getPonderationsScenario() {
        return ponderationsScenario;
    }

    public void setPonderationsScenario(Map<String, Integer> ponderationsScenario) {
        this.ponderationsScenario = ponderationsScenario;
    }

    public void setPonderationsContraintes(ConstraintWeightOverrides<HardMediumSoftScore> ponderationsContraintes) {
        this.ponderationsContraintes = ponderationsContraintes;
    }

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }

    @JsonIgnore
    public PastHorizon getPastHorizon() {
        return pastHorizon;
    }

    @JsonIgnore
    public void setPastHorizon(PastHorizon pastHorizon) {
        this.pastHorizon = pastHorizon;
    }
}
