package dev.sylvain.planning.domain;

import java.time.LocalDate;

/**
 * What a lock freezes: an animateur on one precise timeslot, a stand
 * on one day, or a whole day.
 *
 * <p>{@link VerrouillagePlanning} carries that flat — nullable target
 * columns and a discriminator — because that is what an SQL row, a JSON DTO
 * and a Timefold problem fact all require. The price of that flattening was a
 * {@code switch} where <b>every branch had to remember to set the other
 * columns to {@code null}</b>: forgetting one passed the {@code CHECK}
 * constraint of the database and left a lock aiming at two targets at
 * once.</p>
 *
 * <p>Here the target structurally cannot name two: each variant carries its own
 * fields and nothing else. The move to the columns is written once, in
 * {@link VerrouillagePlanning#apply}, and converting it is an exhaustive
 * {@code switch} with no {@code default} — adding a way to lock will not
 * compile until somebody has said what it freezes.</p>
 */
public sealed interface VerrouillageTarget {

    /** The persisted discriminator, the one the {@code type} column carries. */
    TypeVerrouillage type();

    /**
     * Is this seat frozen? The {@link OnAnimateurAndCreneau} variant reads the
     * current animateur of the seat, so it only answers usefully once the seat
     * has been seeded back from the persisted planning.
     */
    boolean couvre(PosteAffectation poste);

    /** Everything played on this stand on this day. */
    record OnStandAndJour(String standId, LocalDate jour) implements VerrouillageTarget {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.STAND_JOUR;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getStand() != null
                    && standId.equals(poste.getStand().getId())
                    && poste.getCreneau() != null
                    && jour.equals(poste.getCreneau().getDate());
        }
    }

    /** This whole day. */
    record OnJour(LocalDate jour) implements VerrouillageTarget {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.JOUR;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getCreneau() != null && jour.equals(poste.getCreneau().getDate());
        }
    }

    /** This animateur, on that one timeslot only — what an accepted swap sets. */
    record OnAnimateurAndCreneau(String animateurId, long creneauId) implements VerrouillageTarget {

        @Override
        public TypeVerrouillage type() {
            return TypeVerrouillage.ANIMATEUR_CRENEAU;
        }

        @Override
        public boolean couvre(PosteAffectation poste) {
            return poste.getAnimateur() != null
                    && poste.getCreneau() != null
                    && animateurId.equals(poste.getAnimateur().getId())
                    && Long.valueOf(creneauId).equals(poste.getCreneau().getId());
        }
    }
}
