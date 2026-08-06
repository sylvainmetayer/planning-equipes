package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;
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
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrategie("STAND-2"), creneauMatin, a1))
                .penalizesBy(1);
    }

    @Test
    void deuxAnimateursSurMemeCreneauNeSontPasPenalises() {
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauMatin, majeurReferent("A2")))
                .penalizesBy(0);
    }

    @Test
    void memeAnimateurSurCreneauxDifferentsNEstPasPenalise() {
        Animateur a1 = majeurReferent("A1");
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1))
                .penalizesBy(0);
    }

    // --- B10 : deux créneaux distincts qui se recouvrent -------------------

    @Test
    void deuxCreneauxDistinctsQuiSeChevauchentSontPenalises() {
        // Cas exact cité par l'audit : 10 h-14 h et 12 h-16 h, deux créneaux
        // différents, donc invisibles pour l'ancienne comparaison d'identité.
        Animateur a1 = majeurReferent("A1");
        Creneau matinee = creneau("J1-10-14", 1, D1, LocalTime.of(10, 0), LocalTime.of(14, 0));
        Creneau midi = creneau("J1-12-16", 1, D1, LocalTime.of(12, 0), LocalTime.of(16, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, matinee, a1),
                        poste(standStrategie("STAND-2"), midi, a1))
                .penalizesBy(1);
    }

    @Test
    void deuxCreneauxContigusNeSontPasPenalises() {
        // Bout à bout (fin = début) : pas de chevauchement.
        Animateur a1 = majeurReferent("A1");
        Creneau avant = creneau("J1-10-14", 1, D1, LocalTime.of(10, 0), LocalTime.of(14, 0));
        Creneau apres = creneau("J1-14-18", 1, D1, LocalTime.of(14, 0), LocalTime.of(18, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, avant, a1),
                        poste(standStrategie("STAND-2"), apres, a1))
                .penalizesBy(0);
    }

    @Test
    void creneauFranchissantMinuitChevauchantLeLendemainEstPenalise() {
        // 20 h → 00 h le jour J recouvre 23 h → 01 h : la fin du créneau de nuit
        // doit être calculée sur le jour suivant, pas avant son propre début.
        Animateur a1 = majeurReferent("A1");
        Creneau nuitJ1 = creneau("J1-NUIT-CH", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        Creneau tardJ1 = creneau("J1-TARD-CH", 1, D1, LocalTime.of(23, 0), LocalTime.of(1, 0));
        verify("pasDeChevauchementHoraire")
                .given(poste(standStrat, nuitJ1, a1),
                        poste(standStrategie("STAND-2"), tardJ1, a1))
                .penalizesBy(1);
    }
}
