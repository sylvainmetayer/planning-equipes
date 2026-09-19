package dev.sylvain.planning.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.sylvain.planning.solver.PosteAffectationDifficultyComparatorFactory;
import java.time.LocalTime;

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
     * Seat frozen: no move (construction heuristic or local search) may change
     * its {@link #animateur}. Set at problem-building time, never by the
     * solver, by two things — the persisted
     * {@link dev.sylvain.planning.domain.VerrouillagePlanning} rows, which
     * never pin an empty seat (pinning a hole would make it permanently
     * unfillable), and the past (ADR 0044, {@link #passe}), which pins a seat
     * of a timeslot already started <em>empty or not</em>: nobody can staff
     * yesterday, so a past hole is a fact, not a seat to fill.
     *
     * <p>A pinned seat is still scored normally: a lock can therefore leave a
     * visible violation in the plan, deliberately, rather than silently
     * disabling the rules around it. A past seat is the exception, counted
     * and never reproached — that is what the second flag is for.</p>
     */
    @PlanningPin
    private boolean verrouille;

    /**
     * Seat of a timeslot already started when the problem was built — « le
     * passé est figé » (ADR 0044). A plain fact, never a planning variable:
     * set by {@code FrozenPast} at problem-building time from the server's
     * clock, never by the solver, and never read from a caller's JSON — what
     * the server's clock says is not the client's to decide, hence the
     * {@link JsonIgnore}.
     *
     * <p>A past seat is <b>also</b> pinned ({@link #verrouille}), so no move
     * touches it; {@link #verrouille} stays what the locks and the
     * incremental freeze set. This flag is the other half: the constraint
     * streams read it to <em>count</em> a past seat — what somebody worked
     * yesterday conditions what they may do tomorrow — without ever
     * <em>reproaching</em> it: a violation involving only past seats is
     * history, not something a solve can fix.</p>
     */
    private boolean passe;

    /**
     * Seat generated <b>above</b> the staffing the window declares, up to the
     * stand's {@code effectifMax} (issue #505, ADR 0046) — « mieux à trois,
     * tenable à deux ».
     *
     * <p>The difference with an ordinary seat is one thing only:
     * {@code posteDoitEtrePourvu} ignores it, so leaving it empty is never a
     * violation and never counts as an écart. Everything else treats it as the
     * seat it is — somebody assigned to it really works that shift, so the
     * legal rules, the fairness balance and the hours all count it.</p>
     *
     * <p>A plain fact of the problem, like {@link #passe}: set by
     * {@code ProblemBuilder} from the stand's declared capacity, never by the
     * solver. Serialized, unlike {@code passe}, because the screens have to
     * tell a renfort from a seat somebody is missing on — an empty optional
     * seat drawn as a hole would be exactly the false alarm this feature
     * exists to avoid.</p>
     */
    private boolean optionnel;

    public PosteAffectation() {}

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

    /** Whether this seat's timeslot had already started when the problem was built. */
    @JsonIgnore
    public boolean isPasse() {
        return passe;
    }

    @JsonIgnore
    public void setPasse(boolean passe) {
        this.passe = passe;
    }

    /** Whether this seat is a renfort: generated above the declared staffing, never owed. */
    public boolean isOptionnel() {
        return optionnel;
    }

    public void setOptionnel(boolean optionnel) {
        this.optionnel = optionnel;
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
        int secondes =
                finSecondes > debutSecondes ? finSecondes - debutSecondes : (24 * 3600 - debutSecondes) + finSecondes;
        return secondes / 60;
    }
}
