package dev.sylvain.planning.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import dev.sylvain.planning.solver.PosteAffectationDifficultyComparatorFactory;

@PlanningEntity(comparatorFactoryClass = PosteAffectationDifficultyComparatorFactory.class)
public class PosteAffectation {

    @PlanningId
    private String id;
    private Stand stand;
    private Creneau creneau;

    @PlanningVariable(valueRangeProviderRefs = "animateurRange", nullable = true)
    private Animateur animateur;

    public PosteAffectation() {
    }

    public PosteAffectation(String id, Stand stand, Creneau creneau) {
        this.id = id;
        this.stand = stand;
        this.creneau = creneau;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Stand getStand() {
        return stand;
    }

    public void setStand(Stand stand) {
        this.stand = stand;
    }

    public Creneau getCreneau() {
        return creneau;
    }

    public void setCreneau(Creneau creneau) {
        this.creneau = creneau;
    }

    public Animateur getAnimateur() {
        return animateur;
    }

    public void setAnimateur(Animateur animateur) {
        this.animateur = animateur;
    }
}
