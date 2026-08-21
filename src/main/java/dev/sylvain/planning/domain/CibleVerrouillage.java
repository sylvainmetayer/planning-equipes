package dev.sylvain.planning.domain;

import java.time.LocalDate;

/**
 * Ce qu'un verrouillage gèle : un animateur, un stand, un créneau, une
 * journée, ou un animateur sur un créneau précis.
 *
 * <p>{@link VerrouillagePlanning} porte ça à plat — cinq colonnes cibles
 * nullables et un discriminant — parce que c'est ce qu'exigent une ligne SQL,
 * un DTO JSON et un fait de problème Timefold. Le coût de cette mise à plat
 * était un {@code switch} où <b>chaque branche devait penser à mettre les
 * quatre autres colonnes à {@code null}</b> : en oublier une passait la
 * contrainte {@code CHECK} de la base et laissait un verrou visant deux cibles
 * à la fois.</p>
 *
 * <p>Ici, la cible ne peut structurellement pas en désigner deux : chaque
 * variante ne porte que ses propres champs. Le passage aux colonnes est écrit
 * une seule fois, dans {@link VerrouillagePlanning#appliquer}, et sa
 * conversion est un {@code switch} exhaustif sans {@code default} — ajouter une
 * façon de verrouiller ne compilera pas tant que personne n'aura dit ce qu'elle
 * gèle.</p>
 */
public sealed interface CibleVerrouillage {

    /** Le discriminant persisté, celui que porte la colonne {@code type}. */
    TypeVerrouillage type();

    /**
     * Est-ce que ce siège est gelé ? La variante {@link SurAnimateur} lit
     * l'animateur courant du poste, donc elle ne répond utilement qu'une fois
     * le siège réamorcé depuis le planning persisté.
     */
    boolean couvre(PosteAffectation poste);

    /** Tout ce que cet animateur tient, partout. */
    record SurAnimateur(String animateurId) implements CibleVerrouillage {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.ANIMATEUR;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId());
        }
    }

    /** Tout ce qui se joue sur ce stand. */
    record SurStand(String standId) implements CibleVerrouillage {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.STAND;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getStand() != null && standId.equals(poste.getStand().getId());
        }
    }

    /** Toute cette vacation, sur tous les stands. */
    record SurCreneau(long creneauId) implements CibleVerrouillage {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.CRENEAU;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getCreneau() != null && Long.valueOf(creneauId).equals(poste.getCreneau().getId());
        }
    }

    /** Toute cette journée. */
    record SurJour(LocalDate jour) implements CibleVerrouillage {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.JOUR;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getCreneau() != null && jour.equals(poste.getCreneau().getDate());
        }
    }

    /** Cet animateur, sur ce créneau-là seulement — ce que pose un échange accepté. */
    record SurAnimateurEtCreneau(String animateurId, long creneauId) implements CibleVerrouillage {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.ANIMATEUR_CRENEAU;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getAnimateur() != null && poste.getCreneau() != null
                    && animateurId.equals(poste.getAnimateur().getId())
                    && Long.valueOf(creneauId).equals(poste.getCreneau().getId());
        }
    }
}
