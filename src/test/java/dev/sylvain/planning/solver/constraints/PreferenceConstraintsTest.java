package dev.sylvain.planning.solver.constraints;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;

class PreferenceConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Stand standAutre = standStrategie("STAND-AUTRE");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = apresMidi("J1-AM", 1, D1);

    @Test
    void memeStandRepeteEstPenaliseEnSoft() {
        Animateur a1 = majeurReferent("A1");
        verify("favoriserRotationDesStands")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1))
                .penalizesBy(1);
    }

    @Test
    void rotationSurDeuxStandsDifferentsNEstPasPenalisee() {
        Animateur a1 = majeurReferent("A1");
        verify("favoriserRotationDesStands")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standAutre, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void referentSansDebutantEstPenaliseEnSoft() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(1);
    }

    @Test
    void referentAccompagneDunDebutantNEstPasPenalise() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }
}
