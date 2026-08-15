package dev.sylvain.planning.domain;

import java.time.LocalTime;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import dev.sylvain.planning.solver.PosteAffectationDifficultyComparatorFactory;

@PlanningEntity(comparatorFactoryClass = PosteAffectationDifficultyComparatorFactory.class)
public class PosteAffectation {

    @PlanningId
    private String id;
    private Stand stand;
    private Creneau creneau;
    /**
     * Narrower time window this poste actually covers within {@link #creneau},
     * when the stand is only partially closed on that créneau (see
     * {@code Creneau#segmentsOuvertsMinutes}). {@code null} — the overwhelming
     * common case — means the poste covers the créneau's full window; the
     * override can never be persisted as its own créneau row because
     * {@code poste_affectation.creneau_id} is a foreign key to a real,
     * pre-existing créneau, so it lives here instead.
     */
    private LocalTime heureDebutEffective;
    private LocalTime heureFinEffective;

    /**
     * A seat may stay empty during the search (and in an infeasible plan);
     * {@code posteDoitEtrePourvu} is what makes filling it a hard requirement.
     */
    @PlanningVariable(valueRangeProviderRefs = "animateurRange", allowsUnassigned = true)
    private Animateur animateur;

    /**
     * Seat validated by the user and frozen: no move (construction heuristic or
     * local search) may change its {@link #animateur}. Set at problem-building
     * time from the persisted
     * {@link dev.sylvain.planning.domain.VerrouillagePlanning}
     * rows, never by the solver, and never on an empty seat — pinning a hole
     * would make it permanently unfillable.
     *
     * <p>A pinned seat is still scored normally: a lock can therefore leave a
     * visible violation in the plan, deliberately, rather than silently
     * disabling the rules around it.</p>
     */
    @PlanningPin
    private boolean verrouille;

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

    public boolean isVerrouille() {
        return verrouille;
    }

    public void setVerrouille(boolean verrouille) {
        this.verrouille = verrouille;
    }

    public LocalTime getHeureDebutEffective() {
        return heureDebutEffective;
    }

    public void setHeureDebutEffective(LocalTime heureDebutEffective) {
        this.heureDebutEffective = heureDebutEffective;
    }

    public LocalTime getHeureFinEffective() {
        return heureFinEffective;
    }

    public void setHeureFinEffective(LocalTime heureFinEffective) {
        this.heureFinEffective = heureFinEffective;
    }

    /** Start time this poste actually covers: the override if set, else the créneau's own start. */
    public LocalTime heureDebutEffectif() {
        return heureDebutEffective != null ? heureDebutEffective : (creneau != null ? creneau.getHeureDebut() : null);
    }

    /** End time this poste actually covers: the override if set, else the créneau's own end. */
    public LocalTime heureFinEffectif() {
        return heureFinEffective != null ? heureFinEffective : (creneau != null ? creneau.getHeureFin() : null);
    }

    /**
     * Duration in minutes this poste actually covers, handling a window
     * crossing midnight the same way {@code Creneau#getDureeMinutes()} does.
     * Equals {@code creneau.getDureeMinutes()} unless an override narrows it.
     */
    public int getDureeEffectiveMinutes() {
        LocalTime debut = heureDebutEffectif();
        LocalTime fin = heureFinEffectif();
        if (debut == null || fin == null) {
            return 0;
        }
        int debutSecondes = debut.toSecondOfDay();
        int finSecondes = fin.toSecondOfDay();
        int secondes = finSecondes > debutSecondes ? finSecondes - debutSecondes : (24 * 3600 - debutSecondes) + finSecondes;
        return secondes / 60;
    }
}
