package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
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
        Stand stand = referenceData.createStand(new Stand(null, "Stand", Set.of(strategyTypologie()), 1, 1, false));
        String id = stand.getId();
        try {
            assertThat(stand.getModifieLe()).isNotNull();
            // Session B saves first.
            Stand sessionB = referenceData.listStands().stream()
                    .filter(s -> id.equals(s.getId()))
                    .findFirst()
                    .orElseThrow();
            sessionB.setNom("Renommé par B");
            Stand ecritB = referenceData.updateStand(id, sessionB);
            assertThat(ecritB.getModifieLe()).isAfter(stand.getModifieLe());

            // Session A still holds the stamp it loaded before B wrote.
            Stand sessionA = new Stand(id, "Renommé par A", Set.of(strategyTypologie()), 1, 1, false);
            sessionA.setModifieLe(stand.getModifieLe());
            assertThatThrownBy(() -> referenceData.updateStand(id, sessionA))
                    .isInstanceOf(BusinessError.Stale.class)
                    .hasMessageContaining("par une autre session")
                    .extracting(e -> ((BusinessError.Stale) e).getModifieLe())
                    .isEqualTo(ecritB.getModifieLe());
            assertThat(referenceData.listStands())
                    .filteredOn(s -> id.equals(s.getId()))
                    .singleElement()
                    .extracting(Stand::getNom)
                    .isEqualTo("Renommé par B");

            // Knowingly: A sends B's stamp (what the 409 body carries) — or none at all.
            sessionA.setModifieLe(ecritB.getModifieLe());
            assertThat(referenceData.updateStand(id, sessionA).getNom()).isEqualTo("Renommé par A");
            sessionA.setModifieLe(null);
            sessionA.setNom("Sans précondition");
            assertThat(referenceData.updateStand(id, sessionA).getNom()).isEqualTo("Sans précondition");
        } finally {
            referenceData.deleteStand(id);
        }
    }

    /**
     * A creation never takes the caller's id (ADR 0050): two creations sent
     * with the same one make two rows, each under an id the application drew.
     */
    @Test
    void creatingTwiceWithTheSameIdCreatesTwoRows() {
        Stand premier =
                referenceData.createStand(new Stand("CM-DUP", "Le premier", Set.of(strategyTypologie()), 1, 1, false));
        Stand second =
                referenceData.createStand(new Stand("CM-DUP", "Le second", Set.of(strategyTypologie()), 2, 2, false));
        try {
            assertThat(premier.getId()).isNotEqualTo("CM-DUP").startsWith("S");
            assertThat(second.getId()).isNotEqualTo(premier.getId()).startsWith("S");
            assertThat(premier.getModifieLe()).isNotNull();
        } finally {
            referenceData.deleteStand(premier.getId());
            referenceData.deleteStand(second.getId());
        }
    }

    /**
     * A code, unlike an id, is the caller's to choose — and like an id it
     * designates one row: a second stand claiming it is refused.
     */
    @Test
    void creatingATakenCodeIsRefused() {
        Stand premier = new Stand(null, "Le premier", Set.of(strategyTypologie()), 1, 1, false);
        premier.setCode("CM-CODE");
        String id = referenceData.createStand(premier).getId();
        try {
            Stand second = new Stand(null, "Le second", Set.of(strategyTypologie()), 1, 1, false);
            second.setCode("CM-CODE");
            assertThatThrownBy(() -> referenceData.createStand(second))
                    .isInstanceOf(BusinessError.Conflict.class)
                    .hasMessageContaining("CM-CODE");
        } finally {
            referenceData.deleteStand(id);
        }
    }

    @Test
    void staleAnimateurWriteIsRefused() {
        Animateur animateur =
                referenceData.createAnimateur(new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
        String id = animateur.getId();
        try {
            Animateur perime = new Animateur(id, "Prenom", "Autre", LocalDate.of(1990, 1, 1), false);
            perime.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.updateAnimateur(id, perime)).isInstanceOf(BusinessError.Stale.class);
            perime.setModifieLe(animateur.getModifieLe());
            assertThat(referenceData.updateAnimateur(id, perime).getNom()).isEqualTo("Autre");
        } finally {
            referenceData.deleteAnimateur(id);
        }
    }

    @Test
    void staleCreneauWriteIsRefused() {
        Creneau creneau = referenceData.createCreneau(
                new Creneau(null, 1, LocalDate.of(2031, 7, 3), LocalTime.of(9, 0), LocalTime.of(12, 0)));
        try {
            assertThat(creneau.getModifieLe()).isNotNull();
            Creneau perime =
                    new Creneau(creneau.getId(), 1, LocalDate.of(2031, 7, 3), LocalTime.of(10, 0), LocalTime.of(12, 0));
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
        TypologieItem typologie = referenceData.createTypologie(new TypologieItem(null, "Typologie"));
        String id = typologie.id();
        try {
            assertThat(typologie.modifieLe()).isNotNull();
            assertThatThrownBy(() -> referenceData.updateTypologie(
                            id, new TypologieItem(id, null, "Autre", false, null, null, PERIME)))
                    .isInstanceOf(BusinessError.Stale.class);
            TypologieItem ecrite = referenceData.updateTypologie(
                    id, new TypologieItem(id, null, "Autre", false, null, null, typologie.modifieLe()));
            assertThat(ecrite.label()).isEqualTo("Autre");
            assertThat(ecrite.modifieLe()).isAfterOrEqualTo(typologie.modifieLe());
        } finally {
            referenceData.deleteTypologie(id);
        }
    }

    @Test
    void staleEmplacementWriteIsRefused() {
        Emplacement emplacement = referenceData.createEmplacement(new Emplacement(null, "Place", null, null));
        String id = emplacement.getId();
        try {
            Emplacement perime = new Emplacement(id, "Autre place", null, null);
            perime.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.updateEmplacement(id, perime))
                    .isInstanceOf(BusinessError.Stale.class);
            perime.setModifieLe(emplacement.getModifieLe());
            assertThat(referenceData.updateEmplacement(id, perime).getNom()).isEqualTo("Autre place");
        } finally {
            referenceData.deleteEmplacement(id);
        }
    }

    /** The one resource whose edit is a create-or-overwrite POST: the check applies to the overwrite. */
    @Test
    void staleAdHocConstraintOverwriteIsRefused() {
        Animateur animateur =
                referenceData.createAnimateur(new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
        ContrainteAdHoc contrainte = new ContrainteAdHoc(null, TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        contrainte.getAnimateursConcernes().add(animateur);
        contrainte.setRaison("Première raison");
        String contrainteId = null;
        try {
            ContrainteAdHoc ecrite = referenceData.createContrainteAdHoc(contrainte);
            contrainteId = ecrite.getId();
            assertThat(ecrite.getModifieLe()).isNotNull();

            ContrainteAdHoc perimee = new ContrainteAdHoc(ecrite.getId(), TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
            perimee.getAnimateursConcernes().add(animateur);
            perimee.setRaison("Autre raison");
            perimee.setModifieLe(PERIME);
            assertThatThrownBy(() -> referenceData.createContrainteAdHoc(perimee))
                    .isInstanceOf(BusinessError.Stale.class);

            perimee.setModifieLe(ecrite.getModifieLe());
            assertThat(referenceData.createContrainteAdHoc(perimee).getRaison()).isEqualTo("Autre raison");
        } finally {
            if (contrainteId != null) {
                referenceData.deleteContrainteAdHoc(contrainteId);
            }
            referenceData.deleteAnimateur(animateur.getId());
        }
    }

    /** The seeded typologie, under whatever id V100 gave it. */
    private String strategyTypologie() {
        return referenceData.listTypologies().stream()
                .filter(typologie -> "STRATEGIE".equals(typologie.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }
}
