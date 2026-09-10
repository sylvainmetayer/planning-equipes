package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A part of the planning the user has validated and does not want the solver to
 * touch again. Persistent state (one row per active lock in
 * {@code verrouillage_planning}), not a journal: deleting the row unlocks.
 *
 * <p>A lock carries exactly one target, matching its {@link TypeVerrouillage},
 * and belongs to its edition like the rest of the referential.</p>
 *
 * <p>Two mechanisms enforce it, both wired from
 * {@code PlanningService.buildFromReferenceData}:</p>
 * <ul>
 *   <li>every covered seat that already holds an animateur is re-seeded with
 *       that animateur and pinned ({@link PosteAffectation#isVerrouille()}), so
 *       no move can change it;</li>
 *   <li>for {@link TypeVerrouillage#ANIMATEUR} only, the lock also travels into
 *       the solve as a problem fact, so
 *       {@code VerrouillageConstraints.animateurVerrouilleFige} can forbid
 *       <em>adding</em> a new seat to a frozen animateur — pinning alone would
 *       only freeze the seats they already hold.</li>
 * </ul>
 */
public class VerrouillagePlanning {

    private String id;
    private TypeVerrouillage type;
    private String animateurId;
    private String standId;
    private Long creneauId;
    private LocalDate jour;
    private String raison;
    private Instant creeLe;

    public VerrouillagePlanning() {}

    public VerrouillagePlanning(String id, TypeVerrouillage type) {
        this.id = id;
        this.type = type;
    }

    /**
     * Whether this lock freezes {@code poste}. The {@link
     * TypeVerrouillage#ANIMATEUR} case reads the poste's current animateur, so
     * it only answers usefully once the seat has been seeded from the persisted
     * planning.
     */
    public boolean couvre(PosteAffectation poste) {
        if (poste == null) {
            return false;
        }
        return target().map(target -> target.couvre(poste)).orElse(false);
    }

    /**
     * The columns read back as the target they describe — the reading half of
     * the adapter.
     *
     * <p>Empty when the row describes nothing usable: the {@code CHECK} of
     * `V30`/`V41` forbids that case in the database, but a lock built in memory
     * (a request body not validated yet, a test) may perfectly well carry
     * neither type nor target. Returning {@link Optional#empty()} rather than
     * throwing keeps the previous behaviour: an incomplete lock freezes
     * nothing.</p>
     */
    public Optional<VerrouillageTarget> target() {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case ANIMATEUR ->
                animateurId == null ? Optional.empty() : Optional.of(new VerrouillageTarget.OnAnimateur(animateurId));
            case STAND -> standId == null ? Optional.empty() : Optional.of(new VerrouillageTarget.OnStand(standId));
            case CRENEAU ->
                creneauId == null ? Optional.empty() : Optional.of(new VerrouillageTarget.OnCreneau(creneauId));
            case JOUR -> jour == null ? Optional.empty() : Optional.of(new VerrouillageTarget.OnJour(jour));
            case ANIMATEUR_CRENEAU ->
                animateurId == null || creneauId == null
                        ? Optional.empty()
                        : Optional.of(new VerrouillageTarget.OnAnimateurAndCreneau(animateurId, creneauId));
        };
    }

    /**
     * The target written back into the columns — the writing half of the
     * adapter, and the only place in the code that resets the others to
     * {@code null}.
     *
     * <p>It used to be written five times, once per validation branch, and
     * every branch had to remember to clear the four columns it did not use.
     * Here the clearing comes before the assignment, once and for all, and the
     * {@code switch} over the sealed hierarchy is exhaustive with no
     * {@code default}: a new way to lock will not compile until somebody has
     * said which column it lands in.</p>
     */
    public void apply(VerrouillageTarget target) {
        this.type = target.type();
        this.animateurId = null;
        this.standId = null;
        this.creneauId = null;
        this.jour = null;
        switch (target) {
            case VerrouillageTarget.OnAnimateur sur -> this.animateurId = sur.animateurId();
            case VerrouillageTarget.OnStand sur -> this.standId = sur.standId();
            case VerrouillageTarget.OnCreneau sur -> this.creneauId = sur.creneauId();
            case VerrouillageTarget.OnJour sur -> this.jour = sur.jour();
            case VerrouillageTarget.OnAnimateurAndCreneau sur -> {
                this.animateurId = sur.animateurId();
                this.creneauId = sur.creneauId();
            }
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TypeVerrouillage getType() {
        return type;
    }

    public void setType(TypeVerrouillage type) {
        this.type = type;
    }

    public String getAnimateurId() {
        return animateurId;
    }

    public void setAnimateurId(String animateurId) {
        this.animateurId = animateurId;
    }

    public String getStandId() {
        return standId;
    }

    public void setStandId(String standId) {
        this.standId = standId;
    }

    public Long getCreneauId() {
        return creneauId;
    }

    public void setCreneauId(Long creneauId) {
        this.creneauId = creneauId;
    }

    public LocalDate getJour() {
        return jour;
    }

    public void setJour(LocalDate jour) {
        this.jour = jour;
    }

    public String getRaison() {
        return raison;
    }

    public void setRaison(String raison) {
        this.raison = raison;
    }

    public Instant getCreeLe() {
        return creeLe;
    }

    public void setCreeLe(Instant creeLe) {
        this.creeLe = creeLe;
    }
}
