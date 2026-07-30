package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;

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

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }
}
