package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A swap proposal an animateur submits from their espace (issue #165): "give
 * my seat on this créneau/stand to this colleague" — an échange croisé when
 * the colleague also works that créneau, a simple takeover otherwise. Never
 * applied to the planning without an explicit admin acceptation.
 *
 * <p>The seat is referenced by its (créneau, stand) pair rather than a
 * {@code poste_affectation} id: seat ids are renumbered on every solve, the
 * pair is what survives a regeneration.</p>
 */
public class DemandeEchange {

    private String id;
    /** Groupe de créneaux of the persisted planning at submission time, for context; may be null. */
    private String demandeurId;
    private String cibleId;
    private Long creneauId;
    private String standId;
    private String motif;
    private StatutDemandeEchange statut = StatutDemandeEchange.PROPOSEE;
    /** Hard-constraint prevalidation verdict at submission; null while not evaluated. */
    private Boolean prevalidationOk;
    /** Business descriptions of the hard constraints the échange would break, one per entry. */
    private List<String> contraintesViolees = new ArrayList<>();
    private String commentaireAdmin;
    private Instant creeLe;
    private Instant decideLe;

    public DemandeEchange() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDemandeurId() {
        return demandeurId;
    }

    public void setDemandeurId(String demandeurId) {
        this.demandeurId = demandeurId;
    }

    public String getCibleId() {
        return cibleId;
    }

    public void setCibleId(String cibleId) {
        this.cibleId = cibleId;
    }

    public Long getCreneauId() {
        return creneauId;
    }

    public void setCreneauId(Long creneauId) {
        this.creneauId = creneauId;
    }

    public String getStandId() {
        return standId;
    }

    public void setStandId(String standId) {
        this.standId = standId;
    }

    public String getMotif() {
        return motif;
    }

    public void setMotif(String motif) {
        this.motif = motif;
    }

    public StatutDemandeEchange getStatut() {
        return statut;
    }

    public void setStatut(StatutDemandeEchange statut) {
        this.statut = statut;
    }

    public Boolean getPrevalidationOk() {
        return prevalidationOk;
    }

    public void setPrevalidationOk(Boolean prevalidationOk) {
        this.prevalidationOk = prevalidationOk;
    }

    public List<String> getContraintesViolees() {
        return contraintesViolees;
    }

    public void setContraintesViolees(List<String> contraintesViolees) {
        this.contraintesViolees = contraintesViolees;
    }

    public String getCommentaireAdmin() {
        return commentaireAdmin;
    }

    public void setCommentaireAdmin(String commentaireAdmin) {
        this.commentaireAdmin = commentaireAdmin;
    }

    public Instant getCreeLe() {
        return creeLe;
    }

    public void setCreeLe(Instant creeLe) {
        this.creeLe = creeLe;
    }

    public Instant getDecideLe() {
        return decideLe;
    }

    public void setDecideLe(Instant decideLe) {
        this.decideLe = decideLe;
    }
}
