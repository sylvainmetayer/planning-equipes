package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The hand-entered constraints (affinités, incompatibilités, …) the solver reads as problem facts. */
@ApplicationScoped
public class ContrainteAdHocService {

    @Inject
    ContrainteAdHocRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public List<ContrainteAdHoc> list() {
        return repository.listContraintes();
    }

    public ContrainteAdHoc create(ContrainteAdHoc contrainte) {
        contrainte.setId(Identifiants.requis(contrainte.getId(), "constraint id"));
        validerPaireSansContradiction(contrainte);
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        repository.saveContrainte(contrainte);
        changeTracker.markModified();
        return contrainte;
    }

    public void delete(String id) {
        repository.deleteContrainte(id);
        changeTracker.markModified();
    }

    /**
     * A pair declared both INCOMPATIBILITE and AFFINITE must be refused at
     * entry time, not silently arbitrated by the score (issue #80): the two
     * facts would pull the solver in opposite directions and the hard one
     * would always win without the user ever being told. The pair is the
     * unordered couple of the first two animateur ids — exactly what the
     * solver evaluates (see {@code AdHocConstraints}). Overwriting a
     * constraint under its own id is exempt: the saved version replaces the
     * conflicting one instead of coexisting with it.
     */
    private void validerPaireSansContradiction(ContrainteAdHoc contrainte) {
        TypeContrainteAdHoc typeOppose = switch (contrainte.getType()) {
            case AFFINITE -> TypeContrainteAdHoc.INCOMPATIBILITE;
            case INCOMPATIBILITE -> TypeContrainteAdHoc.AFFINITE;
            default -> null;
        };
        Set<String> paire = paireAnimateurs(contrainte);
        if (typeOppose == null || paire == null) {
            return;
        }
        list().stream()
                .filter(existante -> existante.getType() == typeOppose)
                .filter(existante -> !existante.getId().equals(contrainte.getId()))
                .filter(existante -> paire.equals(paireAnimateurs(existante)))
                .findFirst()
                .ifPresent(existante -> {
                    throw new ErreurMetier.Invalide(
                            "La paire d'animateurs " + String.join(" / ", new TreeSet<>(paire))
                                    + " est déjà visée par la contrainte " + existante.getId()
                                    + " (" + existante.getType()
                                    + ") : une même paire ne peut pas être déclarée à la fois incompatible et en affinité."
                                    + " Supprimez d'abord la contrainte existante.");
                });
    }

    /** The unordered pair of the first two animateur ids, or null when the constraint doesn't name a genuine pair. */
    private static Set<String> paireAnimateurs(ContrainteAdHoc contrainte) {
        List<Animateur> animateurs = contrainte.getAnimateursConcernes();
        if (animateurs == null || animateurs.size() < 2
                || animateurs.get(0) == null || animateurs.get(1) == null) {
            return null;
        }
        String premier = animateurs.get(0).getId();
        String second = animateurs.get(1).getId();
        if (premier == null || second == null || premier.equals(second)) {
            return null;
        }
        return Set.of(premier, second);
    }
}
