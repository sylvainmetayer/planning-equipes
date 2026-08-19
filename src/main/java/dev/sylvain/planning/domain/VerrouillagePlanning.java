package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A part of the planning the user has validated and does not want the solver to
 * touch again. Persistent state (one row per active lock in
 * {@code verrouillage_planning}), not a journal: deleting the row unlocks.
 *
 * <p>A lock carries exactly one target, matching its {@link TypeVerrouillage}.
 * It is scoped to the groupe de créneaux it was created for: switching the
 * active group is switching planning, and the seats of another group are not
 * the ones that were validated.</p>
 *
 * <p>Two mechanisms enforce it, both wired from
 * {@code PlanningService.construireDepuisReferenceData}:</p>
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
    private String groupeCreneauId;
    private String animateurId;
    private String standId;
    private Long creneauId;
    private LocalDate jour;
    private String raison;
    private Instant creeLe;

    public VerrouillagePlanning() {
    }

    public VerrouillagePlanning(String id, TypeVerrouillage type) {
        this.id = id;
        this.type = type;
    }

    /**
     * Whether this lock freezes {@code poste}. The {@link
     * TypeVerrouillage#ANIMATEUR} case reads the poste's current animateur, so
     * it only answers usefully once the seat has been seeded from the persisted
     * planning.
     *
     * <p>Says nothing about the groupe de créneaux: the caller only ever
     * evaluates locks already filtered on the active group.</p>
     */
    public boolean couvre(PosteAffectation poste) {
        if (poste == null || type == null) {
            return false;
        }
        return switch (type) {
            case ANIMATEUR -> animateurId != null
                    && poste.getAnimateur() != null
                    && animateurId.equals(poste.getAnimateur().getId());
            case STAND -> standId != null
                    && poste.getStand() != null
                    && standId.equals(poste.getStand().getId());
            case JOUR -> jour != null
                    && poste.getCreneau() != null
                    && jour.equals(poste.getCreneau().getDate());
            case CRENEAU -> creneauId != null
                    && poste.getCreneau() != null
                    && creneauId.equals(poste.getCreneau().getId());
            case ANIMATEUR_CRENEAU -> animateurId != null && creneauId != null
                    && poste.getAnimateur() != null
                    && poste.getCreneau() != null
                    && animateurId.equals(poste.getAnimateur().getId())
                    && creneauId.equals(poste.getCreneau().getId());
        };
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

    public String getGroupeCreneauId() {
        return groupeCreneauId;
    }

    public void setGroupeCreneauId(String groupeCreneauId) {
        this.groupeCreneauId = groupeCreneauId;
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
