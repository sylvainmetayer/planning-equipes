package fr.festival.planning.domain;

import java.time.LocalDate;
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

    @PlanningScore
    private HardMediumSoftScore score;

    public PlanningFestival() {
    }

    public PlanningFestival(LocalDate dateDebutFestival, List<Animateur> animateurs, List<PosteAffectation> postes) {
        this.dateDebutFestival = dateDebutFestival;
        this.animateurs = animateurs;
        this.postes = postes;
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

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }
}
