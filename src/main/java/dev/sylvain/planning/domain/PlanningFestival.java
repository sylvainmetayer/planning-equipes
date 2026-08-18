package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import com.fasterxml.jackson.annotation.JsonIgnore;

@PlanningSolution
public class PlanningFestival {

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
     * Locks applying to this solve, already filtered on the active groupe de
     * créneaux. Most of their effect is applied before the solve, by pinning
     * the covered seats; they travel as facts only so
     * {@code VerrouillageConstraints} can forbid giving a frozen animateur a
     * new seat.
     */
    @ProblemFactCollectionProperty
    private List<VerrouillagePlanning> verrouillages = new ArrayList<>();

    // Never exposed over the API: PlanningService.prepareProblem always sets
    // this from server-side configuration before a solve. Auto-discovered by
    // Timefold from its type alone (no annotation needed), and must never be
    // null when a solve runs, hence the non-null default.
    @JsonIgnore
    private ConstraintWeightOverrides<HardMediumSoftScore> ponderationsContraintes = ConstraintWeightOverrides.none();

    @PlanningScore
    private HardMediumSoftScore score;

    public PlanningFestival() {
    }

    public PlanningFestival(LocalDate dateDebutFestival, List<Animateur> animateurs, List<PosteAffectation> postes) {
        this.dateDebutFestival = dateDebutFestival;
        this.animateurs = animateurs;
        this.postes = postes;
    }

    public PlanningFestival(LocalDate dateDebutFestival, List<Animateur> animateurs, List<PosteAffectation> postes,
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

    public List<VerrouillagePlanning> getVerrouillages() {
        return verrouillages;
    }

    public void setVerrouillages(List<VerrouillagePlanning> verrouillages) {
        this.verrouillages = verrouillages;
    }

    public ConstraintWeightOverrides<HardMediumSoftScore> getPonderationsContraintes() {
        return ponderationsContraintes;
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
}
