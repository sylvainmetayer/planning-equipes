package dev.sylvain.planning.solver.constraints;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;

class AffectationConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = apresMidi("J1-AM", 1, D1);

    @Test
    void posteVideEstPenalise() {
        verify("posteDoitEtrePourvu")
                .given(poste(standStrat, creneauMatin, null))
                .penalizesBy(1);
    }

    @Test
    void postePourvuNEstPasPenalise() {
        verify("posteDoitEtrePourvu")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void animateurIndisponibleEstPenalise() {
        Animateur indisponible = majeurReferent("A1");
        indisponible.setJoursIndisponibles(Set.of(D1));
        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, indisponible))
                .penalizesBy(1);
    }

    @Test
    void animateurDisponibleNEstPasPenalise() {
        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void competenceAbsenteEstPenalisee() {
        Animateur sansCompetenceStrategie = animateur("A1", D1.minusYears(30),
                java.util.Map.of(TypologieJeu.AMBIANCE, dev.sylvain.planning.domain.NiveauCompetence.AUTONOME));
        verify("competenceCompatible")
                .given(poste(standStrat, creneauMatin, sansCompetenceStrategie))
                .penalizesBy(1);
    }

    @Test
    void competencePresenteNEstPasPenalisee() {
        verify("competenceCompatible")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void doubleAffectationSurMemeCreneauEstPenalisee() {
        Animateur a1 = majeurReferent("A1");
        verify("pasDeDoubleAffectationSurMemeCreneau")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrategie("STAND-2"), creneauMatin, a1))
                .penalizesBy(1);
    }

    @Test
    void deuxAnimateursSurMemeCreneauNeSontPasPenalises() {
        verify("pasDeDoubleAffectationSurMemeCreneau")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauMatin, majeurReferent("A2")))
                .penalizesBy(0);
    }

    @Test
    void memeAnimateurSurCreneauxDifferentsNEstPasPenalise() {
        Animateur a1 = majeurReferent("A1");
        verify("pasDeDoubleAffectationSurMemeCreneau")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1))
                .penalizesBy(0);
    }
}
