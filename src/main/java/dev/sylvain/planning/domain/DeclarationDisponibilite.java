package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What an animateur declares about themself for the current edition (issue
 * #291): the days they cannot come, the game categories they would like to
 * animate, and a free word to the organisation.
 *
 * <p><b>A proposal, never the data itself.</b> The referential keeps saying
 * what it said until an admin applies the declaration; this object is the
 * pending intent, held aside. That is why it carries copies of the values
 * rather than pointing at the referential: it is the frozen snapshot of a
 * sentence somebody typed on a given day, and it must stay readable even after
 * the game category it names has been renamed or dropped.</p>
 *
 * <p>Competences are deliberately absent — a self-declared competence feeds
 * <b>hard</b> constraints, and the validation stakes are not the same (issue
 * #292).</p>
 */
public class DeclarationDisponibilite {

    private String id;
    private String animateurId;

    /** Days the animateur declares they cannot come. Empty is a statement too. */
    private List<LocalDate> joursIndisponibles = new ArrayList<>();

    /** Typologie ids they would like to be assigned on. */
    private List<String> souhaits = new ArrayList<>();

    /** Free word to the organisation, {@code null} when they wrote none. */
    private String commentaire;

    private StatutDeclaration statut = StatutDeclaration.EN_ATTENTE;

    /** What the admin answered when refusing, {@code null} otherwise. */
    private String commentaireAdmin;

    private Instant creeLe;
    private Instant decideLe;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAnimateurId() {
        return animateurId;
    }

    public void setAnimateurId(String animateurId) {
        this.animateurId = animateurId;
    }

    public List<LocalDate> getJoursIndisponibles() {
        return joursIndisponibles;
    }

    public void setJoursIndisponibles(List<LocalDate> joursIndisponibles) {
        this.joursIndisponibles = joursIndisponibles == null ? new ArrayList<>() : joursIndisponibles;
    }

    public List<String> getSouhaits() {
        return souhaits;
    }

    public void setSouhaits(List<String> souhaits) {
        this.souhaits = souhaits == null ? new ArrayList<>() : souhaits;
    }

    public String getCommentaire() {
        return commentaire;
    }

    public void setCommentaire(String commentaire) {
        this.commentaire = commentaire;
    }

    public StatutDeclaration getStatut() {
        return statut;
    }

    public void setStatut(StatutDeclaration statut) {
        this.statut = statut;
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
