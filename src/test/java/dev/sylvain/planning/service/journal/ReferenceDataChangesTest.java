package dev.sylvain.planning.service.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.journal.ActionJournalisee.Entite;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * « Qu'est-ce qui a changé depuis cette résolution ? » — the counting, and the
 * line the catalogue draws between an action that changes the problem and one
 * that only reads it or leaves with a copy of it. No database: both are pure.
 */
class ReferenceDataChangesTest {

    @Test
    void countsPerFamilyAndKeepsTheLinesItWasGiven() {
        Map<String, Integer> comptes = new LinkedHashMap<>();
        comptes.put("ANIMATEUR_MODIFIE", 2);
        comptes.put("ANIMATEUR_CREE", 1);
        comptes.put("STAND_CREE", 1);
        List<EntreeJournal> dernieres = List.of(entree("STAND_CREE", "STAND", "loup-garou"));

        ReferenceDataChanges changements = ReferenceDataChanges.of(comptes, dernieres);

        assertThat(changements.total()).isEqualTo(4);
        assertThat(changements.parEntite())
                .containsExactly(
                        new ReferenceDataChanges.CompteEntite(Entite.ANIMATEUR, 3),
                        new ReferenceDataChanges.CompteEntite(Entite.STAND, 1));
        assertThat(changements.dernieres()).isEqualTo(dernieres);
    }

    /**
     * The table outlives the code names it stores — that is why they are never
     * renamed — so a line written by a version that knew more actions than this
     * one must not be counted under a family nobody can name.
     */
    @Test
    void ignoresAnActionThisBuildNoLongerKnows() {
        ReferenceDataChanges changements =
                ReferenceDataChanges.of(Map.of("UNE_ACTION_DISPARUE", 3, "STAND_CREE", 1), List.of());

        assertThat(changements.total()).isEqualTo(1);
        assertThat(changements.parEntite()).containsExactly(new ReferenceDataChanges.CompteEntite(Entite.STAND, 1));
    }

    @Test
    void nothingChangedIsAnEmptySummary() {
        assertThat(ReferenceDataChanges.of(Map.of(), List.of())).isEqualTo(ReferenceDataChanges.NONE);
    }

    /**
     * The classification itself, pinned where it can be read: editing a
     * referential, an adjustment, a lock, a parameter or a rule makes a
     * persisted plan out of date. Sending, exporting, snapshotting or solving
     * does not — the problem is the same afterwards.
     */
    @Test
    void onlyWhatChangesTheProblemMakesAPlanOutOfDate() {
        Set<String> codes = CatalogueActions.codesChangingData();

        assertThat(codes)
                .contains(
                        "ANIMATEUR_CREE",
                        "ANIMATEUR_MODIFIE",
                        "ANIMATEUR_SUPPRIME",
                        "COMPETENCES_IMPORTEES",
                        "STAND_CREE",
                        "STANDS_IMPORTES",
                        "OUVERTURES_SAISIES",
                        "CRENEAU_SUPPRIME",
                        "JOURNEES_TYPES_APPLIQUEES",
                        "EMPLACEMENT_MODIFIE",
                        "TYPOLOGIE_CREEE",
                        "AJUSTEMENT_CREE",
                        "VERROU_POSE",
                        "PARAMETRES_LEGAUX_MODIFIES",
                        "CONTRAINTE_DESACTIVEE",
                        "DECLARATION_APPLIQUEE",
                        "SCENARIO_IMPORTE",
                        "PLANNING_REINITIALISE")
                .doesNotContain(
                        "SOLVE_LANCE",
                        "PLANNING_PUBLIE",
                        "PLANNING_ENVOYE",
                        "EXPORT_PDF",
                        "EXPORT_BASE",
                        "INSTANTANE_CAPTURE",
                        "INSTANTANE_RESTAURE",
                        "ANIMATEUR_JETON_REGENERE",
                        "ANIMATEURS_RELANCES",
                        "SESSION_ESPACE_OUVERTE");
    }

    @Test
    void everyFlaggedActionIsAnActionOfTheInventory() {
        assertThat(CatalogueActions.actions().keySet()).containsAll(CatalogueActions.codesChangingData());
    }

    private static EntreeJournal entree(String action, String entite, String entiteId) {
        return new EntreeJournal(
                1,
                Instant.parse("2026-09-12T12:00:00Z"),
                Acteur.ADMIN,
                null,
                action,
                entite,
                entiteId,
                List.of(),
                EntreeJournal.Resultat.SUCCES,
                200);
    }
}
