package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ContrainteAdHoc {

    private String id;
    private TypeContrainteAdHoc type;
    private List<Animateur> animateursConcernes = new ArrayList<>();
    private Creneau creneau;
    private Stand stand;
    private String raison;
    private String creeParUtilisateurId;
    private Instant creeLe;

    public ContrainteAdHoc() {
    }

    public ContrainteAdHoc(String id, TypeContrainteAdHoc type) {
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TypeContrainteAdHoc getType() {
        return type;
    }

    public void setType(TypeContrainteAdHoc type) {
        this.type = type;
    }

    public List<Animateur> getAnimateursConcernes() {
        return animateursConcernes;
    }

    public void setAnimateursConcernes(List<Animateur> animateursConcernes) {
        this.animateursConcernes = animateursConcernes;
    }

    public Creneau getCreneau() {
        return creneau;
    }

    public void setCreneau(Creneau creneau) {
        this.creneau = creneau;
    }

    public Stand getStand() {
        return stand;
    }

    public void setStand(Stand stand) {
        this.stand = stand;
    }

    public String getRaison() {
        return raison;
    }

    public void setRaison(String raison) {
        this.raison = raison;
    }

    public String getCreeParUtilisateurId() {
        return creeParUtilisateurId;
    }

    public void setCreeParUtilisateurId(String creeParUtilisateurId) {
        this.creeParUtilisateurId = creeParUtilisateurId;
    }

    public Instant getCreeLe() {
        return creeLe;
    }

    public void setCreeLe(Instant creeLe) {
        this.creeLe = creeLe;
    }
}
