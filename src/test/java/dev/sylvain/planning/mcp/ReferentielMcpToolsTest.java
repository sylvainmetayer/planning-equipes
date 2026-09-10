package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.mcp.AnimateurMcpTools.AnimateurView;
import dev.sylvain.planning.mcp.CreneauMcpTools.CreneauView;
import dev.sylvain.planning.mcp.StandMcpTools.StandView;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the referential mutations opened up by the follow-up
 * comment of issue #107 ("all les endpoints peuvent être implémentés via
 * MCP"), against the real database.
 *
 * <p>The privacy-critical assertion here is
 * {@link #modifierUnAnimateurNeDetruitPasSesDonneesPersonnelles}: MCP edits an
 * animateur without ever seeing nom/prénom/date de naissance, so the merge
 * semantics of {@code modifier_animateur} are the only thing standing between
 * an innocuous "rends A-MCP-1 manager" and a silent wipe of the fields that
 * drive the legal constraints.
 */
@QuarkusTest
class ReferentielMcpToolsTest {

    @Inject
    AnimateurMcpTools animateurTools;

    @Inject
    StandMcpTools standTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    JournalActionService journal;

    @Test
    void modifierUnAnimateurNeDetruitPasSesDonneesPersonnelles() {
        Animateur existant = new Animateur("A-MCP-1", "Ada", "Lovelace", LocalDate.of(2010, 6, 1), false);
        referenceDataService.createAnimateur(existant);
        standTools.creer_typologie("TYPO-MCP-1", "Jeux de stratégie", null);

        AnimateurView modifie = animateurTools
                .modifier_animateur(
                        "A-MCP-1",
                        true,
                        Map.of("TYPO-MCP-1", "REFERENT"),
                        List.of("TYPO-MCP-1"),
                        List.of("2026-07-18"),
                        null,
                        null)
                .animateur();

        assertThat(modifie.manager()).isTrue();
        assertThat(modifie.souhaits()).containsExactly("TYPO-MCP-1");
        assertThat(modifie.joursIndisponibles()).containsExactly(LocalDate.of(2026, 7, 18));

        Animateur relu = referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals("A-MCP-1"))
                .findFirst()
                .orElseThrow();
        assertThat(relu.getPrenom()).isEqualTo("Ada");
        assertThat(relu.getNom()).isEqualTo("Lovelace");
        assertThat(relu.getDateNaissance()).isEqualTo(LocalDate.of(2010, 6, 1));

        animateurTools.supprimer_animateur("A-MCP-1", null);
        standTools.supprimer_typologie("TYPO-MCP-1", null);
    }

    /**
     * Issue #392's A6: the tools used to call the bare {@code update*}, so an
     * assistant's edit wrote a history line with an empty « champs » column and
     * came back without the warnings the same edit raises on a screen. Both
     * doors now go through {@code write*}; this pins what an assistant gets
     * and what the history keeps.
     */
    @Test
    void uneModificationParMcpNommeSesChampsDansLeJournalEtRendSesAvertissements() {
        referenceDataService.createAnimateur(
                new Animateur("A-MCP-J", "Grace", "Hopper", LocalDate.of(1990, 12, 9), false));
        CreneauView creneau =
                creneauTools.creer_creneau("2026-07-18", "09:00", "13:00", null).creneau();
        try {
            // An off day years outside the event: the one warning a screen
            // would show, and that the assistant used to be denied.
            AnimateurMcpTools.WrittenAnimateurView ecrit =
                    animateurTools.modifier_animateur("A-MCP-J", true, null, null, List.of("2031-01-01"), null, null);

            assertThat(ecrit.animateur().manager()).isTrue();
            assertThat(ecrit.avertissements()).contains("INDISPONIBILITE_HORS_EVENEMENT");

            EntreeJournal ligne = journal.list(20).stream()
                    .filter(entree -> "A-MCP-J".equals(entree.entiteId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(ligne.acteur()).hasToString("ASSISTANT");
            assertThat(ligne.champs()).contains("manager", "joursIndisponibles");
        } finally {
            animateurTools.supprimer_animateur("A-MCP-J", null);
            creneauTools.supprimer_creneau(creneau.id(), null);
        }
    }

    @Test
    void creerPuisModifierPuisSupprimerUnStand() {
        standTools.creer_typologie("TYPO-MCP-2", "Jeux d'adresse", null);
        StandView cree = standTools
                .creer_stand(
                        "STAND-MCP-1",
                        "Tir à l'arc",
                        List.of("TYPO-MCP-2"),
                        2,
                        4,
                        true,
                        false,
                        "EPUISANT",
                        null,
                        null,
                        null)
                .stand();

        assertThat(cree.effectifMin()).isEqualTo(2);
        assertThat(cree.niveauEffort().name()).isEqualTo("EPUISANT");

        StandView modifie = standTools
                .modifier_stand(
                        "STAND-MCP-1", "Tir à l'arc (grand)", null, null, 6, null, null, null, null, null, null, null)
                .stand();

        assertThat(modifie.nom()).isEqualTo("Tir à l'arc (grand)");
        assertThat(modifie.effectifMax()).isEqualTo(6);
        // Untouched arguments keep their persisted value rather than resetting.
        assertThat(modifie.effectifMin()).isEqualTo(2);
        assertThat(modifie.reserveMajeurs()).isTrue();
        assertThat(modifie.typologiesProposees()).containsExactly("TYPO-MCP-2");

        StandView withClosing = standTools
                .ajouter_fermeture_stand("STAND-MCP-1", "2026-07-18", "12:00", "14:00", "Pause repas", null)
                .stand();
        assertThat(withClosing.fermetures()).hasSize(1);

        assertThat(standTools
                        .effacer_plages_stand("STAND-MCP-1", "2026-07-18", null)
                        .stand()
                        .fermetures())
                .isEmpty();

        assertThat(standTools.supprimer_stand("STAND-MCP-1", null).supprime()).isTrue();
        standTools.supprimer_typologie("TYPO-MCP-2", null);
    }

    @Test
    void creerEtModifierUnCreneau() {
        CreneauView creneau =
                creneauTools.creer_creneau("2026-07-18", "09:00", "13:00", null).creneau();

        CreneauView modifie = creneauTools
                .modifier_creneau(creneau.id(), null, "10:00", null, null, null)
                .creneau();
        assertThat(modifie.heureDebut()).hasToString("10:00");
        assertThat(modifie.heureFin()).hasToString("13:00");

        creneauTools.supprimer_creneau(creneau.id(), null);
    }

    @Test
    void creerUnStandCompletCreeSesDependancesEtLesEnumere() {
        StandMcpTools.CreationStandComplet creation = standTools.creer_stand_complet(
                "STAND-COMPLET-1",
                "Stand complet",
                List.of("TYPO-COMPLET-1"),
                true,
                2,
                4,
                null,
                null,
                null,
                "EMP-COMPLET-1",
                "Kiosque du test",
                46.6,
                -0.2,
                "10:00-12:00,14:00-",
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(creation.typologiesCreees()).containsExactly("TYPO-COMPLET-1");
        assertThat(creation.emplacementCree()).isEqualTo("EMP-COMPLET-1");
        assertThat(creation.stand().emplacementId()).isEqualTo("EMP-COMPLET-1");
        assertThat(creation.stand().effectifMin()).isEqualTo(2);
        assertThat(creation.stand().horaires()).hasSize(1);

        standTools.supprimer_stand("STAND-COMPLET-1", null);
        standTools.supprimer_emplacement("EMP-COMPLET-1", null);
        standTools.supprimer_typologie("TYPO-COMPLET-1", null);
    }

    /**
     * Issue #392's A5: the tool used to write the typologies, the emplacement
     * and the stand in three transactions, and a stand refused on its own
     * validation left the first two behind — created, and never enumerated,
     * since the answer that lists them never came.
     */
    @Test
    void creerUnStandCompletNeLaisseRienDerriereLuiQuandLeStandEstRefuse() {
        // effectifMin above effectifMax: refused by StandValidator, after the
        // typologie and the emplacement would already have been written.
        assertThatThrownBy(() -> standTools.creer_stand_complet(
                        "STAND-ATOMIQUE",
                        "Stand refusé",
                        List.of("TYPO-ATOMIQUE"),
                        true,
                        5,
                        2,
                        null,
                        null,
                        null,
                        "EMP-ATOMIQUE",
                        "Kiosque fantôme",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectifMin");

        assertThat(referenceDataService.listTypologies())
                .extracting(TypologieItem::id)
                .doesNotContain("TYPO-ATOMIQUE");
        assertThat(referenceDataService.listEmplacements())
                .extracting(Emplacement::getId)
                .doesNotContain("EMP-ATOMIQUE");
    }

    @Test
    void creerUnStandCompletRefuseUneTypologieInconnueSansLOptionDeCreation() {
        assertThatThrownBy(() -> standTools.creer_stand_complet(
                        "STAND-COMPLET-2",
                        "Stand complet",
                        List.of("TYPO-INEXISTANTE"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TYPO-INEXISTANTE");
    }

    @Test
    void creerDesCreneauxRecurrentsSauteLeWeekEndEtControleLaGrille() {
        // Starts from an empty grid so the counts are deterministic; the class leaves it
        // empty on the way out, that is, in the state of a fresh test database.
        creneauTools.supprimer_creneaux(null, null, null, true, null);

        CreneauMcpTools.PrevisualisationRecurrence apercu = creneauTools.previsualiser_creneaux_recurrents(
                "AMPLITUDES",
                "09:00-12:00,14:00-18:00",
                "JOURS_SEMAINE",
                "2026-07-06",
                "2026-07-12",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
                null,
                null,
                null);

        assertThat(apercu.nombreGeneres()).isEqualTo(10);
        assertThat(referenceDataService.listCreneaux()).isEmpty(); // la prévisualisation n'écrit rien

        CreneauMcpTools.PrevisualisationRecurrence creation = creneauTools.creer_creneaux_recurrents(
                "AMPLITUDES",
                "09:00-12:00,14:00-18:00",
                "JOURS_SEMAINE",
                "2026-07-06",
                "2026-07-12",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
                null,
                null,
                null);

        assertThat(creation.nombreGeneres()).isEqualTo(10);
        assertThat(referenceDataService.listCreneaux()).hasSize(10);
        // The day is never stored: it is derived back from the dates when read.
        assertThat(referenceDataService.listCreneaux())
                .extracting(dev.sylvain.planning.domain.Creneau::getJour)
                .containsOnly(1, 2, 3, 4, 5);
        // The midday break leaves 12:00-14:00 uncovered: reported as a warning, without blocking.
        assertThat(creation.controle().hasNoBlockingAnomaly()).isTrue();
        assertThat(creation.controle().anomalies())
                .extracting(anomalie -> anomalie.type().name())
                .contains("TROU_DANS_LA_JOURNEE");

        assertThat(creneauTools
                        .supprimer_creneaux(null, null, "14:00", null, null)
                        .supprimes())
                .isEqualTo(5);
        assertThat(creneauTools.supprimer_creneaux(null, null, null, true, null).restants())
                .isZero();
    }

    @Test
    void deriverLaGrilleDesStandsPrevisualiseSansEcrirePuisEcrit() {
        creneauTools.supprimer_creneaux(null, null, null, true, null);
        dev.sylvain.planning.domain.Stand stand = new dev.sylvain.planning.domain.Stand(
                "DERIV-MCP", "Dérivé", java.util.Set.of("STRATEGIE"), 1, 1, false);
        stand.setHoraires(new java.util.ArrayList<>(List.of(dev.sylvain.planning.domain.HoraireStand.everyDay(
                dev.sylvain.planning.domain.ModeHoraire.OUVERTURE,
                new dev.sylvain.planning.domain.FenetreHoraire(
                        java.time.LocalTime.of(10, 0), java.time.LocalTime.of(12, 0)),
                new dev.sylvain.planning.domain.FenetreHoraire(java.time.LocalTime.of(14, 0), null)))));
        referenceDataService.createStand(stand);

        CreneauMcpTools.RapportDerivationMcp apercu = creneauTools.previsualiser_derivation_creneaux(
                "2026-07-06", "2026-07-07", "20:00", null, null, null, null);
        assertThat(apercu.nombreGeneres()).isEqualTo(4);
        assertThat(referenceDataService.listCreneaux()).isEmpty();

        CreneauMcpTools.RapportDerivationMcp ecrit = creneauTools.generer_creneaux_depuis_stands(
                "2026-07-06", "2026-07-07", "20:00", null, null, null, null);
        assertThat(ecrit.nombreGeneres()).isEqualTo(4);
        assertThat(referenceDataService.listCreneaux()).hasSize(4);

        creneauTools.supprimer_creneaux(null, null, null, true, null);
        referenceDataService.deleteStand("DERIV-MCP");
    }

    @Test
    void supprimerDesCreneauxSansFiltreEstRefuse() {
        assertThatThrownBy(() -> creneauTools.supprimer_creneaux(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tous=true");
    }

    /** The edition declares its mode once, on the Créneaux page: a call naming none reads that declaration. */
    @Test
    void unModeDeGrilleManquantLitLeModeDeclareDeLEdition() {
        assertThat(creneauTools.valider_creneaux(null, null).mode())
                .isEqualTo(referenceDataService.getParametresDecoupage().getModeGrille());
        assertThatThrownBy(() -> creneauTools.valider_creneaux("BIDULE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMPLITUDES");
    }

    @Test
    void uneDateMalFormeeRemonteUnMessageExploitable() {
        assertThatThrownBy(() -> creneauTools.creer_creneau("18/07/2026", "09:00", "13:00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AAAA-MM-JJ");
    }

    @Test
    void unNiveauDEffortInconnuListeLesValeursPossibles() {
        assertThatThrownBy(() -> standTools.creer_stand(
                        "STAND-MCP-2", "Stand", null, 1, 1, null, null, "TRANQUILLE", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NORMAL");
    }

    @Test
    void uneFenetreDHoraireEtUneOuverturePortentLeurEffectifParMcp() {
        standTools.creer_stand(
                "STAND-MCP-EFF", "Village", List.of("STRATEGIE"), 1, 4, false, false, null, null, null, null);
        try {
            StandView avecRegle = standTools
                    .ajouter_horaire_stand(
                            "STAND-MCP-EFF",
                            "OUVERTURE",
                            "10:00-12:00@2, 14:00-@4",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)
                    .stand();

            assertThat(avecRegle.horaires()).hasSize(1);
            assertThat(avecRegle.horaires().getFirst().fenetres())
                    .extracting(StandMcpTools.FenetreView::effectif)
                    .containsExactly(2, 4);
            assertThat(avecRegle.horaires().getFirst().fenetres().get(1).heureFin())
                    .isNull();

            StandView avecOuverture = standTools
                    .ajouter_ouverture_stand("STAND-MCP-EFF", "2026-07-18", "14:00", "20:00", "Tournoi", 3, null)
                    .stand();
            assertThat(avecOuverture.ouvertures()).hasSize(1);
            assertThat(avecOuverture.ouvertures().getFirst().effectif()).isEqualTo(3);

            // Without the suffix, the window inherits the stand's minimum: nothing named.
            StandView sansEffectif = standTools
                    .ajouter_ouverture_stand("STAND-MCP-EFF", "2026-07-19", "14:00", null, null, null, null)
                    .stand();
            assertThat(sansEffectif.ouvertures().get(1).effectif()).isNull();
            assertThat(sansEffectif.fermetures()).isEmpty();
        } finally {
            standTools.supprimer_stand("STAND-MCP-EFF", null);
        }
    }

    @Test
    void unEffectifDeFenetreNulOuMalFormeEstRefuse() {
        standTools.creer_stand(
                "STAND-MCP-EFF-0", "Village", List.of("STRATEGIE"), 1, 4, false, false, null, null, null, null);
        try {
            assertThatThrownBy(() -> standTools.ajouter_horaire_stand(
                            "STAND-MCP-EFF-0", "OUVERTURE", "10:00-12:00@0", null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("au moins 1");
            assertThatThrownBy(() -> standTools.ajouter_horaire_stand(
                            "STAND-MCP-EFF-0",
                            "OUVERTURE",
                            "10:00-12:00@deux",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@N");
            assertThatThrownBy(() -> standTools.ajouter_ouverture_stand(
                            "STAND-MCP-EFF-0", "2026-07-18", "14:00", null, null, 0, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("au moins 1");
            // Nothing was written by the refused calls.
            assertThat(standTools.consulter_stand("STAND-MCP-EFF-0", null).horaires())
                    .isEmpty();
        } finally {
            standTools.supprimer_stand("STAND-MCP-EFF-0", null);
        }
    }
}
