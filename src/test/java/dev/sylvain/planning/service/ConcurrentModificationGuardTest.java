package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Concurrent-edit detection (issue #362): a write carrying the {@code modifieLe}
 * it loaded is refused once the row moved, a write carrying none is not
 * checked, and every accepted write hands back the stamp the database wrote.
 *
 * <p>The scenario of the issue, on each referential: two sessions load the
 * same row; the first saves; the second saves what it had — and is told.</p>
 */
@QuarkusTest
class ConcurrentModificationGuardTest {

    private static final Instant PERIME = Instant.parse("2020-01-01T00:00:00Z");

    @Inject
    ReferenceDataService referenceData;

    @Test
    void staleStandWriteIsRefusedAndNothingIsWritten() {
        Stand stand = referenceData.createStand(new Stand("CM-S1", "Stand", Set.of(), 1, 1, false));
        try {
            assertThat(stand.getModifieLe()).isNotNull();
            // Session B saves first.
            Stand sessionB = referenceData.listStands().stream().filter(s -> "CM-S1".equals(s.getId())).findFirst().orElseThrow();
            sessionB.setNom("Renommé par B");
            Stand ecritB = referenceData.updateStand("CM-S1", sessionB);
            assertThat(ecritB.getModifieLe()).isAfter(stand.getModifieLe());

            // Session A still holds the stamp it loaded before B wrote.
            Stand sessionA = new Stand("CM-S1", "Renommé par A", Set.of(), 1, 1, false);
            sessionA.setModifieLe(stand.getModifieLe());
            assertThatThrownBy(() -> referenceData.updateStand("CM-S1", sessionA))
                    .isInstanceOf(BusinessError.Stale.class)
                    .hasMessageContaining("par une autre session")
                    .extracting(e -> ((BusinessError.Stale) e).getModifieLe())
                    .isEqualTo(ecritB.getModifieLe());
            assertThat(referenceData.listStands()).filteredOn(s -> "CM-S1".equals(s.getId()))
                    .singleElement().extracting(Stand::getNom).isEqualTo("Renommé par B");

            // Knowingly: A sends B's stamp (what the 409 body carries) — or none at all.
            sessionA.setModifieLe(ecritB.getModifieLe());
            assertThat(referenceData.updateStand("CM-S1", sessionA).getNom()).isEqualTo("Renommé par A");
            sessionA.setModifieLe(null);
            sessionA.setNom("Sans précondition");
            assertThat(referenceData.updateStand("CM-S1", sessionA).getNom()).isEqualTo("Sans précondition");
        } finally {
            referenceData.deleteStand("CM-S1");
        }
    }

    /**
     * A creation is checked by the same write: the id being taken is the
     * `ON CONFLICT` branch refusing to fire, never a probe that another session
     * could slip past.
     */
    @Test
    void creatingATakenIdIsRefusedInsteadOfReplacing() {
        Stand stand = referenceData.createStand(new Stand("CM-DUP", "Le premier", Set.of(), 1, 1, false));
        try {
            assertThatThrownBy(() -> referenceData.createStand(
                    new Stand("CM-DUP", "Le second", Set.of(), 2, 2, false)))
                    .isInstanceOf(BusinessError.Conflict.class)
                    .hasMessageContaining("déjà pris");
            assertThat(referenceData.listStands()).filteredOn(s -> "CM-DUP".equals(s.getId()))
                    .singleElement().extracting(Stand::getNom).isEqualTo("Le premier");
            assertThat(stand.getModifieLe()).isNotNull();
        } finally {
            referenceData.deleteStand("CM-DUP");
        }
    }

    @Test
    void staleAnimateurWriteIsRefused() {
        Animateur animateur = referenceData.createAnimateur(
                new Animateur("CM-A1", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
        try {
            Animateur perime = new Animateur("CM-A1", "Prenom", "Autre", LocalDate.of(1990, 1, 1), false);
            perime.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.updateAnimateur("CM-A1", perime))
                    .isInstanceOf(BusinessError.Stale.class);
            perime.setModifieLe(animateur.getModifieLe());
            assertThat(referenceData.updateAnimateur("CM-A1", perime).getNom()).isEqualTo("Autre");
        } finally {
            referenceData.deleteAnimateur("CM-A1");
        }
    }

    @Test
    void staleCreneauWriteIsRefused() {
        Creneau creneau = referenceData.createCreneau(
                new Creneau(null, 1, LocalDate.of(2031, 7, 3), LocalTime.of(9, 0), LocalTime.of(12, 0)));
        try {
            assertThat(creneau.getModifieLe()).isNotNull();
            Creneau perime = new Creneau(creneau.getId(), 1, LocalDate.of(2031, 7, 3), LocalTime.of(10, 0),
                    LocalTime.of(12, 0));
            perime.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.updateCreneau(creneau.getId(), perime))
                    .isInstanceOf(BusinessError.Stale.class);
            perime.setModifieLe(creneau.getModifieLe());
            Creneau ecrit = referenceData.updateCreneau(creneau.getId(), perime);
            assertThat(ecrit.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
            assertThat(ecrit.getModifieLe()).isAfterOrEqualTo(creneau.getModifieLe());
        } finally {
            referenceData.deleteCreneaux(List.of(creneau.getId()));
        }
    }

    @Test
    void staleTypologieWriteIsRefused() {
        TypologieItem typologie = referenceData.createTypologie(new TypologieItem("CM-T1", "Typologie"));
        try {
            assertThat(typologie.modifieLe()).isNotNull();
            assertThatThrownBy(() -> referenceData.updateTypologie("CM-T1",
                    new TypologieItem("CM-T1", "Autre", false, PERIME)))
                    .isInstanceOf(BusinessError.Stale.class);
            TypologieItem ecrite = referenceData.updateTypologie("CM-T1",
                    new TypologieItem("CM-T1", "Autre", false, typologie.modifieLe()));
            assertThat(ecrite.label()).isEqualTo("Autre");
            assertThat(ecrite.modifieLe()).isAfterOrEqualTo(typologie.modifieLe());
        } finally {
            referenceData.deleteTypologie("CM-T1");
        }
    }

    @Test
    void staleEmplacementWriteIsRefused() {
        Emplacement emplacement = referenceData.createEmplacement(new Emplacement("CM-E1", "Place", null, null));
        try {
            Emplacement perime = new Emplacement("CM-E1", "Autre place", null, null);
            perime.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.updateEmplacement("CM-E1", perime))
                    .isInstanceOf(BusinessError.Stale.class);
            perime.setModifieLe(emplacement.getModifieLe());
            assertThat(referenceData.updateEmplacement("CM-E1", perime).getNom()).isEqualTo("Autre place");
        } finally {
            referenceData.deleteEmplacement("CM-E1");
        }
    }

    /** The one resource whose edit is a create-or-overwrite POST: the check applies to the overwrite. */
    @Test
    void staleAdHocConstraintOverwriteIsRefused() {
        Animateur animateur = referenceData.createAnimateur(
                new Animateur("CM-A2", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
        ContrainteAdHoc contrainte = new ContrainteAdHoc("CM-C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        contrainte.getAnimateursConcernes().add(animateur);
        contrainte.setRaison("Première raison");
        try {
            ContrainteAdHoc ecrite = referenceData.createContrainteAdHoc(contrainte);
            assertThat(ecrite.getModifieLe()).isNotNull();

            ContrainteAdHoc perimee = new ContrainteAdHoc("CM-C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
            perimee.getAnimateursConcernes().add(animateur);
            perimee.setRaison("Autre raison");
            perimee.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.createContrainteAdHoc(perimee))
                    .isInstanceOf(BusinessError.Stale.class);

            perimee.setModifieLe(ecrite.getModifieLe());
            assertThat(referenceData.createContrainteAdHoc(perimee).getRaison()).isEqualTo("Autre raison");
        } finally {
            referenceData.deleteContrainteAdHoc("CM-C1");
            referenceData.deleteAnimateur("CM-A2");
        }
    }
}
