package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A swap proposal an animateur submits from their espace (issue #165): "give
 * my seat on this créneau/stand to this colleague" — an échange croisé when
 * the colleague also works that créneau, a simple takeover otherwise. With
 * {@code creneauCibleId}/{@code standCibleId} set, the exchange is DIRECTED:
 * the demandeur names the colleague's seat they want IN RETURN ("I give you
 * my Monday, I take your Tuesday — I'd rather be free on Monday"). Never
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
    /** Directed exchange only: the colleague's seat the demandeur wants in return. Null = same-créneau semantics. */
    private Long creneauCibleId;

    private String standCibleId;
    private String motif;
    private StatutDemandeEchange statut = StatutDemandeEchange.EN_ATTENTE_CIBLE;
    /** When the targeted colleague agreed or declined; null while they have not answered. */
    private Instant cibleDecideLe;
    /** Hard-constraint prevalidation verdict at submission; null while not evaluated. */
    private Boolean prevalidationOk;
    /** Business descriptions of the hard constraints the échange would break, one per entry. */
    private List<String> contraintesViolees = new ArrayList<>();

    private String commentaireAdmin;
    private Instant creeLe;
    /**
     * When the <b>organisation</b> decided — accepted or refused. Never set by
     * the demandeur withdrawing their own request: that moment is
     * {@link #annuleLe}, and sharing one column is what made an annulation
     * indistinguishable from a refusal all the way to the publication mail
     * (issue #540).
     */
    private Instant decideLe;

    /** When the demandeur withdrew their own request; null on every other statut. */
    private Instant annuleLe;
    /**
     * When the publication that announced the decision left; null while it has
     * been taken but not yet communicated. Written only by the publication
     * (issue #245), never at insertion — an acceptation changes the working
     * plan, and the espace keeps showing the published one until then.
     */
    private Instant communiqueeLe;

    public DemandeEchange() {}

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

    public Long getCreneauCibleId() {
        return creneauCibleId;
    }

    public void setCreneauCibleId(Long creneauCibleId) {
        this.creneauCibleId = creneauCibleId;
    }

    public String getStandCibleId() {
        return standCibleId;
    }

    public void setStandCibleId(String standCibleId) {
        this.standCibleId = standCibleId;
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

    public Instant getCibleDecideLe() {
        return cibleDecideLe;
    }

    public void setCibleDecideLe(Instant cibleDecideLe) {
        this.cibleDecideLe = cibleDecideLe;
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

    public Instant getAnnuleLe() {
        return annuleLe;
    }

    public void setAnnuleLe(Instant annuleLe) {
        this.annuleLe = annuleLe;
    }

    public Instant getCommuniqueeLe() {
        return communiqueeLe;
    }

    public void setCommuniqueeLe(Instant communiqueeLe) {
        this.communiqueeLe = communiqueeLe;
    }
}
