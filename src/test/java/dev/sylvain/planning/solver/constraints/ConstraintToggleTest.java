package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the disable-a-constraint mechanism itself, one representative
 * constraint per family: the same dataset must be penalised without a
 * {@link ConstraintToggle} and score exactly zero with one.
 *
 * <p>Nothing tested this before, which is how {@code
 * eviterChangementEmplacementEloigne} shipped without its
 * {@link ConstraintToggleSupport#actif} wrapper — the UI offered a switch for
 * it that silently did nothing.</p>
 */
class ConstraintToggleTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Stand standAdresse = stand("STAND-ADRESSE", false, "ADRESSE");
    private final Stand standMajeurs = stand("STAND-MAJ", true, "STRATEGIE");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    @Test
    void animateurDisponiblePeutEtreDesactivee() {
        Animateur indisponible = referentMajeur("A1");
        indisponible.setJoursIndisponibles(java.util.Set.of(D1));

        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, indisponible))
                .penalizesBy(1);

        verify("animateurDisponible")
                .given(poste(standStrat, creneauMatin, indisponible), new ConstraintToggle("animateurDisponible"))
                .penalizesBy(0);
    }

    @Test
    void appreciationIncompatiblePeutEtreDesactivee() {
        // majeurAutonome only masters STRATEGIE: no liking for an ADRESSE stand.
        verify("appreciationIncompatible")
                .given(poste(standAdresse, creneauMatin, majeurAutonome("A1")))
                .penalizesBy(1);

        verify("appreciationIncompatible")
                .given(
                        poste(standAdresse, creneauMatin, majeurAutonome("A1")),
                        new ConstraintToggle("appreciationIncompatible"))
                .penalizesBy(0);
    }

    @Test
    void standReserveAuxMajeursPeutEtreDesactivee() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(1);

        verify("standReserveAuxMajeurs")
                .given(
                        poste(standMajeurs, creneauMatin, mineurDebutant("M1")),
                        new ConstraintToggle("standReserveAuxMajeurs"))
                .penalizesBy(0);
    }

    @Test
    void indisponibiliteForceePeutEtreDesactivee() {
        Animateur a1 = referentMajeur("A1");
        ContrainteAdHoc contrainte = new ContrainteAdHoc("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        contrainte.setAnimateursConcernes(List.of(a1));
        contrainte.setCreneau(creneauMatin);

        verify("indisponibiliteForcee")
                .given(a1, poste(standStrat, creneauMatin, a1), contrainte)
                .penalizesBy(1);

        verify("indisponibiliteForcee")
                .given(
                        a1,
                        poste(standStrat, creneauMatin, a1),
                        contrainte,
                        new ConstraintToggle("indisponibiliteForcee"))
                .penalizesBy(0);
    }

    @Test
    void eviterChangementEmplacementEloignePeutEtreDesactivee() {
        Stand standDrapeau = standWithEmplacement("STAND-DRAPEAU", emplacement("PLACE-DRAPEAU", 46.6513, 2.2492));
        Stand standMairie = standWithEmplacement("STAND-MAIRIE", emplacement("MAIRIE", 46.6490, 2.2547));
        Creneau matin = creneau("J1-MATIN-T", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE-T", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");

        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1), poste(standMairie, suite, a1))
                .penalizesBy(1);

        verify("eviterChangementEmplacementEloigne")
                .given(
                        poste(standDrapeau, matin, a1),
                        poste(standMairie, suite, a1),
                        new ConstraintToggle("eviterChangementEmplacementEloigne"))
                .penalizesBy(0);
    }

    /** The SOFT level is gated like the others — one case per score level. */
    @Test
    void favoriserMixiteDesNiveauxPeutEtreDesactivee() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(1);

        verify("favoriserMixiteDesNiveaux")
                .given(
                        poste(standStrat, creneauMatin, referentMajeur("A1")),
                        new ConstraintToggle("favoriserMixiteDesNiveaux"))
                .penalizesBy(0);
    }

    /** The meal break is its own family, hence its own case here. */
    @Test
    void coupureRepasObligatoirePeutEtreDesactivee() {
        dev.sylvain.planning.domain.FenetreRepas midi = new dev.sylvain.planning.domain.FenetreRepas(
                dev.sylvain.planning.domain.FenetreRepas.MIDI, LocalTime.of(12, 0), LocalTime.of(14, 0), 60);
        Creneau journee = creneau("J1-10-20-T", 1, D1, LocalTime.of(10, 0), LocalTime.of(20, 0));
        Animateur a1 = referentMajeur("A1");

        verify("coupureRepasObligatoire")
                .given(midi, poste(standStrat, journee, a1))
                .penalizesBy(60);

        verify("coupureRepasObligatoire")
                .given(midi, poste(standStrat, journee, a1), new ConstraintToggle("coupureRepasObligatoire"))
                .penalizesBy(0);
    }

    @Test
    void unToggleSurUneAutreContrainteNeDesactivePasCelleCi() {
        verify("standReserveAuxMajeurs")
                .given(
                        poste(standMajeurs, creneauMatin, mineurDebutant("M1")),
                        new ConstraintToggle("appreciationIncompatible"))
                .penalizesBy(1);
    }
}
