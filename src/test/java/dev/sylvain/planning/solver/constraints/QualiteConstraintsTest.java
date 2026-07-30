package dev.sylvain.planning.solver.constraints;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;

class QualiteConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = apresMidi("J1-AM", 1, D1);

    @Test
    void standSansReferentEstPenaliseEnMedium() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A1")))
                .penalizesBy(1);
    }

    @Test
    void standAvecReferentNEstPasPenalise() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void chargeEquilibreeNEstPasPenalisee() {
        // Deux animateurs avec un poste chacun : répartition parfaite.
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauAprem, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void chargeDesequilibreeEstPenalisee() {
        Animateur a1 = majeurReferent("A1");
        // A1 porte quatre postes contre un seul pour A2. NB : la contrainte tronque
        // l'« unfairness » via intValue(), donc un écart faible (2 contre 1) donne 0 ;
        // il faut un déséquilibre marqué pour qu'une pénalité entière apparaisse.
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standStrat, matin("J2-MATIN", 2, D2), a1),
                        poste(standStrat, apresMidi("J2-AM", 2, D2), a1),
                        poste(standStrategie("STAND-2"), creneauMatin, majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void majoriteDeMineursSurUnCreneauEstPenalisee() {
        // Deux mineurs, aucun majeur : pénalité = mineurs - majeurs = 2.
        verify("repartitionMineursParCreneau")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M2")))
                .penalizesBy(2);
    }

    @Test
    void autantDeMineursQueDeMajeursNEstPasPenalise() {
        verify("repartitionMineursParCreneau")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }
}
