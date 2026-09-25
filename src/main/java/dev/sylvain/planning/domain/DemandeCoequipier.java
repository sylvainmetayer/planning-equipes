package dev.sylvain.planning.domain;

import java.time.Instant;
import java.util.List;

/**
 * Teammates an animateur names from the Covoiturage tab of their espace — for
 * now the « Je viens avec… » of a shared car.
 *
 * <p>A wish, sent and decided apart from the availability declaration:
 * applying or refusing a declaration touches nothing of it, and only an admin
 * validating it creates the
 * {@link TypeContrainteAdHoc#ARRIVEE_GROUPEE} exception the solver reads. The
 * teammates are a frozen copy of the ids typed, so a teammate deleted since
 * leaves a readable demand rather than a cascade.</p>
 *
 * @param id           identity of the demand
 * @param animateurId  who declared it
 * @param nature       why the teammates are named
 * @param coequipiers  the teammates, one to three, never the declarant
 * @param statut       where the demand stands
 * @param contrainteId the exception its validation created, {@code null}
 *                     otherwise
 * @param creeLe       when it was declared
 * @param decideLe     when an admin decided, {@code null} while pending
 * @param motif        why an admin set it aside, as they typed it for the
 *                     animateur to read; {@code null} when none was given
 */
public record DemandeCoequipier(
        String id,
        String animateurId,
        NatureCoequipier nature,
        List<String> coequipiers,
        StatutDemandeCoequipier statut,
        String contrainteId,
        Instant creeLe,
        Instant decideLe,
        String motif) {

    /** Largest car the feature plans for: the declarant and three teammates. */
    public static final int COEQUIPIERS_MAX = 3;

    /** Longest reason an admin may give when setting a demand aside: a sentence, not a letter. */
    public static final int MOTIF_MAX = 500;

    public DemandeCoequipier {
        coequipiers = coequipiers == null ? List.of() : List.copyOf(coequipiers);
    }

    /** The declarant first, then the teammates in the order typed. */
    public List<String> members() {
        java.util.List<String> membres = new java.util.ArrayList<>();
        membres.add(animateurId);
        membres.addAll(coequipiers);
        return List.copyOf(membres);
    }
}
